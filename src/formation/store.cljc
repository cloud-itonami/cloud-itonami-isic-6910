(ns formation.store
  "SSoT for the formation actor, behind a `Store` protocol so the backend is
  a swap, not a rewrite -- the same seam `gftd-talent-actor` /
  `ai-gftd-itonami` use:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/formation/store_contract_test.clj), which is the whole point: the
  actor, the RegistrarGovernor and the audit ledger never know which SSoT
  they run on.

  The ledger stays append-only on every backend: 'who filed what, for
  which customer, on what jurisdictional basis, approved by whom' is
  always a query over an immutable log -- the audit trail a customer
  trusting an operator with their incorporation needs, and the evidence an
  operator needs if a filing is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [formation.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (application [s id])
  (all-applications [s])
  (officer [s id])
  (kyc-of [s officer-id] "committed KYC screening verdict for an officer, or nil")
  (assessment-of [s app-id] "committed jurisdiction assessment (doc checklist + fee estimate), or nil")
  (ledger [s])
  (registry-history [s] "the append-only registry-record history (formation.registry drafts)")
  (next-sequence [s jurisdiction] "next registry-number sequence for a jurisdiction")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-applications [s apps] "replace/seed the application directory (map id->application)")
  (with-officers [s officers] "replace/seed the officer directory (map id->officer)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained customer set so the actor + tests run offline."
  []
  {:applications
   {"app-1" {:id "app-1" :entity-name "Kotoba Trading GK" :jurisdiction "JPN"
             :officers ["o-1"] :capital 1000000 :articles "定款ドラフト v1"
             :address "東京都千代田区1-1-1" :status :intake}
    "app-2" {:id "app-2" :entity-name "Nowhere Holdings Ltd" :jurisdiction "ATL"
             :officers ["o-2"] :capital 100 :articles "draft"
             :address "unknown" :status :intake}}
   :officers
   {"o-1" {:id "o-1" :name "田中 一郎" :sanctions-hit? false :id-doc "passport-jp-****1234"}
    "o-2" {:id "o-2" :name "J. Doe" :sanctions-hit? true :id-doc nil}}})

;; ----------------------------- shared filing logic -----------------------------

(defn- file!
  "Backend-agnostic `:filing/mark-submitted` -- looks up the application +
  its officers via the protocol, drafts the registry record, and returns
  {:result .. :app-patch ..} for the caller to persist. Pure w.r.t. any
  particular backend's transaction mechanics."
  [s app-id]
  (let [app (application s app-id)
        seq-n (next-sequence s (:jurisdiction app))
        result (registry/register-incorporation
                (:entity-name app) (mapv #(officer s %) (:officers app))
                (:capital app) (:articles app) (:address app)
                (:jurisdiction app) seq-n)]
    {:result result
     :app-patch {:status :filed
                 :registry-number (get result "registry_number")
                 :lei (get result "lei")}}))

(defn- amend!
  "Backend-agnostic `:registry/amend-submitted` -- looks up the application
  via the protocol, drafts the append-only amendment record via
  `formation.registry/register-change`, and returns {:result ..
  :app-patch ..} for the caller to persist. The amendment record is
  APPENDED to registry-history; the original incorporation record is
  never overwritten (G5-style append-only, matching matsurigoto's
  corp-registry discipline)."
  [s app-id changed-fields effective-date]
  (let [app (application s app-id)
        result (registry/register-change
                (:registry-number app) changed-fields effective-date)]
    ;; app-patch is exactly changed-fields (no extra bookkeeping keys) so
    ;; both backends' app->tx / merge stay in lockstep -- the amendment's
    ;; own effective-date already lives inside `result`'s "record".
    {:result result
     :app-patch changed-fields}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (application [_ id] (get-in @a [:applications id]))
  (all-applications [_] (sort-by :id (vals (:applications @a))))
  (officer [_ id] (get-in @a [:officers id]))
  (kyc-of [_ id] (get-in @a [:kyc id]))
  (assessment-of [_ app-id] (get-in @a [:assessments app-id]))
  (ledger [_] (:ledger @a))
  (registry-history [_] (:registry @a))
  (next-sequence [_ jurisdiction]
    (get-in @a [:sequences jurisdiction] 0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :application/upsert
      (swap! a update-in [:applications (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :kyc/set
      (swap! a assoc-in [:kyc (first path)] payload)

      :filing/mark-submitted
      (let [app-id (first path)
            {:keys [result app-patch]} (file! s app-id)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences (:jurisdiction (get-in state [:applications app-id]))] (fnil inc 0))
                       (update-in [:applications app-id] merge app-patch)
                       (update :registry registry/append result))))
        result)

      :registry/amend-submitted
      (let [app-id (first path)
            {:keys [changed-fields effective-date]} value
            {:keys [result app-patch]} (amend! s app-id changed-fields effective-date)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:applications app-id] merge app-patch)
                       (update :registry registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-applications [s apps] (when (seq apps) (swap! a assoc :applications apps)) s)
  (with-officers [s officers] (when (seq officers) (swap! a assoc :officers officers)) s))

(defn seed-db
  "A MemStore seeded with the demo customer set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :kyc {} :ledger [] :sequences {} :registry []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (officer-id lists, KYC/assessment payloads, ledger
  facts, registry records) are stored as EDN strings so `langchain.db`
  doesn't expand them into sub-entities -- the same convention
  `talent.store` uses for `:emp/protected`."
  {:app/id          {:db/unique :db.unique/identity}
   :officer/id      {:db/unique :db.unique/identity}
   :kyc/officer-id  {:db/unique :db.unique/identity}
   :assessment/app-id {:db/unique :db.unique/identity}
   :ledger/seq      {:db/unique :db.unique/identity}
   :registry/seq    {:db/unique :db.unique/identity}
   :sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- app->tx [{:keys [id entity-name jurisdiction officers capital articles address
                        status registry-number lei]}]
  (cond-> {:app/id id}
    entity-name      (assoc :app/entity-name entity-name)
    jurisdiction     (assoc :app/jurisdiction jurisdiction)
    officers         (assoc :app/officers (enc officers))
    capital          (assoc :app/capital capital)
    articles         (assoc :app/articles articles)
    address          (assoc :app/address address)
    status           (assoc :app/status status)
    registry-number  (assoc :app/registry-number registry-number)
    lei              (assoc :app/lei lei)))

(def ^:private app-pull
  [:app/id :app/entity-name :app/jurisdiction :app/officers :app/capital
   :app/articles :app/address :app/status :app/registry-number :app/lei])

(defn- pull->app [m]
  (when (:app/id m)
    {:id (:app/id m) :entity-name (:app/entity-name m) :jurisdiction (:app/jurisdiction m)
     :officers (or (dec* (:app/officers m)) []) :capital (:app/capital m)
     :articles (:app/articles m) :address (:app/address m) :status (:app/status m)
     :registry-number (:app/registry-number m) :lei (:app/lei m)}))

(defn- officer->tx [{:keys [id name sanctions-hit? id-doc]}]
  (cond-> {:officer/id id}
    name (assoc :officer/name name)
    (some? sanctions-hit?) (assoc :officer/sanctions-hit? sanctions-hit?)
    id-doc (assoc :officer/id-doc id-doc)))

(defn- pull->officer [m]
  (when (:officer/id m)
    {:id (:officer/id m) :name (:officer/name m)
     :sanctions-hit? (boolean (:officer/sanctions-hit? m)) :id-doc (:officer/id-doc m)}))

(defrecord DatomicStore [conn]
  Store
  (application [_ id]
    (pull->app (d/pull (d/db conn) app-pull [:app/id id])))
  (all-applications [_]
    (->> (d/q '[:find [?id ...] :where [?e :app/id ?id]] (d/db conn))
         (map #(pull->app (d/pull (d/db conn) app-pull [:app/id %])))
         (sort-by :id)))
  (officer [_ id]
    (pull->officer (d/pull (d/db conn)
                           [:officer/id :officer/name :officer/sanctions-hit? :officer/id-doc]
                           [:officer/id id])))
  (kyc-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?oid
                :where [?k :kyc/officer-id ?oid] [?k :kyc/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ app-id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?a :assessment/app-id ?aid] [?a :assessment/payload ?p]]
              (d/db conn) app-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (registry-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :registry/seq ?s] [?e :registry/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :application/upsert
      (d/transact! conn [(app->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/app-id (first path) :assessment/payload (enc payload)}])

      :kyc/set
      (d/transact! conn [{:kyc/officer-id (first path) :kyc/payload (enc payload)}])

      :filing/mark-submitted
      (let [app-id (first path)
            {:keys [result app-patch]} (file! s app-id)
            jurisdiction (:jurisdiction (application s app-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(app->tx (assoc app-patch :id app-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      ;; store just the "record" sub-map, matching MemStore's
                      ;; `registry/append` convention -- registry-history is a
                      ;; history of RECORDS, not of the full filing result.
                      {:registry/seq (count (registry-history s)) :registry/record (enc (get result "record"))}])
        result)

      :registry/amend-submitted
      (let [app-id (first path)
            {:keys [changed-fields effective-date]} value
            {:keys [result app-patch]} (amend! s app-id changed-fields effective-date)]
        (d/transact! conn
                     [(app->tx (assoc app-patch :id app-id))
                      {:registry/seq (count (registry-history s)) :registry/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-applications [s apps]
    (when (seq apps) (d/transact! conn (mapv app->tx (vals apps)))) s)
  (with-officers [s officers]
    (when (seq officers) (d/transact! conn (mapv officer->tx (vals officers)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:applications .. :officers ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [applications officers]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-applications applications) (with-officers officers)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo customer set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

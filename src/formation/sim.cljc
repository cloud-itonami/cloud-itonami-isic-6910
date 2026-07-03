(ns formation.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean application through
  intake -> jurisdiction assessment -> KYC screening -> filing proposal
  (always escalates) -> human approval -> commit -> a post-incorporation
  amendment -> a dissolution (both also always escalate), then shows
  three HARD holds (a sanctions hit, a fabricated jurisdiction, a
  double-dissolution attempt) that never reach a human at all, and prints
  the audit ledger + the draft registry record history."
  (:require [langgraph.graph :as g]
            [formation.store :as store]
            [formation.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :registrar :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== intake app-1 (JPN, clean officer) ==")
    (println (exec! actor "t1" {:op :application/intake :subject "app-1"
                                :patch {:id "app-1" :status :ready}} operator))

    (println "== jurisdiction/assess app-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "app-1"} operator))
    (println (approve! actor "t2"))

    (println "== kyc/screen o-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :kyc/screen :subject "o-1"} operator))
    (println (approve! actor "t3"))

    (println "== filing/submit app-1 (always escalates -- actuation) ==")
    (let [r (exec! actor "t4" {:op :filing/submit :subject "app-1"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t4")))

    (println "== registry/amend app-1: registered-address change (always escalates -- actuation) ==")
    (let [r (exec! actor "t4b" {:op :registry/amend :subject "app-1"
                               :changed-fields {:address "東京都港区2-2-2"}
                               :effective-date "2026-07-10"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t4b")))

    (println "== registry/dissolve app-1 (always escalates -- actuation) ==")
    (let [r (exec! actor "t4c" {:op :registry/dissolve :subject "app-1"
                               :reason "voluntary wind-up" :effective-date "2026-12-01"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t4c")))

    (println "== registry/dissolve app-1 AGAIN (already dissolved -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t4d" {:op :registry/dissolve :subject "app-1"
                                 :reason "second attempt" :effective-date "2027-01-01"} operator))

    (println "== kyc/screen o-2 (sanctions hit -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t5" {:op :kyc/screen :subject "o-2"} operator))

    (println "== jurisdiction/assess app-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "app-2" :no-spec? true} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft registry records ==")
    (doseq [r (store/registry-history db)] (println r))))

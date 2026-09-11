(ns formation.registry
  "Pure-function registry-record construction + ISO 17442 LEI issuance with a
  real ISO 7064 MOD 97-10 check-digit computation, then an append-only
  incorporation / amendment record.

  Ported from `matsurigoto`'s `corp-registry` R0 reference implementation
  (etzhayyim/root, ADR-2606062300) -- the LEI/MOD-97-10 arithmetic is
  jurisdiction-agnostic spec math, so it is shared rather than re-derived.
  matsurigoto models a polity's OWN statecraft; this actor models an
  OPERATOR (a licensed registered agent / company-formation professional)
  assisting a customer through a REAL government registry. Same spec, two
  different principals.

  Spec basis: ISO 17442 (LEI) + GLEIF LEI-CDF + W3C VC 2.0.

  This namespace is pure data + pure functions -- no I/O, no network call to
  any government registry. It builds the RECORD an operator would file /
  keep, not the act of filing itself (that is `formation.operation`'s
  `:filing/submit`, which is always human-gated -- see README)."
  (:require [kotoba.lang.text :as str]))

;; -- ISO 17442 LEI + ISO 7064 MOD 97-10 (the conformance anchor) --

(defn- to-digits
  "Convert an alphanumeric string to its ISO 7064 numeric form (0-9 stay; A=10 .. Z=35)."
  [s]
  (apply str
         (map (fn [ch]
                (cond
                  (<= (int \0) (int ch) (int \9)) (str ch)
                  (<= (int \A) (int ch) (int \Z)) (str (- (int ch) 55))
                  :else (throw (ex-info (str "LEI char must be [0-9A-Z], got " (pr-str (str ch))) {}))))
              s)))

(defn- mod97
  "digits mod 97, over arbitrary-precision integers (a plain 18-20 digit
  number overflows a 53-bit JS/double or a 63-bit long)."
  [numeric-str]
  #?(:clj  (.intValue (.mod (java.math.BigInteger. ^String numeric-str) (java.math.BigInteger. "97")))
     :cljs (js/Number (js-mod (js/BigInt numeric-str) (js/BigInt 97)))))

(defn compute-lei-check-digits
  "ISO 7064 MOD 97-10 check digits for an 18-char LEI base.
  digits = numeric(base18 + \"00\"); check = 98 - (digits mod 97); zero-padded to 2."
  [base18]
  (when (not= (count base18) 18)
    (throw (ex-info (str "LEI base must be 18 chars, got " (count base18)) {})))
  (let [m (mod97 (to-digits (str base18 "00")))
        c (- 98 m)]
    (if (< c 10) (str "0" c) (str c))))

(defn validate-lei
  "A 20-char LEI is valid iff numeric(lei) mod 97 == 1 (ISO 7064 MOD 97-10)."
  [lei]
  (if (or (not (string? lei)) (not= (count lei) 20))
    false
    (try
      (= (mod97 (to-digits lei)) 1)
      (catch #?(:clj Exception :cljs :default) _ false))))

(defn assign-lei
  "Build a valid LEI: 4-char LOU prefix + reserved '00' + 12-char entity id + 2 check digits."
  ([lou-prefix entity-id12]
   (when (not= (count lou-prefix) 4)
     (throw (ex-info "LOU prefix must be 4 chars" {})))
   (when (not= (count entity-id12) 12)
     (throw (ex-info "entity id must be 12 chars" {})))
   (let [base (str/upper (str lou-prefix "00" entity-id12))]
     (str base (compute-lei-check-digits base)))))

(defn- default-entity-id12
  "Deterministic 12-char alphanumeric entity id when the caller supplies no
  explicit `entity-id12` -- derived from BOTH `jurisdiction` and `sequence`
  via base-36 arithmetic over their combined digit form (same to-digits /
  arbitrary-precision approach `mod97` uses), never from `sequence` alone.
  `sequence` (formation.store/next-sequence) is PER-JURISDICTION, so two
  different jurisdictions' Nth filing share the same `sequence` value --
  deriving the eid from `sequence` alone would make two entirely unrelated
  companies in two different jurisdictions receive the textually IDENTICAL
  LEI the moment both happen to be, say, each jurisdiction's first-ever
  filing (sequence 0), violating ISO 17442's global-uniqueness guarantee."
  [jurisdiction sequence]
  (let [alnum (str/upper (str/replace (str jurisdiction sequence) #"[^0-9A-Za-z]" ""))
        n #?(:clj  (java.math.BigInteger. ^String (to-digits alnum))
             :cljs (js/BigInt (to-digits alnum)))
        b36 (str/upper #?(:clj  (.toString ^java.math.BigInteger n 36)
                               :cljs (.toString n 36)))
        ;; the last 12 base-36 digits of n == n mod 36^12 (place-value fact,
        ;; same as "last 3 decimal digits" == n mod 1000) -- a deterministic
        ;; reduction into the 12-char alphanumeric space, not a truncation
        ;; that discards jurisdiction's contribution.
        last12 (subs b36 (max 0 (- (count b36) 12)))]
    (str (apply str (repeat (max 0 (- 12 (count last12))) "0")) last12)))

;; -- registry records --

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  government registry's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-incorporation
  "Validate + construct a company incorporation registration DRAFT. Pure
  function -- does not touch any real government registry."
  ([entity-name officers capital articles address jurisdiction sequence]
   (register-incorporation entity-name officers capital articles address jurisdiction sequence "OPER" nil))
  ([entity-name officers capital articles address jurisdiction sequence lou-prefix entity-id12]
   (when-not (and entity-name (not= entity-name ""))
     (throw (ex-info "incorporation: entity_name required" {})))
   (when-not (seq officers)
     (throw (ex-info "incorporation: at least one officer required" {})))
   (when (< capital 0)
     (throw (ex-info "incorporation: capital must be >= 0" {})))
   (when-not (and articles (not= articles ""))
     (throw (ex-info "incorporation: articles required" {})))
   (when-not (and address (not= address ""))
     (throw (ex-info "incorporation: address required" {})))
   (when (< sequence 0)
     (throw (ex-info "incorporation: sequence must be >= 0" {})))
   (let [registry-number (str (str/upper jurisdiction) "-" (zero-pad sequence 8))
         base-eid (or entity-id12 (default-entity-id12 jurisdiction sequence))
         eid (-> base-eid
                 (subs 0 (min 12 (count base-eid)))
                 (#(str (apply str (repeat (max 0 (- 12 (count %))) "0")) %))
                 str/upper)
         lei (assign-lei lou-prefix eid)
         record {"record_id" registry-number
                 "kind" "incorporation-draft"
                 "entity_name" entity-name
                 "officers" (vec officers)
                 "capital" capital
                 "jurisdiction" jurisdiction
                 "lei" lei
                 "immutable" true}]
     {"record" record "lei" lei "registry_number" registry-number
      "certificate" (unsigned-certificate "IncorporationCertificate" registry-number registry-number)})))

(defn register-change
  "Append-only amendment draft. Never overwrites the incorporation record."
  [registry-number changed-fields effective-date]
  (when-not (and registry-number (not= registry-number ""))
    (throw (ex-info "change: registry_number required" {})))
  (when-not (seq changed-fields)
    (throw (ex-info "change: changed_fields required" {})))
  {"record" {"record_id" (str registry-number "#chg@" effective-date)
             "kind" "change-draft"
             "registry_number" registry-number
             "changed" (into {} changed-fields)
             "effective_date" effective-date
             "immutable" true}})

(defn register-dissolution
  "Append-only dissolution (解散/清算) draft. Terminal event for the entity,
  but the registry-number and its history are never deleted (G5,
  append-only) -- dissolution is one more appended record, same as an
  amendment."
  [registry-number reason effective-date]
  (when-not (and registry-number (not= registry-number ""))
    (throw (ex-info "dissolve: registry_number required" {})))
  (when-not (and reason (not= reason ""))
    (throw (ex-info "dissolve: reason required" {})))
  {"record" {"record_id" (str registry-number "#dissolved@" effective-date)
             "kind" "dissolution-draft"
             "registry_number" registry-number
             "reason" reason
             "effective_date" effective-date
             "immutable" true}})

(defn append
  "Append a registry record, returning a NEW list (never mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))

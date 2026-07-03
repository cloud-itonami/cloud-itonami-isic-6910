(ns formation.phase-test
  "The phase table as executable tests. The single invariant this repo
  cannot regress on: `:filing/submit`, `:registry/amend`,
  `:registry/dissolve` (and `:payment/remit`, if it is ever added) must
  NEVER be a member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [formation.phase :as phase]))

(def actuation-ops
  "Every op that touches a real government registry/payment. This set is
  the single source of truth for `filing-submit-never-auto-at-any-phase`
  below -- add a new actuation op here, not just in formation.phase, so a
  forgotten :auto exclusion fails loudly instead of silently."
  #{:filing/submit :registry/amend :registry/dissolve})

(deftest filing-submit-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real actuation op"
    (doseq [[n {:keys [auto]}] phase/phases
            op actuation-ops]
      (is (not (contains? auto op))
          (str "phase " n " must not auto-commit " op)))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-intake
  (is (= #{:application/intake} (:auto (get phase/phases 3)))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :application/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :filing/submit} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :application/intake} :commit)))))

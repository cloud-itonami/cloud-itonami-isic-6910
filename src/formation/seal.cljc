(ns formation.seal
  "Seal craft for legal formation -- a thin, pure wrapper over
  `cloud-itonami/inkan`.

  Coordinates, glyph placement and SVG geometry come ONLY from
  `inkan.geometry` / `inkan.svg`. This namespace never invents layout:
  it validates the request (empty name / unsupported kind are HARD
  rejects), calls `inkan.svg/seal`, and returns hash + metadata for the
  formation ledger / SSoT.

  The composed SVG itself is craft, not actuation -- it has no legal
  force as a seal of office (inkan README is explicit about that). The
  formation actor records that a seal image was composed, under
  governor scrutiny, so an operator can later attach it to a filing
  package."
  (:require [kotoba.lang.text :as str]
            [inkan.geometry :as igeom]
            [inkan.svg :as isvg])
  #?(:clj (:import (java.security MessageDigest))))

(def supported-kinds
  "Kinds allowed for `:seal/compose`. Sourced from `inkan.geometry/kinds`
  only -- never invent a kind here, never hard-code a parallel list that
  can drift from inkan."
  (into #{} (map :kind igeom/kinds)))

(defn- blank-text?
  [s]
  (or (nil? s) (str/blank? (str s))))

(defn validate-spec
  "HARD-violation rules for a seal compose request. Returns a (possibly
  empty) vector of `{:rule kw :detail str}` maps -- same shape the
  RegistrarGovernor uses for every other hard check.

  Rules:
    :empty-seal-text       -- name/company text is blank
    :unsupported-seal-kind -- kind is not one of inkan's supported kinds"
  [{:keys [kind text]}]
  (cond-> []
    (blank-text? text)
    (conj {:rule :empty-seal-text
           :detail "印影の氏名/社名(text)が空のため組版できない"})
    (or (nil? kind) (not (contains? supported-kinds kind)))
    (conj {:rule :unsupported-seal-kind
           :detail (str "未対応の印影 kind: " (pr-str kind)
                        "（inkan が提供する " (pr-str (sort supported-kinds))
                        " のみ）")})))

(defn sha256-hex
  "SHA-256 hex digest of a UTF-8 string. JVM only (formation tests run on
  the JVM; cljs path is not required for the craft wire)."
  [s]
  #?(:clj
     (let [md (MessageDigest/getInstance "SHA-256")
           bs (.digest md (.getBytes (str s) "UTF-8"))]
       ;; BigInteger(1, bytes) preserves leading-zero nibbles via %064x.
       (format "%064x" (BigInteger. 1 bs)))
     :cljs
     (throw (ex-info "formation.seal/sha256-hex is JVM-only" {}))))

(defn compose
  "Compose a seal via inkan. Returns one of:

    {:ok? true  :svg <str> :metadata {...}}
    {:ok? false :violations [{...} ...]}

  Metadata carries hash + the input fields that identify the craft
  (kind/text/size-mm/source). Layout coordinates are NOT re-derived here
  -- they live inside the SVG that inkan produced."
  [spec]
  (let [viols (validate-spec spec)]
    (if (seq viols)
      {:ok? false :violations viols}
      (let [;; inkan fills defaults (size-mm/color/font) from its own
            ;; default-spec; we only pass through what the request set.
            svg (isvg/seal spec)
            kind (:kind spec)
            text (:text spec)]
        {:ok? true
         :svg svg
         :metadata {:kind        kind
                    :text        text
                    :inner-text  (:inner-text spec)
                    :date        (:date spec)
                    :size-mm     (or (:size-mm spec) (:size-mm igeom/default-spec))
                    :svg-hash    (sha256-hex svg)
                    :svg-bytes   #?(:clj (alength (.getBytes svg "UTF-8"))
                                    :cljs (count svg))
                    :source      "inkan.svg/seal"}}))))

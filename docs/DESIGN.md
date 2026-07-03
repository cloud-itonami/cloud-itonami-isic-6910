# Formation Actor Design — Registrar-LLM as a contained intelligence node

## 1. 前提: なぜ actor 層が要るのか

会社設立(法人登記)の実務は「どの書類が要るか」「本人確認は足りているか」
「制裁リストに引っかからないか」「実際に政府へ提出し、手数料を払う」という
4つの異なる性質の判断からなる。LLM は書類チェックリストのドラフトや intake
の正規化には強いが、**どの法域のどの情報源が公式か、誰が制裁対象か、いつ
"下書き"が"実際の提出/送金"に変わるか**については何の権限も判断根拠も持たない。
LLM に直接ファイリングさせる/送金させる設計は、存在しない法域要件の捏造、
制裁対象者の見落とし、無権限での実提出という3つの失敗モードを構造的に
抱え込む。

したがって設計課題は「LLM で法人設立業務を回す」ことではなく、**「LLM を
信頼境界の内側に封じ込め、法域要件の真正性・KYC/制裁チェック・監査・人間承認の
層をどう被せるか」**である。これは `robotaxi-actor` が AR1（安全機構の無い
研究モデル）を SafetyGovernor で封じ込めた構図、`cloud-itonami-6310`
(`gftd-talent-actor`) が HR-LLM を PolicyGovernor で封じ込めた構図の、
そのままの写像である。

## 2. アクター・トポロジ（監督ツリー）

```
formation.operation/build          (OperationActor: langgraph-clj StateGraph)
 ├─ :advise  → formation.registrarllm  (Registrar-LLM, 封じ込め、proposal のみ)
 ├─ :govern  → formation.governor      (RegistrarGovernor, 独立系統)
 ├─ :decide  → formation.phase         (Phase 0→3 rollout gate)
 └─ :commit / :hold / :request-approval → formation.store (SSoT + 台帳)
```

## 3. OperationActor 内部

### 3.1 注入される3つの依存（すべて swap）

- **Store** -- `MemStore`（既定・依存なし）‖ `DatomicStore`（`langchain.db`
  経由、Datomic Local / kotoba-server pod へ差し替え可能）。両バックエンドは
  同一 `Store` protocol contract を通す（`test/formation/store_contract_test.clj`
  で MemStore ≡ DatomicStore を保証） -- `cloud-itonami-6310` / `ai-gftd-itonami`
  と同じ `:db-api` 駆動パターン。
- **Advisor** -- `mock-advisor`（決定論的、デモ/テスト既定）‖
  `llm-advisor`（`langchain.model` 経由の実 LLM）。
- **Phase** -- `context` の `:phase` キー（0..3）で注入。

## 4. RegistrarGovernor（独立検閲層）

11チェック、優先順位順。最初の9つは HARD（人間が承認で上書き不可）:

1. **effect-matches-op** -- 提案の `:effect`（commit 時に実際にSSoTへ
   書き込まれる内容）が、リクエストの `:op` に紐づく**唯一正当な**
   `:effect`（`formation.governor/op->effect`）と一致しているか。
   `formation.operation/commit-record` は `:effect` を advisor（信頼
   できない実LLMを含む）の提案からそのまま取るため、この検査が無いと
   `:jurisdiction/assess` という無害に見えるリクエストに対して
   `:effect :filing/mark-submitted` を返す提案が通り得る -- しかも
   以下の全チェックはリクエストの `:op` を見て判定するため、「assess
   として無害に見える」まま実際には filing 相当の書き込みが実行され、
   spec-basis-for-filing・document-complete・KYC-complete の**どれも
   一度も検査されない**まま実登記が完了してしまう（実機再検証済み:
   Addendum 12）。
2. **spec-basis** -- `:jurisdiction/assess` / `:filing/submit` /
   `:registry/amend` / `:registry/dissolve` の提案が `formation.facts` の
   公式ソースを引用しているか。引用が無ければ「法域要件の捏造」とみなし
   hold。**registry_number の存在だけでは足りない** -- 「変更/解散する
   記録がある」ことと「その法域での変更/解散手続きの法的根拠を知っている」
   ことは別で、amend/dissolve も spec-basis 引用を要求する。
3. **sanctions-hit** -- この提案に**関わる officer**（`formation.governor/
   officers-at-stake` -- filing なら申請の全officer、amendment なら
   `changed-fields :officers` が導入する新しいofficerだけ）が制裁/PEPリスト
   に一致していないか（このリクエストで判明した場合・既に store に記録済み
   の場合の両方をチェック）。一致すれば un-overridable hold。**住所変更のみ
   の amendment は officer に触れないので、この検査の対象にすら入らない**
   （無関係な officer の状態で不当にブロックしない）。
4. **kyc-complete** -- 同じ `officers-at-stake` の**全員**が実際に KYC
   スクリーニング済み(`:verdict :clear`)か。未スクリーニング（`nil`）は
   `:hit` ではないため `sanctions-hit` チェックだけでは検出できない --
   一度もスクリーニングされていない officer がいる filing、あるいは
   amendment で新規追加された未クリアの officer が、そのまま通ってしまう
   抜け穴を塞ぐ。
5. **document-complete** -- `:filing/submit` の時点で、法域の必要書類が
   実際に充足しているか（advisor の自己申告 confidence を信用せず、
   governor 自身が `formation.facts/required-docs-satisfied?` で検証）。
6. **post-filing-intake-block** -- `:application/intake` は**どのフェーズの
   `:auto` にも含まれる唯一の op**（`formation.phase`、事前入力を速くする
   ため）。それゆえ、対象申請が既に `:filed` または `:dissolved` なら
   intake 自体を hold する。さもないと capital・address・officers・status
   など何でも**人間承認ゼロ・governor スクルーティニーゼロで**書き換え
   られてしまう -- amend/dissolve が担保する actuation ゲート全体を
   迂回するバックドアになる。修正は常に「`:registry/amend`（または
   `:registry/dissolve`）を使う」であり、「intake を承認する」ではない
   （そもそも intake は escalate すらせず即 hold のため承認経路が無い）。
7. **intake-fabrication** -- filing 前の intake だからといって白紙委任
   ではない。`:registry-number`/`:lei`（実際の filing でのみ発行される）
   の設定、`:status` を `:filed`/`:dissolved`（実際の filing/dissolve
   でのみ到達する終端状態）にすること、patch の `:id` がリクエストの
   `:subject` と食い違うこと -- いずれも hold。最後のケースが無いと、
   `post-filing-intake-violations` は REQUEST の `subject` から申請を
   引くのに `formation.store` の `:application/upsert` は patch 自身の
   `:id` を書き込み先にするという不一致を突いて、「まだ filing していない
   おとりの subject」を宣言しつつ patch.id で**別の、既に filed 済みの
   申請**を書き換えられてしまう（Addendum 15）。
8. **amendment-target** -- `:registry/amend` の対象申請に registry_number
   （= 初回登記済み）があるか、かつ変更内容が空でないか、かつ
   **`changed-fields` が `amendable-fields` allowlist（`:entity-name`
   `:address` `:capital` `:articles` `:officers`）以外のフィールドに触れて
   いないか**。未登記への変更登記提案・空の変更提案・許可外フィールド
   （`:status`・`:jurisdiction`・`:registry-number`・`:lei`・`:id`）への
   変更提案はすべて hold。最後のケースが無いと、無害に見える住所変更に
   `{:status :dissolved}` を紛れ込ませることで、`:registry/dissolve` 自身の
   検査（spec-basis、二重解散防止）を一切通さずに解散状態へ書き換えられ、
   registry-history には単なる change-draft しか残らない -- 監査台帳が
   実態と食い違う（Addendum 14）。
9. **dissolution-target** -- `:registry/dissolve` の対象申請に
   registry_number があるか、かつ既に解散済み（二重解散）でないか。

残り2つは SOFT（人間が承認すればよい）:

10. **confidence floor** -- confidence が閾値未満なら escalate。
11. **actuation gate** -- `:stake :actuation`（実際の政府提出・実際の変更
   登記提出・実際の解散登記提出・実際の手数料送金）は常に escalate。
   **`formation.phase` のどのフェーズの `:auto` 集合にも `:filing/submit`
   / `:registry/amend` / `:registry/dissolve` を含めない**ことと合わせて、
   「実アクチュエーションは常に人間が行う」という不変条件を governor と
   phase の2層で独立に強制する。

## 5. SSoT と監査台帳

`formation.store` の `MemStore` / `DatomicStore` はどちらも customer
application / officer directory / KYC 結果 / assessment 結果 /
台帳(ledger) / draft registry history を持つ。commit するのは `:commit`
ノードだけ。hold した提案も台帳には残る（何を・なぜ止めたかが追跡できる）。
`DatomicStore` は複合値（officer id リスト・KYC/assessment payload・台帳
fact・registry record）を EDN 文字列として保持する -- `talent.store` の
`:emp/protected` と同じ規約（`langchain.db` にサブエンティティ展開させない）。

## 6. LEI / registry ドラフト record

`formation.registry` は ISO 17442 LEI 発行（ISO 7064 MOD 97-10 チェック
digit を実装）+ registry-number 採番 + 追記型 amendment/dissolution record
を提供する。`matsurigoto`（etzhayyim/root, ADR-2606062300）の corp-registry
モジュールのポートで、スペック数学（LEI/MOD-97-10）はどの principal が
その法域の代理を行うかに依存しないため共有した。**このモジュールが返すのは
常にドラフト（`"proof" nil`, `"issued_by_registry" false`）であり、実際の
政府登記所が発行した証明書ではない。** LEI の entity-id（12桁）は
呼び出し元が明示しない限り `jurisdiction` + `sequence`（法域ごとの採番）
から base-36 演算で導出する -- `sequence` 単体（法域をまたいで再利用される
値）から導出すると、異なる法域の「その法域で何番目の filing か」が
たまたま一致しただけで**無関係な2社が同一のLEIを発行される**（詳細は
Addendum 13）。 `register-change`（変更登記）/
`register-dissolution`（解散登記）はそれぞれ `formation.store` の
`:registry/amend-submitted` / `:registry/dissolve-submitted` effect から
呼ばれ、元の incorporation record を書き換えず registry-history に追記する
（G5 style append-only）。解散も「削除」ではなく「もう1件の追記」であり、
履歴は消えない。

## 7. デモ（`clojure -M:dev:run`）

`formation.sim` は 1件のクリーンな申請（intake → assess → screen → filing
提案 → 人間承認 → commit → 変更登記提案 → 承認 → 解散提案 → 承認）と、
3件の HARD hold ケース（二重解散 / 制裁ヒット / 法域要件の捏造）を通し、
台帳とドラフト registry record の履歴（incorporation → change → dissolution
の3件、いずれも消えない）を出力する。

## 8. テスト（`clojure -M:dev:test`）

- `governor_contract_test.clj` -- 「Registrar-LLM は governor が拒否する
  record を決してファイル/送金しない」契約。
- `phase_test.clj` -- 「`:filing/submit` / `:registry/amend` /
  `:registry/dissolve` はどのフェーズの `:auto` にも含まれない」構造不変条件。
- `registry_test.clj` -- LEI/MOD-97-10 の conformance（matsurigoto 由来）。
- `facts_test.clj` -- 法域カバレッジは常に正直に報告される
  （無いものを「対応済み」と報告しない）。
- `store_contract_test.clj` -- `MemStore` ≡ `DatomicStore` の同一契約
  （`talent.store-contract-test` と同型のパリティテスト）。
- `llm_advisor_test.clj` -- `langchain.model/mock-model` でオフライン駆動する
  実 LLM 経路（`llm-advisor`）のテスト。クリーンな提案は governor を通り、
  法域捏造/制裁ヒットを申告する提案は confidence に関わらず hold、
  パース不能な応答は confidence 0 の noop に落ちて絶対に auto-commit しない
  （`talent.llm-advisor-test` と同型）。

## 9. Scope と cloud-itonami の位置づけ

このリポジトリは `cloud-itonami`（gftdcojp の自社業務オペレーション基盤）
そのものでも、`cloud-itonami` 本体の内部 lane でもない。KYC/書類保管/決済の
liability・custody・settlement を自社業務モデルと混ぜないため、独立した
open business blueprint (`cloud-itonami-{ISIC}` 系列, ADR-2607011000 /
ADR-2607012100 の慣行) として `cloud-itonami` org 直下に発行する。
どの事業者がこの actor を実運用するか（gftdcojp 自身か、各法域の免許を
持つ第三者 registered agent か）は実運用側の選択であり、本ソフトウェアは
その選択を強制しない。

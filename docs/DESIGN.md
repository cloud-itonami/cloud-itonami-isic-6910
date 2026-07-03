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

6チェック、優先順位順。最初の4つは HARD（人間が承認で上書き不可）:

1. **spec-basis** -- `:jurisdiction/assess` / `:filing/submit` の提案が
   `formation.facts` の公式ソースを引用しているか。引用が無ければ
   「法域要件の捏造」とみなし hold。
2. **sanctions-hit** -- 申請に関わる officer が制裁/PEPリストに一致して
   いないか（このリクエストで判明した場合・既に store に記録済みの場合の
   両方をチェック）。一致すれば un-overridable hold。
3. **document-complete** -- `:filing/submit` の時点で、法域の必要書類が
   実際に充足しているか（advisor の自己申告 confidence を信用せず、
   governor 自身が `formation.facts/required-docs-satisfied?` で検証）。
4. **amendment-target** -- `:registry/amend` の対象申請に registry_number
   （= 初回登記済み）があるか、かつ変更内容が空でないか。未登記への変更登記
   提案・空の変更提案はどちらも hold。

残り2つは SOFT（人間が承認すればよい）:

5. **confidence floor** -- confidence が閾値未満なら escalate。
6. **actuation gate** -- `:stake :actuation`（実際の政府提出・実際の変更
   登記提出・実際の手数料送金）は常に escalate。**`formation.phase` の
   どのフェーズの `:auto` 集合にも `:filing/submit` / `:registry/amend`
   を含めない**ことと合わせて、
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
digit を実装）+ registry-number 採番 + 追記型 amendment record を提供する。
`matsurigoto`（etzhayyim/root, ADR-2606062300）の corp-registry モジュールの
ポートで、スペック数学（LEI/MOD-97-10）はどの principal がその法域の代理を
行うかに依存しないため共有した。**このモジュールが返すのは常にドラフト
（`"proof" nil`, `"issued_by_registry" false`）であり、実際の政府登記所が
発行した証明書ではない。** `register-change`（変更登記）は `formation.store`
の `:registry/amend-submitted` effect から呼ばれ、元の incorporation record
を書き換えず registry-history に追記する（G5 style append-only）。

## 7. デモ（`clojure -M:dev:run`）

`formation.sim` は 1件のクリーンな申請（intake → assess → screen → filing
提案 → 人間承認 → commit）と、2件の HARD hold ケース（制裁ヒット / 法域
要件の捏造）を通し、台帳とドラフト registry record を出力する。

## 8. テスト（`clojure -M:dev:test`）

- `governor_contract_test.clj` -- 「Registrar-LLM は governor が拒否する
  record を決してファイル/送金しない」契約。
- `phase_test.clj` -- 「`:filing/submit` / `:registry/amend` はどのフェーズの
  `:auto` にも含まれない」構造不変条件。
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

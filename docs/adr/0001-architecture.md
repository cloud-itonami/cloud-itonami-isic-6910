# ADR-0001: cloud-itonami-M6910 — Registrar-LLM を封じ込めた知能ノードとする会社設立代行アクター設計

- Status: Accepted (2026-07-03)
- 関連: `robotaxi-actor` ADR-0001（研究モデルを信頼境界に封じ込める actor 設計）、
  `cloud-itonami-6310`（旧 `gftd-talent-actor`）ADR-0001（同型の PolicyGovernor
  パターン）、`ai-gftd-itonami`（CertGovernor パターン）、langgraph-clj ADR-0001
  （Pregel superstep + interrupt + Datomic checkpoint）、
  `matsurigoto`（etzhayyim/root, ADR-2606062300 -- corp-registry の LEI/MOD-97-10
  実装の由来）
- 文脈: 「全世界どこでも実際に法人設立を代行実行する」ニーズに対して、
  単一の中央集権的な commercial vendor が全法域の liability/custody/
  settlement を抱え込む設計ではなく、governed・forkable な OSS actor を
  多数の licensed operator が各法域で自己運用できる形で提供する。

## 課題

会社設立代行（company incorporation / registered-agent service）を
グローバルに提供するには、次の3つの異なる性質の判断が必要になる。

1. **法域要件の正しさ** -- 必要書類・法的根拠・提出先が、公式ソースに
   基づいているか。
2. **KYC/制裁チェック** -- 申請に関わる officer/shareholder が制裁・PEP
   リストに一致していないか。
3. **実アクチュエーション** -- 実際に政府へ提出し、実際に手数料を送金する
   という、後戻りのできない実世界の行為。

LLM はこれらのいずれについても、真正性の判断根拠・法的権限・実行責任を
持たない。したがって設計課題は「LLM で登記実務を回す」ことではなく、
**「LLM を信頼境界の内側に封じ込め、法域要件の真正性・KYC/制裁・監査・
人間承認の層をどう被せ、かつ実アクチュエーションを構造的に人間専用に
固定するか」**である。

加えて、この actor が etzhayyim（宗教法人としての統治体）と
cloud-itonami（gftdcojp の自社業務基盤）のどちらに属するべきかという
配置問題があった。KYC/書類保管/決済の liability・custody・settlement は
`repos.edn` の Consensys-pattern 3-axis OR-test（ADR-2605172400 /
ADR-2606011400）で1軸でも該当すれば vendor 側と判定される。一方で
実際の commercial の liability を単一の vendor に集中させると、
「全世界どこでも」に必要なスケールが取れない。

## 決定

### 1. Registrar-LLM は最下層の1ノードに封じ込め、直接ファイル/送金させない

`formation.registrarllm` は intake 正規化・法域要件チェックリスト・
KYC/制裁スクリーニング・filing 提案の4種類の proposal だけを返す。
どの proposal も SSoT への書き込みや実際の政府提出を直接行わない。

### 2. OperationActor = langgraph-clj StateGraph、1 run = 1 登記操作

`formation.operation/build` は `robotaxi-actor` / `cloud-itonami-6310` /
`ai-gftd-itonami` と同型の StateGraph（intake → advise → govern → decide →
commit | hold | request-approval）。1回の graph run が1つの登記操作に
対応し、無限の内部ループを持たない。

### 3. RegistrarGovernor は Registrar-LLM と別系統

`formation.governor` は spec-basis・sanctions-hit・document-complete
（HARD、人間による上書き不可）+ confidence-floor・actuation-gate
（SOFT、人間が承認判断）の5チェックを持つ。

### 4. 実アクチュエーションは構造的に常に人間専用（2層で独立に強制）

`formation.governor` の actuation gate（`:stake :actuation` は常に
escalate）と `formation.phase` のフェーズ表（`:filing/submit` はどの
フェーズの `:auto` にも含まれない）の**両方**が、実際の政府提出・実際の
手数料送金を自動化しない。片方の実装ミスがもう片方で吸収される
二重の設計にした。

### 5. LEI/registry の spec 数学は matsurigoto から移植、principal は分離

`formation.registry` は `matsurigoto`（etzhayyim/root）の corp-registry
モジュールが実装した ISO 17442 LEI + ISO 7064 MOD 97-10 チェックdigitの
ロジックをそのまま移植した。matsurigoto は「政体(etzhayyim またはそれを
採用する国家)自身の統治」をモデル化するのに対し、この actor は
「実在の政府 registry を相手にする licensed operator を支援する」ことを
モデル化する -- 同じスペック数学、異なる principal。

### 6. 配置は cloud-itonami 本体でも cloud-itonami-{ISIC} 系列の外でもなく、独立 blueprint repo

KYC/custody/settlement の liability は自社の内部業務オペレーション基盤
(`cloud-itonami` 本体、gftdcojp 自社業務用)とも、`cloud-itonami` 本体の
内部 lane 化とも混ぜない。既存の `cloud-itonami-{ISIC}` open business
blueprint 系列（ADR-2607011000 / ADR-2607012000、35 repo が `cloud-itonami`
org へ移管済み・ADR-2607012100）と同じ形で、**新規 repo として最初から
`cloud-itonami` org 直下に**発行する（同 ADR の「今後は transfer を経ずに
直接作成してよい」という慣行に従う）。ISIC Rev.5 の分類は 6910 (Legal
activities) -- 会社設立代行はこの分類に含まれる。

これにより liability は「gftdcojp が単独で全世界を背負う」構造ではなく、
「OSS actor を各法域の licensed operator（gftdcojp 自身を含む）が
自己運用し、各自の法域免許の下で liability を持つ」構造になる。
これは `cloud-itonami-6310` が「HR SaaS を自前運用する」ことで
データ主権を守るのと同じ論理の、liability 版である。

## 帰結

- (+) 「全世界どこでも代行実行する」というスケール要求に対して、単一
  vendor の liability 集中を避けつつ、governed・auditable な実行基盤を
  共通化できる。
- (+) 実アクチュエーション不変条件（governor + phase の2層）は
  `test/formation/phase_test.clj` の `filing-submit-never-auto-at-any-phase`
  でリグレッションを機械的に検出できる。
- (+) `matsurigoto` の LEI/MOD-97-10 実装を再利用し、車輪の再発明をしない。
- (-) 本 R0 は10法域（JPN/USA-DE/GBR/DEU/EST/KOR/IND/SGP/NZL/CAN）のみ
  spec-basis を持つ。~194法域のうち、大半は未カバーであり、正直に
  `formation.facts/coverage` で報告する。
- (-) `MemStore` のみで、Datomic/kotoba-server backend への接続は未実装
  （`cloud-itonami-6310` / `ai-gftd-itonami` が既に実証した `:db-api`
  駆動パターンをそのまま適用できる見込みだが、本 ADR の対象外）。
- (-) 実際の政府ポータル統合・実際の決済統合・実際のKYC/制裁スクリーニング
  プロバイダ統合は、この OSS actor の対象外（各 operator の責任）。

## Addendum (2026-07-03) -- coverage 拡大 10→21法域

R0 の初期10法域（JPN/USA-DE/GBR/DEU/EST/KOR/IND/SGP/NZL/CAN）に、
FRA/NOR/DNK/FIN/BEL/CZE/AUS/ZAF/CHE/NLD/ISR の11法域を追加した
（`formation.facts/catalog`、各エントリは公式ソースを引用、捏造なし）。
`test/formation/facts_test.clj` は変更なしで green（既存テストは特定の
jurisdiction 集合に依存しないため）。上記「帰結」の「10法域」という記述は
追加当時の事実として保持し書き換えない -- 現在の法域数は README /
`formation.facts/coverage` を参照。

## 代替案と不採用理由

| 案 | 採否 | 理由 |
|---|---|---|
| etzhayyim の `matsurigoto/corp-registry` を実運用まで拡張 | ❌ | matsurigoto は G1(no-operator-master-key)/G3(`:operated-by` ∈ Council\|adopting-state) という憲法的制約を持ち、不特定多数の一般顧客向け商用代行のprincipalに構造的に当てはまらない |
| `cloud-itonami` 本体（gftdcojp 自社業務基盤）に新規 lane として追加 | ❌ | `cloud-itonami` の activity/decision/effect/audit モデルは gftdcojp *自社* 業務用であり、顧客の KYC/custody/settlement を扱う規制対応サービスとは設計前提が異なる |
| gftdcojp 配下の非公開 vendor repo として実装 | ❌ | liability を単一 vendor に集中させると「全世界どこでも」のスケールが取れない。OSS + 複数 operator 自己運用モデルの方が本要求に合う |
| 全ての `cloud-itonami-{ISIC}` blueprint と同様に robotics premise（ADR-2607011000）を字義通り適用 | ❌（部分的） | 会社設立代行は物理領域作業を伴わないデジタル/書類業務であり、`cloud-itonami-6310` (HR SaaS) と同様に robotics premise の対象外として扱う。cloud-itonami-6310 自体が robotics retrofit の対象外であった先例に倣う |

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

## Addendum 3 (2026-07-03) -- coverage further 21→31法域

IRL/HKG/PRT/ESP/ITA/SWE/POL/MEX/BRA の9法域を追加した（`formation.facts/catalog`、
各エントリは公式ソースを引用、捏造なし）。BRA は連邦(CNPJ)＋州(Junta Comercial)の
併記が必要な federalism note を USA-DE / CAN と同様の形式で明記。全32 tests /
172 assertions green、lint clean。

## Addendum 2 (2026-07-03) -- DatomicStore backend 実装、MemStore ≡ DatomicStore parity

上記「帰結」の「`MemStore` のみで Datomic/kotoba-server backend への接続は
未実装」を解消した。`formation.store` に `DatomicStore`（`langchain.db`
経由）を追加し、`cloud-itonami-6310` / `ai-gftd-itonami` と同じ
`:db-api` 駆動パターンで実装。`test/formation/store_contract_test.clj`
（`talent.store-contract-test` と同型）が両バックエンドの同一契約を保証する。

実装過程で発見・修正した実バグ: `registry-history` に格納する値の形が
バックエンド間で不一致だった（`MemStore` は既存の `registry/append` の
規約どおり `result` の `"record"` サブマップのみを格納するのに対し、初回
実装の `DatomicStore` は `result` 全体をエンコードしていた）。
`store_contract_test.clj` の parity テストがこの不一致をその場で検出し、
`DatomicStore` 側を `MemStore` の規約に合わせて修正した -- **これが
MemStore ≡ DatomicStore parity テストを書く理由そのものの実例**。

Store は本 addendum で `MemStore` ‖ `DatomicStore` の2択になったが、
実際の Datomic Local / kotoba-server pod への接続確認（`:db-api` を
`langchain.kotoba-db/kotoba-api` に差し替えての live 検証）はまだ
行っていない -- `ai-gftd-itonami` の "kotobase.net backend 配線" と同様、
次の follow-up。28 tests / 161 assertions、lint clean。

## Addendum 4 (2026-07-03) -- 変更登記（:registry/amend）の配線

`formation.registry/register-change`（変更登記の追記型record生成）は当初
`registry_test.clj` 単体でしか呼ばれておらず、actor の操作フロー
（registrarllm/governor/phase/store/operation）からは到達不能だった --
初回登記はできるが、住所変更等の事後修正が一切できない実装だった。これを
解消:

- `formation.registrarllm/propose-amendment`（新規）-- `:registry/amend` の
  proposal を drafts。対象 application に `registry-number` が無い場合
  （未登記）は confidence を落として提案する（governor が hard hold する
  前提）。ALWAYS `:stake :actuation`。
- `formation.governor`: 4番目の HARD check `amendment-violations` を追加
  -- 対象未登記 (`:no-registry-number`) または変更内容が空 (`:empty-amendment`)
  は un-overridable hold。
- `formation.phase`: `:registry/amend` を `write-ops` に追加、**どの
  phase の `:auto` にも追加しない**（`:filing/submit` と同じ構造的不変条件）。
  `test/formation/phase_test.clj` は `actuation-ops` 集合（`:filing/submit`
  `:registry/amend`）を単一ソースにしてこの2つを一括検証するよう一般化。
- `formation.store`: `:registry/amend-submitted` effect を両バックエンド
  （`MemStore`/`DatomicStore`）に実装。`register-change` の draft record を
  registry-history に**追記**し（元の incorporation record は書き換えない）、
  application 自体にも変更フィールドをマージする。
- `formation.sim`: 住所変更の変更登記デモを追加（intake→assess→screen→
  filing 承認→**amend 承認**→ledger/registry-history 出力）。

3 tests / 18 assertions を追加（対象未登記→hold、空変更→hold、クリーン
amendment→常に escalate→承認でcommit・registry-historyに2件目が追記される
/拒否で何も変わらない）。35 tests / 190 assertions 全体 green、lint clean。

## Addendum 5 (2026-07-03) -- 会社解散（:registry/dissolve）の配線

法人ライフサイクルの最後のピース：設立(incorporate)・変更(amend)はあるが
解散(dissolve/清算)が無かった。Addendum 4 と全く同じ形で配線:

- `formation.registry/register-dissolution`（新規）-- 解散のドラフト record
  （`"kind" "dissolution-draft"`）を生成。元の incorporation record は
  書き換えない（G5、削除ではなく追記）。
- `formation.registrarllm/propose-dissolution`（新規）-- `:registry/dissolve`
  の proposal を drafts。ALWAYS `:stake :actuation`。対象未登記、または
  既に `:status :dissolved` の場合は confidence を落として提案する
  （governor が hard hold する前提）。
- `formation.governor`: 5番目の HARD check `dissolution-violations` を追加
  -- 対象未登記 (`:no-registry-number`) または既に解散済み
  (`:already-dissolved`、**二重解散の防止**) は un-overridable hold。
- `formation.phase`: `:registry/dissolve` を `write-ops` に追加、どの
  phase の `:auto` にも追加しない。`phase_test.clj` の `actuation-ops` に
  追加してこの3op（filing/amend/dissolve）を一括検証。
- `formation.store`: `:registry/dissolve-submitted` effect を両バックエンド
  に実装。application の `:status` を `:dissolved` にし、解散 record を
  registry-history に追記する。

5 tests / 26 assertions を追加（対象未登記→hold、クリーン dissolve→常に
escalate→承認でcommit(status=:dissolved)/拒否で何も変わらない、**二重解散
は un-overridable hold**）。`formation.sim` に住所変更後の解散デモ
+ 二重解散 HARD hold デモを追加。40 tests / 216 assertions 全体 green、
lint clean。

これで incorporate → amend → dissolve の3操作すべてが同一の actuation
不変条件（governor + phase の2層、常に人間承認）の下で動く。

## Addendum 6 (2026-07-03) -- amend/dissolve に spec-basis 引用を要求（G2 discipline の一貫適用）

発見: `:registry/amend` / `:registry/dissolve` は「registry_number がある
こと」だけをチェックしていて、`:jurisdiction/assess` / `:filing/submit`
に課している「公式ソースを引用しているか」（G2, `spec-basis-violations`）
の対象外だった。「変更/解散する記録がある」と「その法域の変更/解散手続き
の法的根拠を知っている」は別の主張であり、後者を検証しないのは一貫性の
欠如だった。

修正:

- `formation.registrarllm/propose-amendment` / `propose-dissolution` --
  対象 application の `:jurisdiction` から `formation.facts/spec-basis`
  を引き、`:cites` に registry_number に加えて `:legal-basis` /
  `:provenance` を含める。spec-basis が見つからない場合は confidence を
  落として提案（governor が hold する前提）。
- `formation.governor/spec-basis-violations` -- 対象 op 集合に
  `:registry/amend` / `:registry/dissolve` を追加。

この変更は通常フローでは無害（`:filing/submit` に到達した時点で
`document-violations` 経由で spec-basis が既に検証済みのため）。実際に
効くのは **申請の `:jurisdiction` が登記後に書き換わる**ような
データ不整合ケース（`:application/intake` の upsert は現状 jurisdiction
の変更を制限していない）-- そのケースでも amend/dissolve が誤った法域の
記録を追記できないことを保証する。

2 tests / 4 assertions を追加（`drift-jurisdiction-to-atl!` ヘルパーで
登記後の法域drift を再現し、amend/dissolve 双方が `:no-spec-basis` で
hold することを検証）。42 tests / 220 assertions 全体 green、lint clean。

## Addendum 7 (2026-07-03) -- KYC未実施のまま filing が通る抜け穴を修正

発見: `sanctions-violations` は officer の KYC verdict が `:hit` かどうか
だけを見ていた。**一度もスクリーニングされていない officer**（`kyc-of`
が `nil` を返す）は `(= :hit nil)` が false になるため、**KYCを一度も
実施していない状態の filing が governor を素通りしていた** -- README の
"Core Contract" 図が示唆する「screen してから filing」という運用が、
実際には強制されていなかった。

修正: `formation.governor/kyc-completeness-violations`（新規、HARD）を
追加。`:filing/submit` 時点で申請の**全 officer**が `:verdict :clear` を
持っているかを検証し（`:incomplete` や未実施(`nil`)は `:clear` と区別
される）、満たさなければ `:kyc-incomplete` で un-overridable hold。

テスト用に `formation.store/demo-data` へ officer `o-3`（sanctions-hit?
false, id-doc nil）を追加 -- どの application にもデフォルトでは
アタッチされない「予備」officer で、`:sanctions-hit` を誤って同時に
トリガーせずに `:incomplete` verdict を再現するために使う（既存の o-2
は sanctions-hit? true のため、スクリーニングすると必ず `:hit` になり
`:incomplete` を再現できない）。

4 tests / 6 assertions を追加（KYC未実施→hold、`:incomplete` verdict
（o-3、id-doc欠落）→hold、既存の filing 成功系テストは o-1 を screen
済みのため無変更で green）。44 tests / 226 assertions 全体 green、
lint clean。

## Addendum 8 (2026-07-03) -- amendment 経由の officer 追加が sanctions/KYC 検査を素通りする穴を修正

発見: `sanctions-violations` / `kyc-completeness-violations` は
`(when (= op :filing/submit) ...)` で filing にしか officer チェックを
かけていなかった。しかし `:registry/amend` の `changed-fields` は自由形式
map で、`{:officers [...]}` を含めれば application の officer 名簿を
実質的に差し替えられる -- **制裁対象者を「住所変更」等と一緒に紛れ込ませて
追加登記する amendment が、governor の officer チェックを一切受けずに
escalate（人間承認だけ）まで進んでしまっていた**。

修正: `formation.governor/officers-at-stake`（新規）を単一の判定源にし、
`sanctions-violations` と `kyc-completeness-violations` の両方がこれを
参照する:

- `:filing/submit` -- 申請の全 officer（従来通り）
- `:registry/amend` -- `changed-fields :officers` が**導入する** officer
  のみ（officer に触れない amendment は対象外 -- 無関係な officer の状態で
  不当にブロックしない）

`:hit` verdict は actor 自身の `:kyc/screen` フローでは**store に書き込まれる
前に必ず hold する**ため（`sanctions-hit-is-held-and-unoverridable` が保証）、
`hit-on-file?` を直接演習するテストは `store/commit-record!` で `:hit` を
直接 seed する（外部の再スクリーニング/ウォッチリスト更新で、以前クリアだった
officer が後から `:hit` になる現実的経路をモデル化）。

3 tests / 12 assertions を追加（制裁ヒット記録済み officer の追加→hold、
未スクリーニング officer の追加→hold(`:kyc-incomplete`)、officer に
触れない住所変更 amendment は無関係な officer の状態に影響されず正常に
escalate→commit）。47 tests / 234 assertions 全体 green、lint clean。

## Addendum 9 (2026-07-03) -- 登記済み申請への intake 経由の無検閲書き換えを阻止（本 ADR 中で最も重大な修正）

発見（実機再現で確認）: `:application/intake` は `formation.phase` の
**どのフェーズの `:auto` にも入る唯一の op**（事前入力を速くする設計
意図）であり、かつ governor 側にも「filed/dissolved 後は intake 禁止」
という制約が一切無かった。結果、**登記済み(`:filed`)の申請に対して
`:application/intake` で `capital`・`address`・`officers` を含む
**あらゆるフィールドを、人間承認ゼロ・governor 検査ゼロで**即座に
書き換えられた**。実機で検証: 資本金 1,000,000→1、住所を偽の値に、
制裁対象の o-2 を officer に追加 -- すべて一発の intake で自動commit。
これは Addendum 8 の「amendment 経由の officer 追加が sanctions/KYC を
素通り」よりさらに広範な穴で、amend/dissolve が担保する actuation
ゲート全体（spec-basis 引用・officer screening・人間承認）を**完全に
迂回**できた。

修正: `formation.governor/post-filing-intake-violations`（新規、HARD）
を追加。対象申請が既に `:filed` または `:dissolved` なら `:application/
intake` そのものを無条件で hold する（`:post-filing-intake-blocked`）。
パッチの中身が無害に見えるかどうかは問わない -- 登記後の変更は必ず
`:registry/amend` / `:registry/dissolve` を通す。

副作用: 既存の `drift-jurisdiction-to-atl!` テストヘルパー（Addendum 6）
が `:application/intake` 経由で法域drift を再現していたが、この修正で
**その drift 自体がもう起こり得なくなった**（intake がそもそも hold
される）。ヘルパーを `store/commit-record!` 直呼びに変更し、
「actor の操作では届かないが、より低レイヤーのデータ不整合としては
あり得る」シナリオとして再定義（Addendum 8 の `:hit` 直接 seed と同じ
手法）。

4 tests / 11 assertions を追加（資本金/住所/officer の smuggle 試行→
hold・申請完全不変、無害に見えるパッチでも同様にhold、解散後も同様に
保護、**登記前の intake は従来通り正常に auto-commit** することを確認）。
51 tests / 245 assertions 全体 green、lint clean。実機再現スクリプトで
修正前後の挙動差を確認済み（修正後は smuggled intake が申請を一切
変更しないことを確認）。

## 代替案と不採用理由

| 案 | 採否 | 理由 |
|---|---|---|
| etzhayyim の `matsurigoto/corp-registry` を実運用まで拡張 | ❌ | matsurigoto は G1(no-operator-master-key)/G3(`:operated-by` ∈ Council\|adopting-state) という憲法的制約を持ち、不特定多数の一般顧客向け商用代行のprincipalに構造的に当てはまらない |
| `cloud-itonami` 本体（gftdcojp 自社業務基盤）に新規 lane として追加 | ❌ | `cloud-itonami` の activity/decision/effect/audit モデルは gftdcojp *自社* 業務用であり、顧客の KYC/custody/settlement を扱う規制対応サービスとは設計前提が異なる |
| gftdcojp 配下の非公開 vendor repo として実装 | ❌ | liability を単一 vendor に集中させると「全世界どこでも」のスケールが取れない。OSS + 複数 operator 自己運用モデルの方が本要求に合う |
| 全ての `cloud-itonami-{ISIC}` blueprint と同様に robotics premise（ADR-2607011000）を字義通り適用 | ❌（部分的） | 会社設立代行は物理領域作業を伴わないデジタル/書類業務であり、`cloud-itonami-6310` (HR SaaS) と同様に robotics premise の対象外として扱う。cloud-itonami-6310 自体が robotics retrofit の対象外であった先例に倣う |

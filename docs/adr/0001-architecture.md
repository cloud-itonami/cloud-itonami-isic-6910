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

## Addendum 10 (2026-07-03) -- governor 契約の DatomicStore cross-backend 証明

発見: `test/formation/governor_contract_test.clj` の全テスト（~25件、
Addendum 1-9 で追加した hold ケース含む）は `formation.store/seed-db`
（MemStore）専用だった。`store_contract_test.clj` は生の CRUD parity
（application/officer/kyc/assessment/ledger/registry-history の
読み書き）を保証しているが、それは **advisor→governor→phase→commit
という actor 全体の flow が両バックエンドで同じ挙動をする**ことまでは
証明しない -- 特に Addendum 9 で直した重大な post-filing-intake
バイパス修正は、DatomicStore 経由では一度も演習されていなかった。

修正: `fresh` を `db-ctor` を受け取れるよう一般化
（`(fresh)` は従来通り MemStore、`(fresh store/datomic-seed-db)` で
DatomicStore に切替）。既存 ~25 テストは変更なし（後方互換）。新たに
2つの cross-backend テストを追加:

- `post-filing-intake-blocked-on-datomic-store-too` -- Addendum 9 の
  重大修正が DatomicStore でも同一に効くことを確認。
- `full-lifecycle-on-datomic-store-too` -- incorporate → amend →
  dissolve → 二重解散hold の全ライフサイクルを DatomicStore で通し、
  registry-history 件数・application status が MemStore 版と同じ
  遷移をすることを確認。

2 tests / 12 assertions を追加。53 tests / 257 assertions 全体 green、
lint clean。全テストが一発 green（バックエンド抽象化がリークしていない
ことの追加証拠）。

## Addendum 11 (2026-07-03) -- 監査台帳が「誰が承認したか」を実際には答えられなかった

発見: `formation.store` 自身の docstring は「who filed what, for which
customer, on what jurisdictional basis, **approved by whom**, is always
a query over an immutable log」と明記しているが、コードはこれを満たして
いなかった。`:request-approval` ノードの approved 分岐は承認者 id
(`(:by approval)`)を `record` の `:payload` キーへマージしていたが、
`formation.store/commit-record!` の `:filing/mark-submitted` /
`:registry/amend-submitted` / `:registry/dissolve-submitted` はどれも
`value`（元の proposal 値。approved-by を含まない）だけを読み、`payload`
を一切参照しない。台帳に書かれる `:committed` fact
（`formation.operation/commit-fact`）自体も承認者情報を持つフィールドを
一切持たなかった。つまり **filing/amend/dissolve のような、常に人間承認
を要する actuation について、台帳を見ても実際に誰が承認したかは一度も
分からなかった** -- ドキュメントが噓をついていた。

修正: `commit-fact` に `approval`（resume 時の `{:status :approved :by
..}`）を引数として追加し、`:approved-by (:by approval)` を台帳 fact に
直接持たせる。`:commit` ノードは state channel の `approval` をそのまま
渡す（auto-commit 経路では `approval` channel が never-set の `nil` の
ままなので、`:approved-by` は正しく `nil` になり、存在しない承認者を
捏造しない）。`:request-approval` の既存 `:payload` マージは変更して
いない（assess/kyc の SSoT payload に元々別経路で紛れ込んでいた副作用
であり、本修正のスコープ外）。

2 tests / 3 assertions を追加:
- `auto-committed-ledger-fact-has-no-fabricated-approver` -- phase 3 の
  `:application/intake` auto-commit は `:approved-by` が `nil` の
  ままであること（人間が関与していないので承認者を捏造しない）。
- `committed-ledger-fact-records-the-actual-approver` -- 元のリクエスト
  発行者（`operator` の actor-id "op-1"）とは別の承認者
  ("supervisor-9") で承認したとき、台帳の `:committed` fact の
  `:approved-by` がその実際の承認者 id になること（リクエスト発行者の
  id を取り違えて記録しないこと）。

55 tests / 260 assertions 全体 green、lint clean。

## Addendum 12 (2026-07-03) -- advisor の自己申告 `:effect` が検証されず、無害な op で実登記が起こせた（本 ADR 中で最も重大な修正）

発見（実機再現で確認、`formation.operation/build` を `llm-advisor` +
`langchain.model/mock-model` で実際に走らせて検証）: `formation.operation/
commit-record` は commit する `:effect` を advisor の提案からそのまま
取る（`{:effect (:effect proposal) ...}`）。一方 `formation.governor` の
既存チェックは**すべてリクエストの `:op` を見て判定する**
（`spec-basis-violations` の対象op集合、`document-violations` の
`(= op :filing/submit)`、`officers-at-stake` の `case op` 等）。この2つの
事実の間には、`:effect` が `:op` に対応する正しいものであることを保証する
チェックが**一つも無かった**。

結果: `:op :jurisdiction/assess`（無害に見える「法域アセスメント」）
というリクエストに対し、advisor（`mock-advisor` は常に正しい `:effect`
を返すため無関係。危険なのは**実LLMを使う `llm-advisor` 経路**）が
`:effect :filing/mark-submitted`（実登記提出と同じSSoT変更）を返す
提案を出すと:

- `spec-basis-violations` は op=`:jurisdiction/assess` に対する検査
  （`:cites` の有無）しか行わず、advisor が JPN の本物の spec-basis を
  引用していれば通過する。
- `document-violations`（filing の書類充足チェック）は
  `(= op :filing/submit)` が false のため**そもそも実行されない**。
- `officers-at-stake` も op=`:jurisdiction/assess` では `nil` を返す
  ため、`sanctions-violations` / `kyc-completeness-violations` も
  **実質的にノーオペレーション**。
- `formation.phase/gate` は op=`:jurisdiction/assess` を見て
  「(writes に含まれるが auto ではない) → escalate」と判定するので、
  人間の承認画面には「JPN 向け必要書類を提案」という**無害なサマリ**が
  表示される。

これを承認すると `:commit` ノードは `record`（`:effect
:filing/mark-submitted`）をそのまま `store/commit-record!` に渡し、
**アセスメント・書類確認・KYCスクリーニングを一度も経ずに実登記が完了**
した（実機再現で確認: `:status :filed`・registry-number 付与・LEI発行・
registry-history へ incorporation-draft 追記、すべて一発）。承認者は
「アセスメントを承認したつもり」で、実際には filing を実行していた。
Addendum 9（post-filing-intake バイパス）は登記**後**の書き換えに限定
された穴だったが、これは登記**そのもの**を無検証で起こせる、より
根本的な穴だった。

修正: `formation.governor/op->effect`（新規）でリクエストの `:op` ごとに
唯一正当な `:effect` を固定するテーブルを定義し、`effect-mismatch-
violations`（新規、HARD、**チェック順序で最初**）で `(:effect proposal)`
がそのテーブルと一致することを要求。不一致は `:effect-mismatch` で
un-overridable hold（escalate すらしない -- 人間の目に触れる前に止まる）。
`mock-advisor` の `infer` は元々どの op でも正しい `:effect` を返す設計
だったため、既存55テストは無変更で green（この穴は最初から `llm-advisor`
経路専用だった）。

2 tests / 6 assertions を追加:
- `llm-answering-an-assessment-request-with-a-filing-effect-is-rejected`
  -- governor 単体で `:hard?` かつ `:effect-mismatch` を確認。
- `effect-mismatch-cannot-actually-file-through-the-full-actor-graph`
  -- `formation.operation/build` を実際に `llm-advisor` で走らせ、
  修正前は実登記まで到達した同一の攻撃シナリオが、修正後は
  **即座に hold し（interrupt すら起きない）、application/registry-
  history/assessment のいずれも一切変化しない**ことを確認（実機再現
  スクリプトで修正前後の挙動差を確認済み）。

57 tests / 267 assertions 全体 green、lint clean。

## Addendum 13 (2026-07-03) -- 異なる法域の会社が同一のLEIを発行され得た（ISO 17442 の根幹である全域一意性違反）

発見（実機再現で確認）: `formation.registry/register-incorporation` は
呼び出し元が `entity-id12` を明示しない場合、デフォルトで
`(zero-pad sequence 12)` -- つまり **`formation.store/next-sequence` が
返す、法域ごとの採番だけ**を entity-id にしていた。`next-sequence` は
法域ごとに独立したカウンタ（JPN の初回filingもGBRの初回filingも
`sequence 0`）であり、`jurisdiction` は entity-id の計算に一切
使われていなかった。結果、**法域が異なる2つの、名前も officer も
無関係な会社**が、それぞれ「自国での何番目の filing か」がたまたま
一致するだけで（最も起こりやすいのが両方とも初回 = sequence 0）、
**テキストとして完全に同一のLEIを発行される**ことを実機で確認した
（JPN の初回 filing と GBR の初回 filing が両方とも
`OPER0000000000000088` になった）。LEI (Legal Entity Identifier, ISO
17442) の存在意義そのものが「法人を全世界で一意に識別する」ことである
ため、これは registry モジュールの中核となる保証への違反だった。

修正: `formation.registry/default-entity-id12`（新規、private）を追加。
`jurisdiction` と `sequence` を連結した文字列を `to-digits`（既存の
LEI用英数字→数値変換）で数値化し、`BigInteger`/`BigInt` の base-36
表現の**末尾12桁**を entity-id とする（末尾12桁 = `n mod 36^12` という
位取り記数法の性質そのものであり、近似ではなく厳密な剰余簡約）。
`register-incorporation` のデフォルトフォールバックを
`(zero-pad sequence 12)` から `(default-entity-id12 jurisdiction
sequence)` に置き換えた。`entity-id12` を明示的に渡す既存の呼び出し経路
（現状は無いが API として残っている）は無変更。registry-number
自体の採番方式（`JPN-00000000` 等、法域ごとの人間可読な連番）はこの
修正の対象外で変更していない -- 壊れていたのはLEIのentity-id導出だけ。

2 tests / 5 assertions を追加:
- 実際にJPN/GBRそれぞれの初回filingを発行し、LEIが異なること・両方とも
  ISO 7064 で valid であることを確認（実機再現の直接回帰テスト）。
- 8法域 × sequence 0-4 の40通り全組み合わせで LEI の重複が無いこと、
  全て ISO 7064 valid であることを確認（単一ペアに限らない広域スイープ）。

58 tests / 272 assertions 全体 green、lint clean。実機再現スクリプトで
修正前（衝突する）・修正後（衝突しない）の挙動差を確認済み。

## Addendum 14 (2026-07-03) -- 変更登記の `changed-fields` が無制限で、`:status` を密輸して無検証に解散状態へ書き換えられた

発見（実機再現で確認）: Addendum 12（`:effect` と `:op` の不一致）で
「op が主張する行為と実際の SSoT 変更が一致するか」を
固定したが、**その一つ下の層 -- 正当な `:registry/amend-submitted` effect
の「中身」である `changed-fields`** には何のホワイトリストも無かった。
`formation.store/amend!` は `changed-fields` をそのまま application に
`merge` するため、`:registry/amend` の提案が `changed-fields {:address
"新住所" :status :dissolved}` のように**無害に見える住所変更と一緒に
`:status :dissolved` を紛れ込ませる**と:

- `amendment-violations`（当時）は registry_number の有無と空変更しか
  見ておらず、`:status` というキーの妥当性は一切チェックしない。
- `officers-at-stake` は `:officers` キーだけを見るため、`:status` の
  変更は sanctions/KYC チェックの対象にすら入らない。
- `dissolution-violations`（spec-basis 確認・二重解散防止）は op が
  `:registry/dissolve` の時しか実行されないため、**この経路では一度も
  実行されない**。

実機で検証: app-1 を正規に filing した後、上記の amendment を承認すると
`application` の `:status` が `:dissolved` になる一方、registry-history
には **`"kind" "change-draft"`（住所変更）としか記録されず、
dissolution-draft は一件も追記されない** -- 監査台帳を見ても「なぜ・いつ
・どの法的根拠で」解散したのか一切追跡できない、根本的に食い違った状態に
なった。さらに、この後に本物の `:registry/dissolve` を試みると
`:already-dissolved` で hold されてしまい、**正規の解散手続きを一切
経由せずに、その企業は永久に「解散済みだが記録の無い」状態で凍結される**。

修正: `formation.governor/amendable-fields`（新規）で `:registry/amend`
が触れてよいフィールドを**ホワイトリスト**として固定
（`:entity-name` `:address` `:capital` `:articles` `:officers` の5つのみ）。
denylist ではなく allowlist にしたのは、将来 application に新しい
フィールドが追加されたときに「デフォルトで禁止、明示的に許可したものだけ
通す」ため（denylist だと新フィールド追加のたびに禁止リストへの追記漏れが
起こり得る）。`amendment-violations` に3つ目の HARD 条件として追加:
`changed-fields` のキーが `amendable-fields` に含まれないものを1つでも
含めば `:amendment-forbidden-field` で un-overridable hold。

2 tests / 8 assertions を追加:
- `:status :dissolved` を住所変更と一緒に紛れ込ませる、実機再現そのものの
  シナリオ -- hold・application の `:status` が `:filed` のまま・
  registry-history に何も追記されないことを確認。
- `:jurisdiction` / `:registry-number` / `:lei` / `:id` の4フィールドを
  それぞれ単独で `changed-fields` に含めた場合も同様に hold されることを
  確認（allowlist が `:status` 一つだけの特別扱いでないことの証明）。

60 tests / 285 assertions 全体 green、lint clean。実機再現スクリプトで
修正前（解散状態への密輸に成功し、その後の本物の解散が `:already-
dissolved` で永久にブロックされる）・修正後（即座に hold し、その後の
本物の `:registry/dissolve` が正常に escalate へ進む）の挙動差を確認済み。

## Addendum 15 (2026-07-03) -- intake（唯一の自動commit op）が完全に無検証で、人間ゼロで偽の「登記済み」状態を捏造できた

発見（実機再現で確認、2件）: Addendum 14 で `:registry/amend` の
`changed-fields` にホワイトリストを課したが、**`:application/intake` --
どのフェーズの `:auto` にも入る唯一の op、つまり人間承認が一度も
発生しない経路 -- の patch には同種の制約が一切無かった**。これは
Addendum 9（登記後の intake ブロック）が塞いだ穴とは別物: Addendum 9
は「filing 後」を守るが、**filing 前**の intake 自体が持つ patch 内容の
無制限性は誰も見ていなかった。実機で2つの独立した攻撃を確認:

1. **偽の登記済み状態の捏造**: 一度も `:jurisdiction/assess` も
   `:kyc/screen` も `:filing/submit` も経ていない app-1 に対し、
   `:application/intake` で `{:status :filed :registry-number
   "JPN-99999999" :lei "FAKE0000000000000099"}` を patch するだけで、
   **phase 3 で即座に auto-commit**（人間の承認は一度も発生しない）。
   結果、application は完全に `:filed` かつ registry-number・LEI付きに
   見えるが、registry-history には incorporation-draft が一件も無い --
   spec-basis・書類確認・KYCスクリーニングのどれも一度も走っていない、
   完全に捏造された「登記済み」状態。しかもこの偽状態は
   `post-filing-intake-violations`（Addendum 9）自身の保護対象になる
   ため、以後は本物の filing と区別がつかない。
2. **subject 詐称による無関係申請の書き換え**: `post-filing-intake-
   violations` は REQUEST の `:subject` から申請を引いて filed/dissolved
   かを判定するが、`formation.store` の `:application/upsert` は
   **patch 自身の `:id`** を書き込み先にする -- この不一致を突き、
   「まだ filing していないおとりの subject（app-2）」を宣言しつつ
   `patch {:id "app-1" ...}` で**既に filed 済みの別の申請（app-1）**を
   書き換えられることを確認（app-1 の capital・address が無検証で
   書き換わった）。

修正: `formation.governor/intake-fabrication-violations`（新規、HARD）
を追加。`:application/intake` の patch について:
- `:registry-number` / `:lei` の設定を禁止（`:intake-forbidden-field`）。
- `:status` を `:filed`/`:dissolved` にすることを禁止
  （`:intake-forbidden-status`。`:ready` 等の通常の事前ステータス遷移は
  引き続き許可）。
- patch に `:id` が含まれる場合、リクエストの `:subject` と一致しない
  ものを禁止（`:intake-subject-mismatch`）。

3 tests / 10 assertions を追加:
- 実機再現1そのもの（偽の filed+registry-number+LEI 捏造）-- hold・
  application 完全不変・registry-history 空のままであることを確認。
- 実機再現2そのもの（subject 詐称）-- hold・app-1 完全不変であることを
  確認。
- 回帰確認: 通常の `:status :ready` intake は引き続き auto-commit する
  こと（新チェックが正当な事前ステータス遷移まで巻き込んでいないこと）。

63 tests / 296 assertions 全体 green、lint clean。実機再現スクリプト
2本それぞれで修正前（捏造成功・詐称成功）・修正後（両方即座に hold、
application 完全不変）の挙動差を確認済み。本 ADR で見つかった4件の
「smuggling」系バグ（Addendum 9 post-filing-intake / 12 effect-mismatch
/ 14 amendment-forbidden-field / 15 intake-fabrication）の中で、本件は
唯一「人間が一度も介在しない」経路で成立する点が最も深刻だった。

## 代替案と不採用理由

| 案 | 採否 | 理由 |
|---|---|---|
| etzhayyim の `matsurigoto/corp-registry` を実運用まで拡張 | ❌ | matsurigoto は G1(no-operator-master-key)/G3(`:operated-by` ∈ Council\|adopting-state) という憲法的制約を持ち、不特定多数の一般顧客向け商用代行のprincipalに構造的に当てはまらない |
| `cloud-itonami` 本体（gftdcojp 自社業務基盤）に新規 lane として追加 | ❌ | `cloud-itonami` の activity/decision/effect/audit モデルは gftdcojp *自社* 業務用であり、顧客の KYC/custody/settlement を扱う規制対応サービスとは設計前提が異なる |
| gftdcojp 配下の非公開 vendor repo として実装 | ❌ | liability を単一 vendor に集中させると「全世界どこでも」のスケールが取れない。OSS + 複数 operator 自己運用モデルの方が本要求に合う |
| 全ての `cloud-itonami-{ISIC}` blueprint と同様に robotics premise（ADR-2607011000）を字義通り適用 | ❌（部分的） | 会社設立代行は物理領域作業を伴わないデジタル/書類業務であり、`cloud-itonami-6310` (HR SaaS) と同様に robotics premise の対象外として扱う。cloud-itonami-6310 自体が robotics retrofit の対象外であった先例に倣う |

# Read API と RMU の設計

この文書は、M3 の 2 段階目で実装した Read API と Read Model Updater（RMU）の設計をまとめたものです。対象は `query:interface-adaptor`、`rmu`、`bootstrap` の `read` と `local-rmu` プロファイルです。

## モジュールと依存

`query:interface-adaptor` は Spring GraphQL と Spring JDBC に依存し、MySQL のリードモデルだけを読みます。`rmu` は command 側のイベント型とシリアライザ、AWS SDK、Spring JDBC に依存し、head テーブルの Streams と journal を読みます。`bootstrap` は両方を起動します。

Read API の DAO は `JdbcTemplate` を使います。Java 版は MyBatis ですが、SQL は同じ形にしています。

## Read API

Read API は `graphql-read/schema.graphqls` の 6 つの Query を実装します。型名は `GroupChat`、`Member`、`Message` で、時刻は `NaiveDateTime` スカラーです。`NaiveDateTime` は `java.time.LocalDateTime` を ISO 8601 のタイムゾーンなし日時として返します。

すべての Query は `userAccountId` を閲覧者として受け取り、閲覧範囲の絞り込みにも使います。`getGroupChat` と `getGroupChats` は `members` との結合で、閲覧者がメンバーであるグループチャットだけを返します。`getMembers` と `getMessages` は `EXISTS` で閲覧者のメンバーシップを確認します。`getMember` は閲覧者自身のメンバー行を返します。

単一値の Query で行が見つからないときは `ReadModelNotFoundException` を投げ、`@GraphQlExceptionHandler` で GraphQL エラーに変換します。拡張の `code` は `404` です。一覧の Query は空の一覧を返します。

Read API は呼び出し元を認証しません。`userAccountId` はクライアントが渡した値をそのまま信頼するため、API に到達できる人は任意のメンバーとして閲覧できます。実運用では、認証済みのコンテキストから閲覧者 ID を取得してください。

削除されたグループチャットとメッセージは、`disabled` フラグで残します。Read API の SQL は `disabled = 0` で除外します。

## RMU

RMU は head テーブルの DynamoDB Streams を購読します。head テーブルは ESA v5 が管理し、`INSERT` と `MODIFY` のレコードに `aid`、`seq_nr`、`events` を持ちます。`events` は 1 回の書き込みで追加されたイベントのリストで、各要素は `seq_nr`、`occurred_at`、`manifest`、`payload` を持ちます。

### レコードの解析

`HeadRecordParser` は次のレコードを飛ばします。

- `REMOVE`
- `aid = "__config__"`
- `GroupChat-` で始まらない集約

`INSERT` と `MODIFY` のレコードでは `events` を読み、`seq_nr` の昇順に並べます。`events` がないレコードと、`payload` がないイベントは契約違反として例外にします。

### ポーリング

`HeadStreamPoller` は head テーブルの Streams の各シャードを読みます。最初のイテレータは `TRIM_HORIZON` から始めます。イテレータが期限切れになったときは、最後に処理したシーケンス番号の次から読み直します。

ハンドラが例外を投げたときは、シャードのイテレータを最後に成功したレコードの次に戻し、例外を呼び出し側に返します。次のポーリングで失敗したレコードを読み直します。位置のルールがあるため、適用済みのレコードを読み直しても二重には適用されません。

シャードが閉じたときは、そのシャードを完了として記録します。`reset()` を呼ぶと、すべてのイテレータと完了状態を捨てます。

### journal からの補完

`DynamoDBJournalReader` は journal テーブルを強い整合性で読みます。`aid` と `seq_nr` の範囲を指定し、`seq_nr` の昇順で返します。RMU は head のイベントの `seq_nr` が現在位置より大きいとき、足りない範囲を journal から読んで適用します。

### 位置のルールとトランザクション

`ReadModelApplier` は「現在位置 + 1」のイベントだけを適用します。

1. `read_model_positions` の行を `INSERT ... ON DUPLICATE KEY UPDATE` で用意します。
2. `SELECT ... FOR UPDATE` で行をロックし、現在位置を読みます。
3. イベントの `seq_nr` が現在位置以下なら、何もせずに終わります。
4. 現在位置 + 1 でなければ、契約違反として例外にします。
5. リードモデルを更新し、位置を新しい `seq_nr` に更新します。

手順 2 から 5 は同じ MySQL トランザクションで実行します。リードモデルの更新に失敗したときは、位置も進みません。

`read_model_positions` の行を先に用意するのは、存在しない行に対する `SELECT ... FOR UPDATE` がギャップロックを取り、同時に新しい集約を処理する 2 つの RMU がデッドロックするのを避けるためです。

### 復旧（A 案）

RMU は M2 の復旧 A 案に合わせて、再試行、永続復旧タスク、再同期を実装しています。

- 再試行: `RetryPolicy` が回数上限と最大経過時間を守り、指数バックオフとフルジッターで待ちます。恒久的な失敗（payload、journal の欠番、MySQL の制約違反）は再試行せず、復旧タスクにします。
- 永続復旧タスク: `read_model_recovery_tasks` にストア ID、投影名と版、集約 ID、seq_nr、失敗種別、エラーを記録します。タスクは識別子だけを持ち、イベント本文は journal から読み直します。復旧は位置を読み直してから journal を昇順に適用し、対象位置に達したことを確認して完了にします。同じタスクを 2 回処理しても結果は変わりません。
- 再同期（DY-15）: 先に Streams の購読を再開し、購読と並行して head を ConsistentRead で全ページ Scan します。Scan は `__config__` と他の集約型を除外します。head の seq_nr と投影位置を比べ、遅れを journal から補います。購読と Scan の両方が同じレコードを適用しても、位置のガードとトランザクションが重複を捨てます。Streams が切り詰められたときは全件再同期し、イテレータが期限切れになったときはシャードの最後の位置から再開します。
- 実行: `LocalRmuRunner` は起動時に購読スレッドを先に動かし、そのまま再同期と保留タスクの復旧を行います。24 時間を超えてレコードを読めなかった場合と Streams が切り詰められた場合は、全件再同期してから購読を続けます。保留タスクは 1 分ごとに再試行します。
- 設定: 再試行の回数・最大経過時間・待機時間、ポーリングのバックオフ、復旧の間隔と 1 回の上限は `app.stream.*` で指定します。

### ローカル実行

`LocalRmuRunner` は `CommandLineRunner` として動きます。`HeadStreamSubscription` が専用スレッドで `HeadStreamPoller.pollOnce` を繰り返し、`LocalRmuRunner` がそのスレッドを起動してから再同期を始めます。レコードがないときは指数バックオフで待ちます。エラーが起きたときもログを出して待ち、次のポーリングを続けます。Streams が切り詰められると購読スレッドは再同期の要求を立てて待機し、`LocalRmuRunner` が再同期を終えてから購読を再開します。再同期は `LocalRmuRunner` だけが実行するため、同時に 2 つ走りません。

## リードモデル

`tools/refinery/migrations` の 3 つのマイグレーションで、次のテーブルを作ります。

| テーブル | キー | 用途 |
|:--|:--|:--|
| `group_chats` | `id` | グループチャットと `disabled` フラグ |
| `members` | `id`、一意キー `(group_chat_id, user_account_id)` | メンバーとロール |
| `messages` | `id` | メッセージと `disabled` フラグ |
| `read_model_positions` | `aggregate_id` | 集約ごとの最後の `seq_nr` |
| `read_model_recovery_tasks` | `(store_id, projection_name, aggregate_id, seq_nr)` | 未適用イベントの復旧タスク |

グループチャット名の列は、ドメインの上限に合わせて `varchar(255)` にしました。Java 版の v5 マイグレーションは `varchar(64)` です。

## テスト

- `ReadApiResolverTest` は MySQL の Testcontainers に対して 6 つの Query を実行し、`userAccountId` の絞り込み、404 のエラー、`NaiveDateTime` の形式を確認します。
- `HeadRecordParserTest` は `__config__`、他の集約型、`REMOVE`、events の欠落を確認します。
- `HeadStreamPollerTest` はシャードの読み取り、イテレータの期限切れ、切り詰めの検出、ページ上限を確認します。
- `HeadStreamSubscriptionTest` は購読スレッドの即時ポーリング、切り詰め時の再同期要求とリセット、停止の伝播を確認します。
- `LocalRmuRunnerTest` は保持期限の判定に加え、購読を Scan より先に開始することと、Scan 中に入った更新を購読と journal の補完で取りこぼさないことを確認します。
- `ReadModelUpdaterTest` は位置の前進、重複と古いイベントの破棄、journal からの補完、削除、同時適用、journal の範囲適用と head イベントの同時適用、位置行の同時作成、再起動後の再開を確認します。
- `RetryPolicyTest` は再試行の回数上限、経過時間の上限、恒久的な失敗の非再試行、指数バックオフとリセットを確認します。
- `FailureClassifierTest` は恒久的な失敗と再試行可能な失敗の分類を確認します。
- `DynamoDbHeadScannerTest` は LocalStack で複数ページの Scan、`__config__` と他の集約型の除外、store_id の取得を確認します。
- `ResyncerTest` は head と位置の差の journal 補完、恒久的な失敗の復旧タスク、ストレージ障害での中断を確認します。
- `ReadModelUpdaterRecoveryTest` は再試行、再試行上限後の復旧タスク、複数イベントの復旧対象、解析失敗の復旧タスクを確認します。
- `RecoveryServiceTest` は復旧タスクの冪等な記録、位置を読み直す復旧、完了の確認、他ストアのタスクの除外を確認します。

## 残作業

復旧 A 案の実装は入りました。残りは次のとおりです。

1. SQS の notifier と Lambda ハンドラ、AWS のデプロイ資産を追加します。SQS は配送専用とし、イベント本文は journal から読み直す方針を保ちます。
2. 24 時間を超える停止に備えた運用手順（起動時の全件再同期、アラート）を整えます。
3. 最終的なポート、テーブル名、環境変数の決まりをハブの受け入れ基準に反映します。

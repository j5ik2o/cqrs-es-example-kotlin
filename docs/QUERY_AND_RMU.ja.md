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

### ローカル実行

`LocalRmuRunner` は `CommandLineRunner` として動き、`HeadStreamPoller.pollOnce` を繰り返します。レコードがないときは指数バックオフで待ちます。エラーが起きたときもログを出して待ち、次のポーリングを続けます。

現在の実装は、Streams が切り詰められた場合の全件走査による再同期、恒久的な失敗の復旧タスク、DLQ を持ちません。これらは次の段階の作業です。

## リードモデル

`tools/refinery/migrations` の 2 つのマイグレーションで、次のテーブルを作ります。

| テーブル | キー | 用途 |
|:--|:--|:--|
| `group_chats` | `id` | グループチャットと `disabled` フラグ |
| `members` | `id`、一意キー `(group_chat_id, user_account_id)` | メンバーとロール |
| `messages` | `id` | メッセージと `disabled` フラグ |
| `read_model_positions` | `aggregate_id` | 集約ごとの最後の `seq_nr` |

グループチャット名の列は、ドメインの上限に合わせて `varchar(255)` にしました。Java 版の v5 マイグレーションは `varchar(64)` です。

## テスト

- `ReadApiResolverTest` は MySQL の Testcontainers に対して 6 つの Query を実行し、`userAccountId` の絞り込み、404 のエラー、`NaiveDateTime` の形式を確認します。
- `HeadRecordParserTest` は `__config__`、他の集約型、`REMOVE`、events の欠落を確認します。
- `HeadStreamPollerTest` はシャードの読み取りとイテレータの再利用を確認します。
- `ReadModelUpdaterTest` は位置の前進、重複と古いイベントの破棄、journal からの補完、削除、位置を進めない失敗を確認します。

## 残作業

Write、RMU、Read をまとめて動かす compose と e2e の検証スクリプトを追加します。RMU には、起動時の head 全件走査、Streams の切り詰め後の再同期、恒久的な失敗の復旧タスク、DLQ を追加します。Lambda 版が必要なら、ハンドラと AWS のデプロイ資産も追加します。

# cqrs-es-example-kotlin

GroupChat の CQRS とイベントソーシングのサンプルを Kotlin で実装したものです。event-store-adapter-kotlin 5.1.0 を使い、モジュール構成と Write API は
[cqrs-es-example-java](https://github.com/j5ik2o/cqrs-es-example-java) の v5 実装に合わせています。

現在の実装範囲は command 側、Write API、Read API、RMU、compose を使った e2e です。RMU の復旧処理と、Lambda と SQS を使う AWS 向けのデプロイ資産も入っています。

## 全体の構成

```text
Client
  |-- mutation --> Write API --> ESA --> DynamoDB
  |                                      journal
  |                                      snapshot
  |                                      head --- Streams --> RMU --> MySQL (read model)
  |
  `-- query ----> Read API --------------------------------> MySQL (read model)
```

Write API は 8 つの Mutation と `healthCheck` を公開します。集約はイベントストアから復元し、リードモデルは読みません。

## モジュール

| モジュール | 役割 | Java 版の対応 |
|:--|:--|:--|
| `infrastructure` | ULID の Jackson 対応 | `infrastructure` |
| `command:domain` | GroupChat 集約、イベント、値オブジェクト | `command:domain` |
| `command:interface-adaptor-if` | リポジトリのポート | `command:interface-adaptor-if` |
| `command:interface-adaptor-impl` | ESA のリポジトリ実装、payload のシリアライザ、GraphQL リゾルバ | `command:interface-adaptor-impl` |
| `command:processor` | コマンドプロセッサ（ユースケース） | `command:processor` |
| `query:interface-adaptor` | Read API の DAO、GraphQL リゾルバ、`NaiveDateTime` スカラー | `query:interface-adaptor` |
| `rmu` | head Streams のポーラ、journal の補完、リードモデルの適用 | `rmu` |
| `bootstrap` | Spring Boot の起動クラスと設定 | `bootstrap` |

## 必要なもの

- JDK 17 以上。ビルドの対象は JVM 17 です。
- Docker と Docker Compose。Testcontainers の結合テスト、ローカル環境、e2e に使います。

## ビルドとテスト

```sh
./gradlew build
./gradlew spotlessCheck
```

`build` は単体テスト、GraphQL リゾルバのテスト、DynamoDB の結合テスト、RMU のテスト、Read API のテストを実行します。結合テストは Testcontainers で `localstack/localstack:4.14.0` と `mysql:26.10` を起動するため、Docker が必要です。

## Write API

既定では `http://localhost:56080/graphql` で待ち受けます。GraphiQL は `http://localhost:56080/graphiql` です。

| Mutation | 入力 | 返す値 |
|:--|:--|:--|
| `createGroupChat` | `name`, `executorId` | `groupChatId` |
| `deleteGroupChat` | `groupChatId`, `executorId` | `groupChatId` |
| `renameGroupChat` | `groupChatId`, `name`, `executorId` | `groupChatId` |
| `addMember` | `groupChatId`, `userAccountId`, `role`, `executorId` | `groupChatId` |
| `removeMember` | `groupChatId`, `userAccountId`, `executorId` | `groupChatId` |
| `postMessage` | `groupChatId`, `content`, `executorId` | `groupChatId`, `messageId` |
| `editMessage` | `groupChatId`, `messageId`, `content`, `executorId` | `groupChatId` |
| `deleteMessage` | `groupChatId`, `messageId`, `executorId` | `groupChatId` |

`role` は `MEMBER` と `ADMIN` です。

`userAccountId` と `executorId` は、ULID そのものか `UserAccount-` を付けた値を受け付けます。`groupChatId` は ULID そのものか `GroupChat-` を付けた値です。返す `groupChatId` には必ず `GroupChat-` が付きます。返す `messageId` は ULID そのものです。この扱いは Java 版に合わせています。

Query の `healthCheck` は `OK` を返します。

Write API は呼び出し元を認証しません。各 Mutation はクライアントが渡した `executorId` を実行者として使うため、API に到達できる人は管理者として操作できます。認証層を置かずに公開しないでください。実運用では、認証済みのコンテキストから実行者 ID を取得してください。Java 版も同じです。

コマンドプロセッサは楽観ロックの競合を再試行しません。同じ GroupChat に対する同時実行では、後から保存する側が GraphQL エラーになります。Java 版と同じ挙動です。

## Read API

既定では `http://localhost:56082/graphql` で待ち受けます。MySQL のリードモデルだけを読みます。

| Query | 引数 | 返す値 |
|:--|:--|:--|
| `getGroupChat` | `groupChatId`, `userAccountId` | `GroupChat!` |
| `getGroupChats` | `userAccountId` | `[GroupChat!]!` |
| `getMember` | `groupChatId`, `userAccountId` | `Member!` |
| `getMembers` | `groupChatId`, `userAccountId` | `[Member!]!` |
| `getMessage` | `messageId`, `userAccountId` | `Message!` |
| `getMessages` | `groupChatId`, `userAccountId` | `[Message!]!` |

型は `GroupChat`、`Member`、`Message` です。`createdAt` と `updatedAt` は `NaiveDateTime` スカラーで、ISO 8601 のタイムゾーンなし日時として返します。

すべての Query は `userAccountId` を閲覧者として使い、閲覧範囲の絞り込みにも使います。メンバーでないアカウントには空の一覧を返します。単一値の Query では GraphQL エラーを返し、拡張の `code` は `404` です。

Read API は呼び出し元を認証しません。`userAccountId` はクライアントが渡した値をそのまま信頼します。そのため、API に到達できる人は任意のメンバーとして閲覧できます。認証層を置かずに公開しないでください。実運用では、認証済みのコンテキストから閲覧者 ID を取得してください。

## RMU

RMU は head テーブルの DynamoDB Streams を購読します。`INSERT` と `MODIFY` の各レコードについて、`__config__` と他の集約型を飛ばし、head 項目の `events` を読んで各イベントを適用します。

- 古いシーケンス番号と重複したシーケンス番号は捨てます。
- `seq_nr` が飛んでいるときは、head イベントを適用する前に journal から補います。
- 適用するのは「現在位置 + 1」のイベントだけです。
- リードモデルの更新と `read_model_positions` の更新は、同じ MySQL トランザクションで確定します。
- `SELECT ... FOR UPDATE` で位置の行をロックするため、同時に動く RMU が同じイベントを二重に適用したり、飛ばしたりしません。
- イベントの適用に失敗したときは位置を進めません。ポーラは最後に成功したレコードの次から再開します。

ローカルの RMU は `TRIM_HORIZON` から読み始めるため、再起動すると保持されているレコードを読み直します。適用済みのイベントは位置で捨てます。一時的な失敗は指数バックオフとジッターで再試行します。恒久的な失敗、または回数上限・経過時間の上限に達した再試行は、`read_model_recovery_tasks` に復旧タスクとして記録します。タスクにはストア ID、投影名と版、集約 ID、`seq_nr`、失敗種別、エラーを保存します。復旧は位置を読み直してから journal を昇順に適用するため、同じタスクを 2 回処理しても結果は変わりません。

RMU は起動時に先に Streams の購読を再開し、最初の読み取りが成功するまで待ってから、購読と並行して head を全件照合します。長い Scan 中に書かれた更新も保持期間内に購読で届きます。Scan は ConsistentRead を使い、`__config__` と他の集約型を除外します。head の `seq_nr` と投影位置の差は journal から補います。購読と Scan は同じ「現在位置 + 1」のガードとトランザクションを使うため、両方から届いたイベントは 1 回だけ適用されます。Streams が切り詰められたときや保持期限を超えたときは、イテレータを捨てて購読の読み取りが再開してから全件を再同期するため、Scan 中も購読は止まりません。イテレータが期限切れになったときは、最後に処理したシーケンス番号の次から再開します。保留中の復旧タスクは起動時とその後定期的に再試行します。

Lambda と SQS を使う AWS 向けのデプロイ資産と運用手順は [deploy/README.ja.md](deploy/README.ja.md) にあります。復旧タスクの通知は識別子だけを送り、イベント本文は journal から読み直します。配布物のサイズと JAR の内訳は [docs/LAMBDA_PACKAGE_SIZE.ja.md](docs/LAMBDA_PACKAGE_SIZE.ja.md) に記録しています。

## ローカルで動かす

LocalStack と MySQL を起動し、DynamoDB のテーブルと MySQL のスキーマを作ります。

```sh
docker compose -f tools/docker-compose/docker-compose-databases.yml up -d --wait localstack mysql
docker compose -f tools/docker-compose/docker-compose-databases.yml run --rm dynamodb-setup
docker compose -f tools/docker-compose/docker-compose-databases.yml run --rm migration
```

実行可能な jar をビルドします。

```sh
./gradlew :bootstrap:bootJar
```

3 つのアプリを別々のターミナルで起動します。

```sh
java -jar bootstrap/build/libs/bootstrap-0.0.1-SNAPSHOT.jar -w
java -jar bootstrap/build/libs/bootstrap-0.0.1-SNAPSHOT.jar -l
java -jar bootstrap/build/libs/bootstrap-0.0.1-SNAPSHOT.jar -r
```

`-w` はポート 56080 の Write API、`-l` は HTTP サーバを持たないローカル RMU、`-r` はポート 56082 の Read API です。同じ jar を `./gradlew :bootstrap:bootRun --args='-w'` のように起動することもできます。

GroupChat を作成します。

```sh
curl -s http://localhost:56080/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"mutation($input: CreateGroupChatInput!){createGroupChat(input:$input){groupChatId}}","variables":{"input":{"name":"group-chat-example","executorId":"UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV"}}}'
```

返った `groupChatId` を使ってメッセージを投稿します。

```sh
curl -s http://localhost:56080/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"mutation($input: PostMessageInput!){postMessage(input:$input){groupChatId messageId}}","variables":{"input":{"groupChatId":"GroupChat-...","content":"Hello","executorId":"UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV"}}}'
```

Read API で反映を確認します。RMU がイベントを適用するまでポーリングします。

```sh
curl -s http://localhost:56082/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ getGroupChats(userAccountId: \"UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV\") { id name } }"}'
```

スタックを止めます。

```sh
docker compose -f tools/docker-compose/docker-compose-databases.yml down
```

## e2e

e2e のスクリプトは、アプリのイメージと e2e のイメージをビルドし、LocalStack、MySQL、マイグレーション、3 つのアプリを起動して検証します。各イベントが RMU に反映されるまで Read API をポーリングするため、固定の sleep に頼りません。

```sh
./tools/scripts/docker-compose-e2e-test.sh
```

スクリプトは検証を 2 回実行します。1 回目はそのまま、2 回目は RMU を再起動したあとです。2 回目で、`read_model_positions` が再生された head のレコードを捨て、MySQL に重複行が入らないことを確認します。失敗したときは compose のログを出して、その終了コードで終わります。

検証する操作は `createGroupChat`、`addMember`、`postMessage`、`renameGroupChat`、`editMessage`、`deleteMessage`、`removeMember`、`deleteGroupChat` と、6 つの Read API の Query です。

## 設定

| 環境変数 | 既定値 | 用途 |
|:--|:--|:--|
| `SERVER_PORT` | `56080`（Write）、`56082`（Read） | API のポート |
| `AWS_REGION` | `ap-northeast-1` | DynamoDB のリージョン |
| `DYNAMODB_ENDPOINT_URL` | `http://localhost:4566` | DynamoDB のエンドポイント |
| `AWS_ACCESS_KEY_ID` | `x` | LocalStack 用のアクセスキー |
| `AWS_SECRET_ACCESS_KEY` | `x` | LocalStack 用のシークレットキー |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/ceer?...` | MySQL のリードモデル |
| `SPRING_DATASOURCE_USERNAME` | `ceer` | MySQL のユーザー |
| `SPRING_DATASOURCE_PASSWORD` | `ceer` | MySQL のパスワード |
| `JOURNAL_TABLE_NAME` | `journal` | journal テーブル |
| `SNAPSHOT_TABLE_NAME` | `snapshot` | snapshot テーブル |
| `HEAD_TABLE_NAME` | `head` | head テーブル |
| `SNAPSHOT_AID_INDEX_NAME` | `snapshot-aid-index` | snapshot の GSI 名 |
| `SNAPSHOT_INTERVAL` | `10` | 何イベントごとにスナップショットを取るか |
| `STREAM_MAX_ITEM_COUNT` | `32` | head Streams から 1 回に読む件数 |
| `STREAM_POLL_BACKOFF_INITIAL_MILLIS` | `500` | RMU の待機時間の初期値 |
| `STREAM_POLL_BACKOFF_MAX_MILLIS` | `30000` | RMU の待機時間の上限 |
| `STREAM_RETRY_MAX_ATTEMPTS` | `5` | イベントごとの再試行回数 |
| `STREAM_RETRY_MAX_ELAPSED_MILLIS` | `30000` | 再試行の経過時間の上限 |
| `STREAM_RETRY_INITIAL_DELAY_MILLIS` | `200` | 再試行の待機時間の初期値 |
| `STREAM_RETRY_MAX_DELAY_MILLIS` | `5000` | 再試行の待機時間の上限 |
| `STREAM_RECOVERY_INTERVAL_MILLIS` | `60000` | 復旧タスクの再試行間隔 |
| `STREAM_MAX_RECOVERY_TASKS_PER_RUN` | `50` | 1 回に処理する復旧タスクの上限 |
| `GRAPHIQL_ENABLED` | `true` | GraphiQL を有効にするか |

## 保存形式

command 側は ESA v5 の 3 テーブル構成を使います。

| テーブル | キー | Streams |
|:--|:--|:--|
| `journal` | `aid` (S)、`seq_nr` (N) | 無効 |
| `snapshot` | `aid` (S)、`skey` (N)、GSI `snapshot-aid-index` | 無効 |
| `head` | `aid` (S) | 有効、`NEW_IMAGE` |

`__config__` の項目は、ストアの作成時にライブラリが読み取りまたは初期化します。`tools/dynamodb-setup/create-tables.sh` はテーブルだけを作ります。

イベントは ESA v5 の envelope で運びます。manifest は `<イベント型>@<スキーマ版>` の形で、たとえば `GroupChatCreated@1` です。payload は JSON で、イベント ID、実行者 ID、イベント固有の項目だけを持ちます。集約 ID、シーケンス番号、発生日時、manifest は envelope に置きます。

スナップショットの manifest は `GroupChat@1` です。

### リードモデル

RMU は `tools/refinery/migrations` のマイグレーションで MySQL のリードモデルを更新します。

| テーブル | キー | 用途 |
|:--|:--|:--|
| `group_chats` | `id` | グループチャットの行と `disabled` フラグ |
| `members` | `id`、一意キー `(group_chat_id, user_account_id)` | メンバーとロール |
| `messages` | `id` | メッセージの行と `disabled` フラグ |
| `read_model_positions` | `aggregate_id` | 集約ごとに最後に適用した `seq_nr` |

グループチャット名の列は、ドメインの上限に合わせて `varchar(255)` です。Java 版の v5 マイグレーションは `varchar(64)` です。削除した行は `disabled` フラグで残し、Read API の Query で除外します。

## テスト

- `GroupChatTest` は集約の振る舞い、入力の検証、イベントの再生を確認します。
- `GroupChatSerializationTest` はイベントとスナップショットの payload を確認します。payload に envelope のメタデータが入らないことも確認します。
- `GroupChatRepositoryImplTest` は LocalStack に対して journal、snapshot、head の項目と楽観ロックを確認します。
- `GroupChatMutationResolverTest` は GraphQL のスキーマを通して 8 つの Mutation と `healthCheck` を実行します。認可エラーと不正な ID が GraphQL エラーになることも確認します。
- `ReadApiResolverTest` は MySQL の Testcontainers に対して 6 つの Query を実行し、`userAccountId` による閲覧範囲の絞り込みと `NaiveDateTime` スカラーを確認します。
- `HeadRecordParserTest` は head Streams のレコードを解析します。`__config__`、他の集約型、events の欠落を確認します。
- `HeadStreamPollerTest` はシャードの読み取り、イテレータの期限切れ、切り詰めの検出、ページ上限を確認します。
- `ReadModelUpdaterTest` は位置のルール、重複と古いイベント、journal からの補完、削除、同時適用、位置行の同時作成、再起動後の再開を MySQL の Testcontainers で確認します。
- `RetryPolicyTest` は再試行の回数上限、経過時間の上限、恒久的な失敗の非再試行、指数バックオフとリセットを確認します。
- `FailureClassifierTest` は恒久的な失敗と再試行可能な失敗の分類を確認します。
- `DynamoDbHeadScannerTest` は LocalStack で複数ページの Scan、`__config__` と他の集約型の除外、`store_id` の取得を確認します。
- `ResyncerTest` は head と位置の差の journal 補完、恒久的な失敗の復旧タスク、ストレージ障害での中断を確認します。
- `ReadModelUpdaterRecoveryTest` は再試行、再試行上限後の復旧タスク、複数イベントの復旧対象、解析失敗の復旧タスクを確認します。
- `RecoveryServiceTest` は復旧タスクの冪等な記録、位置を読み直す復旧、完了の確認、他ストアのタスクの除外を MySQL の Testcontainers で確認します。
- `RmuDeploymentTemplateTest` は CloudFormation テンプレートのイベントソースマッピング、タイムアウト、DLQ、環境変数、ハンドラ名、アラームを確認します。
- `LambdaStreamRecordsTest` は Lambda のイベント型から SDK v2 のレコードへの変換と、部分バッチの失敗の扱いを確認します。
- `SqsRecoveryNotifierTest` は通知の本文に識別子だけが入ることを確認します。
- `RecoveryWorkerTest` と `ResyncWorkerTest` は、復旧ワーカーのバッチ上限と再配信、再同期ワーカーの件数の返却を確認します。
- `LambdaSettingsTest` は環境変数の既定値と検証、Secrets Manager のシークレットの解決を確認します。

## CI

`.github/workflows/ci.yml` は `main` への push と pull request で 4 つのジョブを実行します。

- `lint`: `./gradlew spotlessCheck`
- `deploy-assets`: `cfn-lint`、シェルスクリプトの構文確認、`:rmu:lambdaZip` のビルドと中身の確認、配布物サイズと JAR 一覧のログ出力
- `test`: `./gradlew test`
- `e2e`: `./tools/scripts/docker-compose-e2e-test.sh`

テストと e2e のジョブは Docker を使います。

## 残作業

復旧 A 案の実装と AWS 向けのデプロイ資産は入りました。残りは次のとおりです。

1. 実際の AWS 環境へのデプロイと、アラームの通知先の設定。デプロイにはオーナーの承認が要ります。
2. MySQL の認証情報のローテーション手順。
3. 最終的なポート、テーブル名、環境変数の決まりをハブの受け入れ基準に反映します。

## Java 版との対応

- モジュールの分割と依存の向きは Java 版に合わせています。
- Write API と Read API のスキーマは Java 版の `graphql-write/schema.graphqls` と `graphql-read/schema.graphqls` に合わせています。Read API の型名は `GroupChat`、`Member`、`Message`、時刻は `NaiveDateTime` です。
- イベントの manifest と 3 テーブルの構成は Java 版の v5 実装に合わせています。
- Read API の DAO は、Java 版の MyBatis ではなく Spring の `JdbcTemplate` を使います。
- RMU は復旧タスクと再同期に加えて、Lambda ハンドラと SQS の notifier を含みます。AWS SDK で head Streams と journal を直接読みます。
- Kotlin の命名規則に合わせて、パッケージ名は `interfaceadaptor` にしました。ディレクトリ名は Java 版に合わせて `interface-adaptor-*` のままです。ktlint の `package-name` ルールがアンダースコアを許可しないためです。
- Write API のポートは 56080、Read API のポートは 56082 です。68080 は 65535 を超えるため使えません。
- e2e の compose と検証スクリプトは Java 版の構成に合わせています。README は既存の英語版と日本語版の両方を更新しました。

## ライセンス

MIT または Apache-2.0 です。`LICENSE-MIT` と `LICENSE-APACHE` を参照してください。

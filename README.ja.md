# cqrs-es-example-kotlin

GroupChat の CQRS とイベントソーシングのサンプルを Kotlin で実装したものです。event-store-adapter-kotlin 5.1.0 を使い、モジュール構成と Write API は
[cqrs-es-example-java](https://github.com/j5ik2o/cqrs-es-example-java) の v5 実装に合わせています。

現在の実装範囲は command 側と Write API です。Read API、Read Model Updater（RMU）、compose を使った e2e はまだありません。

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
| `query:interface-adaptor` | Read API 用の置き場所（未実装） | `query:interface-adaptor` |
| `rmu` | RMU 用の置き場所（未実装） | `rmu` |
| `bootstrap` | Spring Boot の起動クラスと設定 | `bootstrap` |

## 必要なもの

- JDK 17 以上。ビルドの対象は JVM 17 です。
- Docker。LocalStack の結合テストと、ローカル用 DynamoDB の作成に使います。

## ビルドとテスト

```sh
./gradlew build
./gradlew spotlessCheck
```

`build` は単体テスト、GraphQL リゾルバのテスト、DynamoDB の結合テストを実行します。結合テストは Testcontainers で `localstack/localstack:2.3.2` を起動するため、Docker が必要です。

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

## ローカルで動かす

LocalStack を起動し、3 つの DynamoDB テーブルを作ります。

```sh
docker compose -f tools/docker-compose/docker-compose-local.yml up -d localstack
docker compose -f tools/docker-compose/docker-compose-local.yml run --rm dynamodb-setup
```

Write API を起動します。

```sh
./gradlew :bootstrap:bootRun --args='-w'
```

実行可能な jar は `bootstrap/build/libs/bootstrap-0.0.1-SNAPSHOT.jar` です。`-w` を付けると `write` プロファイルが有効になります。

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

LocalStack を止めます。

```sh
docker compose -f tools/docker-compose/docker-compose-local.yml down
```

## 設定

| 環境変数 | 既定値 | 用途 |
|:--|:--|:--|
| `SERVER_PORT` | `56080` | Write API のポート |
| `AWS_REGION` | `ap-northeast-1` | DynamoDB のリージョン |
| `DYNAMODB_ENDPOINT_URL` | `http://localhost:4566` | DynamoDB のエンドポイント |
| `AWS_ACCESS_KEY_ID` | `x` | LocalStack 用のアクセスキー |
| `AWS_SECRET_ACCESS_KEY` | `x` | LocalStack 用のシークレットキー |
| `JOURNAL_TABLE_NAME` | `journal` | journal テーブル |
| `SNAPSHOT_TABLE_NAME` | `snapshot` | snapshot テーブル |
| `HEAD_TABLE_NAME` | `head` | head テーブル |
| `SNAPSHOT_AID_INDEX_NAME` | `snapshot-aid-index` | snapshot の GSI 名 |
| `SNAPSHOT_INTERVAL` | `10` | 何イベントごとにスナップショットを取るか |
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

## テスト

- `GroupChatTest` は集約の振る舞い、入力の検証、イベントの再生を確認します。
- `GroupChatSerializationTest` はイベントとスナップショットの payload を確認します。payload に envelope のメタデータが入らないことも確認します。
- `GroupChatRepositoryImplTest` は LocalStack に対して journal、snapshot、head の項目と楽観ロックを確認します。
- `GroupChatMutationResolverTest` は GraphQL のスキーマを通して 8 つの Mutation と `healthCheck` を実行します。認可エラーと不正な ID が GraphQL エラーになることも確認します。

## CI

`.github/workflows/ci.yml` は `main` への push と pull request で 2 つのジョブを実行します。

- `lint`: `./gradlew spotlessCheck`
- `test`: `./gradlew test`

テストのジョブは Testcontainers のために Docker を使います。

## 残作業

次の段階で query 側と RMU を作ります。

1. `query:interface-adaptor` に Read API を実装し、共通スキーマの 6 つの Query を MySQL のリードモデルに対して提供します。
2. `rmu` に RMU を実装します。head テーブルの Streams を購読し、`(aggregate_id, seq_nr)` の重複と古い番号を捨て、番号の飛びは journal から補い、処理位置を `read_model_positions` に記録します。
3. `group_chats`、`members`、`messages`、`read_model_positions` の MySQL スキーマとマイグレーションを追加します。
4. Write、RMU、Read をまとめて動かす compose と e2e を追加します。
5. `bootstrap` に RMU と Read API の起動オプションを追加します。現在は `-l` と `-r` を予約しているだけです。

## Java 版との対応

- モジュールの分割と依存の向きは Java 版に合わせています。
- Write API のスキーマは Java 版の `graphql-write/schema.graphqls` と同じです。
- イベントの manifest と 3 テーブルの構成は Java 版の v5 実装に合わせています。
- Kotlin の命名規則に合わせて、パッケージ名は `interfaceadaptor` にしました。ディレクトリ名は Java 版に合わせて `interface-adaptor-*` のままです。ktlint の `package-name` ルールがアンダースコアを許可しないためです。
- Write API のポートは 56080 です。68080 は 65535 を超えるため使えません。

## ライセンス

MIT または Apache-2.0 です。`LICENSE-MIT` と `LICENSE-APACHE` を参照してください。

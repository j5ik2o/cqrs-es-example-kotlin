# command 側の設計

この文書は、M3 の最初の段階で実装した command 側と Write API の設計をまとめたものです。対象は `command:domain`、`command:interface-adaptor-if`、
`command:interface-adaptor-impl`、`command:processor`、`bootstrap` です。Read API と RMU は対象外です。

## 依存の向き

`command:domain` は他のモジュールに依存しません。集約とイベントは、ESA の型も Spring の型も知りません。`command:interface-adaptor-if` はドメインに依存し、リポジトリのポートだけを定義します。`command:processor` はドメインとポートに依存し、ユースケースを組み立てます。`command:interface-adaptor-impl` は ESA と Spring GraphQL に依存し、ポートの実装と API の入口を持ちます。`bootstrap` はこれらを起動します。

依存は外側から内側への一方向です。ドメインとユースケースは、DynamoDB や GraphQL の都合で変更されません。

## ドメインモデル

`GroupChat` は集約です。集約 ID、削除済みかどうか、名前、メンバー、メッセージ、シーケンス番号を持ちます。コマンドは `GroupChat` のメソッドとして実装し、成功すると変更後の集約と `PendingEvent` を返します。失敗すると `GroupChatError` を投げます。

`PendingEvent` は、集約 ID、シーケンス番号、発生日時、イベントをまとめた値です。ESA の envelope を作るためのメタデータを、イベント本体から分けて持ちます。イベント本体には集約 ID、シーケンス番号、発生日時、manifest を持たせません。

`GroupChatEvent` は sealed interface です。作成、削除、名前変更、メンバー追加、メンバー削除、メッセージ投稿、メッセージ編集、メッセージ削除の 8 種類があります。各イベントは、イベント ID と実行者 ID を持ちます。イベント種別は `typeName()` で返し、manifest は `<イベント型>@<スキーマ版>` の形で組み立てます。

イベントの再生では、集約のシーケンス番号を 1 つ進めながら状態を適用します。イベント本体にシーケンス番号がないため、保存済みのイベントを読んでも再生の順序は envelope の順序で決まります。

## 保存

`GroupChatRepositoryImpl` は ESA の `EventStoreAsync` を使います。`findById` は head とスナップショットを読み、スナップショットがあればその次のシーケンス番号からイベントを読みます。スナップショットがなければ、最初のイベントが `Created` であることを確認し、そこから集約を作って残りを再生します。

`store` は次のように書き分けます。

- `Created` イベントは、イベントとスナップショットを同じトランザクションで書きます。
- スナップショットの間隔に達したイベントも、イベントとスナップショットを同時に書きます。
- それ以外はイベントだけを書きます。

スナップショットの間隔は `SNAPSHOT_INTERVAL` で指定します。既定値は 10 です。

manifest はイベントが `GroupChatCreated@1` のような形、スナップショットが `GroupChat@1` です。payload は Jackson で JSON にします。`Members` と `Messages` は配列で表し、ULID は文字列で表します。payload には envelope のメタデータを入れません。

## Write API

`GroupChatMutationResolver` は 8 つの Mutation を実装します。`HealthCheckQueryResolver` は `healthCheck` を実装します。どちらも `write` プロファイルでだけ有効になります。

GraphQL の入力は Kotlin の data class に受けます。Spring GraphQL の引数バインダは data class の主コンストラクタを名前で解決するため、`CreateGroupChatInput` のようなクラスをそのまま使えます。

`role` は GraphQL の `Role` 列挙型で受け、ドメインの `MemberRole` に変換します。`ADMIN` は `ADMINISTRATOR` に対応します。

`userAccountId` と `executorId` は、ULID そのものか `UserAccount-` を付けた値を受け付けます。`groupChatId` は `GroupChat-` を付けても付けなくても受け付けます。返すときは `GroupChat-` を付けます。`messageId` は ULID そのものを返します。

ドメインのエラーは RuntimeException として GraphQL のエラーになります。集約が見つからないときは `NotFoundProcessException` を投げます。

## テスト

- 集約のテストは、作成、削除、名前変更、メンバーの追加と削除、メッセージの投稿、編集、削除、再生、入力の検証を確認します。
- シリアライザのテストは、イベントとスナップショットの往復と、payload に envelope のメタデータが入らないことを確認します。
- リポジトリのテストは LocalStack を使い、journal、snapshot、head の項目と楽観ロックを確認します。
- GraphQL のテストは、スキーマを通して 8 つの Mutation と `healthCheck` を実行します。

## 残作業

Read API では、6 つの Query を MySQL のリードモデルに対して実装します。RMU では、head テーブルの Streams を購読し、重複と古い番号を捨て、番号の飛びを journal から補い、処理位置を `read_model_positions` に記録します。その後、compose と e2e を追加します。

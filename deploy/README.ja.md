# RMU 復旧のデプロイ資産

このディレクトリには、RMU（Read Model Updater）の復旧を AWS で動かすための CloudFormation テンプレートと適用手順を置く。テンプレートは Lambda のイベントソースマッピング、OnFailure の SQS、永続復旧タスクを起動する SQS ワーカー、全件再同期を実行するワーカーを定義する。このリポジトリの CI はテンプレートと配布物を検証するだけで、デプロイは行わない。

## なぜ CloudFormation なのか

このリポジトリには Terraform、SAM、CDK のいずれもない。デプロイに関する資産は Docker Compose とシェルスクリプトだけで、AWS 側の構成は管理していない。そこで、追加のツールを入れずに 1 ファイルで構成を再現できる CloudFormation の JSON テンプレートを選んだ。YAML ではなく JSON にしたのは、テストから Jackson でそのまま読み、設定値を検査するためである。

## 構成

| リソース | 役割 |
|:--|:--|
| `RmuFunction` | head テーブルの Streams を購読する Lambda。ハンドラは `RmuLambdaHandler::handleRequest`。 |
| `RmuEventSourceMapping` | head Streams と RMU をつなぐイベントソースマッピング。 |
| `OnFailureQueue` / `OnFailureDlq` | 再試行を使い切ったストリームレコードを受けるキューと、その DLQ。 |
| `RecoveryQueue` / `RecoveryDlq` | 復旧ワーカーを起動するメッセージのキューと、その DLQ。 |
| `RecoveryWorkerFunction` | SQS または EventBridge で起動し、MySQL の `read_model_recovery_tasks` を journal から再生する Lambda。ハンドラは `RecoveryWorker::handleRequest`。 |
| `RecoveryWorkerEventSourceMapping` | `RecoveryQueue` と復旧ワーカーをつなぐイベントソースマッピング。 |
| `RecoveryWorkerSchedule` | 通知が届かなくても未処理タスクを処理する EventBridge の定期実行。既定は 5 分ごと。 |
| `ResyncWorkerFunction` | head テーブルを全件 Scan し、journal から遅れを埋める Lambda。ハンドラは `ResyncWorker::handleRequest`。 |
| `ResyncWorkerSchedule` | 全件再同期の EventBridge の定期実行。既定は 1 日ごと。 |
| `RecoveryBacklogAlarm` ほか | SQS の滞留、Lambda のエラー、DLQ のメッセージ、定期実行の失敗を見る CloudWatch アラーム。 |

## 設定の対応表

| 項目 | テンプレートの指定 | 値 |
|:--|:--|:--|
| `BisectBatchOnFunctionError` | `RmuEventSourceMapping.Properties.BisectBatchOnFunctionError` | `true` |
| `ReportBatchItemFailures` | `RmuEventSourceMapping.Properties.FunctionResponseTypes` | `["ReportBatchItemFailures"]` |
| OnFailure の SQS | `RmuEventSourceMapping.Properties.DestinationConfig.OnFailure.Destination` | `OnFailureQueue` の ARN |
| 再試行回数 | `RmuEventSourceMapping.Properties.MaximumRetryAttempts` | 既定 3 |
| レコードの保持期間 | `RmuEventSourceMapping.Properties.MaximumRecordAgeInSeconds` | 既定 604800 秒（7 日） |
| 関数のタイムアウト | `RmuFunction.Properties.Timeout` | 既定 300 秒 |
| 復旧キューの可視性タイムアウト | `RecoveryQueue.Properties.VisibilityTimeout` | 既定 1800 秒（ワーカーのタイムアウトの 6 倍） |
| 復旧ワーカーの同時実行数 | `RecoveryWorkerFunction.Properties.ReservedConcurrentExecutions` | 既定 1 |
| 復旧ワーカーの起動 | `RecoveryWorkerEventSourceMapping.Properties.BatchSize` | `1` |
| 復旧タスクの定期処理 | `RecoveryWorkerSchedule.Properties.ScheduleExpression` | 既定 `rate(5 minutes)` |
| 全件再同期 | `ResyncWorkerSchedule.Properties.ScheduleExpression` | 既定 `rate(1 day)` |

`BisectBatchOnFunctionError` は失敗したレコードの範囲を分割し、`ReportBatchItemFailures` は失敗したレコードを Lambda に伝える。DynamoDB Streams では、Lambda が返された失敗のうち最小のシーケンス番号をチェックポイントにして、そこから後続のレコードも再試行する。失敗したレコードより後ろの正常なレコードが再配信されることがあるが、位置ガードが重複を捨てるため結果は変わらない。

`MaximumRecordAgeInSeconds` を 7 日にしても、Streams の保持期間は 24 時間である。Lambda はローカルの reader のような起動時 Scan を行わないため、24 時間を超えて止まった場合は `ResyncWorker` の全件再同期で追いつく。`StartingPosition` は `TRIM_HORIZON` にした。位置ガードが重複を捨てるため、再配信されても結果は変わらない。

Lambda のハンドラは Spring のコンテキストを使わない。`LambdaRmuDependencies` が環境変数から同じ設定を読み、DynamoDB クライアント、JDBC の `JdbcTemplate`、復旧タスクのストア、SQS の notifier を組み立てる。環境変数の名前は `application-common.yml` に合わせてある。

| 環境変数 | 既定 | 用途 |
|:--|:--|:--|
| `HEAD_TABLE_NAME` | `head` | head テーブル |
| `JOURNAL_TABLE_NAME` | `journal` | journal テーブル |
| `STREAM_RETRY_MAX_ATTEMPTS` | `5` | 再試行の回数上限 |
| `STREAM_RETRY_MAX_ELAPSED_MILLIS` | `30000` | 再試行の経過時間の上限 |
| `STREAM_RETRY_INITIAL_DELAY_MILLIS` | `200` | 再試行の待機時間の初期値 |
| `STREAM_RETRY_MAX_DELAY_MILLIS` | `5000` | 再試行の待機時間の上限 |
| `APP_RECOVERY_QUEUE_URL` | なし | 復旧タスクの通知先の SQS。未設定なら notifier を作らない |
| `APP_RECOVERY_MAX_TASKS_PER_RUN` | `50` | 復旧ワーカーが 1 バッチで処理するタスク数 |
| `SPRING_DATASOURCE_URL` | なし | MySQL の JDBC URL |
| `APP_DB_SECRET_ARN` | なし | MySQL の認証情報を持つ Secrets Manager のシークレット |

シークレットは `username` と `password` を持つ JSON にする。`url` を入れると `SPRING_DATASOURCE_URL` より優先される。

## 前提

- 3 つのハンドラと依存ライブラリを含む Lambda 配布物を S3 に置いてある。配布物は `deploy/build-lambda-package.sh` で作る。
- head テーブルで DynamoDB Streams が有効になっている。ストリームの ARN を `HeadStreamArn` に渡す。
- MySQL のリードモデルへ到達できる。VPC 内の MySQL を使う場合は `VpcId`、`SubnetIds`、`SecurityGroupIds` を指定する。パブリックに到達できる場合は空のままでよい。
- MySQL の認証情報を持つ Secrets Manager のシークレットがある。
- AWS CLI が使える。パラメータファイルの構文確認には `jq` か `python3` を使う。
- デプロイ先のアカウントとリージョンに切り替えてある。

## 配布物のビルド

JDK 17 で配布物を作る。

```sh
./deploy/build-lambda-package.sh
```

スクリプトは `./gradlew :rmu:lambdaZip` を実行し、`rmu/build/distributions/rmu-lambda.zip` を作る。ZIP のルートにハンドラのクラス、`lib/` に依存 JAR が入る。S3 へは `--upload --bucket <name>` を付けたときだけコピーする。

```sh
./deploy/build-lambda-package.sh --upload --bucket your-lambda-artifacts-bucket
```

## 適用手順

`parameters.example.json` をコピーし、値を埋める。

```sh
cp deploy/parameters.example.json deploy/parameters.json
```

`CodeS3Bucket` と `CodeS3Key`、`HeadStreamArn`、`DataSourceSecretArn` などを実際の値にする。`VpcId` を使わない場合は空のままでよい。パラメータファイルは `ParameterKey` と `ParameterValue` の組を並べた JSON 配列で、AWS CLI の `--parameter-overrides file://...` がそのまま読む。空の値も明示的に渡るため、既存スタックの VPC 設定を空にして外すこともできる。

まずテンプレートの構文だけを確認する。この段階では AWS を呼ばない。

```sh
./deploy/apply-rmu-deployment.sh --parameters deploy/parameters.json
```

問題がなければスタックを作成する。

```sh
./deploy/apply-rmu-deployment.sh --apply --stack-name cqrs-es-example-kotlin-rmu --parameters deploy/parameters.json
```

スクリプトは `aws cloudformation validate-template` のあと、`aws cloudformation deploy` を `CAPABILITY_IAM` 付きで実行する。`--apply` を付けない限りデプロイしない。

スタック作成後、`ResyncWorkerFunction` を 1 回実行して head の全件再同期を済ませる。

```sh
aws lambda invoke \
  --function-name cqrs-es-example-kotlin-rmu-rmu-resync-worker \
  --payload '{}' \
  --cli-binary-format raw-in-base64-out \
  /tmp/resync-worker-response.json
```

`/tmp/resync-worker-response.json` には `heads`、`skipped`、`advanced`、`failed` の件数が入る。

## 適用後の確認

イベントソースマッピングの設定を確認する。

```sh
aws lambda get-event-source-mapping \
  --uuid "$(aws cloudformation describe-stack-resource \
    --stack-name cqrs-es-example-kotlin-rmu \
    --logical-resource-id RmuEventSourceMapping \
    --query 'StackResourceDetail.PhysicalResourceId' --output text)" \
  --query '{State:State,Bisect:BisectBatchOnFunctionError,ResponseTypes:FunctionResponseTypes,Retries:MaximumRetryAttempts,OnFailure:DestinationConfig.OnFailure.Destination}'
```

次の値になっていることを確かめる。

- `BisectBatchOnFunctionError` が `true`
- `FunctionResponseTypes` に `ReportBatchItemFailures` が含まれる
- `DestinationConfig.OnFailure.Destination` が `OnFailureQueue` の ARN
- `State` が `Enabled`

関数のタイムアウトは 300 秒、復旧キューの可視性タイムアウトは 1800 秒である。

```sh
aws lambda get-function-configuration \
  --function-name cqrs-es-example-kotlin-rmu-rmu --query 'Timeout'

aws sqs get-queue-attributes \
  --queue-url "$(aws cloudformation describe-stacks \
    --stack-name cqrs-es-example-kotlin-rmu \
    --query 'Stacks[0].Outputs[?OutputKey==`RecoveryQueueUrl`].OutputValue' --output text)" \
  --attribute-names VisibilityTimeout RedrivePolicy
```

EventBridge の定期実行を確認する。

```sh
aws cloudformation describe-stack-resources --stack-name cqrs-es-example-kotlin-rmu \
  --query 'StackResources[?ResourceType==`AWS::Events::Rule`].[LogicalResourceId,PhysicalResourceId]' \
  --output table
```

`RecoveryWorkerSchedule` は既定で 5 分ごと、`ResyncWorkerSchedule` は既定で 1 日ごとに動く。

## 復旧タスクの運用手順

RMU はイベントを適用できないとき、識別子だけを MySQL の `read_model_recovery_tasks` に書き、`APP_RECOVERY_QUEUE_URL` が設定されていれば `RecoveryQueue` へ起動メッセージを送る。メッセージ本文はストア ID、投影名と版、集約 ID、`seq_nr`、失敗種別だけで、イベント本文は含めない。復旧ワーカーは起動のたびに、未処理タスクを `APP_RECOVERY_MAX_TASKS_PER_RUN`（既定 50 件）ずつ最大 10 バッチ処理する。50 件を超える滞留も 1 回の起動で進み、それでも残るときは SQS が再配信する。EventBridge の定期実行（既定 5 分ごと）があるため、通知が届かなくても未処理タスクは処理される。

ワーカーが失敗として返したメッセージは、キューの `maxReceiveCount`（既定 5）に達するまで再配信される。達したメッセージは `RecoveryDlq` に移る。

### 復旧 DLQ にメッセージが移ったとき

1. `RecoveryDlq` のメッセージ本文で、通知された集約とシーケンス番号を確認する。通知は起動のきっかけであり、そのタスクがまだ `pending` とは限らない。復旧が完了したタスクの通知が残っていることもある。
2. MySQL の `read_model_recovery_tasks` で、`store_id` と投影（`group-chat-read-model` / `1`）に該当する `status = 'pending'` の行を確認する。それぞれの `error_message` と `failure_type` で原因の種類を見る。
3. 原因を取り除く。journal にイベントがあるか、リードモデルの制約違反が解消したかを確認する。
4. ワーカーをもう一度実行する。SQS のメッセージを再送するか、次のコマンドで直接起動する。ワーカーはメッセージ本文を使わないため、空のイベントでも未処理タスクを再生する。未処理タスクが残ると、このコマンドは関数エラーを返す。

```sh
aws lambda invoke \
  --function-name cqrs-es-example-kotlin-rmu-rmu-recovery-worker \
  --payload '{"Records":[]}' \
  --cli-binary-format raw-in-base64-out \
  /tmp/recovery-worker-response.json
```

5. `read_model_recovery_tasks` の対象行の `status` が `completed` になり、`read_model_positions` が対象のシーケンス番号以上に進んだことを確認する。

### OnFailure キューにメッセージが移ったとき

`OnFailureQueue` には、Lambda の再試行を使い切ったバッチのメタデータが入る。本文は元のレコードではなく、`requestContext` と `DynamoDBBatchInfo`（`shardId`、`startSequenceNumber`、`endSequenceNumber`、`streamArn` など）を含む JSON である。この情報だけでは復旧タスクを作れないため、次のどちらかを行う。

- ストリームの保持期間内であれば、`DynamoDBBatchInfo` のシーケンス番号の範囲を `GetRecords` で読み直し、RMU の入力と同じ形に組み立てて再送する。
- 保持期間を過ぎている場合は `ResyncWorker` を実行し、head と journal から全件再同期する。

復旧タスクの記録自体に失敗した場合もここに来る。その場合は `ResyncWorker` か、同じレコードの再送で復旧タスクを作り直す。

### 監視

次のメトリクスを見る。

- Lambda の `Errors`、`Throttles`、`Duration`、`IteratorAge`
- `OnFailureQueue` と `RecoveryQueue` の `ApproximateNumberOfMessagesVisible`、`ApproximateAgeOfOldestMessage`
- 両方の DLQ の `ApproximateNumberOfMessagesVisible`
- `RecoveryWorkerFunction` と `ResyncWorkerFunction` の `Errors`
- `RecoveryWorkerSchedule` と `ResyncWorkerSchedule` の `Invocations`、`FailedInvocations`

テンプレートは、復旧キューの滞留、OnFailure キューと復旧 DLQ のメッセージ、復旧ワーカーと再同期ワーカーのエラー、再同期の定期実行の失敗にアラームを付ける。`AlarmSnsTopicArn` を指定したときだけ SNS へ通知する。

## ロールバック

スタックを削除すると、テンプレートが作ったキューと Lambda も削除される。キューにメッセージが残っている場合は先に退避する。

```sh
aws cloudformation delete-stack --stack-name cqrs-es-example-kotlin-rmu
```

設定だけを戻す場合は、テンプレートのパラメータを変えて `--apply` を再実行する。`RmuEventSourceMapping` の変更はスタック更新で反映される。

## テスト

`RmuDeploymentTemplateTest` がテンプレートを読み、次の点を検査する。

- `BisectBatchOnFunctionError`、`ReportBatchItemFailures`、OnFailure の SQS、`MaximumRetryAttempts` が設定されている。
- 関数のタイムアウトが再試行の最大経過時間を超え、Lambda の上限 900 秒以内である。
- 復旧キューの可視性タイムアウトがワーカーのタイムアウトの 6 倍以上である。
- 両方のキューに退避先がある。
- テンプレートのハンドラ名が実在し、`RequestHandler` を実装したクラスを指している。
- 環境変数の再試行設定が `LambdaSettings` の既定値と一致する。
- 復旧と再同期の定期実行が有効で、それぞれのワーカーを呼び出している。
- 関数が VPC に接続するのは `VpcId` を指定したときだけである。
- 認証情報を Secrets Manager から読み、環境変数にパスワードを置かない。

`SqsRecoveryNotifierTest` は通知の本文に識別子だけが入り、イベント本文とエラーメッセージが入らないことを確認する。`RecoveryWorkerTest` はバッチの上限、メッセージの再配信、手動起動での失敗を確認する。`ResyncWorkerTest` は件数の返却を確認する。`LambdaStreamRecordsTest` は Lambda のイベント型から SDK v2 のレコードへの変換と、部分バッチの失敗の扱いを確認する。

CI の `deploy-assets` ジョブは、`cfn-lint`、シェルスクリプトの構文確認、`:rmu:lambdaZip` のビルド、ZIP に 3 つのハンドラと `aws-lambda-java-events` が入っていることを確認する。

## 残課題

- MySQL の認証情報は Secrets Manager から読む。ローテーションの手順はまだない。
- 実際の AWS 環境へのデプロイと、アラームの通知先の設定。
- 復旧ワーカーの同時実行数と、1 回に処理するタスク数の調整。
- 全件再同期の頻度（既定は 1 日 1 回）。テーブルが大きい場合は `ResyncScheduleExpression` を調整する。
- 配布物は依存をすべて含むため、展開後は約 138 MB になる。直接アップロードの上限を超えるため、S3 に置いて `CodeS3Bucket` と `CodeS3Key` で渡す。250 MB に近づいたらレイヤーの分割を検討する。

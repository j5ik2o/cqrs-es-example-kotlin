# RMU の運用手順（24 時間超停止時の再同期とアラート）

この文書は、Read Model Updater（RMU）が 24 時間を超えて止まったときの再同期手順と、復旧・アラートの一次対応をまとめる。デプロイ資産そのものは `deploy/README.ja.md` を参照する。実デプロイや実値の設定はこのリポジトリでは行わない。

## 前提

- DynamoDB Streams の保持期間は 24 時間である。RMU の購読が 24 時間以上止まる、またはイベントソースマッピングを無効にしたまま 24 時間を超えると、止まっている間に書かれた head のレコードは Streams から読み直せなくなる。
- Lambda 版の RMU は、ローカルの reader が起動時に行う Scan を実行しない。購読を再開するだけでは、24 時間より前の遅れは埋まらない。`ResyncWorker` の全件再同期で head と journal から補う。
- 全件再同期は head テーブルを ConsistentRead で Scan し、各集約の `seq_nr` と `read_model_positions` を比べて、足りない範囲を journal から昇順に適用する。位置ガードとトランザクションがあるため、購読と Scan が同じイベントを適用しても二重にはならない（DY-15）。
- この手順は、停止していた期間のイベントが journal に残っていることを前提とする。journal の保持期間が停止期間より短い場合は、その範囲を復元できない。停止期間を確認し、必要ならバックアップから journal を戻す判断をする。

## 24 時間を超えて止まったときの再同期

順序が重要である。「購読再開 → 並行 Scan → journal 補完」の順で進める。Scan を先に完了させると、Scan が head を読んだあと、購読が始まる前に書かれた更新が Streams の保持期間を超えてしまう可能性がある。先に購読を戻しておけば、Scan 中に書かれた更新は購読が受け取り、Scan がまだ到達していない更新は新しい位置として読める。

### 1. 停止原因を取り除く

1. Lambda の `Errors` と CloudWatch Logs で、停止の原因を特定する。DB の到達性、Secrets Manager の権限、DynamoDB のスロットリング、関数のタイムアウトを順に見る。
2. 原因が残っている状態で購読を再開すると、同じ失敗を繰り返してイベントが OnFailure キューや DLQ に流れる。先に原因を直す。
3. イベントソースマッピングは削除しない。無効化と有効化で処理位置のチェックポイントを保てる。作り直すと `TRIM_HORIZON` から読み直すことになり、24 時間より前のレコードは読めない。

### 2. 購読を再開する

イベントソースマッピングの UUID を取得し、有効化する。

```sh
STACK_NAME=cqrs-es-example-kotlin-rmu
MAPPING_ID=$(aws cloudformation describe-stack-resource \
  --stack-name "$STACK_NAME" \
  --logical-resource-id RmuEventSourceMapping \
  --query 'StackResourceDetail.PhysicalResourceId' --output text)

aws lambda update-event-source-mapping --uuid "$MAPPING_ID" --enabled

aws lambda get-event-source-mapping --uuid "$MAPPING_ID" \
  --query '{State:State,LastProcessingResult:LastProcessingResult,StartingPosition:StartingPosition}'
```

`State` が `Enabled` に戻り、`LastProcessingResult` が `No records processed` または `OK` になることを確認する。ローカルの `LocalRmuRunner` を使っている場合は、プロセスを再起動する。`LocalRmuRunner` は購読スレッドを先に起動してから再同期を始める。

### 3. 購読と並行して全件再同期する

購読を再開した直後に `ResyncWorker` を 1 回実行する。このワーカーは head の全件 Scan と journal 補完を行う。Scan は購読と並行して進む。

```sh
aws lambda invoke \
  --function-name "${STACK_NAME}-rmu-resync-worker" \
  --payload '{}' \
  --cli-binary-format raw-in-base64-out \
  /tmp/resync-worker-response.json

cat /tmp/resync-worker-response.json
```

応答には次の件数が入る。

| 項目 | 意味 |
|:--|:--|
| `heads` | Scan した GroupChat の head の数 |
| `skipped` | `__config__` や他の集約型として飛ばした数 |
| `advanced` | 位置より先に進んでいたため補完対象にした数 |
| `failed` | 恒久的な失敗として復旧タスクに記録した数 |

`failed` が 0 でない場合は、次の節の「復旧タスクが滞留したとき」を確認する。ワーカーが例外を返した場合は、DB や DynamoDB の一時障害が考えられる。原因を取り除いてもう一度実行する。

### 4. journal 補完を確認する

再同期のあと、MySQL で位置が head に追いついたことを確認する。

```sql
SELECT aggregate_id, seq_nr
FROM read_model_positions
ORDER BY seq_nr DESC
LIMIT 20;

SELECT store_id, projection_name, aggregate_id, seq_nr, status, failure_type, error_message
FROM read_model_recovery_tasks
WHERE status = 'pending'
ORDER BY created_at;
```

`read_model_recovery_tasks` に `pending` が残っていれば、復旧ワーカーを直接実行する。

```sh
aws lambda invoke \
  --function-name "${STACK_NAME}-rmu-recovery-worker" \
  --payload '{"Records":[]}' \
  --cli-binary-format raw-in-base64-out \
  /tmp/recovery-worker-response.json
```

### 5. 完了を確認する

1. `read_model_recovery_tasks` の `pending` が 0 になったことを確認する。完了した行の `status` は `completed` になる。
2. イベントソースマッピングの `IteratorAge` が平常時に戻り、`Errors` と `Throttles` が増えていないことを確認する。
3. `OnFailureQueueAlarm`、`OnFailureDlqAlarm`、`RecoveryDlqAlarm` が `OK` に戻ったことを確認する。
4. 代表的な集約について、Read API の表示が command 側の状態と一致することを確認する。読み取りモデルの遅れは `read_model_positions` の `seq_nr` と head の `seq_nr` の比較でも確認できる。

## アラートと一次対応

テンプレートは次の CloudWatch アラームを定義する。通知先は `AlarmSnsTopicArn` パラメータで指定する。リポジトリには実値を置かず、`deploy/parameters.example.json` にはダミーの ARN を置いてある。

| アラーム | 主なメトリクス | 意味 | 一次対応 |
|:--|:--|:--|:--|
| `RecoveryBacklogAlarm` | `RecoveryQueue` の `ApproximateNumberOfMessagesVisible` | 復旧タスクの滞留 | `read_model_recovery_tasks` の `pending` を確認し、復旧ワーカーを実行する。 |
| `OnFailureQueueAlarm` | `OnFailureQueue` の `ApproximateNumberOfMessagesVisible` | ストリームレコードが Lambda の再試行上限に達した | `DynamoDBBatchInfo` の範囲を `GetRecords` で読み直すか、保持期間外なら全件再同期する。 |
| `OnFailureDlqAlarm` | `OnFailureDlq` の `ApproximateNumberOfMessagesVisible` | OnFailure キューの再試行も尽きた | メッセージを退避し、シーケンス番号の範囲を手動で再生する。 |
| `RecoveryDlqAlarm` | `RecoveryDlq` の `ApproximateNumberOfMessagesVisible` | 復旧タスクが SQS の再試行上限に達した | 対象の集約と `seq_nr` を確認し、原因を除いてから復旧ワーカーを実行する。 |
| `RecoveryWorkerErrorsAlarm` | `RecoveryWorkerFunction` の `Errors` | 復旧ワーカーの関数エラー | ログで DB と journal のエラーを確認する。 |
| `ResyncWorkerErrorsAlarm` | `ResyncWorkerFunction` の `Errors` | 再同期ワーカーの関数エラー | ログでストレージ障害を確認し、再同期を再実行する。 |
| `ResyncScheduleFailedInvocationsAlarm` | `ResyncWorkerSchedule` の `FailedInvocations` | 定期再同期の起動失敗 | Lambda の権限と状態を確認し、手動で再同期する。 |

### アラームのパラメータ

しきい値、評価期間、メトリクスの期間はすべてパラメータで変更できる。環境ごとに `parameters.json` で上書きする。

| パラメータ | 既定 | 用途 |
|:--|:--|:--|
| `AlarmSnsTopicArn` | 空 | 通知先の SNS トピック。空ならアラームは作るがアクションは付けない。 |
| `AlarmPeriodSeconds` | `300` | 全アラーム共通のメトリクス期間（秒）。 |
| `RecoveryBacklogThreshold` | `10` | 復旧キューの滞留しきい値。 |
| `RecoveryBacklogEvaluationPeriods` | `2` | 復旧キューの滞留が連続して必要な期間数。 |
| `OnFailureQueueThreshold` | `0` | OnFailure キューのしきい値。 |
| `OnFailureQueueEvaluationPeriods` | `1` | OnFailure キューの評価期間数。 |
| `OnFailureDlqThreshold` | `0` | OnFailure DLQ のしきい値。 |
| `OnFailureDlqEvaluationPeriods` | `1` | OnFailure DLQ の評価期間数。 |
| `RecoveryDlqThreshold` | `0` | 復旧 DLQ のしきい値。 |
| `RecoveryDlqEvaluationPeriods` | `1` | 復旧 DLQ の評価期間数。 |
| `RecoveryWorkerErrorsThreshold` | `0` | 復旧ワーカーのエラー数のしきい値。 |
| `RecoveryWorkerErrorsEvaluationPeriods` | `1` | 復旧ワーカーのエラーの評価期間数。 |
| `ResyncWorkerErrorsThreshold` | `0` | 再同期ワーカーのエラー数のしきい値。 |
| `ResyncWorkerErrorsEvaluationPeriods` | `1` | 再同期ワーカーのエラーの評価期間数。 |
| `ResyncScheduleFailedInvocationsThreshold` | `0` | 定期再同期の起動失敗数のしきい値。 |
| `ResyncScheduleFailedInvocationsEvaluationPeriods` | `1` | 定期再同期の起動失敗の評価期間数。 |

通知先はテンプレートの外で用意する。SNS トピックを作り、メールやチャットの連携をサブスクライブしてから、その ARN を `AlarmSnsTopicArn` に設定する。トピック ARN をリポジトリにコミットしない。

## 復旧タスクが滞留したとき

復旧ワーカーは起動のたびに未処理タスクを `APP_RECOVERY_MAX_TASKS_PER_RUN`（既定 50 件）ずつ最大 10 バッチ処理する。1 回で終わらない場合は SQS が再配信し、5 分ごとの定期実行も未処理タスクを拾う。

1. `read_model_recovery_tasks` の `store_id` と投影（`group-chat-read-model` / `1`）が一致する `pending` を確認する。
2. `failure_type` と `error_message` で原因を分類する。payload の解析失敗、journal の欠番、MySQL の制約違反が多い。
3. 原因を除く。journal にイベントがあるか、リードモデルの制約が解消したかを確認する。
4. 次のコマンドで復旧ワーカーを直接実行する。未処理が残ると関数エラーになる。

```sh
aws lambda invoke \
  --function-name "${STACK_NAME}-rmu-recovery-worker" \
  --payload '{"Records":[]}' \
  --cli-binary-format raw-in-base64-out \
  /tmp/recovery-worker-response.json
```

5. `status` が `completed` になり、`read_model_positions` が対象の `seq_nr` 以上に進んだことを確認する。

## DLQ のメッセージを確認するとき

```sh
aws sqs receive-message \
  --queue-url "$(aws cloudformation describe-stacks \
    --stack-name "$STACK_NAME" \
    --query 'Stacks[0].Outputs[?OutputKey==`RecoveryDlqUrl`].OutputValue' --output text)" \
  --max-number-of-messages 10 \
  --visibility-timeout 0
```

確認だけなら `--visibility-timeout 0` を付け、メッセージを再び見えるようにしておく。本文にはイベント本文が入らない。識別子から journal と `read_model_recovery_tasks` を引き直して原因を見る。

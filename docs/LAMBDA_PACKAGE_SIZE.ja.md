# Lambda 配布物のサイズと内訳

RMU（Read Model Updater）の Lambda 配布物の大きさを計測した記録です。2026-11-08 時点の結果を載せています。issue #17 の決定に従い、**計測のみ**を行い、レイヤー分割や依存の除外はしていません。

## 計測方法

`deploy/measure-lambda-package.sh` が配布物をビルドし、圧縮サイズ、展開後サイズ、`lib/` の JAR 一覧、GCP/SQLite 依存の有無を標準出力に出す。合否のしきい値は設けていません。CI の `deploy-assets` ジョブも同じスクリプトを `--skip-build` で実行し、ビルドのたびに結果をログに残します。

```sh
# ビルドして計測する（JDK 17）
./deploy/measure-lambda-package.sh

# ビルド済みの ZIP を計測する
./deploy/measure-lambda-package.sh --skip-build

# 別の ZIP と一覧の件数を指定する（--zip は読み取り専用）
./deploy/measure-lambda-package.sh --zip /path/to/rmu-lambda.zip --skip-build --top 30
```

`--zip` は計測対象を指定するだけで、そのファイルには書き込みません。既定のパス以外を指定するときは `--skip-build` が必須です。`--zip` を省略すると `:rmu:lambdaZip` をビルドし、既定の配布物を計測します。

展開後サイズは ZIP を一時ディレクトリに展開し、ファイルサイズを合計して求めています。JAR 一覧も展開後のサイズで並べています。ZIP のヘッダーを含むファイルサイズと、`zipinfo` が集計する圧縮後サイズは一致しません。

## 計測結果（2026-11-08）

| 項目 | バイト | MiB | 補足 |
|:--|--:|--:|:--|
| ZIP（圧縮） | 127,188,075 | 121.30 | `rmu/build/distributions/rmu-lambda.zip` |
| 展開後 | 137,883,597 | 131.50 | 273 ファイル |
| `lib/` の JAR 合計 | 137,538,298 | 131.17 | 190 JAR |

ZIP 内のエントリは 282 件で、圧縮後の内容は 127,143,679 バイト、圧縮率は 7.8% でした。展開後は Lambda の展開上限 250 MiB の 52.6% です。直接アップロードの上限 50 MB を超えるため、これまでどおり S3 に置いて `CodeS3Bucket` と `CodeS3Key` で渡す必要があります。

## `lib/` の上位 20 JAR

| # | JAR | バイト | MiB |
|--:|:--|--:|--:|
| 1 | `sqlite-jdbc-3.49.1.0.jar` | 14,317,659 | 13.65 |
| 2 | `grpc-xds-1.83.0.jar` | 12,310,552 | 11.74 |
| 3 | `grpc-netty-shaded-1.83.0.jar` | 10,837,690 | 10.34 |
| 4 | `bcprov-jdk18on-1.80.jar` | 8,682,747 | 8.28 |
| 5 | `conscrypt-openjdk-uber-2.6.2.jar` | 7,548,583 | 7.20 |
| 6 | `kotlin-reflect-2.4.21.jar` | 3,808,541 | 3.63 |
| 7 | `graphql-java-24.3.jar` | 3,756,768 | 3.58 |
| 8 | `dynamodb-2.57.11.jar` | 3,139,372 | 2.99 |
| 9 | `guava-33.6.0-jre.jar` | 3,064,793 | 2.92 |
| 10 | `proto-google-common-protos-2.78.0.jar` | 2,830,165 | 2.70 |
| 11 | `mysql-connector-j-9.7.0.jar` | 2,607,237 | 2.49 |
| 12 | `tink-1.16.0.jar` | 2,469,561 | 2.36 |
| 13 | `proto-google-cloud-bigtable-admin-v2-2.85.0.jar` | 2,113,305 | 2.02 |
| 14 | `spring-web-6.2.19.jar` | 2,096,068 | 2.00 |
| 15 | `spring-boot-autoconfigure-3.5.16.jar` | 2,088,460 | 1.99 |
| 16 | `proto-google-cloud-monitoring-v3-3.100.0.jar` | 2,036,851 | 1.94 |
| 17 | `spring-core-6.2.19.jar` | 1,983,775 | 1.89 |
| 18 | `google-cloud-spanner-6.124.0.jar` | 1,955,738 | 1.87 |
| 19 | `spring-boot-3.5.16.jar` | 1,904,513 | 1.82 |
| 20 | `reactor-core-3.7.19.jar` | 1,899,418 | 1.81 |

## ESA Java 経由の Spanner / Bigtable / SQLite

含まれています。`io.github.j5ik2o:event-store-adapter-kotlin:5.1.0` が `io.github.j5ik2o:event-store-adapter-java:5.1.0` に依存し、その POM が次の 3 つを宣言しています。

| 依存 | 宣言されたバージョン | スコープ | 実際に解決されたバージョン |
|:--|:--|:--|:--|
| `com.google.cloud:google-cloud-spanner` | 6.124.0 | compile | 6.124.0 |
| `com.google.cloud:google-cloud-bigtable` | 2.85.0 | compile | 2.85.0 |
| `org.xerial:sqlite-jdbc` | 3.53.4.0 | runtime | 3.49.1.0（Spring Boot の BOM が固定） |

`event-store-adapter-java-5.1.0.jar` には `com/github/j5ik2o/event/store/adapter/java/{spanner,bigtable,sqlite}/` のクラスが入っています（spanner 20、bigtable 21、sqlite 19 クラス）。RMU が実際に使うのは DynamoDB のアダプタだけですが、依存は任意ではなく通常の compile / runtime 依存として解決されるため、配布物に含まれます。

Spanner / Bigtable / SQLite と、それらが引き込む gRPC、GCP 認証、protobuf、暗号ライブラリをまとめると、57 JAR、79,526,529 バイト（75.84 MiB）で、`lib/` 全体の 57.8% を占めます。内訳の大半は次のとおりです。

- SQLite ドライバ: `sqlite-jdbc`（13.65 MiB）
- gRPC 関連: `grpc-xds`、`grpc-netty-shaded`、`grpc-google-cloud-*` などの `grpc-*` と opencensus で 25 JAR（25.51 MiB）
- GCP クライアント: `google-cloud-spanner`、`google-cloud-bigtable`、`google-cloud-monitoring`、`google-cloud-core`、gax など
- 暗号・認証: `bcprov-jdk18on`、`conscrypt-openjdk-uber`、`tink`
- protobuf: `proto-google-*`、`protobuf-java*`

## その他の観察

- 2 番目に大きい `grpc-xds` と 3 番目の `grpc-netty-shaded` は、Spanner / Bigtable の gRPC スタックが引き込んでいます。
- `graphql-java`、`spring-web`、`spring-boot-*`、`reactor-core` は `:command:interface-adaptor-impl` の `spring-boot-starter-graphql` 経由で入っています。Lambda のハンドラは Spring のコンテキストを使いませんが、モジュールの依存として配布物に含まれます。関連する JAR は 19 件、17.36 MiB です。
- `mysql-connector-j` は `:rmu` の `runtimeOnly` です。Lambda から MySQL のリードモデルへ接続するため、配布物に必要です。

## CI での記録

`.github/workflows/ci.yml` の `deploy-assets` ジョブは、`:rmu:lambdaZip` のビルド後に `./deploy/measure-lambda-package.sh --skip-build` を実行します。サイズと JAR 一覧はジョブログに残り、しきい値による失敗はありません。

## 残課題

- レイヤー分割と依存の除外は、2026-11-08 の決定どおり今回は行っていません。展開後は 250 MiB の 52.6% で、まだ余裕はあります。
- サイズが上限に近づいたときの候補は、使っていない Spanner / Bigtable / SQLite と gRPC スタックの除外、Spring 依存の分離、依存 JAR のレイヤー化です。実施するときは、ESA Java の依存を除外しても DynamoDB アダプタが動くことをテストで確認する必要があります。
- 計測値は依存のバージョンで変わるため、更新時はこのページの日付と数値を更新してください。

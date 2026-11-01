# HC Export（Health Connect → Google ドライブ CSV）

Pixel の Health Connect に入っている健康データ（Mi Fitness / OMRON connect などが書き込んだもの）を、
毎朝 4:00〜4:30 頃に前日分を CSV にして Google ドライブの `マイドライブ/HealthConnectExport/` へアップロードします。
PC 側は Google Drive for desktop でこのフォルダを同期すれば、`health_YYYY-MM-DD.csv` が自動で届きます。

## 構成（容量を抑えるための方針）

- 依存ライブラリなし。Java + Android フレームワーク API のみ（Jetpack / Kotlin / Play services 不使用）
- Health Connect は OS 組み込みの `android.health.connect`（Android 14 以降）を直接使用
- 定期実行は WorkManager ではなく `AlarmManager`（非正確アラーム）+ `JobScheduler`（ネットワーク接続を条件）
- Drive は REST v3 を `HttpURLConnection` で直接呼び出し、トークンは端末の `AccountManager` から取得
- R8（minify + shrinkResources）有効
- minSdk 35 / targetSdk 36（Pixel 10a は Android 16 なので対象）

| ファイル | 役割 |
|---|---|
| `HealthCsv.java` | 1日分の全レコードを読み、縦持ち CSV を作成 |
| `Drive.java` | フォルダ作成・同名ファイル上書き・アップロード |
| `Exporter.java` | 対象日の決定（未送信日の補完、直近2日の再送） |
| `Scheduler.java` / `AlarmReceiver.java` / `ExportJobService.java` | 毎朝の自動実行 |
| `MainActivity.java` | 権限設定・手動実行・ログ表示の最小画面 |

## 動作仕様

- 毎回「前回成功日の翌日〜昨日」を書き出します（最大30日）。PC やスマホが止まっていた日も自動で補完します。
- Mi Fitness などはデータを遅れて Health Connect に書き込むことがあるため、毎回 **直近2日分は再作成して上書き** します。
- 同じ日付のファイルは Drive 上で上書き（ファイルIDは同じまま）なので、重複ファイルはできません。
- 失敗時（圏外など）は JobScheduler が 15 分〜の指数バックオフで再試行します。

## CSV の形式

詳細な仕様（PC側の取り込み実装用）は [docs/csv-spec.md](docs/csv-spec.md) を参照してください。
（UTF-8、BOM なし、1日1ファイル、縦持ち）

レコード種類ごとに項目が違うため、1 行 = 1 項目の縦持ちにしています。

| 列 | 内容 |
|---|---|
| record_type | `Steps`, `HeartRate`, `BloodPressure`, `SleepSession`, `Weight` など（`Record` を除いた型名） |
| record_id | Health Connect のレコードID（同一レコードの行をまとめるキー） |
| record_start / record_end | 期間型レコードの開始・終了。瞬間型（体重・血圧など）は record_start のみ |
| item_start / item_end | 心拍のサンプル時刻、睡眠ステージの区間など、レコード内の明細の時刻 |
| field | 項目名。明細は `samples.beatsPerMinute`、`stages.type` のようにドット区切り |
| value | 値 |
| unit | `kcal` `m` `kg` `mmHg` `degC` `m/s` `L` `W` `%` `mmol/L`（単位なしは空） |
| source_app | 書き込み元アプリのパッケージ名（Mi Fitness: `com.mi.health`、OMRON connect: `jp.co.omron.healthcare.omron_connect` など） |
| device | 機器メーカー・モデル（書き込み元が設定している場合） |
| last_modified | 最終更新日時 |

時刻はすべて端末のタイムゾーン付き ISO 8601（例 `2026-10-31T07:15:00+09:00`）です。

例：
```
record_type,record_id,record_start,record_end,item_start,item_end,field,value,unit,source_app,device,last_modified
BloodPressure,abc...,2026-10-31T07:15+09:00,,,,systolic,128,mmHg,jp.co.omron.healthcare.omron_connect,OMRON ...,...
BloodPressure,abc...,2026-10-31T07:15+09:00,,,,diastolic,82,mmHg,...
HeartRate,def...,2026-10-31T10:00+09:00,2026-10-31T10:59:59+09:00,2026-10-31T10:00:05+09:00,,samples.beatsPerMinute,72,,com.mi.health,...
SleepSession,ghi...,2026-10-30T23:40+09:00,2026-10-31T06:50+09:00,2026-10-30T23:40+09:00,2026-10-31T00:10+09:00,stages.type,4,,com.mi.health,...
```

`stages.type`（睡眠ステージ）、`exerciseType`（運動種目）、`measurementLocation` などの数値コードは
Android の定数値そのままです。意味は公式リファレンス（例: `SleepSessionRecord.StageType`、
`ExerciseSessionType`）を参照してください。

項目は getter をリフレクションで列挙して出力しているため、OS 更新で Health Connect に項目が増えても自動で CSV に出ます。
対象外にしているのは、トレーニング「計画」（PlannedExerciseSession）と運動ルート（GPS位置情報）です。

---

## セットアップ手順

### 1. Google Cloud 側（初回のみ・10分程度）

1. https://console.cloud.google.com/ でプロジェクトを新規作成（名前は任意）
2. 「API とサービス」→「ライブラリ」→ **Google Drive API** を有効化
3. 「Google Auth Platform」（旧: OAuth 同意画面）を開いて設定
   - アプリ名・サポートメール：任意（自分のアドレス）
   - 対象：**外部**
   - データアクセス：スコープ `https://www.googleapis.com/auth/drive.file` を追加
   - 対象ユーザー：「アプリを公開」で **本番環境** にするのがおすすめです。
     `drive.file` は機密性の低いスコープなので審査は不要です。
     テスト中のままだと、許可が7日で切れて再設定が必要になることがあります
     （テストのままにする場合は自分の Gmail をテストユーザーに追加）。
4. 「クライアント」→「クライアントを作成」→ 種類 **Android**
   - パッケージ名：`dev.hcexport`
   - SHA-1 証明書フィンガープリント：
     ```
     44:3A:61:04:2B:87:78:2B:16:6F:88:9F:5C:52:9B:16:BD:21:58:EC
     ```
   - クライアントIDはアプリに埋め込む必要はありません（パッケージ名と署名で識別されます）。

### 2. スマホ側

1. Mi Fitness と OMRON connect で、それぞれ Health Connect への書き込み連携を ON にする
2. APK をインストール（ファイルアプリから開き「この提供元を許可」）
3. 「HC Export」を開いて
   1. **「1. Health Connect の読み取りを許可」** → 「すべて許可」。
      「バックグラウンドでのアクセス」と「過去のデータへのアクセス」も ON にしてください
      （後者を許可すると、許可前30日より古いデータも読めます）
   2. **「2. Google アカウントを選択」** → アカウントを選び、ドライブへのアクセスを許可
   3. **「過去30日分をまとめて書き出す」** で初回分を作成（動作確認を兼ねる）
4. 以降は毎朝 4 時台に自動実行されます。画面下のログで結果を確認できます。
5. （任意）設定 →アプリ →HC Export →バッテリー を「制限なし」にすると、実行がより確実になります。

### 3. PC 側

Google Drive for desktop で `マイドライブ/HealthConnectExport` が同期されるようにしてください
（ストリーミングの場合は、フォルダを右クリック →「オフラインで使用可能にする」にすると取り込みが確実です）。

---

## ビルド方法

```
# Android SDK（platform 36 / build-tools）と JDK 17 以上が必要
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

署名鍵（`keystore/release.jks` と `keystore/signing.properties`）はリポジトリには含めていません。
**この鍵を無くすと、同じ SHA-1 でアップデートできなくなる**ので大切に保管してください。

GitHub Actions（`.github/workflows/build.yml`）は push のたびに APK を作り、Actions の Artifacts に置きます。
署名付きにするには、リポジトリの Settings → Secrets and variables → Actions に次の2つを登録します。

| Secret 名 | 値 |
|---|---|
| `HC_KEYSTORE_B64` | `release.jks` を base64 にした文字列（`base64 -w0 release.jks`） |
| `HC_STORE_PASSWORD` | `signing.properties` の `hcStorePassword` の値 |

未登録の場合は未署名 APK（ビルド確認用、インストール不可）になります。

## トラブル時

- ログに「未許可: N種」：端末が対応していない、または許可していないデータ種類があります（対応外の種類は正常）
- ログに「Googleアカウントを再設定」：アプリを開いて「2.」をもう一度実行
- Drive で 403 `accessNotConfigured`：Google Drive API の有効化漏れ
- アカウント選択後に失敗（`UNREGISTERED_ON_API_CONSOLE` など）：Android クライアントのパッケージ名 / SHA-1 の登録ミス

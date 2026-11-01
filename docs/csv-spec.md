# HC Export CSV 仕様書

PC 側でデータを取り込む処理を実装するための仕様書です。
対象は HC Export アプリ（`dev.hcexport`）が Google ドライブに出力する CSV ファイルです。

- 対象バージョン：アプリ 1.0（リポジトリ `main`、2026-11-01 時点）
- 実装の根拠：`app/src/main/java/dev/hcexport/HealthCsv.java`、`Exporter.java`、`Drive.java`

---

## 1. ファイル

| 項目 | 仕様 |
|---|---|
| 保存場所 | Google ドライブ `マイドライブ/HealthConnectExport/`（PC では Google Drive for desktop の同期フォルダ配下） |
| ファイル名 | `health_YYYY-MM-DD.csv`（例 `health_2026-10-31.csv`）。日付は **スマホのタイムゾーンでの暦日** |
| 単位 | 1日1ファイル。その日の 0:00:00〜翌日 0:00:00（スマホのタイムゾーン）のデータ |
| 文字コード | UTF-8、**BOM なし** |
| 改行 | LF（`\n`）。最終行の末尾にも LF あり |
| 区切り文字 | カンマ `,` |
| 囲み文字 | 値にカンマ・ダブルクォート・CR・LF のいずれかが含まれる場合だけ `"` で囲む。値の中の `"` は `""` にする（RFC 4180 準拠） |
| ヘッダー行 | 1行目に必ずある（列名は下記の英字固定） |
| データなしの日 | ヘッダー行だけのファイルが作られる |
| 行の順序 | データ種別（下記 4 章の順）→ 同じ種別内では Health Connect の返却順。**順序に依存しないこと** |

### 1.1 出力タイミングと上書き（取り込み側で重要）

- 毎朝 4:00〜4:30 頃に自動実行し、「前回成功した日の翌日〜昨日」を出力します（最大 30 日分さかのぼる）。
- Mi Fitness などは遅れて同期されることがあるため、**毎回、直近 2 日分（昨日・一昨日）のファイルを作り直して上書き**します。
- アプリの手動ボタン「過去30日分をまとめて書き出す」を押すと、過去 30 日分のファイルがすべて上書きされます。
- 上書きはドライブ上の同じファイル（ファイル ID も同じ）の中身の差し替えです。同じ日付のファイルが 2 つできることはありません。

**取り込み側の推奨実装**

1. ファイルの更新日時（またはハッシュ）を覚えておき、変わったファイルだけ取り込む。
2. 取り込むときは「そのファイルの日付分を削除してから全件登録」するか、`record_id` をキーに UPSERT する。
3. 日付をまたぐ記録（睡眠など）は **前日と当日の両方のファイルに同じ `record_id` で出る** ことがあります。重複は `record_id` で除外してください。
4. Drive for desktop の同期中は、書き込み途中のファイルを読む可能性があります。取り込み時に行数が 0 件なら、あとで再試行する作りにしておくと安全です。

---

## 2. 列定義（12 列固定）

データの持ち方は **縦持ち**（1 行 = 1 レコードの 1 項目）です。1 つのレコード（例：血圧 1 回分）は、項目の数だけ行が出ます。同じレコードの行は `record_id` が同じです。

| # | 列名 | 型 | 必須 | 内容 |
|---|---|---|---|---|
| 1 | `record_type` | 文字列 | ○ | データ種別。例 `Steps`、`HeartRate`、`BloodPressure`（4 章を参照） |
| 2 | `record_id` | 文字列 | ○ | Health Connect のレコード ID（UUID 形式）。**レコードを一意に識別するキー** |
| 3 | `record_start` | 日時 | ○ | 期間型レコードは開始日時。瞬間型レコードは測定日時 |
| 4 | `record_end` | 日時 | | 期間型レコードの終了日時。瞬間型レコードは空 |
| 5 | `item_start` | 日時 | | レコード内の明細の日時。心拍のサンプル時刻や睡眠ステージの開始など。明細でない項目は空 |
| 6 | `item_end` | 日時 | | 明細の終了日時（睡眠ステージなど区間を持つ明細だけ）。それ以外は空 |
| 7 | `field` | 文字列 | | 項目名（3 章・4 章を参照）。項目を持たないレコードは空 |
| 8 | `value` | 文字列 | | 値（3.2 節を参照）。項目を持たないレコードは空 |
| 9 | `unit` | 文字列 | | 単位（3.3 節を参照）。単位のない値は空 |
| 10 | `source_app` | 文字列 | ○ | データを書き込んだアプリのパッケージ名 |
| 11 | `device` | 文字列 | | 測定機器の「メーカー モデル」（書き込み元アプリが設定した場合のみ） |
| 12 | `last_modified` | 日時 | ○ | Health Connect 上での最終更新日時 |

`source_app` の値の例（端末で要確認）

| アプリ | パッケージ名 |
|---|---|
| Mi Fitness | `com.mi.health` |
| OMRON connect | `jp.co.omron.healthcare.omron_connect` |
| Fitbit / Pixel Watch | `com.google.android.apps.fitness` など |

同じ種類のデータを複数のアプリが書き込むと、重複して出力されます（例：Mi Fitness と別アプリの歩数）。どのアプリの値を採用するかは `source_app` で判断してください。

---

## 3. 値の書式

### 3.1 日時（`record_start`、`record_end`、`item_start`、`item_end`、`last_modified`、日時型の `value`）

- ISO 8601 の **オフセット付き日時**。スマホのタイムゾーン（日本なら `+09:00`）で出力します。
- Java の `OffsetDateTime.toString()` の形式のため、**秒やミリ秒が 0 のときは省略されます**。次のどれも出る可能性があります。
  - `2026-10-31T07:15+09:00`
  - `2026-10-31T07:15:30+09:00`
  - `2026-10-31T07:15:30.123+09:00`
- Java では `OffsetDateTime.parse(s)`（`DateTimeFormatter.ISO_OFFSET_DATE_TIME`）でそのまま読めます。

### 3.2 数値・その他の `value`

| 元の型 | 出力 | 例 |
|---|---|---|
| 整数 | 10 進の整数 | `8532` |
| 小数（整数値のもの） | 小数点なしの整数 | `128` |
| 小数（それ以外） | Java の `Double.toString()` | `61.35`、`0.025` |
| 真偽値 | `true` / `false` | `true` |
| 文字列 | そのまま（1 章のルールで囲む） | `朝のウォーキング` |
| コード値 | 整数（4.2 節の対応表を参照） | `4` |

**注意**：とても小さい値や大きい値は `1.0E-4` のような指数表記になることがあります（`Double.toString()` の仕様）。数値は `BigDecimal` か `Double.parseDouble` で読んでください。

### 3.3 単位（`unit`）

Health Connect 内部の単位から、次の単位に換算して出力します。

| `unit` | 意味 | 元の型 |
|---|---|---|
| `kcal` | キロカロリー | Energy |
| `m` | メートル | Length |
| `kg` | キログラム | Mass（**栄養素の量も kg** です。例：たんぱく質 25 g → `0.025`） |
| `mmHg` | ミリメートル水銀柱 | Pressure |
| `degC` | 摂氏（℃）。皮膚温の変化量（delta）も℃差 | Temperature / TemperatureDelta |
| `m/s` | メートル毎秒 | Velocity |
| `L` | リットル | Volume |
| `W` | ワット | Power（基礎代謝も W。kcal/日 = W × 20.6362） |
| `%` | パーセント（0〜100） | Percentage |
| `mmol/L` | ミリモル毎リットル（mg/dL = mmol/L × 18.016） | BloodGlucose |
| （空） | 単位なし（回数・bpm・コード値・文字列など） | |

> **要実機確認**：`kcal` は Health Connect の内部値（カロリー）を 1/1000 にして求めています。初回の取り込み時に、Mi Fitness の画面の消費カロリーと桁が合っているか確認してください。

### 3.4 項目名（`field`）

- 項目名は Health Connect の getter 名から作っています（`getBeatsPerMinute` → `beatsPerMinute`）。
- 明細（リスト）の中の項目は `リスト名.項目名`（例 `samples.beatsPerMinute`、`stages.type`）、入れ子のオブジェクトの項目も `.` でつなぎます。
- 明細は 1 件ごとに行が出て、その明細の日時が `item_start`／`item_end` に入ります。
- **取り込み側は、知らない `field` が来ても無視するように実装してください。** OS の更新で Health Connect に項目が増えると、アプリを変えなくても新しい `field` が出力されます。

---

## 4. データ種別ごとの内容

### 4.1 種別と項目の一覧

「型」は瞬間型（`record_end` が空）か期間型（`record_start`〜`record_end`）か、「明細」は明細行（`item_start` あり）を持つかどうかです。
`*` が付いた項目は、書き込み元アプリが設定した場合だけ出ます（値が無い項目は行自体が出ません）。

| record_type | 内容 | 型 | field | unit | 備考 |
|---|---|---|---|---|---|
| `ActiveCaloriesBurned` | 活動消費カロリー | 期間 | `energy` | kcal | |
| `ActivityIntensity` | 活動強度（Android 16 以降） | 期間 | `activityIntensityType` | | コード：0=中程度、1=高強度 |
| `BasalBodyTemperature` | 基礎体温 | 瞬間 | `temperature`、`measurementLocation` | degC | 測定部位は 4.2 節 |
| `BasalMetabolicRate` | 基礎代謝 | 瞬間 | `basalMetabolicRate` | W | kcal/日 = W × 20.6362 |
| `BloodGlucose` | 血糖値 | 瞬間 | `level`、`specimenSource`、`mealType`、`relationToMeal` | mmol/L | コードは 4.2 節 |
| `BloodPressure` | 血圧 | 瞬間 | `systolic`（最高）、`diastolic`（最低）、`bodyPosition`、`measurementLocation` | mmHg | 脈拍は別種別 `HeartRate` で出ることが多い |
| `BodyFat` | 体脂肪率 | 瞬間 | `percentage` | % | |
| `BodyTemperature` | 体温 | 瞬間 | `temperature`、`measurementLocation` | degC | |
| `BodyWaterMass` | 体水分量 | 瞬間 | `bodyWaterMass` | kg | |
| `BoneMass` | 骨量 | 瞬間 | `mass` | kg | |
| `CervicalMucus` | 頸管粘液 | 瞬間 | `appearance`、`sensation` | | コード値 |
| `CyclingPedalingCadence` | ペダル回転数 | 期間・明細 | `samples.revolutionsPerMinute` | | rpm |
| `Distance` | 移動距離 | 期間 | `distance` | m | |
| `ElevationGained` | 獲得標高 | 期間 | `elevation` | m | |
| `ExerciseSession` | 運動 | 期間・明細 | `exerciseType`、`title*`、`notes*`、`segments.segmentType`、`segments.repetitionsCount`、`laps.length` | lapsは m | 種目コードは 4.2 節。GPS ルートは出力しない |
| `FloorsClimbed` | 上った階数 | 期間 | `floors` | | 小数のことがある |
| `HeartRate` | 心拍数 | 期間・明細 | `samples.beatsPerMinute` | | bpm。1 レコードに多数の明細行 |
| `HeartRateVariabilityRmssd` | 心拍変動（RMSSD） | 瞬間 | `heartRateVariabilityMillis` | | ミリ秒 |
| `Height` | 身長 | 瞬間 | `height` | m | |
| `Hydration` | 水分摂取 | 期間 | `volume` | L | |
| `IntermenstrualBleeding` | 不正出血 | 瞬間 | （なし） | | `field` が空の行が 1 行出る |
| `LeanBodyMass` | 除脂肪体重 | 瞬間 | `mass` | kg | |
| `MenstruationFlow` | 月経の量 | 瞬間 | `flow` | | コード値 |
| `MenstruationPeriod` | 月経期間 | 期間 | （なし） | | `field` が空の行が 1 行出る |
| `MindfulnessSession` | マインドフルネス | 期間 | `mindfulnessSessionType`、`title*`、`notes*` | | コード値 |
| `Nutrition` | 栄養・食事 | 期間 | `energy`、`protein`、`totalFat`、`totalCarbohydrate`、`sugar`、`dietaryFiber`、`sodium` ほか栄養素ごと、`mealType`、`mealName*` | kcal / kg | 栄養素は kg（g なら ×1000） |
| `OvulationTest` | 排卵検査 | 瞬間 | `result` | | コード値 |
| `OxygenSaturation` | 血中酸素（SpO2） | 瞬間 | `percentage` | % | |
| `Power` | パワー | 期間・明細 | `samples.power` | W | |
| `RespiratoryRate` | 呼吸数 | 瞬間 | `rate` | | 回/分 |
| `RestingHeartRate` | 安静時心拍数 | 瞬間 | `beatsPerMinute` | | bpm |
| `SexualActivity` | 性行為 | 瞬間 | `protectionUsed` | | コード値 |
| `SkinTemperature` | 皮膚温 | 期間・明細 | `baseline*`、`measurementLocation`、`deltas.delta` | degC | delta は基準からの差 |
| `SleepSession` | 睡眠 | 期間・明細 | `title*`、`notes*`、`stages.type` | | ステージコードは 4.2 節。各ステージの区間は `item_start`〜`item_end` |
| `Speed` | 速度 | 期間・明細 | `samples.speed` | m/s | km/h = m/s × 3.6 |
| `StepsCadence` | ピッチ | 期間・明細 | `samples.rate` | | 歩/分 |
| `Steps` | 歩数 | 期間 | `count` | | 1 日の合計は同じ日の行の合計（ただし `source_app` ごとに集計） |
| `TotalCaloriesBurned` | 総消費カロリー | 期間 | `energy` | kcal | |
| `Vo2Max` | 最大酸素摂取量 | 瞬間 | `vo2MillilitersPerMinuteKilogram`、`measurementMethod` | | mL/kg/分 |
| `Weight` | 体重 | 瞬間 | `weight` | kg | |
| `WheelchairPushes` | 車いすのプッシュ回数 | 期間 | `count` | | |

> 上の field 一覧は Android 16 の Health Connect API をもとにした想定です。実際の項目は getter から自動で作られるため、端末の OS バージョンにより項目が増減することがあります。
> 初回の CSV で実際の `record_type` と `field` の組み合わせを確認し、上の表と違いがあれば知らせてください（表を更新します）。

**出力しないもの**：トレーニング計画（PlannedExerciseSession）、運動の GPS ルート、Health Connect の設定、医療記録（FHIR）。

### 4.2 主なコード値

コード値は Android の定数値をそのまま出力しています。正式な一覧は Android の公式リファレンス（`android.health.connect.datatypes` パッケージの各クラス）を参照してください。

**睡眠ステージ `SleepSession` / `stages.type`**

| 値 | 意味 |
|---|---|
| 0 | 不明 |
| 1 | 覚醒（睡眠中の目覚め） |
| 2 | 睡眠（ステージ区別なし） |
| 3 | 離床 |
| 4 | 浅い睡眠 |
| 5 | 深い睡眠 |
| 6 | レム睡眠 |
| 7 | ベッドで覚醒 |

**血圧の姿勢 `BloodPressure` / `bodyPosition`**

| 値 | 意味 |
|---|---|
| 0 | 不明 |
| 1 | 立位 |
| 2 | 座位 |
| 3 | 仰臥位（寝た状態） |
| 4 | 半臥位（もたれた状態） |

**血圧の測定部位 `BloodPressure` / `measurementLocation`**

| 値 | 意味 |
|---|---|
| 0 | 不明 |
| 1 | 左手首 |
| 2 | 右手首 |
| 3 | 左上腕 |
| 4 | 右上腕 |

**体温の測定部位 `BodyTemperature`・`BasalBodyTemperature` / `measurementLocation`**

| 値 | 意味 | 値 | 意味 |
|---|---|---|---|
| 0 | 不明 | 6 | こめかみ（側頭動脈） |
| 1 | 脇の下 | 7 | 足の指 |
| 2 | 指 | 8 | 耳 |
| 3 | 額 | 9 | 手首 |
| 4 | 口 | 10 | 膣 |
| 5 | 直腸 | | |

**食事の種類 `mealType`（BloodGlucose・Nutrition）**

| 値 | 意味 |
|---|---|
| 0 | 不明 |
| 1 | 朝食 |
| 2 | 昼食 |
| 3 | 夕食 |
| 4 | 間食 |

**血糖値と食事の関係 `BloodGlucose` / `relationToMeal`**

| 値 | 意味 |
|---|---|
| 0 | 不明 |
| 1 | 一般（随時） |
| 2 | 空腹時 |
| 3 | 食前 |
| 4 | 食後 |

**血糖値の検体 `BloodGlucose` / `specimenSource`**

| 値 | 意味 |
|---|---|
| 0 | 不明 |
| 1 | 間質液 |
| 2 | 毛細血管血 |
| 3 | 血漿 |
| 4 | 血清 |
| 5 | 涙液 |
| 6 | 全血 |

**運動の種目 `ExerciseSession` / `exerciseType`（よく使うもの）**

| 値 | 意味 | 値 | 意味 |
|---|---|---|---|
| 0 | その他 | 56 | ランニング |
| 8 | サイクリング | 57 | トレッドミル |
| 9 | エアロバイク | 61 | スキー |
| 25 | エリプティカル | 70 | 筋力トレーニング |
| 32 | ゴルフ | 71 | ストレッチ |
| 36 | HIIT | 73 | 水泳（オープンウォーター） |
| 37 | ハイキング | 74 | 水泳（プール） |
| 48 | ピラティス | 79 | ウォーキング |
| 54 | ローイングマシン | 83 | ヨガ |

> 運動種目は 80 種類ほどあります。上の表にない値は `ExerciseSessionType` の公式リファレンスで確認してください。上の表は初回データで実際の値と照合することをおすすめします。

---

## 5. 出力例

```csv
record_type,record_id,record_start,record_end,item_start,item_end,field,value,unit,source_app,device,last_modified
Steps,2f6c...e1,2026-10-31T09:00+09:00,2026-10-31T10:00+09:00,,,count,1532,,com.mi.health,Xiaomi Smart Band 9,2026-10-31T10:05:12+09:00
HeartRate,8a10...3c,2026-10-31T10:00+09:00,2026-10-31T10:59:59+09:00,2026-10-31T10:00:05+09:00,,samples.beatsPerMinute,72,,com.mi.health,Xiaomi Smart Band 9,2026-10-31T11:02+09:00
HeartRate,8a10...3c,2026-10-31T10:00+09:00,2026-10-31T10:59:59+09:00,2026-10-31T10:01:05+09:00,,samples.beatsPerMinute,75,,com.mi.health,Xiaomi Smart Band 9,2026-10-31T11:02+09:00
BloodPressure,c0d4...77,2026-10-31T07:15+09:00,,,,bodyPosition,2,,jp.co.omron.healthcare.omron_connect,OMRON HCR-7xxx,2026-10-31T07:16:40+09:00
BloodPressure,c0d4...77,2026-10-31T07:15+09:00,,,,diastolic,82,mmHg,jp.co.omron.healthcare.omron_connect,OMRON HCR-7xxx,2026-10-31T07:16:40+09:00
BloodPressure,c0d4...77,2026-10-31T07:15+09:00,,,,measurementLocation,3,,jp.co.omron.healthcare.omron_connect,OMRON HCR-7xxx,2026-10-31T07:16:40+09:00
BloodPressure,c0d4...77,2026-10-31T07:15+09:00,,,,systolic,128,mmHg,jp.co.omron.healthcare.omron_connect,OMRON HCR-7xxx,2026-10-31T07:16:40+09:00
SleepSession,51ab...09,2026-10-30T23:40+09:00,2026-10-31T06:50+09:00,2026-10-30T23:40+09:00,2026-10-31T00:10+09:00,stages.type,4,,com.mi.health,,2026-10-31T07:30+09:00
SleepSession,51ab...09,2026-10-30T23:40+09:00,2026-10-31T06:50+09:00,2026-10-31T00:10+09:00,2026-10-31T01:05+09:00,stages.type,5,,com.mi.health,,2026-10-31T07:30+09:00
Weight,77e2...b5,2026-10-31T07:20+09:00,,,,weight,64.35,kg,jp.co.omron.healthcare.omron_connect,,2026-10-31T07:21+09:00
IntermenstrualBleeding,9d3e...aa,2026-10-31T08:00+09:00,,,,,,,com.example.app,,2026-10-31T08:00+09:00
```

（`record_id` と機器名は例のため省略しています）

---

## 6. 取り込み処理の実装メモ（Java）

- 読み込みは RFC 4180 対応の CSV パーサー（Apache Commons CSV、OpenCSV など）を使ってください。`split(",")` だと、メモ欄（`notes`）などにカンマや改行が含まれたときに壊れます。
- 横持ちのテーブルにしたい場合は、`(record_type, record_id)` ごとにまとめ、`item_start` が空の行をレコードの属性、`item_start` がある行を明細テーブルの行として分けると扱いやすいです。
  - 例：`blood_pressure(record_id, measured_at, systolic, diastolic, body_position, measurement_location, source_app)`
  - 例：`heart_rate_sample(record_id, sampled_at, bpm, source_app)`
- 日別の集計（歩数の合計など）は、`source_app` ごとに分けて集計してください。複数のアプリが同じデータを書き込んでいると、そのまま合計すると二重になります。

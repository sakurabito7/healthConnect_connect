package dev.hcexport;

import android.content.Context;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.datatypes.Device;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.os.OutcomeReceiver;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Health Connect（OS組み込みのフレームワークAPI）から1日分の全健康データを読み、縦持ちCSVにする。
 *
 * レコードの各項目はリフレクションで getter を列挙して出力するため、
 * OSの更新で項目が増えてもコード変更なしで出力される。
 */
final class HealthCsv {

    /** 読み取り対象（健康データのみ。トレーニング計画など計画・設定系は除外）。存在しない型は自動でスキップ。 */
    private static final String[] RECORD_TYPES = {
            "ActiveCaloriesBurnedRecord", "ActivityIntensityRecord",
            "BasalBodyTemperatureRecord", "BasalMetabolicRateRecord", "BloodGlucoseRecord",
            "BloodPressureRecord", "BodyFatRecord", "BodyTemperatureRecord", "BodyWaterMassRecord",
            "BoneMassRecord", "CervicalMucusRecord", "CyclingPedalingCadenceRecord", "DistanceRecord",
            "ElevationGainedRecord", "ExerciseSessionRecord", "FloorsClimbedRecord", "HeartRateRecord",
            "HeartRateVariabilityRmssdRecord", "HeightRecord", "HydrationRecord",
            "IntermenstrualBleedingRecord", "LeanBodyMassRecord", "MenstruationFlowRecord",
            "MenstruationPeriodRecord", "MindfulnessSessionRecord", "NutritionRecord",
            "OvulationTestRecord", "OxygenSaturationRecord", "PowerRecord", "RespiratoryRateRecord",
            "RestingHeartRateRecord", "SexualActivityRecord", "SkinTemperatureRecord",
            "SleepSessionRecord", "SpeedRecord", "StepsCadenceRecord", "StepsRecord",
            "TotalCaloriesBurnedRecord", "Vo2MaxRecord", "WeightRecord", "WheelchairPushesRecord",
    };

    static final String HEADER = "record_type,record_id,record_start,record_end,item_start,item_end,"
            + "field,value,unit,source_app,device,last_modified\n";

    /** 時刻列として別扱いする getter */
    private static final List<String> TIME_GETTERS = Arrays.asList(
            "getTime", "getStartTime", "getEndTime",
            "getZoneOffset", "getStartZoneOffset", "getEndZoneOffset", "getMetadata", "getRecordType");

    private final HealthConnectManager hc;
    private final ZoneId zone = ZoneId.systemDefault();
    private final Executor direct = Runnable::run;

    /** 読み取り中にスキップした型（権限なし等）。ログ表示用 */
    final List<String> skipped = new ArrayList<>();

    HealthCsv(Context ctx) {
        hc = ctx.getSystemService(HealthConnectManager.class);
    }

    static String[] supportedRecordClasses() {
        return RECORD_TYPES;
    }

    /** 指定日（端末のタイムゾーンで0:00〜24:00）のCSVを返す。データ行数を rows[0] に入れる。 */
    String buildDay(LocalDate day, int[] rows) throws InterruptedException {
        Instant start = day.atStartOfDay(zone).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
        StringBuilder sb = new StringBuilder(64 * 1024).append(HEADER);
        int count = 0;
        skipped.clear();
        for (String name : RECORD_TYPES) {
            Class<?> cls;
            try {
                cls = Class.forName("android.health.connect.datatypes." + name);
            } catch (ClassNotFoundException e) {
                continue; // このOSバージョンに存在しない型
            }
            try {
                for (Record r : readAll(cls, start, end)) {
                    count += writeRecord(sb, name.substring(0, name.length() - "Record".length()), r);
                }
            } catch (RuntimeException e) { // 権限未許可(SecurityException)・HealthConnectException など
                skipped.add(name);
            }
        }
        rows[0] = count;
        return sb.toString();
    }

    // ---------------------------------------------------------------- 読み取り

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Record> readAll(Class<?> cls, Instant start, Instant end) throws InterruptedException {
        List<Record> out = new ArrayList<>();
        TimeInstantRangeFilter filter = new TimeInstantRangeFilter.Builder()
                .setStartTime(start).setEndTime(end).build();
        long pageToken = -1;
        do {
            ReadRecordsRequestUsingFilters.Builder b =
                    new ReadRecordsRequestUsingFilters.Builder((Class) cls)
                            .setTimeRangeFilter(filter)
                            .setPageSize(5000);
            if (pageToken != -1) b.setPageToken(pageToken);
            ReadRecordsResponse<Record> resp = readBlocking(b.build());
            out.addAll(resp.getRecords());
            pageToken = resp.getNextPageToken();
        } while (pageToken != -1);
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ReadRecordsResponse<Record> readBlocking(ReadRecordsRequestUsingFilters req)
            throws InterruptedException {
        final Object[] result = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        hc.readRecords(req, direct, new OutcomeReceiver<ReadRecordsResponse, HealthConnectException>() {
            @Override public void onResult(ReadRecordsResponse r) { result[0] = r; latch.countDown(); }
            @Override public void onError(HealthConnectException e) { result[0] = e; latch.countDown(); }
        });
        if (!latch.await(2, TimeUnit.MINUTES)) throw new IllegalStateException("Health Connect read timeout");
        if (result[0] instanceof HealthConnectException) throw (HealthConnectException) result[0];
        if (result[0] instanceof RuntimeException) throw (RuntimeException) result[0];
        return (ReadRecordsResponse<Record>) result[0];
    }

    // ---------------------------------------------------------------- CSV出力

    /** 1レコードの共通列（record_type〜record_end と source_app〜last_modified） */
    private static final class Ctx {
        String type, id, recStart, recEnd, source, device, modified;
    }

    private int writeRecord(StringBuilder sb, String type, Record r) {
        Ctx c = new Ctx();
        c.type = type;
        Metadata m = r.getMetadata();
        c.id = m.getId();
        c.source = m.getDataOrigin() != null ? m.getDataOrigin().getPackageName() : "";
        Device d = m.getDevice();
        c.device = d == null ? "" : join(d.getManufacturer(), d.getModel());
        c.modified = fmt(m.getLastModifiedTime());
        Object t = call(r, "getTime");
        if (t instanceof Instant) {
            c.recStart = fmt((Instant) t);
            c.recEnd = "";
        } else {
            c.recStart = fmt((Instant) call(r, "getStartTime"));
            c.recEnd = fmt((Instant) call(r, "getEndTime"));
        }
        int n = writeFields(sb, c, r, "", "", "", 0);
        if (n == 0) { // 項目を持たないレコード（例：特定の型の記録のみ）も1行は出す
            row(sb, c, "", "", "", "", "");
            n = 1;
        }
        return n;
    }

    private int writeFields(StringBuilder sb, Ctx c, Object obj, String prefix,
                            String itemStart, String itemEnd, int depth) {
        int n = 0;
        Method[] methods = obj.getClass().getMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method mt : methods) {
            String mn = mt.getName();
            if (mt.getParameterCount() != 0 || Modifier.isStatic(mt.getModifiers())
                    || mt.getDeclaringClass() == Object.class || TIME_GETTERS.contains(mn)) continue;
            String field;
            if (mn.startsWith("get") && mn.length() > 3) field = decap(mn.substring(3));
            else if (mn.startsWith("is") && mn.length() > 2) field = decap(mn.substring(2));
            else continue;
            Object v;
            try {
                v = mt.invoke(obj);
            } catch (Exception e) {
                continue;
            }
            if (v == null) continue;
            n += writeValue(sb, c, prefix + field, v, itemStart, itemEnd, depth);
        }
        return n;
    }

    private int writeValue(StringBuilder sb, Ctx c, String field, Object v,
                           String itemStart, String itemEnd, int depth) {
        if (v instanceof Collection) {
            int n = 0;
            for (Object e : (Collection<?>) v) {
                if (e == null) continue;
                // サンプル（心拍など）や区間（睡眠ステージなど）の時刻を item_start / item_end に入れる
                String s = itemStart, en = itemEnd;
                Object t = call(e, "getTime");
                if (t instanceof Instant) { s = fmt((Instant) t); en = ""; }
                Object st = call(e, "getStartTime");
                if (st instanceof Instant) { s = fmt((Instant) st); en = fmt((Instant) call(e, "getEndTime")); }
                if (isLeaf(e)) n += writeValue(sb, c, field, e, s, en, depth + 1);
                else if (depth < 3) n += writeFields(sb, c, e, field + ".", s, en, depth + 1);
            }
            return n;
        }
        String[] unit = toUnit(v);
        if (unit != null) {
            row(sb, c, itemStart, itemEnd, field, unit[0], unit[1]);
            return 1;
        }
        if (v instanceof Instant) {
            row(sb, c, itemStart, itemEnd, field, fmt((Instant) v), "");
            return 1;
        }
        if (v instanceof ZoneOffset) return 0;
        if (isLeaf(v)) {
            row(sb, c, itemStart, itemEnd, field, String.valueOf(v), "");
            return 1;
        }
        if (depth < 3 && v.getClass().getName().startsWith("android.health.connect")) {
            return writeFields(sb, c, v, field + ".", itemStart, itemEnd, depth + 1);
        }
        row(sb, c, itemStart, itemEnd, field, String.valueOf(v), "");
        return 1;
    }

    private static boolean isLeaf(Object v) {
        return v instanceof Number || v instanceof Boolean || v instanceof CharSequence
                || v instanceof Character || v instanceof Enum || toUnit(v) != null;
    }

    /** Health Connect の単位クラスを {値, 単位} に変換。対象外なら null */
    private static String[] toUnit(Object v) {
        switch (v.getClass().getSimpleName()) {
            case "Energy": return num(call(v, "getInCalories"), 0.001, "kcal");
            case "Length": return num(call(v, "getInMeters"), 1, "m");
            case "Mass": return num(call(v, "getInGrams"), 0.001, "kg");
            case "Pressure": return num(call(v, "getInMillimetersOfMercury"), 1, "mmHg");
            case "Temperature":
            case "TemperatureDelta": return num(call(v, "getInCelsius"), 1, "degC");
            case "Velocity": return num(call(v, "getInMetersPerSecond"), 1, "m/s");
            case "Volume": return num(call(v, "getInLiters"), 1, "L");
            case "Power": return num(call(v, "getInWatts"), 1, "W");
            case "Percentage": return num(call(v, "getValue"), 1, "%");
            case "BloodGlucose": return num(call(v, "getInMillimolesPerLiter"), 1, "mmol/L");
            default: return null;
        }
    }

    private static String[] num(Object raw, double factor, String unit) {
        if (!(raw instanceof Number)) return null;
        double d = ((Number) raw).doubleValue() * factor;
        String s = (d == Math.rint(d) && Math.abs(d) < 1e15) ? Long.toString((long) d) : Double.toString(d);
        return new String[]{s, unit};
    }

    private static Object call(Object o, String name) {
        try {
            return o.getClass().getMethod(name).invoke(o);
        } catch (Exception e) {
            return null;
        }
    }

    private void row(StringBuilder sb, Ctx c, String is, String ie, String field, String value, String unit) {
        csv(sb, c.type).append(',');
        csv(sb, c.id).append(',');
        csv(sb, c.recStart).append(',');
        csv(sb, c.recEnd).append(',');
        csv(sb, is).append(',');
        csv(sb, ie).append(',');
        csv(sb, field).append(',');
        csv(sb, value).append(',');
        csv(sb, unit).append(',');
        csv(sb, c.source).append(',');
        csv(sb, c.device).append(',');
        csv(sb, c.modified).append('\n');
    }

    private static StringBuilder csv(StringBuilder sb, String s) {
        if (s == null) return sb;
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return sb.append(s);
        }
        return sb.append('"').append(s.replace("\"", "\"\"")).append('"');
    }

    private String fmt(Instant i) {
        return i == null ? "" : OffsetDateTime.ofInstant(i, zone).toString();
    }

    private static String decap(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String join(String a, String b) {
        a = a == null ? "" : a;
        b = b == null ? "" : b;
        return (a + " " + b).trim();
    }
}

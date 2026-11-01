package dev.hcexport;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** 日付ごとにCSVを作ってDriveへアップロードする。 */
final class Exporter {

    /** 遅れて同期されたデータを拾うため、毎回この日数ぶん遡って上書きする */
    static final int REFRESH_DAYS = 2;
    /** 未送信日の自動補完の上限 */
    static final int MAX_BACKFILL_DAYS = 30;

    /** 定期実行用：前回成功日の翌日〜昨日（最低でも直近 REFRESH_DAYS 日）を書き出す */
    static void runScheduled(Context ctx) throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate from = yesterday.minusDays(REFRESH_DAYS - 1);
        String last = Prefs.get(ctx).getString(Prefs.LAST_DAY, null);
        if (last != null) {
            LocalDate next = LocalDate.parse(last).plusDays(1);
            if (next.isBefore(from)) from = next;
        }
        LocalDate min = yesterday.minusDays(MAX_BACKFILL_DAYS - 1);
        if (from.isBefore(min)) from = min;
        run(ctx, from, yesterday);
    }

    /** from〜to（両端含む）を1日1ファイルで書き出す */
    static void run(Context ctx, LocalDate from, LocalDate to) throws Exception {
        HealthCsv hc = new HealthCsv(ctx);
        Drive drive = new Drive(ctx);
        SharedPreferences p = Prefs.get(ctx);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            int[] rows = new int[1];
            String csv = hc.buildDay(d, rows);
            String name = "health_" + d + ".csv";
            drive.upload(name, csv.getBytes(StandardCharsets.UTF_8));
            String last = p.getString(Prefs.LAST_DAY, null);
            if (last == null || LocalDate.parse(last).isBefore(d)) {
                p.edit().putString(Prefs.LAST_DAY, d.toString()).apply();
            }
            Prefs.log(ctx, name + " " + rows[0] + "行"
                    + (hc.skipped.isEmpty() ? "" : "（未許可: " + hc.skipped.size() + "種）"));
        }
    }

    private Exporter() {}
}

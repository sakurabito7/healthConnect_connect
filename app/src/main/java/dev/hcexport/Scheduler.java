package dev.hcexport;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 毎日、設定時刻（既定 4:00）にアラーム → ネットワーク接続を条件にジョブ実行。
 * WorkManager を使わずフレームワークの AlarmManager + JobScheduler だけで実装している。
 *
 * <p>取りこぼし対策として二重化している:
 * <ul>
 *   <li>アラーム: Doze 中でも発火する *AllowWhileIdle 系（正確アラームが使えれば正確に）</li>
 *   <li>保険の定期ジョブ: 数時間おきに起き、設定時刻を過ぎても昨日分が未送信なら書き出す</li>
 * </ul>
 */
final class Scheduler {
    /** アラームから投入する1回限りのジョブ */
    static final int JOB_ID = 1;
    /** 保険の定期ジョブ */
    static final int CATCHUP_JOB_ID = 2;
    static final String EXTRA_CATCHUP = "catchup";
    private static final long CATCHUP_INTERVAL_MS = 4 * 60 * 60 * 1000L;
    private static final long CATCHUP_FLEX_MS = 60 * 60 * 1000L;

    /** 次回のアラームを張り直す（同じ PendingIntent なので前のアラームは置き換わる）。保険ジョブも登録する。 */
    static void armAlarm(Context ctx) {
        ZoneId z = ZoneId.systemDefault();
        ZonedDateTime next = ZonedDateTime.of(LocalDate.now(z), Prefs.runTime(ctx), z);
        if (!next.isAfter(ZonedDateTime.now(z))) next = next.plusDays(1);
        long at = next.toInstant().toEpochMilli();
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        boolean exact = am.canScheduleExactAlarms();
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx));
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx));
        }
        Prefs.get(ctx).edit()
                .putString(Prefs.NEXT_ALARM, next.toLocalDateTime().toString().replace('T', ' ')
                        + (exact ? "" : "（非正確）"))
                .apply();
        ensureCatchupJob(ctx);
    }

    static void enqueueJob(Context ctx) {
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, ExportJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(15 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build();
        int r = ctx.getSystemService(JobScheduler.class).schedule(job);
        if (r != JobScheduler.RESULT_SUCCESS) Prefs.log(ctx, "[自動] ジョブ登録に失敗しました");
    }

    /** 保険の定期ジョブ。登録済みなら何もしない（再登録すると周期がリセットされるため）。 */
    static void ensureCatchupJob(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js.getPendingJob(CATCHUP_JOB_ID) != null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(EXTRA_CATCHUP, true);
        JobInfo job = new JobInfo.Builder(CATCHUP_JOB_ID, new ComponentName(ctx, ExportJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(CATCHUP_INTERVAL_MS, CATCHUP_FLEX_MS)
                .setPersisted(true)
                .setExtras(extras)
                .build();
        js.schedule(job);
    }

    /** 設定時刻を過ぎているのに昨日分が未送信か（＝定時実行を取りこぼした） */
    static boolean isOverdue(Context ctx) {
        ZoneId z = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(z);
        if (now.toLocalTime().isBefore(Prefs.runTime(ctx))) return false;
        String last = Prefs.get(ctx).getString(Prefs.LAST_DAY, null);
        LocalDate yesterday = now.toLocalDate().minusDays(1);
        return last == null || LocalDate.parse(last).isBefore(yesterday);
    }

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(AlarmReceiver.ACTION_ALARM);
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Scheduler() {}
}

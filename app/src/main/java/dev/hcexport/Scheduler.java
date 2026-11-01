package dev.hcexport;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 毎朝4:00〜4:30の間にアラーム（省電力でも動く非正確アラーム）→ ネットワーク接続を条件にジョブ実行。
 * WorkManager を使わずフレームワークの AlarmManager + JobScheduler だけで実装している。
 */
final class Scheduler {
    static final int JOB_ID = 1;
    static final LocalTime RUN_AT = LocalTime.of(4, 0);
    private static final long WINDOW_MS = 30 * 60 * 1000L;

    static void armAlarm(Context ctx) {
        ZoneId z = ZoneId.systemDefault();
        ZonedDateTime next = ZonedDateTime.of(LocalDate.now(z), RUN_AT, z);
        if (!next.isAfter(ZonedDateTime.now(z))) next = next.plusDays(1);
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        am.setWindow(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), WINDOW_MS, alarmIntent(ctx));
    }

    static void enqueueJob(Context ctx) {
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, ExportJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(15 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build();
        ctx.getSystemService(JobScheduler.class).schedule(job);
    }

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(AlarmReceiver.ACTION_ALARM);
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Scheduler() {}
}

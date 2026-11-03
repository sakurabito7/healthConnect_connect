package dev.hcexport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 毎朝のアラーム、および再起動・アプリ更新時にアラームを張り直す */
public final class AlarmReceiver extends BroadcastReceiver {
    static final String ACTION_ALARM = "dev.hcexport.ALARM";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (ACTION_ALARM.equals(action)) {
            Prefs.log(ctx, "[自動] アラーム受信");
            Scheduler.enqueueJob(ctx);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // 電源オフ中などで定時を逃していたら、すぐ取り戻す
            if (Scheduler.isOverdue(ctx)) {
                Prefs.log(ctx, "[自動] 未送信分があるため実行を予約");
                Scheduler.enqueueJob(ctx);
            }
        }
        Scheduler.armAlarm(ctx);
    }
}

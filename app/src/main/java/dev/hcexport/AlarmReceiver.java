package dev.hcexport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 毎朝のアラーム、および再起動・アプリ更新時にアラームを張り直す */
public final class AlarmReceiver extends BroadcastReceiver {
    static final String ACTION_ALARM = "dev.hcexport.ALARM";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION_ALARM.equals(intent.getAction())) Scheduler.enqueueJob(ctx);
        Scheduler.armAlarm(ctx);
    }
}

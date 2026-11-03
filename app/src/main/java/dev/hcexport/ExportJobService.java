package dev.hcexport;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class ExportJobService extends JobService {
    private volatile Thread worker;

    @Override
    public boolean onStartJob(JobParameters params) {
        boolean catchup = params.getExtras().getBoolean(Scheduler.EXTRA_CATCHUP, false);
        if (catchup && (!Scheduler.isOverdue(this)
                || getSystemService(android.app.job.JobScheduler.class).getPendingJob(Scheduler.JOB_ID) != null)) {
            return false; // 定時実行で送信済み、または定時実行のジョブが待機・実行中
        }
        Prefs.log(this, catchup ? "[自動] 取りこぼし分の書き出し開始" : "[自動] 書き出し開始");
        worker = new Thread(() -> {
            boolean retry = false;
            try {
                Exporter.runScheduled(this, "[自動] ");
                Prefs.log(this, "[自動] 完了");
            } catch (Drive.NeedsConsentException e) {
                Prefs.log(this, "[自動] 失敗: アプリを開いてGoogleアカウントを再設定してください");
            } catch (InterruptedException e) {
                return; // onStopJob 側でログ済み・再実行される
            } catch (Throwable e) {
                Prefs.log(this, "[自動] 失敗(再試行します): " + e);
                // 定期ジョブは次の周期で再判定するので再試行不要
                retry = !catchup;
            }
            jobFinished(params, retry);
        }, "export");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Prefs.log(this, "[自動] OSにより中断（理由コード " + params.getStopReason() + "）。後で再実行します");
        Thread t = worker;
        if (t != null) t.interrupt();
        return true; // 中断されたら後で再実行
    }
}

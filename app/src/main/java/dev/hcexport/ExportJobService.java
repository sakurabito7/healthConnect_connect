package dev.hcexport;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class ExportJobService extends JobService {
    private volatile Thread worker;

    @Override
    public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            boolean retry = false;
            try {
                Exporter.runScheduled(this);
            } catch (Drive.NeedsConsentException e) {
                Prefs.log(this, "失敗: アプリを開いてGoogleアカウントを再設定してください");
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Prefs.log(this, "失敗(再試行します): " + e.getMessage());
                retry = true;
            }
            jobFinished(params, retry);
        }, "export");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread t = worker;
        if (t != null) t.interrupt();
        return true; // 中断されたら後で再実行
    }
}

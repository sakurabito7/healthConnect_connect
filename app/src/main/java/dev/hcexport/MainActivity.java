package dev.hcexport;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.app.usage.UsageStatsManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** 設定と手動実行だけの最小画面（レイアウトXML・AppCompat 不使用） */
public final class MainActivity extends Activity {
    private static final int REQ_HEALTH = 1;
    private static final int REQ_ACCOUNT = 2;

    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        if ("android.intent.action.VIEW_PERMISSION_USAGE".equals(getIntent().getAction())) {
            TextView t = new TextView(this);
            t.setText("このアプリは Health Connect の健康データを読み取り、CSVにしてあなた自身の"
                    + " Google ドライブ（" + Prefs.folderPath(this) + " フォルダ）にのみ保存します。"
                    + "第三者への送信は行いません。");
            root.addView(t);
            setContentView(root);
            applyInsets(root);
            return;
        }

        TextView title = new TextView(this);
        title.setText("HC Export");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, pad);
        root.addView(title);
        root.addView(button("1. Health Connect の読み取りを許可", v -> requestHealth()));
        root.addView(button("2. Google アカウントを選択（ドライブ）", v -> chooseAccount()));
        root.addView(button("自動実行の時刻を変更", v -> editRunTime()));
        root.addView(button("保存先フォルダを変更", v -> editFolder()));
        root.addView(button("ファイル名を変更", v -> editFilePattern()));
        root.addView(button("自動実行を安定させる（バッテリー最適化の対象外に）", v -> requestIgnoreBatteryOpt()));
        root.addView(button("今すぐ実行（未送信日〜昨日）", v -> runNow(false)));
        root.addView(button("過去30日分をまとめて書き出す", v -> runNow(true)));
        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setPadding(0, pad, 0, 0);
        root.addView(status);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        applyInsets(sv);
        Scheduler.armAlarm(this);
    }

    /**
     * targetSdk 35 以降は全画面(edge-to-edge)表示が強制され、タイトルバーやステータスバーの下に
     * 内容が潜り込むため、タイトルバーを消してシステムバーの分だけ余白を取る。
     */
    private void applyInsets(View content) {
        if (getActionBar() != null) getActionBar().hide();
        final int l = content.getPaddingLeft(), t = content.getPaddingTop(),
                r = content.getPaddingRight(), b = content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(l + i.left, t + i.top, r + i.right, b + i.bottom);
            return insets;
        });
        content.requestApplyInsets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    // ------------------------------------------------------------ Health Connect

    private List<String> healthPermissions() {
        List<String> out = new ArrayList<>();
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(),
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS));
            if (pi.requestedPermissions != null) {
                for (String p : pi.requestedPermissions) {
                    if (p.startsWith("android.permission.health.")) out.add(p);
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return out;
    }

    private void requestHealth() {
        List<String> missing = new ArrayList<>();
        for (String p : healthPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            toastStatus("Health Connect の権限はすべて許可済みです");
            return;
        }
        requestPermissions(missing.toArray(new String[0]), REQ_HEALTH);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refresh();
    }

    // ------------------------------------------------------------ Google アカウント

    private void chooseAccount() {
        Intent i = AccountManager.newChooseAccountIntent(null, null, new String[]{"com.google"},
                null, null, null, null);
        startActivityForResult(i, REQ_ACCOUNT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_ACCOUNT || res != RESULT_OK || data == null) return;
        String name = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
        if (name == null) return;
        Prefs.get(this).edit().putString(Prefs.ACCOUNT, name).remove(Prefs.FOLDER_ID).apply();
        // Activity を渡すと、必要なら同意画面を自動で表示してくれる
        AccountManager.get(this).getAuthToken(new Account(name, "com.google"), Drive.SCOPE, null, this,
                f -> {
                    try {
                        f.getResult();
                        toastStatus("ドライブへのアクセスを許可しました: " + name);
                    } catch (Exception e) {
                        toastStatus("ドライブの許可に失敗: " + e);
                    }
                }, null);
    }

    // ------------------------------------------------------------ 設定

    private void editRunTime() {
        LocalTime t = Prefs.runTime(this);
        new TimePickerDialog(this, (v, h, m) -> {
            Prefs.get(this).edit().putInt(Prefs.RUN_MINUTES, h * 60 + m).apply();
            Scheduler.armAlarm(this);
            toastStatus("自動実行の時刻を " + LocalTime.of(h, m) + " に変更しました");
        }, t.getHour(), t.getMinute(), true).show();
    }

    private void editFolder() {
        textDialog("保存先フォルダ",
                "マイドライブからのパス。「/」で区切ると階層を作れます（例 健康/HealthConnect）。\n"
                        + "※ このアプリが作ったフォルダにしか書き込めないため、既存の同名フォルダとは別に新しく作られます。",
                Prefs.folderPath(this), Prefs.DEFAULT_FOLDER_PATH, InputType.TYPE_CLASS_TEXT,
                s -> {
                    String err = Prefs.validateFolderPath(s);
                    if (err != null) return err;
                    String path = String.join("/", Prefs.splitFolderPath(s));
                    Prefs.get(this).edit().putString(Prefs.FOLDER_PATH, path).apply();
                    toastStatus("保存先を マイドライブ/" + path + " に変更しました（次回の書き出しから）");
                    return null;
                });
    }

    private void editFilePattern() {
        textDialog("ファイル名",
                "{date} が日付（例 2026-10-31）に置き換わります。\n"
                        + "{date:yyyyMMdd} のように書式も指定できます（Java の DateTimeFormatter 形式）。",
                Prefs.filePattern(this), Prefs.DEFAULT_FILE_PATTERN, InputType.TYPE_CLASS_TEXT,
                s -> {
                    String err = Prefs.validateFilePattern(s);
                    if (err != null) return err;
                    String pat = s.trim();
                    Prefs.get(this).edit().putString(Prefs.FILE_PATTERN, pat).apply();
                    toastStatus("ファイル名を変更しました（例: "
                            + Prefs.fileName(pat, LocalDate.now().minusDays(1)) + "）");
                    return null;
                });
    }

    /** 入力値を検証して保存する処理。エラーなら文言を返す（ダイアログは閉じない） */
    private interface Saver {
        String save(String value);
    }

    private void textDialog(String title, String help, String current, String def, int inputType, Saver saver) {
        EditText et = new EditText(this);
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setText(current);
        et.setSelection(current.length());
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(help)
                .setView(et)
                .setPositiveButton("保存", null)
                .setNeutralButton("既定に戻す", null)
                .setNegativeButton("キャンセル", null)
                .create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String err = saver.save(et.getText().toString());
                if (err == null) d.dismiss();
                else et.setError(err);
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                et.setText(def);
                et.setSelection(def.length());
            });
        });
        d.show();
    }

    // ------------------------------------------------------------ 自動実行の安定化

    private boolean ignoringBatteryOpt() {
        return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBatteryOpt() {
        if (ignoringBatteryOpt()) {
            toastStatus("すでにバッテリー最適化の対象外です");
            return;
        }
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
    }

    /** アプリ待機バケット。RARE / RESTRICTED だとジョブやアラームが大きく制限される */
    private String standbyBucket() {
        int b = getSystemService(UsageStatsManager.class).getAppStandbyBucket();
        switch (b) {
            case UsageStatsManager.STANDBY_BUCKET_ACTIVE: return "ACTIVE";
            case UsageStatsManager.STANDBY_BUCKET_WORKING_SET: return "WORKING_SET";
            case UsageStatsManager.STANDBY_BUCKET_FREQUENT: return "FREQUENT";
            case UsageStatsManager.STANDBY_BUCKET_RARE: return "RARE（制限あり）";
            case UsageStatsManager.STANDBY_BUCKET_RESTRICTED: return "RESTRICTED（強く制限）";
            default: return String.valueOf(b);
        }
    }

    // ------------------------------------------------------------ 実行

    private void runNow(boolean last30) {
        toastStatus("実行中…");
        new Thread(() -> {
            String msg;
            try {
                if (last30) {
                    LocalDate y = LocalDate.now().minusDays(1);
                    Exporter.run(this, y.minusDays(Exporter.MAX_BACKFILL_DAYS - 1), y);
                } else {
                    Exporter.runScheduled(this);
                }
                msg = "完了しました";
            } catch (Drive.NeedsConsentException e) {
                runOnUiThread(() -> startActivity(e.intent));
                msg = "Google の許可画面で許可してから、もう一度実行してください";
            } catch (Exception e) {
                msg = "失敗: " + e;
                Prefs.log(this, msg);
            }
            String m = msg;
            runOnUiThread(() -> toastStatus(m));
        }, "manual").start();
    }

    private void toastStatus(String msg) {
        refresh();
        status.setText(msg + "\n\n" + status.getText());
    }

    private void refresh() {
        if (status == null) return;
        int granted = 0;
        List<String> all = healthPermissions();
        for (String p : all) {
            if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) granted++;
        }
        android.content.SharedPreferences p = Prefs.get(this);
        status.setText("Health Connect 権限: " + granted + " / " + all.size()
                + "（端末が対応していない権限は許可されません）\n"
                + "Google アカウント: " + p.getString(Prefs.ACCOUNT, "未設定") + "\n"
                + "保存先: マイドライブ/" + Prefs.folderPath(this) + "\n"
                + "ファイル名: " + Prefs.fileName(Prefs.filePattern(this), LocalDate.now().minusDays(1)) + "\n"
                + "自動実行: 毎日 " + Prefs.runTime(this) + "（次回 " + p.getString(Prefs.NEXT_ALARM, "未設定") + "）\n"
                + "バッテリー最適化: " + (ignoringBatteryOpt() ? "対象外（OK）" : "対象（自動実行が遅れることがあります）") + "\n"
                + "待機バケット: " + standbyBucket() + "\n"
                + "最終送信日: " + p.getString(Prefs.LAST_DAY, "なし") + "\n\n"
                + "ログ:\n" + p.getString(Prefs.LOG, ""));
    }
}

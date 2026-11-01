package dev.hcexport;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
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
                    + " Google ドライブ（" + Drive.FOLDER_NAME + " フォルダ）にのみ保存します。"
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
                + "保存先: マイドライブ/" + Drive.FOLDER_NAME + "\n"
                + "自動実行: 毎朝 " + Scheduler.RUN_AT + " 頃\n"
                + "最終送信日: " + p.getString(Prefs.LAST_DAY, "なし") + "\n\n"
                + "ログ:\n" + p.getString(Prefs.LOG, ""));
    }
}

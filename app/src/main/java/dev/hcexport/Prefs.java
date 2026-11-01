package dev.hcexport;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String ACCOUNT = "account";
    static final String FOLDER_ID = "folder_id";
    /** 最後にアップロードが成功した日付（yyyy-MM-dd） */
    static final String LAST_DAY = "last_day";
    static final String LOG = "log";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("hcexport", Context.MODE_PRIVATE);
    }

    /** 直近のログを最大30行保持 */
    static synchronized void log(Context c, String line) {
        SharedPreferences p = get(c);
        String now = java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ');
        String all = now + "  " + line + "\n" + p.getString(LOG, "");
        String[] lines = all.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(30, lines.length); i++) sb.append(lines[i]).append('\n');
        p.edit().putString(LOG, sb.toString()).apply();
    }

    private Prefs() {}
}

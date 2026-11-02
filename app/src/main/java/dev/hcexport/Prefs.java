package dev.hcexport;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Prefs {
    static final String ACCOUNT = "account";
    static final String FOLDER_ID = "folder_id";
    /** FOLDER_ID がどのフォルダパスに対するものか（パス変更時にキャッシュを捨てるため） */
    static final String FOLDER_ID_PATH = "folder_id_path";
    /** 自動実行時刻（0:00 からの分） */
    static final String RUN_MINUTES = "run_minutes";
    /** 保存先フォルダ（マイドライブからの相対パス。"/" 区切りで階層指定可） */
    static final String FOLDER_PATH = "folder_path";
    /** ファイル名パターン。{date} または {date:書式} を日付に置換する */
    static final String FILE_PATTERN = "file_pattern";

    static final int DEFAULT_RUN_MINUTES = 4 * 60;
    static final String DEFAULT_FOLDER_PATH = "HealthConnectExport";
    static final String DEFAULT_FILE_PATTERN = "health_{date}.csv";

    private static final Pattern DATE_TOKEN = Pattern.compile("\\{date(?::([^}]+))?\\}");
    /** Drive では使えるが PC（Windows）の同期先で問題になる文字 */
    private static final String BAD_CHARS = "\\/:*?\"<>|";
    /** 最後にアップロードが成功した日付（yyyy-MM-dd） */
    static final String LAST_DAY = "last_day";
    static final String LOG = "log";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("hcexport", Context.MODE_PRIVATE);
    }

    static LocalTime runTime(Context c) {
        int m = get(c).getInt(RUN_MINUTES, DEFAULT_RUN_MINUTES);
        return LocalTime.of(m / 60, m % 60);
    }

    static String folderPath(Context c) {
        return get(c).getString(FOLDER_PATH, DEFAULT_FOLDER_PATH);
    }

    static String filePattern(Context c) {
        return get(c).getString(FILE_PATTERN, DEFAULT_FILE_PATTERN);
    }

    /** "a / b/c/" → [a, b, c]。空要素は捨てる */
    static List<String> splitFolderPath(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** フォルダパスの検証。問題なければ null、あればエラーメッセージ */
    static String validateFolderPath(String path) {
        List<String> parts = splitFolderPath(path);
        if (parts.isEmpty()) return "フォルダ名を入力してください";
        for (String s : parts) {
            if (containsBad(s, "/")) return "フォルダ名に使えない文字があります: " + BAD_CHARS.replace("/", "");
            if (s.equals(".") || s.equals("..")) return "「.」「..」はフォルダ名に使えません";
        }
        return null;
    }

    /** ファイル名パターンの検証。問題なければ null、あればエラーメッセージ */
    static String validateFilePattern(String pattern) {
        pattern = pattern.trim();
        Matcher m = DATE_TOKEN.matcher(pattern);
        if (!m.find()) return "日付ごとに別ファイルにするため {date} を含めてください";
        String sample;
        try {
            sample = fileName(pattern, LocalDate.of(2026, 1, 2));
        } catch (RuntimeException e) { // IllegalArgumentException / DateTimeException
            return "日付の書式が不正です: " + e.getMessage();
        }
        if (sample.equals(fileName(pattern, LocalDate.of(2026, 1, 3))))
            return "日付の書式に年月日を含めてください（例 {date:yyyyMMdd}）";
        if (containsBad(sample, "")) return "ファイル名に使えない文字があります: " + BAD_CHARS;
        return null;
    }

    /** パターンの {date} / {date:書式} を置換したファイル名 */
    static String fileName(String pattern, LocalDate d) {
        Matcher m = DATE_TOKEN.matcher(pattern.trim());
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String fmt = m.group(1);
            String v = fmt == null ? d.toString() : d.format(DateTimeFormatter.ofPattern(fmt));
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean containsBad(String s, String allowed) {
        for (char ch : s.toCharArray()) {
            if (ch < 0x20) return true;
            if (BAD_CHARS.indexOf(ch) >= 0 && allowed.indexOf(ch) < 0) return true;
        }
        return false;
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

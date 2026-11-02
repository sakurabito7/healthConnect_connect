package dev.hcexport;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Google Drive REST v3 を HttpURLConnection で直接叩く最小クライアント。
 * 認証は端末の AccountManager（Googleアカウント）から OAuth トークンを取得する。
 * スコープ drive.file：このアプリが作ったファイル／フォルダだけにアクセスできる。
 */
final class Drive {

    static final String SCOPE = "oauth2:https://www.googleapis.com/auth/drive.file";
    private static final String API = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";

    /** 同意画面が必要なときに投げる。intent を Activity から起動すると同意画面が出る */
    static final class NeedsConsentException extends Exception {
        final Intent intent;
        NeedsConsentException(Intent i) { super("Googleアカウントの許可が必要です"); intent = i; }
    }

    static final class HttpException extends IOException {
        final int code;
        HttpException(int code, String body) { super("HTTP " + code + ": " + body); this.code = code; }
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Account account;
    private String token;

    Drive(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        prefs = Prefs.get(ctx);
        String name = prefs.getString(Prefs.ACCOUNT, null);
        account = name == null ? null : new Account(name, "com.google");
    }

    boolean hasAccount() {
        return account != null;
    }

    /** バックグラウンドスレッドから呼ぶこと */
    String fetchToken() throws Exception {
        if (account == null) throw new IllegalStateException("Googleアカウントが未設定です");
        Bundle b = AccountManager.get(ctx)
                .getAuthToken(account, SCOPE, null, true, null, null)
                .getResult();
        Intent i = b.getParcelable(AccountManager.KEY_INTENT, Intent.class);
        if (i != null) throw new NeedsConsentException(i);
        token = b.getString(AccountManager.KEY_AUTHTOKEN);
        if (token == null) throw new IllegalStateException("トークンを取得できませんでした");
        return token;
    }

    /** CSVをフォルダにアップロード。同名ファイルがあれば中身を上書きする。 */
    void upload(String fileName, byte[] data) throws Exception {
        try {
            uploadOnce(fileName, data);
        } catch (HttpException e) {
            if (e.code != 401) throw e;
            // トークン期限切れ：破棄して取り直し、1回だけ再試行
            AccountManager.get(ctx).invalidateAuthToken("com.google", token);
            token = null;
            uploadOnce(fileName, data);
        }
    }

    private void uploadOnce(String fileName, byte[] data) throws Exception {
        if (token == null) fetchToken();
        String folder = folderId();
        String existing = findId("name='" + esc(fileName) + "' and '" + folder + "' in parents and trashed=false");
        if (existing != null) {
            request("PATCH", UPLOAD + "/" + existing + "?uploadType=media", "text/csv", data);
            return;
        }
        String boundary = "hcexport" + System.nanoTime();
        JSONObject meta = new JSONObject()
                .put("name", fileName)
                .put("mimeType", "text/csv")
                .put("parents", new JSONArray().put(folder));
        ByteArrayOutputStream body = new ByteArrayOutputStream(data.length + 512);
        body.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + meta + "\r\n--" + boundary + "\r\nContent-Type: text/csv\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        request("POST", UPLOAD + "?uploadType=multipart&fields=id",
                "multipart/related; boundary=" + boundary, body.toByteArray());
    }

    private String folderId() throws Exception {
        String path = String.join("/", Prefs.splitFolderPath(Prefs.folderPath(ctx)));
        if (path.isEmpty()) path = Prefs.DEFAULT_FOLDER_PATH;
        String id = path.equals(prefs.getString(Prefs.FOLDER_ID_PATH, Prefs.DEFAULT_FOLDER_PATH))
                ? prefs.getString(Prefs.FOLDER_ID, null) : null;
        if (id != null) {
            try {
                JSONObject f = new JSONObject(request("GET", API + "/" + id + "?fields=id,trashed", null, null));
                if (!f.optBoolean("trashed")) return id;
            } catch (HttpException e) {
                if (e.code != 404) throw e;
            }
        }
        // マイドライブ直下から1階層ずつ探し、無ければ作る
        String parent = "root";
        for (String name : path.split("/")) {
            parent = childFolder(parent, name);
        }
        prefs.edit().putString(Prefs.FOLDER_ID, parent).putString(Prefs.FOLDER_ID_PATH, path).apply();
        return parent;
    }

    private String childFolder(String parent, String name) throws Exception {
        String id = findId("name='" + esc(name) + "' and '" + parent + "' in parents"
                + " and mimeType='application/vnd.google-apps.folder' and trashed=false");
        if (id != null) return id;
        JSONObject meta = new JSONObject()
                .put("name", name)
                .put("mimeType", "application/vnd.google-apps.folder")
                .put("parents", new JSONArray().put(parent));
        return new JSONObject(request("POST", API + "?fields=id", "application/json; charset=UTF-8",
                meta.toString().getBytes(StandardCharsets.UTF_8))).getString("id");
    }

    private String findId(String q) throws Exception {
        String url = API + "?spaces=drive&fields=files(id)&pageSize=1&q=" + URLEncoder.encode(q, "UTF-8");
        JSONArray files = new JSONObject(request("GET", url, null, null)).getJSONArray("files");
        return files.length() == 0 ? null : files.getJSONObject(0).getString("id");
    }

    private String request(String method, String url, String contentType, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(30_000);
            c.setReadTimeout(60_000);
            if ("PATCH".equals(method)) {
                // HttpURLConnection は PATCH 非対応のため Drive の上書き指定を使う
                c.setRequestMethod("POST");
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            } else {
                c.setRequestMethod(method);
            }
            c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }
            int code = c.getResponseCode();
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = is == null ? "" : read(is);
            if (code >= 400) throw new HttpException(code, text);
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream is) throws IOException {
        try (InputStream in = is) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString(StandardCharsets.UTF_8);
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}

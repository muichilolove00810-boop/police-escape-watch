package com.grara.policeescape;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GitHub Releases 의 최신 버전을 확인하고, 새 버전이 있으면 APK 를 받아 설치한다.
 * - version.json : {"versionCode":2,"versionName":"1.1"}
 * - police-escape.apk
 */
public class Updater {
    private static final String BASE =
            "https://github.com/muichilolove00810-boop/police-escape-watch/releases/latest/download/";
    private static final long CHECK_INTERVAL_MS = 30 * 60 * 1000L;

    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File apkFile;
    private long lastCheck = -CHECK_INTERVAL_MS;
    private boolean busy;
    private boolean readyToInstall;
    private boolean askedPermission;
    private volatile String status = "";

    public Updater(Activity act) {
        this.act = act;
        this.apkFile = new File(act.getCacheDir(), "update.apk");
    }

    /** 화면에 보여줄 상태 문구 (없으면 빈 문자열). */
    public String status() {
        return status;
    }

    public boolean isReady() {
        return readyToInstall;
    }

    public static int currentVersionCode(Context c) {
        try {
            return (int) c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentVersionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 30분에 한 번만 실제로 확인한다. */
    public void checkIfDue() {
        long now = SystemClock.elapsedRealtime();
        if (busy || readyToInstall || now - lastCheck < CHECK_INTERVAL_MS) return;
        lastCheck = now;
        busy = true;
        new Thread(this::checkAndDownload, "updater").start();
    }

    private void checkAndDownload() {
        try {
            JSONObject v = new JSONObject(new String(get(BASE + "version.json"), "UTF-8"));
            int remote = v.getInt("versionCode");
            String name = v.optString("versionName", String.valueOf(remote));
            if (remote <= currentVersionCode(act)) {
                status = "";
                return;
            }
            status = "업데이트 v" + name + " 받는 중…";
            byte[] apk = get(BASE + "police-escape.apk");
            try (FileOutputStream out = new FileOutputStream(apkFile)) {
                out.write(apk);
            }
            // 받은 APK 가 진짜 이 앱의 새 버전인지 확인
            PackageInfo info = act.getPackageManager().getPackageArchiveInfo(apkFile.getPath(), 0);
            if (info == null || !act.getPackageName().equals(info.packageName)
                    || info.getLongVersionCode() <= currentVersionCode(act)) {
                status = "";
                apkFile.delete();
                return;
            }
            status = "새 버전 v" + name + " 준비됨";
            readyToInstall = true;
        } catch (Exception e) {
            status = "";
        } finally {
            busy = false;
        }
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** 게임 중이 아닐 때 호출. 준비된 업데이트가 있으면 설치를 시작한다. */
    public void installIfReady() {
        if (!readyToInstall) return;
        if (!act.getPackageManager().canRequestPackageInstalls() && !askedPermission) {
            askedPermission = true;
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
                status = "설치 허용 후 돌아오세요";
                return; // 돌아오면 onResume 에서 다시 시도
            } catch (ActivityNotFoundException ignored) {
                // 설정 화면이 없는 워치면 바로 설치 요청으로 진행
            }
        }
        readyToInstall = false;
        status = "설치 중…";
        new Thread(this::commitInstall, "installer").start();
    }

    private void commitInstall() {
        try {
            PackageInstaller pi = act.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(act.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (OutputStream out = s.openWrite("app.apk", 0, apkFile.length());
                     InputStream in = new FileInputStream(apkFile)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent cb = new Intent(act, InstallReceiver.class);
                PendingIntent p = PendingIntent.getBroadcast(act, id, cb,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                s.commit(p.getIntentSender());
            }
        } catch (Exception e) {
            status = "업데이트 실패";
            main.postDelayed(() -> {
                status = "";
                readyToInstall = apkFile.exists();
            }, 3000);
        }
    }
}

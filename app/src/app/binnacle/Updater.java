package app.binnacle;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Updates Binnacle from this project's latest GitHub release, on the owner's tap only (Advanced →
 * Updates), never in the background.
 *
 * Fixed source: RELEASE_BASE + INFO (a few key=value lines: versionCode, versionName, size,
 * sha256) and RELEASE_BASE + APK (the signed APK). The guards pin both names, HTTPS only, download
 * only (GET). The APK is streamed straight into an Android PackageInstaller session (nothing is
 * written to the app's own storage), its size and SHA-256 must match the info file, and the
 * session is locked to this app's own package name, so it can only ever update Binnacle itself.
 * Android then checks the APK is signed with the same key as the installed copy and refuses a
 * lower version, and shows its own "install update?" screen: nothing installs without that tap.
 * Nothing here touches the car.
 */
final class Updater {
    static final String RELEASE_BASE = "https://github.com/lotech/binnacle/releases/latest/download/";
    static final String INFO = "binnacle-update.txt";
    static final String APK = "Binnacle.apk";
    private static final int MAX_INFO_BYTES = 4096;
    private static final long MAX_APK_BYTES = 256L * 1024 * 1024;
    private static final int TIMEOUT_MS = 20_000;
    private static final String STATUS_ACTION = "app.binnacle.UPDATE_STATUS";

    /** What the latest release offers, from its info file. */
    static final class Release {
        final int versionCode;
        final String versionName;
        final long size;
        final String sha256;
        Release(int versionCode, String versionName, long size, String sha256) {
            this.versionCode = versionCode; this.versionName = versionName; this.size = size; this.sha256 = sha256;
        }
    }

    interface Progress {
        /** Bytes downloaded so far (called on the download thread). */
        void onProgress(long bytes);
    }

    private Updater() {}

    private static boolean busy;

    private static synchronized boolean begin() { if (busy) return false; busy = true; return true; }

    private static synchronized void end() { busy = false; }

    static synchronized boolean installing() { return busy; }

    /** The installed version code (0 if Android can't say). */
    static int installedCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** True if Android lets this app ask to install (Android 8+: "Install unknown apps" for Binnacle). */
    static boolean allowedToInstall(Context c) {
        return android.os.Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    /** Blocking: reads the latest release's info file. Call off the main thread. */
    static Release check() throws IOException {
        HttpURLConnection http = open(INFO);
        try {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            try (InputStream in = http.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    text.write(buf, 0, n);
                    if (text.size() > MAX_INFO_BYTES) throw new IOException("update info too large");
                }
            }
            return parse(text.toString("UTF-8"));
        } finally {
            http.disconnect();
        }
    }

    /** Reads the info file: key=value lines; every field must be there and well formed. */
    static Release parse(String text) throws IOException {
        int code = -1;
        String name = null, sha = null;
        long size = -1;
        for (String line : text.split("\n")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq < 1) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            try {
                if (k.equals("versionCode")) code = Integer.parseInt(v);
                else if (k.equals("versionName")) name = v;
                else if (k.equals("size")) size = Long.parseLong(v);
                else if (k.equals("sha256")) sha = v.toLowerCase(java.util.Locale.ROOT);
            } catch (NumberFormatException e) {
                throw new IOException("bad update info");
            }
        }
        if (code < 1 || name == null || !name.matches("[0-9A-Za-z.\\-]{1,24}")
                || size < 1 || size > MAX_APK_BYTES || sha == null || !sha.matches("[0-9a-f]{64}")) {
            throw new IOException("bad update info");
        }
        return new Release(code, name, size, sha);
    }

    /**
     * Blocking: downloads the release's APK into an install session and commits it. Android then
     * shows its confirm screen (Status, below, opens it). Call off the main thread.
     */
    static void install(Context c, Release r, Progress progress) throws IOException {
        if (r.versionCode <= installedCode(c)) throw new IOException("already up to date");
        if (!begin()) throw new IOException("already updating");
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());
            params.setSize(r.size);
            int id = pi.createSession(params);
            PackageInstaller.Session session = pi.openSession(id);
            boolean committed = false;
            try {
                stream(session, r, progress);
                Intent status = new Intent(STATUS_ACTION).setClass(c, Status.class);
                PendingIntent sender = PendingIntent.getBroadcast(c, id, status, PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(sender.getIntentSender());
                committed = true;
            } finally {
                if (!committed) session.abandon();   // never leave a half-written session behind
                session.close();
            }
        } finally {
            end();
        }
    }

    private static void stream(PackageInstaller.Session session, Release r, Progress progress) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("no SHA-256");
        }
        HttpURLConnection http = open(APK);
        long total = 0;
        // DigestInputStream hashes what it reads, so the digest needs no call of its own here
        try (InputStream in = new DigestInputStream(new BufferedInputStream(http.getInputStream()), sha);
             OutputStream out = session.openWrite("base.apk", 0, r.size)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > r.size) throw new IOException("download larger than expected");
                out.write(buf, 0, n);
                if (progress != null) progress.onProgress(total);
            }
            session.fsync(out);
        } finally {
            http.disconnect();
        }
        if (total != r.size) throw new IOException("download incomplete");
        if (!hex(sha.digest()).equals(r.sha256)) throw new IOException("download damaged (checksum)");
    }

    /** GET one of the release's two fixed files, over HTTPS. */
    private static HttpURLConnection open(String file) throws IOException {
        if (!INFO.equals(file) && !APK.equals(file)) throw new IOException("not an update file");
        HttpURLConnection http = (HttpURLConnection) new URL(RELEASE_BASE + file).openConnection();
        http.setConnectTimeout(TIMEOUT_MS);
        http.setReadTimeout(TIMEOUT_MS);
        http.setInstanceFollowRedirects(true);   // GitHub serves release files through an HTTPS redirect
        boolean ok = false;
        try {
            int code = http.getResponseCode();
            if (code != 200) throw new IOException(code == 404 ? "no update published yet" : "server said " + code);
            if (!"https".equals(http.getURL().getProtocol())) throw new IOException("not HTTPS");
            ok = true;
            return http;
        } finally {
            if (!ok) http.disconnect();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return s.toString();
    }

    /**
     * Android's answer to the commit: first "needs the user" with its confirm screen, which this
     * opens; then a failure (shown as a toast), or nothing at all on success because installing
     * replaces this app and restarts it.
     */
    public static final class Status extends BroadcastReceiver {
        /** Android's confirm screen, held while the car isn't in Park (audit D7); main thread only. */
        private static Intent waitingForPark;
        /** Live data says Park (set by DashApp from each car message; never true in demo). */
        private static boolean inPark;

        @Override public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) return;
                // The download started in Park, but the car may have moved off since: the install
                // screen covers the cluster, so it only opens in Park.
                if (!inPark) {
                    waitingForPark = confirm;
                    Toast.makeText(c, "Update ready: it installs when the car is in Park", Toast.LENGTH_LONG).show();
                    return;
                }
                open(c, confirm);
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) return;
            Toast.makeText(c, reason(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)), Toast.LENGTH_LONG).show();
        }

        /** The gear from live data: in Park, an install screen that was held back opens now. */
        static void gear(Context c, boolean park) {
            inPark = park;
            Intent confirm = waitingForPark;
            if (!park || confirm == null) return;
            waitingForPark = null;
            open(c, confirm);
        }

        private static void open(Context c, Intent confirm) {
            try {
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                Toast.makeText(c, "Couldn't open Android's install screen", Toast.LENGTH_LONG).show();
            }
        }
    }

    static String reason(int status, String message) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "Update cancelled";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "Update refused: it isn't signed with the same key as this copy of Binnacle";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "Update failed: not enough space on the unit";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "Update blocked by Android";
            default: return "Update failed" + (message != null ? ": " + message : "");
        }
    }
}

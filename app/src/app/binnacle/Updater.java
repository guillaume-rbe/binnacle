package app.binnacle;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
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
 * Updates Binnacle, and Binnacle's CarPlay app when it is installed, from this project's latest
 * GitHub release, on the owner's tap only (Advanced → Updates), never in the background.
 *
 * Fixed source: RELEASE_BASE + INFO (a few key=value lines: versionCode, versionName, size,
 * sha256, and the same for the CarPlay app as carplayVersionCode, ...), RELEASE_BASE + APK (the
 * signed dash) and RELEASE_BASE + CARPLAY_APK (the signed CarPlay app). The guards pin the three
 * names, HTTPS only, download only (GET). The APK is streamed straight into an Android
 * PackageInstaller session (nothing is written to the app's own storage), its size and SHA-256
 * must match the info file, and the session is locked to one of two package names (target():
 * this app's own, or CARPLAY_PACKAGE), so it can only ever update Binnacle or its CarPlay app. The
 * CarPlay app is only updated when it is already installed, so Android checks the APK is signed
 * with the same key as the installed copy in both cases, refuses a lower version, and shows its
 * own "install update?" screen: nothing installs without that tap. Nothing here touches the car.
 */
final class Updater {
    static final String RELEASE_BASE = "https://github.com/lotech/binnacle/releases/latest/download/";
    static final String INFO = "binnacle-update.txt";
    static final String APK = "Binnacle.apk";
    static final String CARPLAY_APK = "BinnacleCarPlay.apk";
    /** Binnacle's CarPlay app (carplay/ in this repo), released next to the dash. */
    static final String CARPLAY_PACKAGE = "com.shilapi.xcertplay";
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
        /** The CarPlay app in the same release, or null (releases before 0.9 have none). */
        final Release carplay;
        Release(int versionCode, String versionName, long size, String sha256, Release carplay) {
            this.versionCode = versionCode; this.versionName = versionName; this.size = size; this.sha256 = sha256;
            this.carplay = carplay;
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

    /**
     * What the update is doing now, for the Updates card: null when idle, otherwise e.g.
     * "Downloading CarPlay · 1 of 2". Main thread only. stageProgress is 0..1000 while
     * downloading, or -1 while Android (or Play Protect) has the install screen up.
     */
    static String stage;
    static int stageProgress;
    /** The Updates card on screen, if any: redraws itself from stage (main thread). */
    static Runnable onStage;

    private static Handler mainHandler;

    /** The main thread's Handler, made on first use (plain-JVM tests load this class without one). */
    private static synchronized Handler main() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    /** Sets the stage from any thread; the card on screen redraws. */
    static void show(final String text, final int progress) {
        main().post(new Runnable() {
            @Override public void run() {
                stage = text;
                stageProgress = progress;
                if (onStage != null) onStage.run();
            }
        });
    }

    /** install() refused because a download is already running: that one carries on. */
    static final class Busy extends IOException {
        Busy() { super("already updating"); }
    }

    /**
     * Runs one part of the release (the CarPlay app or the dash) in the background: downloads it
     * into Android's installer with progress on the stage, then waits for Android's answer
     * (Status). With dashNext (CarPlay first, the dash after it) the dash's part is saved, so
     * Status starts it as soon as Android reports CarPlay installed, even if the settings screen
     * closed or the dash restarted meanwhile (owner, 17 Nov: the hand-off got lost behind Play
     * Protect's prompt). secondStep: this is that dash part ("2 of 2").
     */
    static void start(Context c, final Release r, final boolean carplay, final boolean dashNext, boolean secondStep) {
        final Context app = c.getApplicationContext();
        final String step = dashNext ? " · 1 of 2" : secondStep ? " · 2 of 2" : "";
        final String name = carplay ? "CarPlay" : "Binnacle";
        final long total = Math.max(1, (carplay ? r.carplay : r).size);
        show("Downloading " + name + step, 0);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    install(app, r, carplay, new Progress() {
                        int shown = -1;
                        @Override public void onProgress(long bytes) {
                            int now = (int) Math.min(1000, bytes * 1000 / total);
                            if (now / 5 == shown / 5) return;   // every half percent is plenty
                            shown = now;
                            show("Downloading " + name + step, now);
                        }
                    });
                    // saved once this download is in Android's hands (before its answer can come)
                    saveNext(app, dashNext ? r : null);
                    if (!carplay) prefs(app).edit().putBoolean(DASH_UPDATING, true).apply();
                    show("Installing " + name + step, -1);   // Android's screen (and maybe Play Protect) next
                } catch (Busy e) {
                    // another download is running; leave its stage and hand-off alone
                } catch (Exception e) {
                    finished(app);
                    Status.toast(app, "Couldn't update: " + e.getMessage());
                }
            }
        }, "update").start();
    }

    /** The dash's part to run after CarPlay ("code name size sha"), and whether CarPlay is done. */
    private static final String NEXT = "next_dash", NEXT_READY = "next_dash_ready";
    /** Set when the dash's own install goes to Android, for Relaunch's "Binnacle updated". */
    private static final String DASH_UPDATING = "dash_updating";

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Status.PREFS, Context.MODE_PRIVATE);
    }

    /** Saves (or clears, with null) the dash's part to run after CarPlay. */
    private static void saveNext(Context c, Release r) {
        prefs(c).edit()
                .putString(NEXT, r == null ? null : r.versionCode + " " + r.versionName + " " + r.size + " " + r.sha256)
                .putBoolean(NEXT_READY, false)
                .apply();
    }

    /** The dash's part saved by start(), or null; clears it. */
    private static Release takeNext(Context c) {
        String s = prefs(c).getString(NEXT, null);
        prefs(c).edit().remove(NEXT).remove(NEXT_READY).apply();
        if (s == null) return null;
        String[] f = s.split(" ");
        if (f.length != 4) return null;
        try {
            return part(f[0], f[1], f[2], f[3], null);
        } catch (IOException e) {
            return null;
        }
    }

    /** CarPlay installed: the dash's part now in Park, else at the next Park (Status.gear). */
    private static void carplayDone(Context c, boolean park) {
        if (prefs(c).getString(NEXT, null) == null) {
            finished(c);
            Status.toast(c, "CarPlay updated");
        } else if (park) {
            Release next = takeNext(c);
            Status.toast(c, "CarPlay updated. Now Binnacle…");
            if (next != null) start(c, next, false, false, true);
        } else {
            prefs(c).edit().putBoolean(NEXT_READY, true).apply();
            show("Binnacle next, in Park · 2 of 2", -1);
            Status.toast(c, "CarPlay updated. Binnacle updates in Park");
        }
    }

    /** In Park: the dash's part, if CarPlay installed while the car was out of Park. */
    private static void nextInPark(Context c) {
        if (!prefs(c).getBoolean(NEXT_READY, false)) return;
        Release next = takeNext(c);
        if (next != null) start(c, next, false, false, true);
    }

    /** True once after an in-app update of the dash itself (Relaunch says it finished). */
    static boolean takeDashUpdated(Context c) {
        boolean was = prefs(c).getBoolean(DASH_UPDATING, false);
        if (was) prefs(c).edit().remove(DASH_UPDATING).apply();
        return was;
    }

    /** An install ended (failed, cancelled or done): nothing follows. */
    private static void finished(Context c) {
        saveNext(c, null);
        prefs(c).edit().remove(DASH_UPDATING).apply();
        show(null, 0);
    }

    /** The installed version code (0 if Android can't say). */
    static int installedCode(Context c) {
        return installedCode(c, false);
    }

    /** The installed version code of Binnacle or its CarPlay app (0 if it isn't installed). */
    static int installedCode(Context c, boolean carplay) {
        try {
            return c.getPackageManager().getPackageInfo(target(c, carplay), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** The only two packages an install session may be locked to (the guards pin this). */
    private static String target(Context c, boolean carplay) {
        return carplay ? CARPLAY_PACKAGE : c.getPackageName();
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

    /**
     * Reads the info file: key=value lines; every dash field must be there and well formed. The
     * CarPlay app's fields (the same names with a "carplay" prefix) are optional, but all or none.
     */
    static Release parse(String text) throws IOException {
        java.util.Map<String, String> f = new java.util.HashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq < 1) continue;
            f.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        Release carplay = null;
        if (f.containsKey("carplayVersionCode") || f.containsKey("carplayVersionName")
                || f.containsKey("carplaySize") || f.containsKey("carplaySha256")) {
            carplay = part(f.get("carplayVersionCode"), f.get("carplayVersionName"), f.get("carplaySize"), f.get("carplaySha256"), null);
        }
        return part(f.get("versionCode"), f.get("versionName"), f.get("size"), f.get("sha256"), carplay);
    }

    private static Release part(String code, String name, String size, String sha, Release carplay) throws IOException {
        int c;
        long n;
        try {
            c = code == null ? -1 : Integer.parseInt(code);
            n = size == null ? -1 : Long.parseLong(size);
        } catch (NumberFormatException e) {
            throw new IOException("bad update info");
        }
        if (sha != null) sha = sha.toLowerCase(java.util.Locale.ROOT);
        if (c < 1 || name == null || !name.matches("[0-9A-Za-z.\\-]{1,24}")
                || n < 1 || n > MAX_APK_BYTES || sha == null || !sha.matches("[0-9a-f]{64}")) {
            throw new IOException("bad update info");
        }
        return new Release(c, name, n, sha, carplay);
    }

    /**
     * Blocking: downloads the release's dash APK, or with carplay its CarPlay app (only when that
     * is installed), into an install session, then commits it (in Park only; see Status.ready).
     * Android then shows its confirm screen, which Status opens. Call off the main thread.
     */
    static void install(Context c, Release r, boolean carplay, Progress progress) throws IOException {
        Release part = carplay ? r.carplay : r;
        if (part == null) throw new IOException("this release has no CarPlay support");
        int installed = installedCode(c, carplay);
        if (carplay && installed == 0) throw new IOException("CarPlay support isn't installed");
        // Android would refuse the install anyway; checking first saves a download of up to 256 MB
        if (carplay && c.getPackageManager().checkSignatures(c.getPackageName(), target(c, true))
                != android.content.pm.PackageManager.SIGNATURE_MATCH) {
            throw new IOException("installed CarPlay support isn't signed with Binnacle's key");
        }
        if (part.versionCode <= installed) throw new IOException("already up to date");
        if (!begin()) throw new Busy();
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(target(c, carplay));
            params.setSize(part.size);
            int id = pi.createSession(params);
            PackageInstaller.Session session = pi.openSession(id);
            boolean written = false;
            try {
                stream(session, part, carplay ? CARPLAY_APK : APK, progress);
                written = true;
            } finally {
                if (!written) session.abandon();   // never leave a half-written session behind
                session.close();
            }
            Status.ready(c.getApplicationContext(), id);
        } finally {
            end();
        }
    }

    /** Commits a written session; Android answers on Status. False if the session is gone. */
    private static boolean commit(Context c, int id) {
        try {
            PackageInstaller.Session session = c.getPackageManager().getPackageInstaller().openSession(id);
            try {
                Intent status = new Intent(STATUS_ACTION).setClass(c, Status.class);
                PendingIntent sender = PendingIntent.getBroadcast(c, id, status, PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(sender.getIntentSender());
            } finally {
                session.close();
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void stream(PackageInstaller.Session session, Release r, String file, Progress progress) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("no SHA-256");
        }
        HttpURLConnection http = open(file);
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

    /** GET one of the release's three fixed files, over HTTPS. */
    private static HttpURLConnection open(String file) throws IOException {
        if (!INFO.equals(file) && !APK.equals(file) && !CARPLAY_APK.equals(file)) throw new IOException("not an update file");
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
     * opens; then a failure (shown as a toast), CarPlay installed (the dash's part next, if
     * saved), or nothing at all for the dash because installing replaces this app and restarts it.
     */
    public static final class Status extends BroadcastReceiver {
        static final String PREFS = "updater";
        private static final String PENDING = "pending_session";
        /** The session whose answer the stage waits for; an older one's answer changes nothing. */
        private static final String CURRENT = "current_session";
        private static final int UNKNOWN = -2;
        /**
         * A downloaded update not yet committed because the car wasn't in Park (audit D7). Its id is
         * also saved, so it still installs at the next Park if Android restarts the dash meanwhile
         * (PackageInstaller keeps the written session). -1: none; UNKNOWN: not read yet.
         */
        private static int pending = UNKNOWN;
        /** Android's confirm screen, if the car left Park between the commit and Android's answer. */
        private static Intent waitingForPark;
        /** Live data says Park (set by DashApp from each car message; never true in demo). */
        private static boolean inPark;

        /**
         * A written session (download thread): committed now in Park, otherwise kept for the next
         * Park, since Android's install screen covers the cluster.
         */
        static void ready(final Context c, int id) {
            boolean now;
            synchronized (Status.class) {
                int old = load(c);
                if (old >= 0 && old != id) abandon(c, old);   // a newer download replaces it
                // and one whose install screen was left unanswered (Home pressed, then tapped again)
                int shown = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(CURRENT, -1);
                if (shown >= 0 && shown != id && shown != old) abandon(c, shown);
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(CURRENT, id).apply();
                now = inPark;
                save(c, now ? -1 : id);
            }
            if (now) {
                if (!commit(c, id)) lost(c);
            } else {
                toast(c, "Update ready: it installs when the car is in Park");
            }
        }

        @Override public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) return;
                // Committed in Park, but the car may have moved off before Android answered: the
                // install screen covers the cluster, so it only opens in Park.
                if (!inPark) {
                    waitingForPark = confirm;
                    Toast.makeText(c, "Update ready: it installs when the car is in Park", Toast.LENGTH_LONG).show();
                    return;
                }
                open(c, confirm);
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) {
                // a dash update restarts the dash, so only the CarPlay app's success is seen here
                if (CARPLAY_PACKAGE.equals(intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME))) {
                    carplayDone(c, inPark);   // the dash's part next, if this release has one
                }
                return;
            }
            // an older session's answer (replaced by a newer download) leaves the current one alone
            int id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            int current = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(CURRENT, -1);
            if (id >= 0 && current >= 0 && id != current) return;
            finished(c);
            Toast.makeText(c, reason(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)), Toast.LENGTH_LONG).show();
        }

        /** The gear from live data: in Park, a held update is committed and a held screen opens. */
        static void gear(Context c, boolean park) {
            int id;
            synchronized (Status.class) {
                inPark = park;
                if (!park) return;
                id = load(c);
                if (id >= 0) save(c, -1);
            }
            if (id >= 0 && !commit(c, id)) lost(c);
            nextInPark(c);
            Intent confirm = waitingForPark;
            if (confirm == null) return;
            waitingForPark = null;
            open(c, confirm);
        }

        /** Caller holds Status.class. */
        private static int load(Context c) {
            if (pending == UNKNOWN) pending = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(PENDING, -1);
            return pending;
        }

        /** Caller holds Status.class. */
        private static void save(Context c, int id) {
            pending = id;
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(PENDING, id).apply();
        }

        private static void abandon(Context c, int id) {
            try {
                c.getPackageManager().getPackageInstaller().abandonSession(id);
            } catch (RuntimeException e) {
                // already gone
            }
        }

        /** A held or written session is gone: the update stops. */
        private static void lost(Context c) {
            finished(c);
            toast(c, "Update failed: the download was lost, check again");
        }

        static void toast(final Context c, final String text) {
            main().post(new Runnable() {
                @Override public void run() { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); }
            });
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
                return "Update refused: it isn't signed with the same key as the installed copy";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "Update failed: not enough space on the unit";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "Update blocked by Android";
            default: return "Update failed" + (message != null ? ": " + message : "");
        }
    }
}

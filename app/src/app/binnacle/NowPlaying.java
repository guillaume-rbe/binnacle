package app.binnacle;

import android.graphics.Bitmap;
import android.os.SystemClock;

/**
 * What the iPhone is playing through CarPlay, as xcertplay reports it (CarPlayLink). Read only:
 * the card shows it.
 */
final class NowPlaying {
    final String title, artist, album, app;
    final long durationMs;
    final boolean playing, controls;
    final int artId;
    /** The cover (at most 256 px), or null until the iPhone has sent it. */
    final Bitmap art;
    private final long positionMs, atMs;

    NowPlaying(String title, String artist, String album, String app, long durationMs, long positionMs,
               boolean playing, boolean controls, int artId, Bitmap art) {
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.app = app == null ? "" : app;
        this.durationMs = Math.max(0, durationMs);
        this.positionMs = Math.max(0, positionMs);
        this.playing = playing;
        this.controls = controls;
        this.artId = artId;
        this.art = art;
        this.atMs = SystemClock.uptimeMillis();
    }

    /** Position now: the reported one, moved on by the time since while playing. */
    long positionNowMs() {
        long p = positionMs + (playing ? SystemClock.uptimeMillis() - atMs : 0);
        return durationMs > 0 ? Math.min(p, durationMs) : p;
    }
}

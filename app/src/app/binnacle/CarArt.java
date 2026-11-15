package app.binnacle;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.InputStream;

/**
 * Car renders. The app bundles its own, rendered from a CC BY 4.0 model (by
 * Ameer Studio, see assets/cars/CREDITS.txt), in assets/cars/<hand>/<colour>/ (hand =
 * rhd or lhd: which side the steering wheel and driver's door are on; y-rhd / y-lhd for a Model Y,
 * downloaded only) with the same file names and
 * layout as the stock ZIQI renders (the stock dash's own renders are never used). Door layers are
 * named by their real side in both, so the door logic is the same either way.
 * Where to look, first match wins:
 * <ol>
 *   <li>a pack in this app's external files dir, files/<hand>/<colour>/ (another colour,
 *       or new renders without a build);</li>
 *   <li>the bundled pack for the car's colour and hand;</li>
 *   <li>any bundled colour in that hand (silver first);</li>
 *   <li>the other hand, so there is always a car (a mirror-image interior beats none).</li>
 * </ol>
 * If none are found the views draw their own simple car shapes instead.
 */
final class CarArt {
    /** Index = the car's "carcolor" value, same order as the stock launcher's BitMapLoader. */
    private static final String[] COLOURS = {"white", "red", "blue", "black", "silver"};
    private static final String ASSET_ROOT = "cars";

    static final CarArt NONE = new CarArt(null, null, null, null, null);

    /** Where a colour's renders come from: a folder on the unit, or the bundled assets. */
    abstract static class Source {
        /** The colour folder's name, e.g. white. */
        final String name;
        Source(String name) { this.name = name; }

        /** The image at rel (e.g. "turn/3.png"), or null if missing or undecodable. */
        abstract Bitmap decode(String rel);
    }

    final Bitmap side, rear, top;
    /**
     * The driving view's own render (drive/rear.png, DRIVE_W x 210; 15 Nov 2026): a level camera
     * whose horizon meets the road's lane lines, so the car sits on the road. Null in packs from
     * before it (cars-v3 and older): the driving view then uses rear, as before.
     */
    final Bitmap drive;
    /** drive/rear.png's size, and the car's box in it (matches rear's 247-560 x 10-387 of 800x434). */
    static final float DRIVE_W = 240, DRIVE_H = 210, DRIVE_CAR_X0 = 60.8f, DRIVE_CAR_X1 = 179.2f, DRIVE_CAR_BOTTOM = 185;
    static final float REAR_CAR_X0 = 247, REAR_CAR_X1 = 560, REAR_CAR_BOTTOM = 387;
    /** The colour's renders (null if none), for loading door frames on demand. */
    final Source src;

    private CarArt(Source src, Bitmap side, Bitmap rear, Bitmap top, Bitmap drive) {
        this.src = src;
        this.drive = drive;
        this.side = side;
        this.rear = rear;
        this.top = top;
    }

    /**
     * hand = the set to show (CarPacks.HANDS: rhd / lhd, or y-rhd / y-lhd for a Model Y). A Model Y
     * without its pack (only dark grey right-hand drive is bundled) falls back to any bundled Model Y
     * colour on that side, its other side, then the Model 3, so there is always a car while the pack
     * downloads.
     */
    static CarArt load(Context c, int carColour, String hand) {
        boolean modelY = hand.startsWith("y-");
        String side = modelY ? hand.substring(2) : hand;
        String other = side.equals("lhd") ? "rhd" : "lhd";
        Source s = find(c, carColour, hand);
        if (s == null && modelY) s = find(c, carColour, "y-" + other);
        if (s == null) s = find(c, carColour, side);
        if (s == null) s = find(c, carColour, other);
        if (s == null) return NONE;
        return new CarArt(s,
                s.decode("turn/0.png"),
                s.decode("body/middle.png"),
                s.decode("tpmscar/tpmscar.png"),
                s.decode("drive/rear.png"));
    }

    private static Source find(Context c, int carColour, String hand) {
        String preferred = carColour >= 0 && carColour < COLOURS.length ? COLOURS[carColour] : "silver";
        AssetManager am = c.getAssets();
        Source s = folder(c.getExternalFilesDir(null), hand, preferred);
        if (s == null) s = asset(am, hand, preferred);
        for (int i = COLOURS.length - 1; s == null && i >= 0; i--) s = asset(am, hand, COLOURS[i]);   // silver first
        return s;
    }

    private static Source folder(File root, String hand, String colour) {
        if (root == null) return null;
        File d = new File(new File(root, hand), colour);
        return d.isDirectory() ? new FolderSource(d) : null;
    }

    private static Source asset(AssetManager am, String hand, String colour) {
        String dir = ASSET_ROOT + "/" + hand + "/" + colour;
        try {
            String[] list = am.list(dir);
            return list != null && list.length > 0 ? new AssetSource(am, dir) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class FolderSource extends Source {
        private final File dir;
        FolderSource(File dir) { super(dir.getName()); this.dir = dir; }

        @Override Bitmap decode(String rel) {
            try {
                File f = new File(dir, rel);
                return f.canRead() ? BitmapFactory.decodeFile(f.getPath()) : null;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public boolean equals(Object o) { return o instanceof FolderSource && ((FolderSource) o).dir.equals(dir); }
        @Override public int hashCode() { return dir.hashCode(); }
    }

    private static final class AssetSource extends Source {
        private final AssetManager am;
        private final String dir;
        AssetSource(AssetManager am, String dir) { super(dir.substring(dir.lastIndexOf('/') + 1)); this.am = am; this.dir = dir; }

        @Override Bitmap decode(String rel) {
            try (InputStream in = am.open(dir + "/" + rel)) {
                return BitmapFactory.decodeStream(in);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public boolean equals(Object o) { return o instanceof AssetSource && ((AssetSource) o).dir.equals(dir); }
        @Override public int hashCode() { return dir.hashCode(); }
    }
}

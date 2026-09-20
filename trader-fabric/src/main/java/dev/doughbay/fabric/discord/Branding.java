package dev.doughbay.fabric.discord;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The wordmark and the robot, for the panel Discord draws.
 *
 * <p>The header wrote "DoughBay" in a system font because the picture is built
 * with Java2D and the game's own textures are loaded by the game. They do not
 * have to be: they are ordinary PNGs sitting in the mod's jar, and reading them
 * from the classpath needs nothing from Minecraft at all. So the panel gets the
 * same logo and the same robot as the screen in the game, and the two stop
 * looking like different products.
 *
 * <p>The robot's faces are sprite sheets of frames stacked vertically, because
 * in the game it animates. Here it does not: a page is a still picture, and one
 * frame of each face is enough to say what it is doing. The first frame of a
 * sheet is where its animation rests, so that is the one taken.
 */
final class Branding {
    /** One frame of a mascot sheet, matching the sizes the game draws with. */
    private static final int FRAME_WIDTH = 192;
    private static final int FRAME_HEIGHT = 165;

    private static final Map<String, BufferedImage> SHEETS = new ConcurrentHashMap<>();
    private static final BufferedImage MISSING = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private Branding() {
    }

    /** The DoughBay wordmark, or null if it cannot be read. */
    static BufferedImage logo() {
        BufferedImage logo = sheet("logo");
        return logo == MISSING ? null : logo;
    }

    /**
     * The robot wearing the given face.
     *
     * @param face one of the mascot sheet names: idle, watch, work, sale,
     *             paused, sleep, alert
     */
    static BufferedImage mascot(String face) {
        BufferedImage sheet = sheet("mascot_" + face.toLowerCase(Locale.ROOT));
        if (sheet == MISSING || sheet.getHeight() < FRAME_HEIGHT) return null;
        return sheet.getSubimage(0, 0, Math.min(FRAME_WIDTH, sheet.getWidth()), FRAME_HEIGHT);
    }

    private static BufferedImage sheet(String name) {
        return SHEETS.computeIfAbsent(name, n -> {
            try (InputStream in = Branding.class.getResourceAsStream(
                    "/assets/doughbay/textures/gui/" + n + ".png")) {
                if (in == null) return MISSING;
                BufferedImage image = ImageIO.read(in);
                return image == null ? MISSING : image;
            } catch (Exception | Error e) {
                // The header falls back to type; a missing picture is not a
                // reason for the page not to be published.
                return MISSING;
            }
        });
    }
}

package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the README's animated mascot GIFs from the production face code, so
 * the page shows exactly what the HUD draws rather than a hand-made imitation.
 *
 * <p>Off in normal builds. To regenerate, point the variable at the output
 * folder and rerun just this class:
 * <pre>GONUTS_RENDER_MASCOT=docs/assets ./gradlew :trader-fabric:test --tests "*MascotGifExport*" --rerun</pre>
 *
 * <p>Each GIF is one loop of a mood, cut at a length where the motion wraps.
 * All frames share one palette so nothing flickers, and each frame after the
 * first stores only the pixels that changed: the head never moves, only the
 * face, which keeps the files small enough for a README.
 */
@EnabledIfEnvironmentVariable(named = "GONUTS_RENDER_MASCOT", matches = ".+")
class MascotGifExport {

    private static final int TILE = 200;
    private static final int HEAD = 176;
    private static final Color BACKGROUND = new Color(0x161B22);
    /** 20 frames a second; GIF delays are in hundredths of a second. */
    private static final int DELAY_CS = 5;

    @Test
    void rendersTheReadmeLoops() throws IOException {
        Path out = Path.of(System.getenv("GONUTS_RENDER_MASCOT"));
        if (!out.isAbsolute()) out = Path.of("..").resolve(out); // tests run in trader-fabric/
        Files.createDirectories(out);
        BufferedImage shell;
        try (InputStream in = MascotGifExport.class.getResourceAsStream(
                "/assets/doughbay/textures/gui/mascot_shell.png")) {
            shell = ImageIO.read(in);
        }

        // Reading repeats every 3.6 s and idle every 11 s, straight from the
        // choreography. A sale is shown as back-to-back sales: the reels spin
        // and settle, then the next sale restarts them; 3.75 s also closes
        // three cycles of the mouth's wobble.
        render(out.resolve("mascot-reading.gif"), shell, MascotAnimation.Mood.WATCH, 3.6);
        render(out.resolve("mascot-sale.gif"), shell, MascotAnimation.Mood.SALE, 3.75);
        render(out.resolve("mascot-idle.gif"), shell, MascotAnimation.Mood.IDLE, 11.0);
        render(out.resolve("mascot-working.gif"), shell, MascotAnimation.Mood.WORK, workingLoop());

        for (String name : List.of("reading", "sale", "idle", "working")) {
            assertTrue(Files.size(out.resolve("mascot-" + name + ".gif")) > 1_000);
        }
    }

    /**
     * Working mixes three waves (gaze 2.1 and 1.6 rad/s, mouth dots 2.8 rad/s)
     * that only line up every 63 s, so pick the frame-aligned length between 6
     * and 12 s where they come closest to a whole cycle, and not mid-blink.
     */
    private static double workingLoop() {
        double best = 9.0;
        double bestError = Double.MAX_VALUE;
        for (int frames = 120; frames <= 240; frames++) {
            double l = frames * DELAY_CS / 100.0;
            double blinkPhase = l % 4.8;
            if (blinkPhase > 3.7 && blinkPhase < 4.15) continue;
            double error = 2 * cycleError(2.8, l) + cycleError(2.1, l) + .5 * cycleError(1.6, l);
            if (error < bestError) {
                bestError = error;
                best = l;
            }
        }
        return best;
    }

    private static double cycleError(double radPerSecond, double seconds) {
        double cycles = radPerSecond * seconds / (2 * Math.PI);
        return Math.abs(cycles - Math.rint(cycles));
    }

    private static void render(Path file, BufferedImage shell, MascotAnimation.Mood mood,
                               double seconds) throws IOException {
        int count = (int) Math.round(seconds * 100 / DELAY_CS);
        List<BufferedImage> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long millis = Math.round(i * DELAY_CS * 10.0);
            frames.add(frame(shell, MascotAnimation.sample(mood, millis, false)));
        }
        IndexColorModel palette = sharedPalette(frames);
        List<BufferedImage> indexed = new ArrayList<>(count);
        for (BufferedImage f : frames) indexed.add(toPalette(f, palette));
        writeDeltaGif(file, indexed);
    }

    /** One frame: the still shell, then the moving face in the shell's 256-unit space. */
    private static BufferedImage frame(BufferedImage shell, MascotAnimation.Pose pose) {
        BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, TILE, TILE);
        int inset = (TILE - HEAD) / 2;
        g.translate(inset, inset);
        g.scale(HEAD / 256.0, HEAD / 256.0);
        g.drawImage(shell, 0, 0, 256, 256, null);
        AffineTransform base = g.getTransform();
        MascotFace.draw(new MascotFace.Canvas() {
            @Override
            public void fill(int left, int top, int right, int bottom, int color) {
                g.setColor(new Color(color, true));
                g.fill(new Rectangle2D.Double(left, top, right - left, bottom - top));
            }

            @Override
            public void clip(int left, int top, int right, int bottom) {
                g.setTransform(base);
                g.setClip(new Rectangle2D.Double(left, top, right - left, bottom - top));
            }

            @Override
            public void unclip() {
                g.setClip(null);
            }
        }, pose);
        g.dispose();
        return img;
    }

    /**
     * One palette for the whole loop, built by letting the GIF encoder quantise
     * a strip of every frame at once. Per-frame palettes would shift shading
     * colours from frame to frame and make the still head shimmer.
     */
    private static IndexColorModel sharedPalette(List<BufferedImage> frames) throws IOException {
        BufferedImage strip = new BufferedImage(TILE, TILE * frames.size(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = strip.createGraphics();
        for (int i = 0; i < frames.size(); i++) g.drawImage(frames.get(i), 0, i * TILE, null);
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(strip, "gif", bytes);
        BufferedImage quantised = ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        return (IndexColorModel) quantised.getColorModel();
    }

    private static BufferedImage toPalette(BufferedImage frame, IndexColorModel palette) {
        BufferedImage out = new BufferedImage(TILE, TILE, BufferedImage.TYPE_BYTE_INDEXED, palette);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE);
        g.drawImage(frame, 0, 0, null);
        g.dispose();
        return out;
    }

    /**
     * Writes a looping GIF where each frame after the first carries only the
     * rectangle that changed; an unchanged frame just lengthens the one before.
     */
    private static void writeDeltaGif(Path file, List<BufferedImage> frames) throws IOException {
        record Piece(BufferedImage image, int x, int y, int delay) { }
        List<Piece> pieces = new ArrayList<>();
        pieces.add(new Piece(frames.get(0), 0, 0, DELAY_CS));
        for (int i = 1; i < frames.size(); i++) {
            int[] box = changed(frames.get(i - 1), frames.get(i));
            if (box == null) {
                Piece last = pieces.remove(pieces.size() - 1);
                pieces.add(new Piece(last.image(), last.x(), last.y(), last.delay() + DELAY_CS));
                continue;
            }
            BufferedImage part = frames.get(i).getSubimage(box[0], box[1], box[2] - box[0], box[3] - box[1]);
            pieces.add(new Piece(part, box[0], box[1], DELAY_CS));
        }

        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (OutputStream os = Files.newOutputStream(file);
             ImageOutputStream ios = ImageIO.createImageOutputStream(os)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            boolean first = true;
            for (Piece p : pieces) {
                IIOMetadata meta = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(p.image()), writer.getDefaultWriteParam());
                String format = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                IIOMetadataNode control = child(root, "GraphicControlExtension");
                control.setAttribute("disposalMethod", "doNotDispose");
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("transparentColorIndex", "0");
                control.setAttribute("delayTime", Integer.toString(p.delay()));
                IIOMetadataNode descriptor = child(root, "ImageDescriptor");
                descriptor.setAttribute("imageLeftPosition", Integer.toString(p.x()));
                descriptor.setAttribute("imageTopPosition", Integer.toString(p.y()));
                if (first) {
                    IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                    app.setAttribute("applicationID", "NETSCAPE");
                    app.setAttribute("authenticationCode", "2.0");
                    app.setUserObject(new byte[] {1, 0, 0}); // loop forever
                    child(root, "ApplicationExtensions").appendChild(app);
                    first = false;
                }
                meta.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(p.image(), null, meta), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    /** {left, top, right, bottom} of the pixels that differ, or null if none do. */
    private static int[] changed(BufferedImage a, BufferedImage b) {
        int left = TILE, top = TILE, right = -1, bottom = -1;
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                if (a.getRaster().getSample(x, y, 0) != b.getRaster().getSample(x, y, 0)) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        return right < 0 ? null : new int[] {left, top, right + 1, bottom + 1};
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) n;
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}

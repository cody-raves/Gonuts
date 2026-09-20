package dev.doughbay.fabric.discord;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Item pictures for the Discord panel, taken from the game's own textures.
 *
 * <p>The panel is drawn on a background thread with Java2D, so it cannot ask
 * the renderer for an item; instead the texture PNG is read once through the
 * resource manager on the client thread and cached as an image. Items whose
 * texture cannot be found are simply drawn without a picture.
 */
final class ItemTextures {
    private static final Map<String, BufferedImage> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();
    private static final BufferedImage NONE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private ItemTextures() {
    }

    /** Puts a picture in the cache directly; the panel preview renderer uses this outside the game. */
    static void seed(String itemId, BufferedImage image) {
        if (itemId != null && image != null) CACHE.put(itemId, image);
    }

    /**
     * The picture for an item id, or null while it is being loaded or when
     * the game has no flat texture for it.
     */
    static BufferedImage get(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        String id = itemId.indexOf('#') >= 0 ? itemId.substring(0, itemId.indexOf('#')) : itemId;
        BufferedImage hit = CACHE.get(id);
        if (hit != null) return hit == NONE ? null : hit;
        if (MISSING.contains(id) || !PENDING.add(id)) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            PENDING.remove(id);
            return null;
        }
        mc.execute(() -> {
            try {
                BufferedImage image = load(id);
                if (image == null) {
                    MISSING.add(id);
                    CACHE.put(id, NONE);
                } else {
                    CACHE.put(id, image);
                }
            } catch (RuntimeException e) {
                MISSING.add(id);
            } finally {
                PENDING.remove(id);
            }
        });
        return null;
    }

    private static BufferedImage load(String id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getResourceManager() == null) return null;
        String namespace = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        // An item has one flat texture; a block has a face per side, and the
        // plain name, then the top or side, is the one that reads as the item.
        String[] candidates = {
                "textures/item/" + name + ".png",
                "textures/block/" + name + ".png",
                "textures/block/" + name + "_top.png",
                "textures/block/" + name + "_side.png",
                "textures/block/" + name + "_front.png",
                "textures/block/" + name + "_outside.png",
                "textures/item/" + name + "_00.png",
        };
        for (String path : candidates) {
            Identifier location = Identifier.tryParse(namespace + ":" + path);
            if (location == null) continue;
            Optional<Resource> resource = mc.getResourceManager().getResource(location);
            if (resource.isEmpty()) continue;
            try (InputStream in = resource.get().open()) {
                BufferedImage image = ImageIO.read(in);
                if (image == null) continue;
                // Animated textures are a column of frames; the first is the item.
                if (image.getHeight() >= image.getWidth() * 2) {
                    image = image.getSubimage(0, 0, image.getWidth(), image.getWidth());
                }
                return image;
            } catch (Exception e) {
                // unreadable pack entry: try the next candidate
            }
        }
        return null;
    }
}

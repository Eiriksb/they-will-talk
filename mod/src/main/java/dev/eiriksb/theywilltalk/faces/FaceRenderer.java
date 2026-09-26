package dev.eiriksb.theywilltalk.faces;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Draws a villager's face the way the game's model does, straight from their skin textures, as a small pixel-art icon
 * with a dark outline (so it reads on any map background).
 */
final class FaceRenderer {
    /** Icon pixels per texture pixel. */
    static final int SCALE = 4;
    private static final int OUTLINE = 0xC8141414;
    private static final int NO_DYE = 0xFF000000;
    private static final String CITIZEN_TEXTURES = "minecolonies:textures/entity/citizen/";
    private static final String CITIZEN_ICONS = "minecolonies:textures/entity_icon/citizen/";

    private final FaceTextures textures;

    FaceRenderer(FaceTextures textures) {
        this.textures = textures;
    }

    /** The icon as a PNG, or empty when the textures aren't available. */
    Optional<byte[]> png(FaceSpec spec) throws IOException {
        Canvas face = switch (spec) {
            case FaceSpec.Villager v -> villager(v);
            case FaceSpec.Mca m -> mca(m);
            case FaceSpec.Citizen c -> citizen(c);
        };
        if (face == null || face.empty()) {
            return Optional.empty();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(face.icon(), "png", out);
        return Optional.of(out.toByteArray());
    }

    /**
     * The vanilla villager head: an 8x10 front face, the hat layer over it and the nose sticking out in front
     * (it hangs one pixel below the chin).
     */
    private Canvas villager(FaceSpec.Villager v) {
        List<BufferedImage> layers = new ArrayList<>();
        v.layers().forEach(id -> textures.image(id).ifPresent(layers::add));
        if (layers.isEmpty()) {
            return null;
        }
        Canvas c = new Canvas(8, 11);
        layers.forEach(t -> c.cutout(t, 8, 8, 8, 10, 0, 0, -1));   // head front
        layers.forEach(t -> c.cutout(t, 40, 8, 8, 10, 0, 0, -1));  // hat front
        layers.forEach(t -> c.cutout(t, 26, 2, 2, 4, 3, 7, -1));   // nose front
        return c;
    }

    /**
     * A MineColonies citizen: MineColonies' own 16x16 face icon for their skin, else the head cut from the skin.
     * Textures missing from the colony's style pack come from the default one, as in MineColonies.
     */
    private Canvas citizen(FaceSpec.Citizen s) {
        String name = s.model() + (s.female() ? "female" : "male");
        // How many variants the model has is only known to the client; count the default pack's files instead.
        int variants = 0;
        while (variants < 16 && textures.exists(CITIZEN_TEXTURES + "default/" + name + (variants + 1) + s.suffix() + ".png")) {
            variants++;
        }
        if (variants == 0) {
            return null;
        }
        String file = name + (Math.floorMod(s.textureId(), variants) + 1) + s.suffix() + ".png";
        for (String style : List.of(s.style(), "default")) {
            Optional<BufferedImage> icon = textures.image(CITIZEN_ICONS + style + "/" + file);
            if (icon.isPresent()) {
                Canvas c = new Canvas(16, 16, SCALE / 2);
                c.cutout(icon.get(), 0, 0, 16, 16, 0, 0, -1);
                return c;
            }
        }
        for (String style : List.of(s.style(), "default")) {
            Optional<BufferedImage> skin = textures.image(CITIZEN_TEXTURES + style + "/" + file);
            if (skin.isPresent()) {
                Canvas c = new Canvas(8, 8);
                c.cutout(skin.get(), 8, 8, 8, 8, 0, 0, -1);   // head front
                c.cutout(skin.get(), 40, 8, 8, 8, 0, 0, -1);  // hat front
                return c;
            }
        }
        return null;
    }

    /** MCA's layers in MCA's order: skin, face (eyes), clothing, hair; each on the 64x64 player head. */
    private Canvas mca(FaceSpec.Mca m) {
        Canvas c = new Canvas(8, 8);
        textures.image(m.skin()).ifPresent(t -> c.cutout(t, 8, 8, 8, 8, 0, 0, skinTint(m)));
        pickFace(m.faceGene()).flatMap(textures::image).ifPresent(t -> eyes(c, t, m));
        m.clothing().stream().map(textures::image).flatMap(Optional::stream).findFirst().ifPresent(t -> {
            c.cutout(t, 8, 8, 8, 8, 0, 0, -1);
            c.cutout(t, 40, 8, 8, 8, 0, 0, -1);
        });
        int hairTint = hairTint(m);
        for (String id : m.hair()) {
            textures.image(id).ifPresent(t -> {
                c.cutout(t, 8, 8, 8, 8, 0, 0, hairTint);
                c.cutout(t, 40, 8, 8, 8, 0, 0, hairTint);
            });
            if (id.endsWith(".png")) {
                textures.image(id.replace(".png", "_overlay.png")).ifPresent(t -> {
                    c.cutout(t, 8, 8, 8, 8, 0, 0, -1);
                    c.cutout(t, 40, 8, 8, 8, 0, 0, -1);
                });
            }
        }
        return c;
    }

    private int skinTint(FaceSpec.Mca m) {
        if (m.skinDye() != NO_DYE) {
            return m.skinDye();
        }
        float albinism = m.albinism() ? 0.1f : 1.0f;
        return palette("mca:textures/colormap/villager_skin.png", m.melanin() * albinism, m.hemoglobin() * albinism);
    }

    private int hairTint(FaceSpec.Mca m) {
        if (m.hairDye() != NO_DYE) {
            return m.hairDye();
        }
        float albinism = m.albinism() ? 0.1f : 1.0f;
        return palette("mca:textures/colormap/villager_hair.png", m.eumelanin() * albinism, m.pheomelanin() * albinism);
    }

    /** MCA's ColorPalette: u picks the row, v the column. */
    private int palette(String id, float u, float v) {
        return textures.image(id).map(img -> {
            int x = (int) Math.floor(Math.clamp(v, 0f, 1f) * (img.getWidth() - 1));
            int y = (int) Math.floor(Math.clamp(u, 0f, 1f) * (img.getHeight() - 1));
            return img.getRGB(x, y);
        }).orElse(-1);
    }

    /** MCA's FaceList: the face textures sorted MCA's way, one picked by the face gene. */
    private Optional<String> pickFace(float gene) {
        return textures.text("mca:eyes/normal.json").map(json -> {
            JsonArray list = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("textures");
            List<String> pool = new ArrayList<>();
            list.forEach(e -> pool.add(e.getAsString()));
            pool.sort(FaceRenderer::compareIdentifiers);
            return pool.isEmpty() ? null : pool.get((int) Math.min(pool.size() - 1, Math.max(0, gene * pool.size())));
        });
    }

    /** MCA's SkinListEntry.compareIdentifiers: by the number in the file name, else alphabetically. */
    static int compareIdentifiers(String a, String b) {
        String numA = a.substring(Math.max(0, a.lastIndexOf('/'))).replaceAll("\\D+", "");
        String numB = b.substring(Math.max(0, b.lastIndexOf('/'))).replaceAll("\\D+", "");
        if (numA.isEmpty() || numB.isEmpty()) {
            return a.compareTo(b);
        }
        String na = numA.replaceFirst("^0+(?=.)", "");
        String nb = numB.replaceFirst("^0+(?=.)", "");
        int byLength = Integer.compare(na.length(), nb.length());
        if (byLength != 0) {
            return byLength;
        }
        int byNumber = na.compareTo(nb);
        return byNumber != 0 ? byNumber : a.compareTo(b);
    }

    /**
     * MCA's eye layers: pale pixels are the sclera (drawn white), very dark ones details (drawn grey), the rest the
     * iris, drawn in the eye colour; with heterochromia the left half of the eyes gets the other colour.
     */
    private static void eyes(Canvas c, BufferedImage face, FaceSpec.Mca m) {
        int s = Math.max(1, face.getWidth() / 64);
        int splitX = splitX(face);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int sx = (8 + x) * s;
                int p = face.getRGB(sx, (8 + y) * s);
                int a = p >>> 24;
                if (a == 0) {
                    continue;
                }
                int r = p >> 16 & 255;
                int g = p >> 8 & 255;
                int b = p & 255;
                int min = Math.min(r, Math.min(g, b));
                int max = Math.max(r, Math.max(g, b));
                boolean sclera = a == 1 || (a == 255 && min >= 160 && max - min <= 32);
                int tint = sclera ? 0xFFFFFFFF : max < 32 ? 0xFF808080
                        : m.heterochromia() && sx >= splitX ? m.eyeLeftColor() : m.eyeColor();
                c.blend(x, y, multiply(p, tint));
            }
        }
    }

    /** Middle of the visible part of the face texture (MCA splits heterochromia there). */
    private static int splitX(BufferedImage face) {
        int minX = face.getWidth();
        int maxX = -1;
        for (int x = 0; x < face.getWidth(); x++) {
            for (int y = 0; y < face.getHeight(); y++) {
                if (face.getRGB(x, y) >>> 24 != 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
        }
        return maxX < minX ? 0 : minX + (maxX - minX + 1) / 2;
    }

    static int multiply(int argb, int tint) {
        if (tint == -1) {
            return argb;
        }
        int a = (argb >>> 24) * (tint >>> 24) / 255;
        int r = (argb >> 16 & 255) * (tint >> 16 & 255) / 255;
        int g = (argb >> 8 & 255) * (tint >> 8 & 255) / 255;
        int b = (argb & 255) * (tint & 255) / 255;
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** A tiny ARGB face being composed. */
    static final class Canvas {
        final int w;
        final int h;
        final int scale;
        final int[] px;

        Canvas(int w, int h) {
            this(w, h, SCALE);
        }

        /** @param scale how big each pixel is in the icon */
        Canvas(int w, int h, int scale) {
            this.w = w;
            this.h = h;
            this.scale = scale;
            this.px = new int[w * h];
        }

        /**
         * Draws a region of a texture like the game's cutout render types: nearly transparent pixels are skipped,
         * the rest drawn opaque. HD skins (taller than 64) are sampled at the same relative spot; wide 128x64
         * skins (MineColonies) keep the usual layout.
         */
        void cutout(BufferedImage tex, int sx, int sy, int sw, int sh, int dx, int dy, int tint) {
            int s = Math.max(1, tex.getHeight() / 64);
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    int tx = (sx + x) * s;
                    int ty = (sy + y) * s;
                    if (tx >= tex.getWidth() || ty >= tex.getHeight() || dx + x >= w || dy + y >= h) {
                        continue;
                    }
                    int p = multiply(tex.getRGB(tx, ty), tint);
                    if ((p >>> 24) >= 26) {
                        px[(dy + y) * w + dx + x] = 0xFF000000 | (p & 0xFFFFFF);
                    }
                }
            }
        }

        /** Alpha-blends one pixel over what's there. */
        void blend(int x, int y, int argb) {
            int a = argb >>> 24;
            if (a == 0) {
                return;
            }
            int i = y * w + x;
            int dst = px[i];
            int da = dst >>> 24;
            int oa = a + da * (255 - a) / 255;
            if (oa == 0) {
                return;
            }
            int r = ((argb >> 16 & 255) * a + (dst >> 16 & 255) * da * (255 - a) / 255) / oa;
            int g = ((argb >> 8 & 255) * a + (dst >> 8 & 255) * da * (255 - a) / 255) / oa;
            int b = ((argb & 255) * a + (dst & 255) * da * (255 - a) / 255) / oa;
            px[i] = oa << 24 | r << 16 | g << 8 | b;
        }

        boolean empty() {
            for (int p : px) {
                if (p >>> 24 != 0) {
                    return false;
                }
            }
            return true;
        }

        /** Scaled up with crisp pixels and a 2 px dark outline around the shape. */
        BufferedImage icon() {
            int pad = 2;
            BufferedImage img = new BufferedImage(w * scale + 2 * pad, h * scale + 2 * pad, BufferedImage.TYPE_INT_ARGB);
            for (int pass = 0; pass < 2; pass++) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int p = px[y * w + x];
                        if (p >>> 24 == 0) {
                            continue;
                        }
                        int x0 = x * scale + pad;
                        int y0 = y * scale + pad;
                        int grow = pass == 0 ? pad : 0;
                        for (int yy = y0 - grow; yy < y0 + scale + grow; yy++) {
                            for (int xx = x0 - grow; xx < x0 + scale + grow; xx++) {
                                if (pass == 0) {
                                    if (img.getRGB(xx, yy) >>> 24 == 0) {
                                        img.setRGB(xx, yy, OUTLINE);
                                    }
                                } else {
                                    img.setRGB(xx, yy, p);
                                }
                            }
                        }
                    }
                }
            }
            return img;
        }
    }
}

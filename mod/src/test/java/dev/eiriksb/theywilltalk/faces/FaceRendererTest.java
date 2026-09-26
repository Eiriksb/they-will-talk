package dev.eiriksb.theywilltalk.faces;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceRendererTest {
    private static final String SKINS = "minecolonies:textures/entity/citizen/";
    private static final String ICONS = "minecolonies:textures/entity_icon/citizen/";

    private final Map<String, byte[]> files = new HashMap<>();
    private final FaceRenderer renderer = new FaceRenderer(new FaceTextures(id -> Optional.ofNullable(files.get(id))));

    @Test
    void citizensGetMineColoniesOwnIconForTheirVariantAndStyle() throws IOException {
        for (int n = 1; n <= 3; n++) {
            files.put(SKINS + "default/citizenfemale" + n + "_a.png", png(128, 64, 0xFF000000 | n));
            files.put(ICONS + "default/citizenfemale" + n + "_a.png", png(16, 16, 0xFF000000 | n));
        }
        files.put(ICONS + "medieval/citizenfemale2_a.png", png(16, 16, 0xFFAA0000));

        // Texture id 4 of 3 variants is variant 2, as in MineColonies; the colony's style pack wins.
        BufferedImage face = image(renderer.png(new FaceSpec.Citizen("citizen", true, 4, "_a", "medieval")));
        assertEquals(36, face.getWidth(), "16x16 icon at half scale plus the outline");
        assertEquals(0xFFAA0000, face.getRGB(18, 18));

        // Missing from the style pack: the default pack's icon.
        face = image(renderer.png(new FaceSpec.Citizen("citizen", true, 0, "_a", "nordic")));
        assertEquals(0xFF000001, face.getRGB(18, 18));
    }

    @Test
    void withoutAnIconTheHeadIsCutFromTheWideSkin() throws IOException {
        BufferedImage skin = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                skin.setRGB(x, y, 0xFF00AA00); // face at the usual 64x64 spot, not scaled for the width
            }
        }
        skin.setRGB(40, 8, 0xFF0000AA); // hat layer, top-left pixel
        files.put(SKINS + "default/bakermale1_d.png", bytes(skin));

        BufferedImage face = image(renderer.png(new FaceSpec.Citizen("baker", false, 7, "_d", "default")));
        assertEquals(36, face.getWidth());
        assertEquals(0xFF0000AA, face.getRGB(3, 3));
        assertEquals(0xFF00AA00, face.getRGB(18, 18));
    }

    @Test
    void unknownModelsHaveNoFace() throws IOException {
        assertTrue(renderer.png(new FaceSpec.Citizen("custom", false, 0, "_b", "default")).isEmpty());
    }

    private static BufferedImage image(Optional<byte[]> png) throws IOException {
        assertTrue(png.isPresent());
        return ImageIO.read(new ByteArrayInputStream(png.get()));
    }

    private static byte[] png(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return bytes(img);
    }

    private static byte[] bytes(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

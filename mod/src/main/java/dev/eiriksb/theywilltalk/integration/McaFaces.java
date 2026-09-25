package dev.eiriksb.theywilltalk.integration;

import dev.eiriksb.theywilltalk.faces.FaceSpec;
import net.conczin.mca.entity.VillagerLike;
import net.conczin.mca.entity.ai.Genetics;
import net.conczin.mca.entity.ai.Traits;
import net.conczin.mca.resources.data.skin.LayeredHair;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/** What an MCA villager's face is drawn from, read the way MCA's own render layers read it. Only loaded with MCA. */
public final class McaFaces {
    private static final int NO_DYE = 0xFF000000;
    private static final int NATURAL_EYES = 0xFFFFFFFF;
    private static final int ALBINISM_EYES = 0xFFE8A0A0;
    private static final int BLUE_EYES = 0xFF3A98E8;
    private static final int GREEN_EYES = 0xFF4CB346;
    private static final int HAZEL_EYES = 0xFFC29B35;
    private static final int BROWN_EYES = 0xFF7C4825;

    private McaFaces() {}

    /** Null for anything that isn't an MCA villager. */
    public static FaceSpec spec(Entity entity) {
        if (!(entity instanceof VillagerLike<?> v)) {
            return null;
        }
        Genetics genes = v.getGenetics();
        Traits traits = v.getTraits();
        boolean albinism = traits.hasTrait(Traits.ALBINISM);

        String skin = v.getSkin();
        if (skin == null || skin.isBlank()) {
            int n = (int) Math.min(4, Math.max(0, genes.getGene(Genetics.SKIN) * 5));
            skin = "mca:skins/skin/" + genes.getGender().getDataName() + "/" + n + ".png";
        }

        List<String> clothing = new ArrayList<>();
        String clothes = v.getClothes();
        if (clothes != null && !clothes.isBlank() && !clothes.startsWith("immersive_library:")) {
            if (v.isBurned()) {
                clothing.add(clothes.replace("normal", "burnt")); // MCA wears the normal one when there's no burnt version
            }
            clothing.add(clothes);
        }

        List<String> hair = new ArrayList<>();
        for (LayeredHair.Category category : LayeredHair.Category.RENDER_ORDER) {
            String id = v.getLayeredHair(category);
            if (id != null && !id.isBlank() && !id.startsWith("immersive_library:")) {
                hair.add(id);
            }
        }
        if (hair.isEmpty() && v.getHair() != null && !v.getHair().isBlank() && !v.getHair().startsWith("immersive_library:")) {
            hair.add(v.getHair());
        }

        boolean heterochromia = traits.hasTrait(Traits.HETEROCHROMIA);
        float brightness = genes.getGene(Genetics.EYE_BRIGHTNESS);
        int eyes = brightness(eyeColor(v, albinism, v.getEyeDye(), false), brightness);
        int eyesLeft = heterochromia ? brightness(eyeColor(v, albinism, v.getEyeLeftDye(), true), brightness) : eyes;

        return new FaceSpec.Mca(skin, genes.getGene(Genetics.MELANIN), genes.getGene(Genetics.HEMOGLOBIN), albinism, v.getSkinDye(),
                genes.getGene(Genetics.FACE), eyes, eyesLeft, heterochromia, List.copyOf(clothing), List.copyOf(hair),
                genes.getGene(Genetics.EUMELANIN), genes.getGene(Genetics.PHEOMELANIN), v.getHairDye());
    }

    /** MCA's EyeTextureLayers: a dye, else a colour from the face gene (blue, green, hazel, brown). */
    private static int eyeColor(VillagerLike<?> v, boolean albinism, int dye, boolean shifted) {
        if (dye != NATURAL_EYES) {
            return dye;
        }
        if (albinism) {
            return ALBINISM_EYES;
        }
        float c = v.getGenetics().getGene(Genetics.FACE) + (shifted ? 0.43f : 0f);
        c -= (float) Math.floor(c);
        if (c < 0.35f) {
            return lerp(c / 0.35f, BLUE_EYES, GREEN_EYES);
        }
        if (c < 0.70f) {
            return lerp((c - 0.35f) / 0.35f, GREEN_EYES, HAZEL_EYES);
        }
        return lerp((c - 0.70f) / 0.30f, HAZEL_EYES, BROWN_EYES);
    }

    private static int lerp(float t, int a, int b) {
        // Minecraft's Mth.lerpInt: start + floor(t * (end - start)) per channel
        int r = (a >> 16 & 255) + (int) Math.floor(t * ((b >> 16 & 255) - (a >> 16 & 255)));
        int g = (a >> 8 & 255) + (int) Math.floor(t * ((b >> 8 & 255) - (a >> 8 & 255)));
        int bl = (a & 255) + (int) Math.floor(t * ((b & 255) - (a & 255)));
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private static int brightness(int argb, float gene) {
        float f = 0.5f + Math.clamp(gene, 0f, 1f);
        int r = Math.clamp(Math.round((argb >> 16 & 255) * f), 0, 255);
        int g = Math.clamp(Math.round((argb >> 8 & 255) * f), 0, 255);
        int b = Math.clamp(Math.round((argb & 255) * f), 0, 255);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }
}

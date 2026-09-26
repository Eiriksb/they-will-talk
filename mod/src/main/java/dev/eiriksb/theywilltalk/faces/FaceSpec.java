package dev.eiriksb.theywilltalk.faces;

import java.util.List;

/** Everything a villager's face is drawn from; equal specs draw the same face. */
public sealed interface FaceSpec {
    /** Changes whenever the face would look different. */
    default String key() {
        return toString();
    }

    /**
     * A vanilla-model head (villagers, wandering traders): the base texture, then overlays such as the biome type and
     * the profession, each drawn over the one before.
     */
    record Villager(List<String> layers) implements FaceSpec {}

    /**
     * A MineColonies citizen, whose skin MineColonies picks by model (job), gender, texture variant, skin tone suffix
     * and the colony's style pack.
     *
     * @param model     the model's texture base, like {@code baker} or {@code citizen}
     * @param textureId picks one of the model's variants
     * @param suffix    skin tone, like {@code _a}
     * @param style     the colony's style pack folder, like {@code medieval}
     */
    record Citizen(String model, boolean female, int textureId, String suffix, String style) implements FaceSpec {}

    /**
     * An MCA villager as MCA draws them: skin tinted by melanin/hemoglobin (or a dye), a face chosen by the face gene
     * with eyes in their genetic or dyed colour, clothing, and hair tinted by eumelanin/pheomelanin (or a dye).
     *
     * @param skinDye   ARGB, or {@code 0xFF000000} for none
     * @param eyeColor  ARGB of the (right) iris; {@code eyeLeftColor} differs with heterochromia
     * @param clothing  clothing texture ids, the first one that exists is worn (burnt, then normal)
     * @param hair      hair texture ids in drawing order
     * @param hairDye   ARGB, or {@code 0xFF000000} for none
     */
    record Mca(String skin, float melanin, float hemoglobin, boolean albinism, int skinDye, float faceGene,
               int eyeColor, int eyeLeftColor, boolean heterochromia, List<String> clothing, List<String> hair,
               float eumelanin, float pheomelanin, int hairDye) implements FaceSpec {}
}

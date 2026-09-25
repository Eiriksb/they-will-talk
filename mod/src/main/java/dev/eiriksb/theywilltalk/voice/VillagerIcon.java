package dev.eiriksb.theywilltalk.voice;

/** 16x16 villager face for the "Villagers" volume slider in the voice chat settings. */
final class VillagerIcon {
    private static final String[] ROWS = {
            "................",
            "...HHHHHHHHHH...",
            "..HHHHHHHHHHHH..",
            "..HSSSSSSSSSSH..",
            "..SSSSSSSSSSSS..",
            "..SBBBBSSBBBBS..",
            "..SWGSSSSSSGWS..",
            "..SSSSSNNSSSSS..",
            "..SSSSSNNSSSSS..",
            "..SSSSSNNSSSSS..",
            "..SSSSNNNNSSSS..",
            "..SSSSSSSSSSSS..",
            "..SSSMMMMMMSSS..",
            "..RRRRRRRRRRRR..",
            "..RRRRRRRRRRRR..",
            "................",
    };

    private VillagerIcon() {}

    static int[][] pixels() {
        int[][] px = new int[16][16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                px[x][y] = abgr(switch (ROWS[y].charAt(x)) {
                    case 'H' -> 0xFF4A2F1B; // hair
                    case 'S' -> 0xFFBD8B72; // skin
                    case 'B' -> 0xFF3B2414; // brow
                    case 'W' -> 0xFFFFFFFF; // eye white
                    case 'G' -> 0xFF2F8A3B; // green eye
                    case 'N' -> 0xFFA86F58; // the nose
                    case 'M' -> 0xFF7A4A3A; // mouth
                    case 'R' -> 0xFF6B4A2B; // robe
                    default -> 0x00000000;
                });
            }
        }
        return px;
    }

    /** SVC hands the pixels to NativeImage#setPixelRGBA, which expects ABGR. */
    private static int abgr(int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}

package dev.eiriksb.theywilltalk.faces;

import dev.eiriksb.theywilltalk.TheyWillTalk;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Every talking villager's real face, drawn from their skin: the vanilla villager head for vanilla villagers and
 * wandering traders, MCA's layered skin for MCA villagers, MineColonies' own face icons for citizens. Faces are drawn in the background when a villager's looks
 * change (new job, haircut, dye...) and kept in the world folder ({@code faces/<uuid>.png}), so unloaded villagers keep
 * theirs. Used for BlueMap markers and the dashboard.
 */
public final class VillagerFaces {
    private final Path dir;
    private final FaceTextures textures;
    private final FaceRenderer renderer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "twt-faces");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** Versions of the faces on disk. */
    private final Map<UUID, String> versions = new ConcurrentHashMap<>();
    /** Versions being drawn. */
    private final Map<UUID, String> drawing = new ConcurrentHashMap<>();
    private final Function<Entity, FaceSpec> mcaSpecs;
    private final Function<Entity, FaceSpec> colonySpecs;

    public VillagerFaces(Path gameDir, Path dir) {
        this.dir = dir;
        this.textures = new FaceTextures(gameDir);
        this.renderer = new FaceRenderer(textures);
        this.mcaSpecs = ModList.get().isLoaded("mca") ? mcaSpecs() : e -> null;
        this.colonySpecs = ModList.get().isLoaded("minecolonies") ? colonySpecs() : e -> null;
    }

    private static Function<Entity, FaceSpec> mcaSpecs() {
        try {
            return dev.eiriksb.theywilltalk.integration.McaFaces::spec;
        } catch (Throwable t) {
            TheyWillTalk.LOGGER.warn("MCA faces unavailable (version mismatch?): {}", t.toString());
            return e -> null;
        }
    }

    private static Function<Entity, FaceSpec> colonySpecs() {
        try {
            return dev.eiriksb.theywilltalk.integration.MineColoniesFaces::spec;
        } catch (Throwable t) {
            TheyWillTalk.LOGGER.warn("MineColonies faces unavailable (version mismatch?): {}", t.toString());
            return e -> null;
        }
    }

    /** Checks a loaded villager's looks and redraws their face if they changed. Server thread. */
    public void update(Entity entity) {
        FaceSpec spec;
        try {
            spec = spec(entity);
        } catch (Throwable t) {
            TheyWillTalk.LOGGER.debug("No face for {}: {}", entity.getUUID(), t.toString());
            return;
        }
        if (spec == null) {
            return;
        }
        UUID id = entity.getUUID();
        String version = hash(spec.key());
        if (version.equals(drawing.get(id)) || version.equals(version(id).orElse(null))) {
            return;
        }
        drawing.put(id, version);
        worker.execute(() -> draw(id, spec, version));
    }

    private void draw(UUID id, FaceSpec spec, String version) {
        try {
            Optional<byte[]> png = renderer.png(spec);
            if (png.isEmpty()) {
                textures.recheckClientJar(); // textures not there (yet): try again on a later scan
                return;
            }
            Files.createDirectories(dir);
            Path tmp = dir.resolve(id + ".png.tmp");
            Files.write(tmp, png.get());
            Files.move(tmp, dir.resolve(id + ".png"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(dir.resolve(id + ".version"), version, StandardCharsets.UTF_8);
            versions.put(id, version);
        } catch (IOException | RuntimeException e) {
            TheyWillTalk.LOGGER.debug("Could not draw the face of {}: {}", id, e.toString());
        } finally {
            drawing.remove(id, version);
        }
    }

    /** The face icon (PNG), if one has been drawn. */
    public Optional<byte[]> face(UUID villager) {
        try {
            Path f = dir.resolve(villager + ".png");
            return Files.isRegularFile(f) ? Optional.of(Files.readAllBytes(f)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Changes with the face, for cache busting; empty when there is no face. */
    public Optional<String> version(UUID villager) {
        String v = versions.get(villager);
        if (v != null) {
            return Optional.of(v);
        }
        try {
            Path f = dir.resolve(villager + ".version");
            if (Files.isRegularFile(f) && Files.isRegularFile(dir.resolve(villager + ".png"))) {
                v = Files.readString(f, StandardCharsets.UTF_8).trim();
                versions.put(villager, v);
                return Optional.of(v);
            }
        } catch (IOException ignored) {
            // no face
        }
        return Optional.empty();
    }

    private FaceSpec spec(Entity entity) {
        FaceSpec mca = mcaSpecs.apply(entity);
        if (mca != null) {
            return mca;
        }
        FaceSpec citizen = colonySpecs.apply(entity);
        if (citizen != null) {
            return citizen;
        }
        if (entity instanceof WanderingTrader) {
            return new FaceSpec.Villager(List.of("minecraft:textures/entity/wandering_trader.png"));
        }
        if (entity instanceof Villager v) {
            VillagerData data = v.getVillagerData();
            List<String> layers = new ArrayList<>();
            layers.add("minecraft:textures/entity/villager/villager.png");
            ResourceLocation type = BuiltInRegistries.VILLAGER_TYPE.getKey(data.getType());
            layers.add(type.getNamespace() + ":textures/entity/villager/type/" + type.getPath() + ".png");
            if (data.getProfession() != VillagerProfession.NONE) {
                ResourceLocation prof = BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession());
                layers.add(prof.getNamespace() + ":textures/entity/villager/profession/" + prof.getPath() + ".png");
            }
            return new FaceSpec.Villager(layers);
        }
        return null;
    }

    private static String hash(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    public void shutdown() {
        worker.shutdownNow();
    }
}

package dev.eiriksb.theywilltalk.faces;

import net.neoforged.fml.ModList;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Entity textures for drawing faces on the server. Mod textures come from the mods' own jars; Minecraft's are only in
 * the client jar, which dedicated servers don't have, so they come from the game when it has them (dev runs,
 * singleplayer) and otherwise from the client jar BlueMap downloads for its own rendering.
 */
final class FaceTextures {
    private final Path gameDir;
    private final Map<String, Optional<byte[]>> files = new ConcurrentHashMap<>();
    private final Map<String, Optional<BufferedImage>> images = new ConcurrentHashMap<>();
    private volatile FileSystem clientJar;
    private volatile boolean clientJarChecked;

    FaceTextures(Path gameDir) {
        this.gameDir = gameDir;
    }

    /** A texture by resource id ({@code namespace:path}). */
    Optional<BufferedImage> image(String id) {
        return images.computeIfAbsent(id, k -> bytes(k).flatMap(b -> {
            try {
                return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(b)));
            } catch (IOException e) {
                return Optional.empty();
            }
        }));
    }

    Optional<String> text(String id) {
        return bytes(id).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    boolean exists(String id) {
        return bytes(id).isPresent();
    }

    private Optional<byte[]> bytes(String id) {
        return files.computeIfAbsent(id, this::load);
    }

    private Optional<byte[]> load(String id) {
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        if (path.contains("..")) {
            return Optional.empty();
        }
        String[] rel = ("assets/" + ns + "/" + path).split("/");
        var mod = ModList.get().getModFileById(ns);
        if (mod != null) {
            Path p = mod.getFile().findResource(rel);
            if (Files.isRegularFile(p)) {
                return read(p);
            }
        }
        if (ns.equals("minecraft")) {
            FileSystem jar = clientJar();
            if (jar != null) {
                Path p = jar.getPath(String.join("/", rel));
                if (Files.isRegularFile(p)) {
                    return read(p);
                }
            }
        }
        return Optional.empty();
    }

    /** BlueMap's copy of the Minecraft client jar ({@code <server>/bluemap/minecraft-client-*.jar}), if any. */
    private FileSystem clientJar() {
        if (clientJarChecked) {
            return clientJar;
        }
        synchronized (this) {
            if (!clientJarChecked) {
                clientJarChecked = true;
                Path dir = gameDir.resolve("bluemap");
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> jars = Files.list(dir)) {
                        Path jar = jars.filter(p -> p.getFileName().toString().matches("minecraft-client-.*\\.jar")).sorted().reduce((a, b) -> b).orElse(null);
                        if (jar != null) {
                            clientJar = FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), Map.of());
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // no vanilla faces then
                    }
                }
            }
        }
        return clientJar;
    }

    private volatile long lastRecheck;

    /** BlueMap may download its jar after we first looked (checked at most once a minute). */
    void recheckClientJar() {
        long now = System.currentTimeMillis();
        if (clientJar == null && now - lastRecheck > 60_000) {
            lastRecheck = now;
            clientJarChecked = false;
            files.keySet().removeIf(k -> k.startsWith("minecraft:"));
            images.keySet().removeIf(k -> k.startsWith("minecraft:"));
        }
    }

    private static Optional<byte[]> read(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            return Optional.of(in.readAllBytes());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}

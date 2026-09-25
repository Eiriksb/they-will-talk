package dev.eiriksb.theywilltalk.voice;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks a downloaded archive for the mod's model installer. It runs here, in the voice server's jar, so the mod
 * itself doesn't have to ship an archive library next to Minecraft's own copy.
 *
 * <pre>
 *   java -cp voice-server.jar dev.eiriksb.theywilltalk.voice.Unpack --archive a.tar.bz2 --format tar.bz2 --into dir
 *        [--strip 1] [--flatten] [--include glob]... [--exclude glob]...
 * </pre>
 *
 * Globs without a '/' match the file name, others the path inside the archive (after --strip); {@code *} stays within
 * one directory, {@code **} crosses directories. Prints {@code UNPACKED <files>} and exits 0 on success.
 */
public final class Unpack {
    private Unpack() {}

    public static void main(String[] args) {
        try {
            Options o = Options.parse(args);
            int files = unpack(o);
            System.out.println("UNPACKED " + files);
        } catch (Exception e) {
            System.err.println("Unpack failed: " + e);
            System.exit(1);
        }
    }

    /** A glob with a '/' matches the path inside the archive, one without matches the file name. */
    record Glob(boolean path, Pattern regex) {
        boolean matches(String path, String file) {
            return regex.matcher(this.path ? path : file).matches();
        }
    }

    record Options(Path archive, String format, Path into, int strip, boolean flatten, List<Glob> include, List<Glob> exclude) {
        static Options parse(String[] args) {
            Path archive = null;
            Path into = null;
            String format = null;
            int strip = 0;
            boolean flatten = false;
            List<Glob> include = new ArrayList<>();
            List<Glob> exclude = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--archive" -> archive = Path.of(args[++i]);
                    case "--format" -> format = args[++i];
                    case "--into" -> into = Path.of(args[++i]);
                    case "--strip" -> strip = Integer.parseInt(args[++i]);
                    case "--flatten" -> flatten = true;
                    case "--include" -> include.add(glob(args[++i]));
                    case "--exclude" -> exclude.add(glob(args[++i]));
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            if (archive == null || into == null || format == null) {
                throw new IllegalArgumentException("--archive, --format and --into are required");
            }
            return new Options(archive, format, into.toAbsolutePath().normalize(), strip, flatten, include, exclude);
        }
    }

    static int unpack(Options o) throws IOException {
        Files.createDirectories(o.into());
        if (o.format().equals("zip")) {
            return unzip(o);
        }
        int files = 0;
        List<TarArchiveEntry> links = new ArrayList<>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(decompress(o))) {
            TarArchiveEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = relative(entry.getName(), o.strip());
                if (name == null || entry.isDirectory() || !selected(name, o)) {
                    continue;
                }
                if (entry.isSymbolicLink()) {
                    links.add(entry);
                } else if (entry.isFile()) { // skip hard links, devices, fifos: nothing we need
                    Path target = target(o, name);
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    permissions(target, entry.getMode());
                    files++;
                }
            }
        }
        return files + links(o, links);
    }

    private static InputStream decompress(Options o) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(o.archive()), 1 << 16);
        return switch (o.format()) {
            case "tar.gz" -> new GzipCompressorInputStream(raw, true);
            case "tar.bz2" -> new BZip2CompressorInputStream(raw, true);
            case "tar" -> raw;
            default -> throw new IllegalArgumentException("unknown archive format " + o.format());
        };
    }

    private static int unzip(Options o) throws IOException {
        int files = 0;
        try (ZipFile zip = new ZipFile(o.archive().toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = relative(entry.getName(), o.strip());
                if (name == null || entry.isDirectory() || !selected(name, o)) {
                    continue;
                }
                Path target = target(o, name);
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                files++;
            }
        }
        return files;
    }

    /**
     * Symlinks go last, and only to files inside the target folder (shared libraries: libfoo.so -> libfoo.so.1 ->
     * libfoo.so.1.2). Links can point at other links, so keep going while that makes progress.
     */
    private static int links(Options o, List<TarArchiveEntry> links) throws IOException {
        int made = 0;
        boolean progress = true;
        while (!links.isEmpty() && progress) {
            progress = false;
            for (Iterator<TarArchiveEntry> it = links.iterator(); it.hasNext(); ) {
                TarArchiveEntry link = it.next();
                Path target = target(o, relative(link.getName(), o.strip()));
                Path pointsTo = o.flatten() ? Path.of(Path.of(link.getLinkName()).getFileName().toString()) : Path.of(link.getLinkName());
                Path resolved = target.getParent().resolve(pointsTo).normalize();
                if (!resolved.startsWith(o.into())) {
                    it.remove();
                    continue;
                }
                if (!Files.exists(resolved)) {
                    continue;
                }
                Files.deleteIfExists(target);
                try {
                    Files.createSymbolicLink(target, pointsTo);
                } catch (UnsupportedOperationException | IOException e) {
                    Files.copy(resolved, target, StandardCopyOption.REPLACE_EXISTING);
                }
                it.remove();
                made++;
                progress = true;
            }
        }
        return made;
    }

    /** The entry's path with {@code strip} leading folders removed; null when nothing is left. */
    static String relative(String entryName, int strip) {
        String[] parts = entryName.replace('\\', '/').replaceAll("^\\./", "").split("/");
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            if (i >= strip && !parts[i].isEmpty() && !parts[i].equals(".")) {
                kept.add(parts[i]);
            }
        }
        return kept.isEmpty() ? null : String.join("/", kept);
    }

    static boolean selected(String path, Options o) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        if (!o.include().isEmpty() && o.include().stream().noneMatch(g -> g.matches(path, file))) {
            return false;
        }
        return o.exclude().stream().noneMatch(g -> g.matches(path, file));
    }

    static Glob glob(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                re.append(".*");
                i++;
            } else if (c == '*') {
                re.append("[^/]*");
            } else if (c == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return new Glob(glob.contains("/"), Pattern.compile(re.toString()));
    }

    private static Path target(Options o, String name) throws IOException {
        Path target = (o.flatten() ? o.into().resolve(name.substring(name.lastIndexOf('/') + 1)) : o.into().resolve(name)).normalize();
        if (!target.startsWith(o.into()) || target.equals(o.into())) {
            throw new IOException("archive entry escapes the target folder: " + name);
        }
        return target;
    }

    private static void permissions(Path file, int mode) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
        if ((mode & 0100) != 0) {
            perms.addAll(EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
        }
        try {
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException | UnsupportedOperationException ignored) {
            // best effort
        }
    }
}

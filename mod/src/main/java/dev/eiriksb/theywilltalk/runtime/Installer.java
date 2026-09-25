package dev.eiriksb.theywilltalk.runtime;

import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.runtime.Catalog.FileSpec;
import dev.eiriksb.theywilltalk.runtime.Catalog.Package;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Downloads and installs catalogue packages into the runtime folder, one at a time, only when an admin asks for them.
 * Downloads resume after an interruption, are checked against their pinned SHA-256 before anything is unpacked, and
 * archives are unpacked into a staging folder first so a failed install never leaves half a package behind.
 */
public final class Installer {
    public enum Phase { QUEUED, DOWNLOADING, VERIFYING, UNPACKING, DONE, FAILED, CANCELLED }

    public static final class Job {
        public final Package pkg;
        volatile Phase phase = Phase.QUEUED;
        volatile long done;
        volatile long total;
        volatile long bytesPerSecond;
        volatile String error = "";
        volatile boolean cancelled;

        Job(Package pkg) {
            this.pkg = pkg;
        }

        public Phase phase() {
            return phase;
        }

        public long done() {
            return done;
        }

        public long total() {
            return total;
        }

        public long bytesPerSecond() {
            return bytesPerSecond;
        }

        public String error() {
            return error;
        }

        public boolean active() {
            return phase == Phase.QUEUED || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING || phase == Phase.UNPACKING;
        }
    }

    private final Path runtimeDir;
    private final Path downloads;
    private final Path staging;
    private final Platform platform;
    private final Catalog catalog;
    private final Supplier<Path> helperJar;
    private final Consumer<List<Package>> onInstalled;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Deque<Job> queue = new ArrayDeque<>();
    private final List<Package> installedInBatch = new ArrayList<>();
    private boolean working;

    /**
     * @param helperJar   the voice server jar, which also unpacks archives
     * @param onInstalled called (off the server thread) with what got installed once the queue runs empty
     */
    public Installer(Path runtimeDir, Platform platform, Catalog catalog, Supplier<Path> helperJar, Consumer<List<Package>> onInstalled) {
        this.runtimeDir = runtimeDir;
        this.downloads = runtimeDir.resolve(".twt/downloads");
        this.staging = runtimeDir.resolve(".twt/staging");
        this.platform = platform;
        this.catalog = catalog;
        this.helperJar = helperJar;
        this.onInstalled = onInstalled;
    }

    public boolean installed(Package pkg) {
        return !pkg.check().isEmpty() && pkg.check().stream().allMatch(c -> Files.exists(path(c)));
    }

    public synchronized Job job(String id) {
        return jobs.get(id);
    }

    public synchronized boolean busy() {
        return jobs.values().stream().anyMatch(Job::active);
    }

    /** Queues a package and whatever it needs that isn't installed yet. */
    public synchronized void install(String id) {
        Package pkg = catalog.get(id).orElseThrow(() -> new IllegalArgumentException("unknown package " + id));
        if (!pkg.available(platform)) {
            throw new IllegalArgumentException(pkg.name() + " can't be downloaded on " + platform.id);
        }
        for (String req : pkg.requires()) {
            Package dep = catalog.get(req).orElseThrow();
            if (!installed(dep) && !dep.available(platform)) {
                throw new IllegalArgumentException(pkg.name() + " needs " + dep.name() + ", which can't be downloaded yet");
            }
        }
        for (String req : pkg.requires()) {
            Package dep = catalog.get(req).orElseThrow();
            if (!installed(dep)) {
                install(req);
            }
        }
        Job existing = jobs.get(id);
        if (existing != null && existing.active()) {
            return;
        }
        Job job = new Job(pkg);
        job.total = pkg.downloadSize(platform);
        jobs.put(id, job);
        queue.add(job);
        if (!working) {
            working = true;
            Thread.ofVirtual().name("twt-installer").start(this::work);
        }
    }

    public synchronized void cancel(String id) {
        Job job = jobs.get(id);
        if (job != null && job.active()) {
            job.cancelled = true;
            if (queue.remove(job)) {
                job.phase = Phase.CANCELLED;
            }
        }
    }

    /** Deletes an installed package's files. */
    public synchronized void remove(String id) throws IOException {
        Package pkg = catalog.get(id).orElseThrow(() -> new IllegalArgumentException("unknown package " + id));
        Job job = jobs.get(id);
        if (job != null && job.active()) {
            throw new IllegalStateException(pkg.name() + " is being installed");
        }
        for (FileSpec f : pkg.files(platform)) {
            Path into = path(f.into());
            if (f.archive() != null) {
                deleteTree(into);
            } else {
                Files.deleteIfExists(into.resolve(f.fileName()));
            }
        }
        for (String check : pkg.check()) {
            Files.deleteIfExists(path(check));
        }
        jobs.remove(id);
        TheyWillTalk.LOGGER.info("Removed {}", pkg.name());
    }

    private void work() {
        while (true) {
            Job job;
            List<Package> done = null;
            synchronized (this) {
                job = queue.poll();
                if (job == null) {
                    working = false;
                    if (!installedInBatch.isEmpty()) {
                        done = List.copyOf(installedInBatch);
                        installedInBatch.clear();
                    }
                }
            }
            if (job == null) {
                if (done != null) {
                    onInstalled.accept(done);
                }
                return;
            }
            run(job);
        }
    }

    private void run(Job job) {
        Package pkg = job.pkg;
        try {
            for (String req : pkg.requires()) {
                Package dep = catalog.get(req).orElseThrow();
                if (!installed(dep)) {
                    throw new IOException("it needs " + dep.name() + ", which didn't install");
                }
            }
            List<FileSpec> files = pkg.files(platform);
            checkDiskSpace(files);
            TheyWillTalk.LOGGER.info("Downloading {} ({} MB)", pkg.name(), job.total / 1_000_000);
            Map<FileSpec, Path> fetched = new LinkedHashMap<>();
            job.phase = Phase.DOWNLOADING;
            for (FileSpec f : files) {
                if (f.download()) {
                    fetched.put(f, download(f, job));
                }
            }
            job.phase = Phase.UNPACKING;
            int n = 0;
            for (FileSpec f : files) {
                place(pkg, f, fetched.get(f), n++, job);
            }
            job.phase = Phase.DONE;
            synchronized (this) {
                installedInBatch.add(pkg);
            }
            TheyWillTalk.LOGGER.info("Installed {}", pkg.name());
        } catch (CancellationException e) {
            job.phase = Phase.CANCELLED;
            pkg.files(platform).stream().filter(FileSpec::download).forEach(f -> deleteQuietly(downloads.resolve(f.sha256() + ".part")));
        } catch (Exception e) {
            job.phase = Phase.FAILED;
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            TheyWillTalk.LOGGER.warn("Installing {} failed: {}", pkg.name(), job.error);
        }
    }

    private void checkDiskSpace(List<FileSpec> files) throws IOException {
        long need = 0;
        for (FileSpec f : files) {
            if (f.download() && !Files.exists(downloads.resolve(f.sha256()))) {
                need += f.size();
            }
            if (f.archive() != null) {
                need += f.size() * 3; // unpacked size, roughly
            }
        }
        Files.createDirectories(downloads);
        long free = Files.getFileStore(downloads).getUsableSpace();
        if (free < need) {
            throw new IOException(String.format("not enough disk space: needs about %.1f GB, %.1f GB free", need / 1e9, free / 1e9));
        }
    }

    /** Downloads (or resumes) one file and checks its SHA-256. */
    private Path download(FileSpec f, Job job) throws IOException, InterruptedException {
        Path verified = downloads.resolve(f.sha256());
        if (Files.exists(verified) && Files.size(verified) == f.size()) {
            job.done += f.size(); // downloaded and checked before, but the install didn't finish
            return verified;
        }
        Path part = downloads.resolve(f.sha256() + ".part");
        long have = Files.exists(part) ? Files.size(part) : 0;
        if (have > f.size()) {
            Files.delete(part);
            have = 0;
        }
        job.done += have;
        if (have < f.size()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(f.url())).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "TheyWillTalk (Minecraft mod; model installer)");
            if (have > 0) {
                request.header("Range", "bytes=" + have + "-");
            }
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            int code = response.statusCode();
            if (code == 200 && have > 0) { // the server ignored the range: start over
                job.done -= have;
                have = 0;
            } else if (code != 200 && code != 206) {
                response.body().close();
                throw new IOException("HTTP " + code + " downloading " + f.fileName() + " from " + response.uri().getHost());
            }
            if (!"https".equals(response.uri().getScheme())) {
                response.body().close();
                throw new IOException("refusing a non-https redirect for " + f.fileName());
            }
            StandardOpenOption[] mode = have > 0
                    ? new StandardOpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
            long written = have;
            long windowStart = System.nanoTime();
            long windowBytes = 0;
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part, mode)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (job.cancelled) {
                        throw new CancellationException();
                    }
                    written += n;
                    if (written > f.size()) {
                        throw new IOException(f.fileName() + " is larger than expected");
                    }
                    out.write(buf, 0, n);
                    job.done += n;
                    windowBytes += n;
                    long elapsed = System.nanoTime() - windowStart;
                    if (elapsed > 1_000_000_000L) {
                        job.bytesPerSecond = windowBytes * 1_000_000_000L / elapsed;
                        windowStart = System.nanoTime();
                        windowBytes = 0;
                    }
                }
            }
            if (written != f.size()) {
                throw new IOException("download of " + f.fileName() + " stopped early; try again to resume it");
            }
        }
        job.phase = Phase.VERIFYING;
        job.bytesPerSecond = 0;
        String actual = sha256(part);
        if (!actual.equals(f.sha256())) {
            Files.delete(part);
            throw new IOException("checksum mismatch for " + f.fileName() + " (corrupted download, or the file changed upstream)");
        }
        Files.move(part, verified, StandardCopyOption.REPLACE_EXISTING);
        job.phase = Phase.DOWNLOADING;
        return verified;
    }

    /** Moves a downloaded file into place, unpacks an archive, or copies a file shipped in the mod jar. */
    private void place(Package pkg, FileSpec f, Path fetched, int index, Job job) throws IOException, InterruptedException {
        Path into = path(f.into());
        if (f.resource() != null) {
            try (InputStream in = Installer.class.getResourceAsStream("/assets/theywilltalk/runtime/" + f.resource())) {
                if (in == null) {
                    throw new IOException("missing bundled file " + f.resource());
                }
                Files.createDirectories(into);
                Files.copy(in, into.resolve(f.fileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        } else if (f.archive() == null) {
            Files.createDirectories(into);
            Files.move(fetched, into.resolve(f.fileName()), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Path tmp = staging.resolve(pkg.id() + "-" + index);
            deleteTree(tmp);
            unpack(f, fetched, tmp, job);
            deleteTree(into);
            Files.createDirectories(into.getParent());
            Files.move(tmp, into);
            Files.delete(fetched);
        }
    }

    private void unpack(FileSpec f, Path archive, Path into, Job job) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(RuntimeManager.javaExecutable().toString(), "-cp", helperJar.get().toString(),
                "dev.eiriksb.theywilltalk.voice.Unpack", "--archive", archive.toString(), "--format", f.archive(),
                "--into", into.toString(), "--strip", Integer.toString(f.strip())));
        if (f.flatten()) {
            cmd.add("--flatten");
        }
        f.include().forEach(g -> cmd.addAll(List.of("--include", g)));
        f.exclude().forEach(g -> cmd.addAll(List.of("--exclude", g)));
        Files.createDirectories(staging);
        Path log = staging.resolve("unpack.log");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        while (!p.waitFor(1, TimeUnit.SECONDS)) {
            if (job.cancelled) {
                p.destroyForcibly();
                throw new CancellationException();
            }
        }
        String out = Files.readString(log).trim();
        if (p.exitValue() != 0 || !out.contains("UNPACKED")) {
            throw new IOException("unpacking " + f.fileName() + " failed: " + out);
        }
    }

    private Path path(String relative) {
        Path p = runtimeDir.resolve(platform.expand(relative)).normalize();
        if (!p.startsWith(runtimeDir)) {
            throw new IllegalArgumentException("path escapes the runtime folder: " + relative);
        }
        return p;
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // leftover partial download; overwritten next time
        }
    }
}

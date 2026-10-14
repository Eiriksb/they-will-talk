package dev.eiriksb.theywilltalk.runtime;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One AI helper process (LLM server, voice server...): started with its output in {@code logs/theywilltalk/<name>.log},
 * READY once its health URL answers 200, restarted a few times if it crashes after having been ready.
 * <p>
 * It never outlives the game, even one that's killed outright (a crash, kill -9), which would leave it holding the
 * GPU's memory: on Linux and macOS it runs under a small sh guard ({@link #GUARD}) that stops it when the game's end of
 * its stdin pipe closes; and its pid is kept in {@code <name>.pid} next to its log, so one left over from before (on
 * Windows, or if the guard went too) is stopped the next time it's started.
 */
public final class ManagedProcess {
    public enum State { STOPPED, STARTING, READY, FAILED }

    private static final Logger LOGGER = LoggerFactory.getLogger("TheyWillTalk");

    /**
     * Runs the program ("$@") in the background and waits for it; a reader on the game's pipe (kept as fd 3: a
     * background job's stdin is /dev/null) gets end-of-file when the game is gone, however it went, and stops it.
     * Stopping the guard stops the program too; its exit code is the program's.
     */
    static final String GUARD = """
            exec 3<&0
            "$@" &
            c=$!
            trap 'kill $c 2>/dev/null' TERM INT HUP
            ( read -r _ <&3; kill $c 2>/dev/null ) &
            w=$!
            wait $c
            s=$?
            kill $w 2>/dev/null
            exit $s
            """;
    private static final int MAX_RESTARTS = 3;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final String name;
    private final Path logFile;

    private volatile State state = State.STOPPED;
    private volatile String lastError = "";
    private volatile Process process;
    private volatile long startedAt;

    private List<String> command;
    private Map<String, String> env;
    private Path workDir;
    private URI health;
    private Duration startTimeout;
    private boolean stopping;
    private int restarts;

    public ManagedProcess(String name, Path logFile) {
        this.name = name;
        this.logFile = logFile;
    }

    public synchronized void start(List<String> command, Map<String, String> env, Path workDir, URI health, Duration startTimeout) {
        stop();
        this.command = List.copyOf(command);
        this.env = Map.copyOf(env);
        this.workDir = workDir;
        this.health = health;
        this.startTimeout = startTimeout;
        this.restarts = 0;
        launch();
    }

    private synchronized void launch() {
        stopping = false;
        lastError = "";
        try {
            Files.createDirectories(logFile.getParent());
            if (Files.exists(logFile)) {
                Files.move(logFile, logFile.resolveSibling(logFile.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(logFile, "$ " + String.join(" ", command) + System.lineSeparator());
            stopLeftover();
            ProcessBuilder pb = new ProcessBuilder(guarded(command)).directory(workDir.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.environment().putAll(env);
            Process p = pb.start();
            process = p;
            startedAt = System.currentTimeMillis();
            state = State.STARTING;
            LOGGER.info("[{}] starting (pid {})", name, p.pid());
            Thread.ofVirtual().name("twt-" + name + "-health").start(() -> awaitReady(p));
            p.onExit().thenAccept(this::exited);
        } catch (IOException e) {
            process = null;
            fail("could not start: " + e.getMessage());
        }
    }

    private void awaitReady(Process p) {
        long deadline = System.nanoTime() + startTimeout.toNanos();
        HttpRequest request = HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(3)).GET().build();
        while (p.isAlive() && process == p && System.nanoTime() < deadline) {
            try {
                if (HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    ready(p);
                    return;
                }
            } catch (IOException ignored) {
                // not listening yet
            } catch (InterruptedException e) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
        synchronized (this) {
            if (p.isAlive() && process == p && state == State.STARTING) {
                fail("did not become ready within " + startTimeout.toSeconds() + " s");
                stopping = true;
                p.destroyForcibly();
            }
        }
    }

    private synchronized void ready(Process p) {
        if (process == p && state == State.STARTING) {
            state = State.READY;
            remember(p);
            LOGGER.info("[{}] ready in {} s", name, (System.currentTimeMillis() - startedAt) / 1000);
        }
    }

    private synchronized void exited(Process p) {
        if (process != p) {
            return;
        }
        forget();
        if (stopping) {
            if (state != State.FAILED) {
                state = State.STOPPED;
            }
            return;
        }
        boolean wasReady = state == State.READY;
        String last = lastLogLine();
        fail("exited with code " + p.exitValue() + (last.isEmpty() ? "" : ": " + last));
        if (wasReady && restarts < MAX_RESTARTS) {
            restarts++;
            long delay = 5_000L * restarts;
            LOGGER.warn("[{}] restarting in {} s", name, delay / 1000);
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (this) {
                    if (process == p && !stopping) {
                        launch();
                    }
                }
            });
        }
    }

    private void fail(String error) {
        state = State.FAILED;
        lastError = error;
        LOGGER.warn("[{}] {} (see {})", name, error, logFile);
    }

    public synchronized void stop() {
        Process p = process;
        if (p == null) {
            if (state != State.FAILED) {
                state = State.STOPPED;
            }
            return;
        }
        stopping = true;
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process = null;
        forget();
        state = State.STOPPED;
        lastError = "";
    }

    // ---- never outliving the game -----------------------------------------------------------------------------

    /** The command under the guard, where there's a POSIX sh (not Windows). */
    static List<String> guarded(List<String> command) {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows") || !Files.isExecutable(Path.of("/bin/sh"))) {
            return command;
        }
        List<String> out = new ArrayList<>(List.of("/bin/sh", "-c", GUARD, "twt-guard"));
        out.addAll(command);
        return out;
    }

    /** The program itself: under the guard, its child that isn't the guard's watcher (another sh), else the first started. */
    private static ProcessHandle program(Process p) {
        String guard = p.info().command().orElse("");
        List<ProcessHandle> children = p.children().sorted(java.util.Comparator.comparingLong(ProcessHandle::pid)).toList();
        return children.stream().filter(c -> !c.info().command().orElse(guard).equals(guard)).findFirst()
                .or(() -> children.stream().findFirst()).orElse(p.toHandle());
    }

    private Path pidFile() {
        return logFile.resolveSibling(name + ".pid");
    }

    /** Notes which process is ours (pid, when it started, what it is), for {@link #stopLeftover}. */
    private void remember(Process p) {
        ProcessHandle h = program(p);
        try {
            Files.writeString(pidFile(), h.pid() + " " + h.info().startInstant().map(i -> i.toEpochMilli()).orElse(0L) + " "
                    + h.info().command().orElse(""));
        } catch (IOException e) {
            LOGGER.debug("[{}] couldn't write {}: {}", name, pidFile(), e.toString());
        }
    }

    private void forget() {
        try {
            Files.deleteIfExists(pidFile());
        } catch (IOException ignored) {
            // it's checked before it's trusted anyway
        }
    }

    /**
     * Stops the process this one left behind last time, if it's still running: only when it's the very same process
     * (same pid, started at the same moment, the same program), never some other one that got its pid since.
     */
    void stopLeftover() {
        Path file = pidFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            String[] f = Files.readString(file).trim().split(" ", 3);
            long pid = Long.parseLong(f[0]), started = Long.parseLong(f[1]);
            String what = f.length > 2 ? f[2] : "";
            Optional<ProcessHandle> h = ProcessHandle.of(pid);
            if (h.isPresent() && h.get().isAlive() && started != 0 && h.get().info().startInstant().map(i -> i.toEpochMilli()).orElse(-1L) == started
                    && h.get().info().command().orElse("").equals(what)) {
                LOGGER.warn("[{}] stopping the one left running from before (pid {})", name, pid);
                h.get().descendants().forEach(ProcessHandle::destroyForcibly);
                h.get().destroyForcibly();
                h.get().onExit().get(5, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            LOGGER.debug("[{}] couldn't check {}: {}", name, file, e.toString());
        }
        forget();
    }

    public String name() {
        return name;
    }

    public State state() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    /** The program's pid (not the guard's). */
    public long pid() {
        Process p = process;
        return p == null ? -1 : program(p).pid();
    }

    public long uptimeMs() {
        Process p = process;
        return p != null && p.isAlive() ? System.currentTimeMillis() - startedAt : 0;
    }

    public Path logFile() {
        return logFile;
    }

    /** The last {@code lines} lines of the log (reads at most the last 64 KB). */
    public List<String> tailLog(int lines) {
        try (RandomAccessFile f = new RandomAccessFile(logFile.toFile(), "r")) {
            long len = f.length();
            long from = Math.max(0, len - 65_536);
            byte[] buf = new byte[(int) (len - from)];
            f.seek(from);
            f.readFully(buf);
            List<String> all = new ArrayList<>(Arrays.asList(new String(buf, StandardCharsets.UTF_8).split("\\R")));
            if (from > 0 && !all.isEmpty()) {
                all.removeFirst(); // probably cut in half
            }
            return all.subList(Math.max(0, all.size() - lines), all.size());
        } catch (IOException e) {
            return List.of();
        }
    }

    private String lastLogLine() {
        List<String> tail = tailLog(20);
        for (int i = tail.size() - 1; i >= 0; i--) {
            String line = tail.get(i).trim();
            if (!line.isEmpty()) {
                return line.length() > 300 ? line.substring(0, 300) + "..." : line;
            }
        }
        return "";
    }
}

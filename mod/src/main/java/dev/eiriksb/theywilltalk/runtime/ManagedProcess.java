package dev.eiriksb.theywilltalk.runtime;

import dev.eiriksb.theywilltalk.TheyWillTalk;

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
import java.util.concurrent.TimeUnit;

/**
 * One AI helper process (LLM server, voice server...): started with its output in {@code logs/theywilltalk/<name>.log},
 * READY once its health URL answers 200, restarted a few times if it crashes after having been ready.
 */
public final class ManagedProcess {
    public enum State { STOPPED, STARTING, READY, FAILED }

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
            ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.environment().putAll(env);
            Process p = pb.start();
            process = p;
            startedAt = System.currentTimeMillis();
            state = State.STARTING;
            TheyWillTalk.LOGGER.info("[{}] starting (pid {})", name, p.pid());
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
            TheyWillTalk.LOGGER.info("[{}] ready in {} s", name, (System.currentTimeMillis() - startedAt) / 1000);
        }
    }

    private synchronized void exited(Process p) {
        if (process != p) {
            return;
        }
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
            TheyWillTalk.LOGGER.warn("[{}] restarting in {} s", name, delay / 1000);
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
        TheyWillTalk.LOGGER.warn("[{}] {} (see {})", name, error, logFile);
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
        state = State.STOPPED;
        lastError = "";
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

    public long pid() {
        Process p = process;
        return p == null ? -1 : p.pid();
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

package dev.eiriksb.theywilltalk.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The AI helper processes never outlive the game (they'd hold the GPU's memory). */
class ManagedProcessTest {
    /** A stand-in for the game: starts a long sleep the way the runtime starts a server, says its pid, and waits to be killed. */
    public static void main(String[] args) throws Exception {
        ManagedProcess p = new ManagedProcess("sleeper", Path.of(args[0]).resolve("sleeper.log"));
        p.start(List.of("sleep", "300"), Map.of(), Path.of(args[0]), URI.create("http://127.0.0.1:9/"), Duration.ofMinutes(10));
        long pid = p.pid();
        for (int i = 0; i < 100 && (pid == -1 || ProcessHandle.of(pid).flatMap(h -> h.info().command()).map(c -> !c.endsWith("sleep")).orElse(true)); i++) {
            Thread.sleep(50);
            pid = p.pid();
        }
        System.out.println(pid);
        System.out.flush();
        Thread.sleep(600_000);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theProgramStopsWhenTheGameIsKilled(@TempDir Path dir) throws Exception {
        Process game = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                ManagedProcessTest.class.getName(), dir.toString()).redirectErrorStream(true).start();
        long pid;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(game.getInputStream()))) {
            String line;
            do {
                line = out.readLine();
            } while (line != null && !line.matches("\\d+"));
            assertTrue(line != null, "the stand-in game said the sleep's pid");
            pid = Long.parseLong(line);
            ProcessHandle sleep = ProcessHandle.of(pid).orElseThrow();
            assertTrue(sleep.isAlive(), "the sleep runs");
            game.destroyForcibly(); // kill -9: no shutdown hooks, nothing tidied
            game.waitFor(10, TimeUnit.SECONDS);
            sleep.onExit().get(10, TimeUnit.SECONDS);
            assertFalse(sleep.isAlive(), "the sleep went with the game");
        } finally {
            game.destroyForcibly();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void oneLeftFromBeforeIsStoppedNextTime(@TempDir Path dir) throws Exception {
        Process left = new ProcessBuilder("sleep", "300").start(); // as if a game crashed with it running, unguarded
        Process other = new ProcessBuilder("sleep", "301").start();
        try {
            ProcessHandle h = left.toHandle();
            Files.writeString(dir.resolve("sleeper.pid"), h.pid() + " " + h.info().startInstant().orElseThrow().toEpochMilli() + " "
                    + h.info().command().orElseThrow());
            ManagedProcess p = new ManagedProcess("sleeper", dir.resolve("sleeper.log"));
            p.stopLeftover();
            assertTrue(left.waitFor(5, TimeUnit.SECONDS), "the one left over is stopped");
            assertFalse(Files.exists(dir.resolve("sleeper.pid")), "and forgotten");

            // a pid that's someone else's now (started at another time) is left alone
            ProcessHandle o = other.toHandle();
            Files.writeString(dir.resolve("sleeper.pid"), o.pid() + " 12345 " + o.info().command().orElseThrow());
            p.stopLeftover();
            assertTrue(other.isAlive(), "not ours: left running");
        } finally {
            left.destroyForcibly();
            other.destroyForcibly();
        }
    }

    /** A program that crashes after running out of GPU memory says so, not just its exit code. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runningOutOfGpuMemoryIsSaid(@TempDir Path dir) throws Exception {
        ManagedProcess p = new ManagedProcess("cloner", dir.resolve("cloner.log"));
        p.start(List.of("sh", "-c", "echo '[GGML] cudaMalloc failed: out of memory'; echo '[SpkExtract] Extracted'; exit 139"), Map.of(), dir,
                URI.create("http://127.0.0.1:9/"), Duration.ofMinutes(1));
        for (int i = 0; i < 100 && p.state() != ManagedProcess.State.FAILED; i++) {
            Thread.sleep(50);
        }
        assertTrue(p.lastError() != null && p.lastError().contains("ran out of GPU memory") && p.lastError().contains("cudaMalloc"),
                "said: " + p.lastError());
    }
}

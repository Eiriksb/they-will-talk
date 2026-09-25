package dev.eiriksb.theywilltalk.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** NVIDIA GPU stats from {@code nvidia-smi}, cached for a couple of seconds (the dashboard asks often). */
public final class GpuInfo {
    public record Gpu(String name, int memoryUsedMb, int memoryTotalMb, int utilization, int temperature, double powerW) {}

    private static final long FRESH_MS = 2_000;
    /** Without nvidia-smi, don't try to spawn it on every request. */
    private static final long MISSING_RETRY_MS = 60_000;

    private static List<Gpu> cached = List.of();
    private static long cachedAt;
    private static boolean missing;

    private GpuInfo() {}

    public static synchronized List<Gpu> query() {
        long now = System.currentTimeMillis();
        if (cachedAt != 0 && now - cachedAt < (missing ? MISSING_RETRY_MS : FRESH_MS)) {
            return cached;
        }
        cachedAt = now;
        try {
            Process p = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=name,memory.used,memory.total,utilization.gpu,temperature.gpu,power.draw",
                    "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            // The output is a few lines, well within the pipe buffer, so waiting before reading can't deadlock.
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                cached = List.of();
                return cached;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            cached = p.exitValue() == 0 ? parse(out) : List.of();
            missing = false;
        } catch (IOException e) {
            cached = List.of();
            missing = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return cached;
    }

    static List<Gpu> parse(String csv) {
        List<Gpu> gpus = new ArrayList<>();
        for (String line : csv.split("\\R")) {
            String[] f = line.split(",\\s*");
            if (f.length < 6 || f[0].isBlank()) {
                continue;
            }
            gpus.add(new Gpu(f[0].trim(), (int) number(f[1]), (int) number(f[2]), (int) number(f[3]), (int) number(f[4]), number(f[5])));
        }
        return gpus;
    }

    /** nvidia-smi prints "[N/A]" or "[Not Supported]" for missing values. */
    private static double number(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

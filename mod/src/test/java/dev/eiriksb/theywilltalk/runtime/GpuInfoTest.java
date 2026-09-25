package dev.eiriksb.theywilltalk.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GpuInfoTest {
    @Test
    void parsesNvidiaSmiCsvIncludingMissingValues() {
        List<GpuInfo.Gpu> gpus = GpuInfo.parse("""
                NVIDIA GeForce RTX 5070 Ti, 4521, 16303, 37, 54, 88.12
                NVIDIA GeForce GTX 1060, 300, 6144, 0, 41, [N/A]
                """);
        assertEquals(2, gpus.size());
        assertEquals(new GpuInfo.Gpu("NVIDIA GeForce RTX 5070 Ti", 4521, 16303, 37, 54, 88.12), gpus.get(0));
        assertEquals(0.0, gpus.get(1).powerW());
    }
}

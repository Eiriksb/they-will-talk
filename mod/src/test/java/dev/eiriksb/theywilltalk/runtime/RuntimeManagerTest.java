package dev.eiriksb.theywilltalk.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeManagerTest {
    @Test
    void lessQuantizedModelsArePreferred() {
        int q4 = RuntimeManager.precision("qwen-talker-1.7b-base-Q4_K_M.gguf");
        int q8 = RuntimeManager.precision("qwen-talker-1.7b-base-Q8_0.gguf");
        int bf16 = RuntimeManager.precision("qwen-tokenizer-12hz-BF16.gguf");
        assertTrue(q4 > 0 && q4 < q8 && q8 < bf16, q4 + " " + q8 + " " + bf16);
    }
}

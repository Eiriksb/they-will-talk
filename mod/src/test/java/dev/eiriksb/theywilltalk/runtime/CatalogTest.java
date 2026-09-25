package dev.eiriksb.theywilltalk.runtime;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogTest {
    private final Catalog catalog = Catalog.load();

    @Test
    void everyPackageIsConsistent() {
        Set<String> ids = new HashSet<>();
        for (Catalog.Package p : catalog.packages()) {
            assertTrue(ids.add(p.id()), "duplicate id " + p.id());
            assertTrue(Set.of("programs", "brain", "voices").contains(p.group()), p.id());
            assertFalse(p.check().isEmpty(), p.id() + " needs check paths");
            assertTrue(p.available(Platform.LINUX_X64) || p.unavailable() != null, p.id() + " must say why it can't be downloaded");
            for (String req : p.requires()) {
                assertTrue(catalog.get(req).isPresent(), p.id() + " requires unknown " + req);
            }
            if (p.llmModel() != null) {
                assertEquals("models/llm/" + p.llmModel(), p.check().getFirst(), p.id());
                assertEquals(p.llmModel(), p.files(Platform.LINUX_X64).getFirst().fileName(), p.id());
            }
        }
    }

    @Test
    void theRecommendedSetupRunsOnLinuxAndWindows() {
        for (Platform platform : new Platform[]{Platform.LINUX_X64, Platform.WINDOWS_X64}) {
            assertTrue(catalog.get("llm-runtime").orElseThrow().available(platform), platform.id);
            assertTrue(catalog.get("gemma-4-e2b").orElseThrow().available(platform), platform.id);
            assertTrue(catalog.get("kokoro").orElseThrow().available(platform), platform.id);
        }
        assertFalse(catalog.get("llm-runtime").orElseThrow().available(Platform.UNSUPPORTED));
    }

    @Test
    void uncensoredModelsAreTaggedAndNotRecommended() {
        long uncensored = catalog.packages().stream().filter(p -> p.tagged("uncensored")).count();
        assertEquals(2, uncensored);
        catalog.packages().stream().filter(p -> p.tagged("uncensored")).forEach(p -> assertFalse(p.tagged("recommended"), p.id()));
    }

    @Test
    void onlyPinnedGithubAndHuggingFaceDownloadsAreAccepted() {
        String ok = """
                {"packages":[{"id":"x","group":"brain","check":["models/llm/a.gguf"],"platforms":{"any":[
                  {"url":"https://huggingface.co/a/b/resolve/0/a.gguf","sha256":"%s","size":1,"into":"models/llm"}]}}]}
                """;
        String sha = "0".repeat(64);
        assertNotNull(Catalog.parse(JsonParser.parseString(ok.formatted(sha)).getAsJsonObject()));
        assertThrows(IllegalStateException.class, () -> Catalog.parse(JsonParser.parseString(
                ok.formatted(sha).replace("https://huggingface.co", "https://example.com")).getAsJsonObject()));
        assertThrows(IllegalStateException.class, () -> Catalog.parse(JsonParser.parseString(ok.formatted("abc")).getAsJsonObject()));
        assertThrows(IllegalStateException.class, () -> Catalog.parse(JsonParser.parseString(
                ok.formatted(sha).replace("\"into\":\"models/llm\"", "\"into\":\"../../mods\"")).getAsJsonObject()));
    }
}

package dev.eiriksb.theywilltalk.audio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.eiriksb.theywilltalk.ai.QwenTtsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceBankTest {
    private static final UUID VILLAGER = UUID.fromString("7b0f7c1e-2a31-4c55-9b7e-0d3c6f1a2b44");

    @TempDir
    Path tmp;

    private final Fake designer = new Fake();
    private final Fake cloner = new Fake();

    @AfterEach
    void stop() {
        designer.http.stop(0);
        cloner.http.stop(0);
    }

    @Test
    void designsTheVoiceOnceAndSpeaksEveryMoodInIt() throws Exception {
        VoiceBank bank = bank();

        assertEquals("twt-" + VILLAGER + "-neutral", bank.voice(VILLAGER, "An old farmer", 42, "neutral"));
        assertEquals(1, designer.speech.size(), "the voice is designed once, from the neutral script");
        assertTrue(designer.speech.getFirst().get("instructions").getAsString().startsWith("An old farmer"));
        assertEquals(List.of("twt-" + VILLAGER + "-neutral"), cloner.registeredNames());
        assertTrue(cloner.voices.getFirst().has("ref_text"), "lines continue from the clip (with its transcript)");

        assertEquals("twt-" + VILLAGER + "-angry", bank.voice(VILLAGER, "An old farmer", 42, "annoyed"));
        assertEquals(1, designer.speech.size(), "moods don't design a new voice");
        JsonObject moodClip = cloner.speech.getFirst();
        assertEquals("twt-" + VILLAGER + "-timbre", moodClip.get("voice").getAsString(), "the mood clip is read in the locked voice");
        assertFalse(moodClip.has("instructions"), "the Base model takes no instructions");
        JsonObject timbre = cloner.voices.get(1);
        assertFalse(timbre.has("ref_text"), "the timbre registration uses the speaker only");

        int registrations = cloner.voices.size();
        bank.voice(VILLAGER, "An old farmer", 42, "angry");
        bank.voice(VILLAGER, "An old farmer", 42, "neutral");
        assertEquals(registrations, cloner.voices.size(), "nothing is registered twice");
        assertEquals(1, cloner.speech.size(), "nothing is generated twice");
        assertTrue(Files.isRegularFile(tmp.resolve(VILLAGER.toString()).resolve("angry.wav")));
    }

    @Test
    void aNewDescriptionMakesANewVoiceAndARestartedServerGetsItAgain() throws Exception {
        VoiceBank bank = bank();
        bank.voice(VILLAGER, "An old farmer", 42, "happy");

        bank.voice(VILLAGER, "A young baker", 42, "neutral");
        assertEquals(2, designer.speech.size(), "a changed description designs the voice again");
        assertFalse(Files.exists(tmp.resolve(VILLAGER.toString()).resolve("happy.wav")), "old mood clips are dropped");

        int before = cloner.voices.size();
        bank.forgetRegistrations();
        bank.voice(VILLAGER, "A young baker", 42, "neutral");
        assertEquals(before + 1, cloner.voices.size(), "registered again after the server forgot it");
        assertEquals(2, designer.speech.size(), "but the clip on disk is reused");
    }

    @Test
    void anUploadedClipReplacesTheDesignedVoice() throws Exception {
        VoiceBank bank = bank();
        bank.voice(VILLAGER, "An old farmer", 42, "neutral");
        assertEquals(1, designer.speech.size());

        bank.setCustomClip(VILLAGER, Wav.encode(new short[24_000 * 5], 24_000), "Hello there, I am Bob.");
        assertEquals(5.0, bank.customClip(VILLAGER).orElseThrow().seconds(), 0.001);
        bank.voice(VILLAGER, "An old farmer", 42, "neutral");
        assertEquals(1, designer.speech.size(), "an uploaded voice isn't designed");
        JsonObject neutral = cloner.voices.getLast();
        assertEquals("Hello there, I am Bob.", neutral.get("ref_text").getAsString(), "lines continue from the uploaded clip");

        bank.voice(VILLAGER, "An old farmer", 42, "sad");
        assertEquals("twt-" + VILLAGER + "-timbre", cloner.speech.getLast().get("voice").getAsString(), "moods are read in the uploaded voice");

        bank.removeCustomClip(VILLAGER);
        bank.voice(VILLAGER, "An old farmer", 42, "neutral");
        assertEquals(2, designer.speech.size(), "without the clip the voice is designed again");
    }

    @Test
    void clipsMustBeWavFilesOfAFewSeconds() {
        VoiceBank bank = bank();
        assertThrows(IllegalArgumentException.class, () -> bank.setCustomClip(VILLAGER, "not audio".getBytes(StandardCharsets.UTF_8), ""));
        assertThrows(IllegalArgumentException.class, () -> bank.setCustomClip(VILLAGER, Wav.encode(new short[24_000], 24_000), ""));
        assertThrows(IllegalArgumentException.class, () -> bank.setCustomClip(VILLAGER, Wav.encode(new short[48_000 * 31], 48_000), ""));
    }

    @Test
    void emotionsShareFiveMoods() {
        assertEquals(VoiceBank.Mood.HAPPY, VoiceBank.Mood.of("laugh"));
        assertEquals(VoiceBank.Mood.ANGRY, VoiceBank.Mood.of("annoyed"));
        assertEquals(VoiceBank.Mood.SAD, VoiceBank.Mood.of("sleepy"));
        assertEquals(VoiceBank.Mood.SCARED, VoiceBank.Mood.of("surprised"));
        assertEquals(VoiceBank.Mood.NEUTRAL, VoiceBank.Mood.of("confused"));
        assertEquals(VoiceBank.Mood.NEUTRAL, VoiceBank.Mood.of(null));
    }

    private VoiceBank bank() {
        return new VoiceBank(tmp, new QwenTtsClient(designer::url), new QwenTtsClient(cloner::url));
    }

    /** A Qwen3-TTS server that answers every request and remembers them. */
    private static final class Fake {
        final List<JsonObject> speech = new CopyOnWriteArrayList<>();
        final List<JsonObject> voices = new CopyOnWriteArrayList<>();
        final HttpServer http;

        Fake() {
            try {
                http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            http.createContext("/v1/audio/speech", ex -> {
                speech.add(JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                byte[] wav = new byte[4096];
                ex.sendResponseHeaders(200, wav.length);
                ex.getResponseBody().write(wav);
                ex.close();
            });
            http.createContext("/v1/audio/voices", ex -> {
                voices.add(JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                ex.sendResponseHeaders(200, -1);
                ex.close();
            });
            http.start();
        }

        String url() {
            return "http://127.0.0.1:" + http.getAddress().getPort();
        }

        List<String> registeredNames() {
            return voices.stream().map(v -> v.get("name").getAsString()).toList();
        }
    }
}

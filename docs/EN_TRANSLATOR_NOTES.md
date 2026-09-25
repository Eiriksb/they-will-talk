# EN Translator Core notes (found while integrating They Will Talk)

Tested against **EN Translator Core 1.0.21** (`en_translator-1.0.21-all.jar`, CurseForge file 8005810) on
NeoForge 21.1.251, Linux x86_64.

## 1. How They Will Talk hooks in (no changes needed in EN Translator)

They Will Talk injects at the head of

```java
com.example.voicetranslator.translation.TranslationService#relayClientTranslation(
        UUID speakerId, String sourceText, String translatedText, String sourceLanguage,
        String translationLanguage, int displayDurationMs, int lineId, boolean partial)
```

with a `@Pseudo` mixin (`mod/src/main/java/dev/eiriksb/theywilltalk/mixin/EnTranslatorRelayMixin.java`).
It ignores `partial == true` lines, prefers `translatedText` when `translationLanguage` is English and hands the
text to the villagers. **If this method's name or parameters change, voice input stops working** (the mixin is
optional, so nothing crashes). A tiny public event would make this future-proof, e.g.:

```java
// in relayClientTranslation, after the partial/blank checks:
NeoForge.EVENT_BUS.post(new VoiceLineRelayedEvent(speakerId, normalizedSource, normalizedTranslated,
        normalizeLanguageCode(sourceLanguage), normalizeLanguageCode(translationLanguage), lineId, partial));
```

## 2. Bug: the Linux client crashes on startup (missing onnxruntime 1.17.1)

```
java.lang.UnsatisfiedLinkError: ~/lib/linux-x64/libsherpa-onnx-jni.so: libonnxruntime.so:
cannot open shared object file: No such file or directory
```

* The bundled `sherpa-onnx-java-api-1.0.1.jar` extracts only `libsherpa-onnx-jni.so` (to `~/lib/linux-x64/`).
* That library was built against **onnxruntime 1.17.1** (`NEEDED libonnxruntime.so`, symbol version `VERS_1.17.1`,
  `RPATH $ORIGIN`), but no 1.17.1 `libonnxruntime.so` ships for Linux. The `onnxruntime-1.20.0.jar` in jarJar exports
  `VERS_1.20.0`, so it can't satisfy it.
* Fix options: bundle `libonnxruntime.so` 1.17.1 next to the JNI library (from
  `com.microsoft.onnxruntime:onnxruntime:1.17.1`, path `ai/onnxruntime/native/linux-x64/libonnxruntime.so`), or
  upgrade to a sherpa-onnx Java package whose native jar includes both libraries (sherpa-onnx 1.13.x
  `sherpa-onnx-native-lib-linux-x64-*.jar` does).
* Workaround used in the dev environment: `-Dsherpa_onnx.native.path=<dir>` pointing at a folder holding both
  files (see `mod/build.gradle`, `enTranslatorNatives`).

## 3. Idea: chat bubbles above villagers (translated per player)

EN Translator already translates each bubble into the *reader's* language. If the client rendered bubbles above
any entity (not only players), They Will Talk could send villager replies as bubbles and every player would see
them in their own language:

* `ChatBubbleRenderer.renderAboveNameTag(..., Player player, ...)` -> take `Entity`/`LivingEntity`.
* Render from a `RenderLivingEvent.Post` (or `RenderNameTagEvent`) for any entity that has bubbles in
  `ChatBubbleManager`, instead of only players.
* They Will Talk would then send `BubbleSyncPayload(villagerUuid, text, text, "en", "en", durationMs, lineId, false)`
  to nearby players.

## 4. Heads-up: speakers + open microphone

With voice activation and speakers (no headphones), villager voices can be picked up by the microphone and
transcribed as the player's own words. They Will Talk now filters these echoes (it ignores voice lines while a nearby
villager is talking and lines that repeat what a villager just said), but push-to-talk is still the nicest setup.

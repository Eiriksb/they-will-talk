package dev.eiriksb.theywilltalk.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.runtime.Catalog.Package;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The AI runtime: the villager brain (llama.cpp {@code llama-server}), the expressive voices (qwentts.cpp
 * {@code tts-server}) and the CPU voices (our voice server), each a separate low-priority process on a free local port.
 * Programs and models live in the runtime folder, downloaded from the dashboard ({@link Installer}) or put there by
 * {@code scripts/fetch-runtime.sh}.
 *
 * <pre>
 * runtime/
 *   linux-x64/llama/llama-server   linux-x64/cuda/libcudart...   linux-x64/qwentts/tts-server
 *   models/llm/*.gguf   models/qwentts/*.gguf   models/tts/kokoro/   voice-server/voice-server.jar
 * </pre>
 */
public final class RuntimeManager {
    private static final String VOICE_SERVER_RESOURCE = "/assets/theywilltalk/runtime/voice-server.jar";

    private final Path runtimeDir;
    private final Platform platform = Platform.current();
    private final Catalog catalog = Catalog.load();
    private final Installer installer;
    private final ManagedProcess llm;
    private final ManagedProcess qwen;
    private final ManagedProcess qwenClone;
    private final ManagedProcess voice;

    private volatile String llmModelFile;
    private volatile String qwenModelFile;
    private volatile int llmPort;
    private volatile int qwenPort;
    private volatile int qwenClonePort;
    private volatile int voicePort;
    private volatile String externalLlm;
    private volatile boolean externalReady;
    private volatile boolean running;

    public RuntimeManager(Path gameDir) {
        this.runtimeDir = resolveRuntimeDir(gameDir);
        Path logs = gameDir.resolve("logs").resolve("theywilltalk");
        this.llm = new ManagedProcess("llm", logs.resolve("llm.log"));
        this.qwen = new ManagedProcess("qwen-tts", logs.resolve("qwen-tts.log"));
        this.qwenClone = new ManagedProcess("qwen-clone", logs.resolve("qwen-clone.log"));
        this.voice = new ManagedProcess("voice", logs.resolve("voice.log"));
        this.installer = new Installer(runtimeDir, platform, catalog, this::voiceServerJar, this::installed);
    }

    static Path resolveRuntimeDir(Path gameDir) {
        String dev = System.getProperty("theywilltalk.runtimeDir");
        if (dev != null && !dev.isBlank()) {
            return Path.of(dev).toAbsolutePath().normalize();
        }
        String configured = TwtConfig.RUNTIME_DIR.get().trim();
        Path dir = configured.isEmpty() ? gameDir.resolve("theywilltalk").resolve("runtime") : gameDir.resolve(configured);
        return dir.toAbsolutePath().normalize();
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------

    /** (Re)starts everything that is installed. Returns quickly; the processes become ready in the background. */
    public synchronized void start() {
        stop();
        running = true;
        startLlm();
        startQwen();
        startVoice();
    }

    public synchronized void stop() {
        running = false;
        externalLlm = null;
        externalReady = false;
        llm.stop();
        qwen.stop();
        qwenClone.stop();
        voice.stop();
    }

    public synchronized void restartLlm() {
        llm.stop();
        externalLlm = null;
        externalReady = false;
        startLlm();
    }

    public synchronized void restartVoices() {
        qwen.stop();
        qwenClone.stop();
        voice.stop();
        startQwen();
        startVoice();
    }

    private void startLlm() {
        String external = TwtConfig.EXTERNAL_LLM_URL.get().trim().replaceAll("/+$", "");
        if (!external.isEmpty()) {
            externalLlm = external;
            Thread.ofVirtual().name("twt-llm-external").start(() -> awaitExternal(external));
            return;
        }
        Path bin = llamaServer();
        Path model = selectLlmModel(true);
        llmModelFile = model == null ? null : model.getFileName().toString();
        if (!Files.isRegularFile(bin) || model == null) {
            return;
        }
        llmPort = freePort();
        List<String> cmd = List.of(bin.toString(), "-m", model.toString(), "--host", "127.0.0.1", "--port", Integer.toString(llmPort),
                "-ngl", Integer.toString(TwtConfig.GPU_LAYERS.get()), "-c", Integer.toString(TwtConfig.CONTEXT_SIZE.get()),
                "-np", Integer.toString(TwtConfig.PARALLEL_SLOTS.get()), "--jinja");
        llm.start(niced(cmd), libraryPath(bin.getParent(), cudaDir()), bin.getParent(), health(llmPort, "/health"), Duration.ofMinutes(5));
    }

    private void awaitExternal(String url) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/v1/models")).timeout(Duration.ofSeconds(5)).build();
        while (url.equals(externalLlm) && !externalReady) {
            try {
                if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    externalReady = true;
                    TheyWillTalk.LOGGER.info("[llm] using the external server at {}", url);
                    return;
                }
            } catch (IOException ignored) {
                // not up yet
            } catch (InterruptedException e) {
                return;
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void startQwen() {
        Path bin = runtimeDir.resolve(platform.id).resolve("qwentts").resolve("tts-server" + platform.exe);
        Path talker = selectQwenModel();
        Path codec = firstFile(runtimeDir.resolve("models").resolve("qwentts"), "qwen-tokenizer-", ".gguf");
        qwenModelFile = talker == null ? null : talker.getFileName().toString();
        if (!qwenWanted() || !Files.isRegularFile(bin) || talker == null || codec == null) {
            return;
        }
        qwenPort = freePort();
        List<String> cmd = List.of(bin.toString(), "--model", talker.toString(), "--codec", codec.toString(), "--host", "127.0.0.1",
                "--port", Integer.toString(qwenPort), "--alias", "qwen3-tts", "--lang", "English");
        qwen.start(niced(cmd), libraryPath(bin.getParent(), cudaDir()), bin.getParent(), health(qwenPort, "/health"), Duration.ofMinutes(3));

        // Voice cloning: the Base model speaks every line in the villager's once-designed voice (see VoiceBank).
        Path base = firstFile(runtimeDir.resolve("models").resolve("qwentts"), "qwen-talker-", "-base-");
        if (base != null) {
            qwenClonePort = freePort();
            List<String> clone = List.of(bin.toString(), "--model", base.toString(), "--codec", codec.toString(), "--host", "127.0.0.1",
                    "--port", Integer.toString(qwenClonePort), "--alias", "qwen3-tts-base", "--lang", "English");
            qwenClone.start(niced(clone), libraryPath(bin.getParent(), cudaDir()), bin.getParent(), health(qwenClonePort, "/health"),
                    Duration.ofMinutes(3));
        }
    }

    private void startVoice() {
        Path models = runtimeDir.resolve("models").resolve("tts");
        if (!hasVoiceEngines(models)) {
            return;
        }
        Path jar = voiceServerJar();
        if (jar == null) {
            return;
        }
        voicePort = freePort();
        List<String> cmd = List.of(javaExecutable().toString(), "-Xmx1g", "-jar", jar.toString(), "--port", Integer.toString(voicePort),
                "--models", models.toString(), "--threads", Integer.toString(TwtConfig.TTS_THREADS.get()),
                "--cache", runtimeDir.resolve(".twt").resolve("voice-cache").toString());
        voice.start(niced(cmd), Map.of(), runtimeDir, health(voicePort, "/health"), Duration.ofMinutes(3));
    }

    /** After downloads: start whatever is new. Runs on the installer thread. */
    private void installed(List<Package> packages) {
        // Downloading another brain must not silently replace the one villagers use ('auto' picks by catalogue order).
        if (TwtConfig.LLM_MODEL.get().trim().equalsIgnoreCase("auto")) {
            String current = llmModelFile;
            String pin = current != null ? current
                    : packages.stream().map(Package::llmModel).filter(m -> m != null).findFirst().orElse(null);
            if (pin != null) {
                TwtConfig.LLM_MODEL.set(pin);
                TwtConfig.LLM_MODEL.save();
            }
        }
        if (!running) {
            return;
        }
        boolean brain = packages.stream().anyMatch(p -> p.group().equals("brain") || p.id().equals("llm-runtime"));
        boolean voices = packages.stream().anyMatch(p -> p.group().equals("voices") || p.id().equals("qwentts-server"));
        if (brain && !llmReady()) {
            restartLlm();
        }
        if (voices) {
            restartVoices();
        }
    }

    // ---- model selection -----------------------------------------------------------------------------------

    private Path selectLlmModel(boolean log) {
        Path dir = runtimeDir.resolve("models").resolve("llm");
        String wanted = TwtConfig.LLM_MODEL.get().trim();
        if (!wanted.isEmpty() && !wanted.equalsIgnoreCase("auto")) {
            Path p = dir.resolve(wanted.endsWith(".gguf") ? wanted : wanted + ".gguf");
            if (Files.isRegularFile(p)) {
                return p;
            }
            if (log) {
                TheyWillTalk.LOGGER.warn("[llm] llmModel '{}' is not in {}; picking one automatically", wanted, dir);
            }
        }
        // Catalogue order: the recommended brain first.
        for (Package pkg : catalog.packages()) {
            if (pkg.llmModel() != null && Files.isRegularFile(dir.resolve(pkg.llmModel()))) {
                return dir.resolve(pkg.llmModel());
            }
        }
        return llmFiles(dir).stream().findFirst().orElse(null);
    }

    /** GGUF language models in a folder, without the vision/MTP side files some repos ship next to them. */
    private static List<Path> llmFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".gguf") && !n.startsWith("mmproj") && !n.startsWith("mtp");
            }).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path selectQwenModel() {
        Path dir = runtimeDir.resolve("models").resolve("qwentts");
        String wanted = TwtConfig.QWEN_TTS_MODEL.get().trim();
        if (!wanted.isEmpty() && !wanted.equalsIgnoreCase("auto") && Files.isRegularFile(dir.resolve(wanted))) {
            return dir.resolve(wanted);
        }
        // The Base model only clones voices (it takes no voice description), so it never designs them.
        Path design = firstFile(dir, "qwen-talker-", "voicedesign");
        return design != null ? design : firstFile(dir, "qwen-talker-", "customvoice");
    }

    private boolean qwenWanted() {
        String engine = TwtConfig.TTS_ENGINE.get().trim().toLowerCase(Locale.ROOT);
        return !engine.equals("kokoro") && !engine.equals("supertonic");
    }

    private static Path firstFile(Path dir, String prefix, String contains) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(prefix) && n.contains(contains) && n.endsWith(".gguf");
            }).sorted().findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean hasVoiceEngines(Path models) {
        if (!Files.isDirectory(models)) {
            return false;
        }
        try (Stream<Path> dirs = Files.list(models)) {
            return dirs.anyMatch(Files::isDirectory);
        } catch (IOException e) {
            return false;
        }
    }

    // ---- processes ------------------------------------------------------------------------------------------

    private Path llamaServer() {
        return runtimeDir.resolve(platform.id).resolve("llama").resolve("llama-server" + platform.exe);
    }

    private Path cudaDir() {
        return runtimeDir.resolve(platform.id).resolve("cuda");
    }

    /** The voice server jar ships inside the mod; it is copied into the runtime folder when it changed. */
    synchronized Path voiceServerJar() {
        Path target = runtimeDir.resolve("voice-server").resolve("voice-server.jar");
        try (InputStream in = RuntimeManager.class.getResourceAsStream(VOICE_SERVER_RESOURCE)) {
            if (in == null) {
                TheyWillTalk.LOGGER.error("The voice server is missing from the mod jar ({})", VOICE_SERVER_RESOURCE);
                return Files.isRegularFile(target) ? target : null;
            }
            byte[] bytes = in.readAllBytes();
            Path hashFile = target.resolveSibling("voice-server.jar.sha256");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!Files.isRegularFile(target) || !Files.exists(hashFile) || !Files.readString(hashFile).trim().equals(hash)) {
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling("voice-server.jar.tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(hashFile, hash);
            }
            return target;
        } catch (IOException | NoSuchAlgorithmException e) {
            TheyWillTalk.LOGGER.error("Could not unpack the voice server: {}", e.toString());
            return null;
        }
    }

    static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", Platform.current() == Platform.WINDOWS_X64 ? "java.exe" : "java");
    }

    /** Linux: run the AI at a lower priority so the Minecraft server thread always wins. */
    private List<String> niced(List<String> cmd) {
        int nice = TwtConfig.PROCESS_NICE.get();
        Path bin = Path.of("/usr/bin/nice");
        if (platform != Platform.LINUX_X64 || nice <= 0 || !Files.isExecutable(bin)) {
            return cmd;
        }
        List<String> out = new ArrayList<>(List.of(bin.toString(), "-n", Integer.toString(nice)));
        out.addAll(cmd);
        return out;
    }

    /** Lets a program find the shared libraries next to it and the bundled CUDA runtime. */
    private Map<String, String> libraryPath(Path... dirs) {
        String var = platform == Platform.WINDOWS_X64 ? "PATH" : "LD_LIBRARY_PATH";
        StringBuilder sb = new StringBuilder();
        for (Path d : dirs) {
            if (Files.isDirectory(d)) {
                sb.append(d).append(java.io.File.pathSeparatorChar);
            }
        }
        String existing = System.getenv(var);
        if (existing != null && !existing.isEmpty()) {
            sb.append(existing);
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put(var, sb.toString().replaceAll(java.io.File.pathSeparator + "$", ""));
        return env;
    }

    private static URI health(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free local port", e);
        }
    }

    // ---- state ----------------------------------------------------------------------------------------------

    public Path runtimeDir() {
        return runtimeDir;
    }

    public Installer installer() {
        return installer;
    }

    public Catalog catalog() {
        return catalog;
    }

    public Platform platform() {
        return platform;
    }

    public boolean llmReady() {
        return externalLlm != null ? externalReady : llm.state() == ManagedProcess.State.READY;
    }

    public String llmBaseUrl() {
        String external = externalLlm;
        if (external != null) {
            return externalReady ? external : null;
        }
        return llmReady() ? "http://127.0.0.1:" + llmPort : null;
    }

    public boolean voiceReady() {
        return voice.state() == ManagedProcess.State.READY;
    }

    public String voiceBaseUrl() {
        return "http://127.0.0.1:" + voicePort;
    }

    public boolean qwenTtsReady() {
        return qwen.state() == ManagedProcess.State.READY;
    }

    public String qwenTtsBaseUrl() {
        return qwenTtsReady() ? "http://127.0.0.1:" + qwenPort : null;
    }

    public String llmModelName() {
        if (externalLlm != null) {
            return "external: " + externalLlm;
        }
        String f = llmModelFile;
        return f == null ? null : f.replaceAll("\\.gguf$", "");
    }

    /** File name of the language model in use (or chosen but still loading). */
    public String llmModelFile() {
        return externalLlm != null ? null : llmModelFile;
    }

    public boolean qwenCloneReady() {
        return qwenClone.state() == ManagedProcess.State.READY;
    }

    public String qwenCloneBaseUrl() {
        return qwenCloneReady() ? "http://127.0.0.1:" + qwenClonePort : null;
    }

    public ManagedProcess qwenCloneProcess() {
        return qwenClone;
    }

    public String qwenTtsModelName() {
        String f = qwenModelFile;
        return f == null ? null : f.replaceAll("\\.gguf$", "");
    }

    public ManagedProcess llmProcess() {
        return llm;
    }

    public ManagedProcess qwenTtsProcess() {
        return qwen;
    }

    public ManagedProcess voiceProcess() {
        return voice;
    }

    /** Nothing to talk with yet: the dashboard offers the one-click install. */
    public boolean setupNeeded() {
        return externalLlm == null && TwtConfig.EXTERNAL_LLM_URL.get().isBlank()
                && (!Files.isRegularFile(llamaServer()) || selectLlmModel(false) == null);
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (platform == Platform.UNSUPPORTED) {
            problems.add("The bundled AI runs on Linux and Windows (x64) only. Set externalLlmUrl in the config to use your own server.");
            return problems;
        }
        boolean external = !TwtConfig.EXTERNAL_LLM_URL.get().isBlank();
        if (!external && !Files.isRegularFile(llamaServer())) {
            problems.add("The villagers' AI isn't installed yet. Open the dashboard's Models page and click Install recommended.");
        } else if (!external && selectLlmModel(false) == null) {
            problems.add("No villager brain (language model) is installed. Download one on the dashboard's Models page.");
        }
        failed(problems, llm, "The villager brain");
        failed(problems, qwen, "The Qwen3-TTS voices");
        failed(problems, qwenClone, "Qwen3-TTS voice cloning");
        failed(problems, voice, "The voice server");
        if (!hasVoiceEngines(runtimeDir.resolve("models").resolve("tts")) && !qwenRunning()) {
            problems.add("No voices are installed, so villagers answer with subtitles only. Download Kokoro voices on the Models page.");
        }
        return problems;
    }

    private static void failed(List<String> problems, ManagedProcess p, String what) {
        if (p.state() == ManagedProcess.State.FAILED) {
            problems.add(what + " failed: " + p.lastError());
        }
    }

    /** The Models page: every package, whether it's installed, in use or downloading. */
    public JsonObject modelsJson() {
        JsonObject o = new JsonObject();
        o.addProperty("platform", platform.id);
        o.addProperty("runtimeDir", runtimeDir.toString());
        try {
            Files.createDirectories(runtimeDir);
            o.addProperty("freeBytes", Files.getFileStore(runtimeDir).getUsableSpace());
        } catch (IOException e) {
            o.addProperty("freeBytes", -1);
        }
        String llmFile = llmModelFile();
        String engine = TwtConfig.TTS_ENGINE.get().trim().toLowerCase(Locale.ROOT);
        JsonArray packages = new JsonArray();
        long recommendedBytes = 0;
        for (Package pkg : catalog.packages()) {
            JsonObject p = new JsonObject();
            boolean installed = installer.installed(pkg);
            p.addProperty("id", pkg.id());
            p.addProperty("group", pkg.group());
            p.addProperty("name", pkg.name());
            p.addProperty("summary", pkg.summary());
            p.addProperty("licence", pkg.licence());
            p.addProperty("source", pkg.source());
            p.addProperty("size", pkg.downloadSize(platform));
            p.addProperty("installed", installed);
            p.addProperty("available", pkg.available(platform));
            if (!pkg.available(platform) && pkg.unavailable() != null) {
                p.addProperty("unavailable", platform.expand(pkg.unavailable()));
            }
            JsonArray tags = new JsonArray();
            pkg.tags().forEach(tags::add);
            p.add("tags", tags);
            boolean active = pkg.llmModel() != null ? pkg.llmModel().equals(llmFile)
                    : pkg.ttsEngine() != null && installed && engineInUse(pkg.ttsEngine(), engine);
            p.addProperty("active", active);
            p.addProperty("selectable", pkg.llmModel() != null || pkg.ttsEngine() != null);
            JsonArray blockedBy = new JsonArray();
            blockers(pkg).forEach(b -> blockedBy.add(b.name()));
            p.add("blockedBy", blockedBy);
            Installer.Job job = installer.job(pkg.id());
            if (job != null) {
                JsonObject j = new JsonObject();
                j.addProperty("phase", job.phase().name());
                j.addProperty("done", job.done());
                j.addProperty("total", job.total());
                j.addProperty("bytesPerSecond", job.bytesPerSecond());
                j.addProperty("error", job.error());
                p.add("job", j);
            }
            if (recommended(pkg) && !installed) {
                recommendedBytes += pkg.downloadSize(platform);
            }
            packages.add(p);
        }
        o.add("packages", packages);
        o.addProperty("recommendedBytes", recommendedBytes);
        o.addProperty("busy", installer.busy());
        o.addProperty("setupNeeded", setupNeeded());
        JsonObject settings = new JsonObject();
        settings.addProperty("ttsEngine", engine);
        settings.addProperty("crudeLanguage", TwtConfig.CRUDE_LANGUAGE.get());
        settings.addProperty("externalLlmUrl", TwtConfig.EXTERNAL_LLM_URL.get());
        o.add("settings", settings);
        return o;
    }

    /** Which voice engine actually speaks: 'auto' prefers Qwen3-TTS when it runs. */
    private boolean engineInUse(String pkgEngine, String setting) {
        if (setting.equals("auto")) {
            return pkgEngine.equals("qwen3") ? qwenRunning() : pkgEngine.equals("kokoro") && !qwenRunning();
        }
        return pkgEngine.equals(setting);
    }

    private boolean qwenRunning() {
        return qwen.state() == ManagedProcess.State.READY || qwen.state() == ManagedProcess.State.STARTING;
    }

    /** Packages this one needs that are neither installed nor downloadable here: installing it would be pointless. */
    private List<Package> blockers(Package pkg) {
        return pkg.requires().stream().map(r -> catalog.get(r).orElseThrow())
                .filter(d -> !installer.installed(d) && !d.available(platform)).toList();
    }

    /** What "Install recommended" installs on this platform. */
    public boolean recommended(Package pkg) {
        if (!pkg.available(platform) || !blockers(pkg).isEmpty()) {
            return false;
        }
        // The expressive voices come along once their server can be downloaded.
        return pkg.tagged("recommended") || pkg.id().startsWith("qwen3-tts") || pkg.id().equals("qwentts-server");
    }

    /** Switches to an installed brain or voice package (the dashboard's "Use" button). */
    public void use(String id) {
        Package pkg = catalog.get(id).orElseThrow(() -> new IllegalArgumentException("unknown package " + id));
        if (!installer.installed(pkg)) {
            throw new IllegalStateException(pkg.name() + " isn't installed");
        }
        if (pkg.llmModel() != null) {
            TwtConfig.LLM_MODEL.set(pkg.llmModel());
            TwtConfig.LLM_MODEL.save();
            TheyWillTalk.LOGGER.info("[llm] switching to {}", pkg.name());
            Thread.ofVirtual().start(this::restartLlm);
        } else if (pkg.ttsEngine() != null) {
            setTtsEngine(pkg.ttsEngine());
        } else {
            throw new IllegalArgumentException(pkg.name() + " can't be selected");
        }
    }

    public void setTtsEngine(String engine) {
        String e = engine.trim().toLowerCase(Locale.ROOT);
        if (!List.of("auto", "qwen3", "kokoro", "supertonic").contains(e)) {
            throw new IllegalArgumentException("unknown voice engine " + engine);
        }
        TwtConfig.TTS_ENGINE.set(e);
        TwtConfig.TTS_ENGINE.save();
        Thread.ofVirtual().start(this::restartVoices);
    }

    /** Deletes a package, stopping whatever uses its files first (Windows can't delete open files). */
    public synchronized void remove(String id) throws IOException {
        Package pkg = catalog.get(id).orElseThrow(() -> new IllegalArgumentException("unknown package " + id));
        boolean brain = pkg.group().equals("brain") || pkg.id().equals("llm-runtime");
        boolean voices = pkg.group().equals("voices") || pkg.id().equals("qwentts-server") || pkg.id().equals("llm-runtime");
        if (brain) {
            llm.stop();
        }
        if (voices) {
            qwen.stop();
            qwenClone.stop();
            voice.stop();
        }
        try {
            installer.remove(id);
        } finally {
            if (running && brain) {
                startLlm();
            }
            if (running && voices) {
                startQwen();
                startVoice();
            }
        }
    }

    public void installRecommended() {
        for (Package pkg : catalog.packages()) {
            if (recommended(pkg) && !installer.installed(pkg)) {
                installer.install(pkg.id());
            }
        }
    }
}

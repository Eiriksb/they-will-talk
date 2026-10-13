package dev.eiriksb.theywilltalk.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * OpenAI-compatible chat client for the bundled llama-server. Requests run through a small priority queue with one
 * worker per llama.cpp slot, so a player waiting for an answer always goes before background work like memory
 * reflection or ambient villager chatter.
 *
 * <p>A player can lend their own GPU ({@link #lend}): requests about that player (their conversations) then go to it,
 * and only those, because prompts hold what villagers remember about players. When the lent GPU drops out, its
 * requests fall back to the server's own model.
 */
public final class LlmClient {
    /** TWT's logger, without loading the mod class (unit tests run without NeoForge). */
    private static final Logger LOG = LoggerFactory.getLogger("TheyWillTalk");

    public enum Priority { CONVERSATION, GREETING, REFLECTION, PLANNING, AMBIENT }

    public record Message(String role, String content) {
        public static Message system(String c) { return new Message("system", c); }
        public static Message user(String c) { return new Message("user", c); }
        public static Message assistant(String c) { return new Message("assistant", c); }
    }

    public record Result(String text, long firstTokenMs, long totalMs, int tokens) {}

    /** One request as it goes to a model: what a lent GPU is sent. */
    public record Request(List<Message> messages, double temperature, int maxTokens, JsonObject jsonSchema) {}

    /** Somewhere else requests can run: a player's lent GPU. {@link #run} blocks on the backend's own threads. */
    public interface Backend {
        String name();

        /** How many requests it takes at once. */
        int slots();

        Result run(Request request, Consumer<String> onDelta, BooleanSupplier cancelled) throws IOException, InterruptedException;
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Supplier<String> baseUrl;
    private final BooleanSupplier ready;
    private final Map<UUID, Lane> lent = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
    private final AtomicLong seq = new AtomicLong();
    private final List<Thread> workers;
    private volatile boolean running = true;
    /** A server without llama.cpp's /apply-template and /completion (Ollama, LM Studio...): JSON goes through chat. */
    private volatile String noRawCompletion;

    // stats for the dashboard
    public final AtomicLong requests = new AtomicLong();
    public final AtomicLong tokensGenerated = new AtomicLong();
    /** Requests answered by players' lent GPUs. */
    public final AtomicLong lentRequests = new AtomicLong();
    public volatile long lastFirstTokenMs;
    public volatile double lastTokensPerSecond;

    public LlmClient(Supplier<String> baseUrl, int workers) {
        this(baseUrl, () -> baseUrl.get() != null, workers);
    }

    /** {@code ready} says whether the model takes requests without asking {@code baseUrl}, which may start it. */
    public LlmClient(Supplier<String> baseUrl, BooleanSupplier ready, int workers) {
        this.baseUrl = baseUrl;
        this.ready = ready;
        this.workers = java.util.stream.IntStream.range(0, Math.max(1, workers))
                .mapToObj(i -> Thread.ofPlatform().daemon().name("twt-llm-" + i).start(this::workLoop))
                .toList();
    }

    public void shutdown() {
        running = false;
        workers.forEach(Thread::interrupt);
        List.copyOf(lent.keySet()).forEach(this::unlend);
        Job j;
        while ((j = queue.poll()) != null) {
            j.future.completeExceptionally(new IllegalStateException("shutting down"));
        }
    }

    public int queued() {
        return queue.size();
    }

    /** Whether the server's own model takes requests. */
    public boolean ready() {
        return ready.getAsBoolean();
    }

    /** Whether requests about {@code player} can run: on the server's model or on the GPU they lend. */
    public boolean readyFor(UUID player) {
        return ready() || player != null && lent.containsKey(player);
    }

    /** From now on, requests about {@code player} run on {@code backend} (the GPU they lend). */
    public void lend(UUID player, Backend backend) {
        Lane old = lent.put(player, new Lane(backend));
        if (old != null) {
            old.close();
        }
        LOG.info("[llm] {} lends their GPU ({} at a time)", backend.name(), backend.slots());
    }

    /** {@code player} stopped lending: what was waiting for their GPU goes to the server's model. */
    public void unlend(UUID player) {
        Lane lane = lent.remove(player);
        if (lane != null) {
            lane.close();
            LOG.info("[llm] {} stopped lending their GPU ({} requests answered)", lane.backend.name(), lane.answered.get());
        }
    }

    /** Who lends their GPU right now, with how many requests each has answered. */
    public List<String> lenders() {
        List<String> out = new ArrayList<>();
        lent.values().forEach(l -> out.add(l.backend.name() + " (" + l.answered.get() + " answered)"));
        return out;
    }

    /**
     * Streams a chat completion. {@code onDelta} is called on the worker thread with each text fragment.
     *
     * @param jsonSchema optional JSON schema (constrains output via llama.cpp grammar)
     */
    public CompletableFuture<Result> chat(Priority priority, List<Message> messages, double temperature, int maxTokens,
                                          JsonObject jsonSchema, Consumer<String> onDelta) {
        return chat(priority, messages, temperature, maxTokens, jsonSchema, onDelta, null);
    }

    /** As above, for a request about {@code about} (a player): it runs on their lent GPU when they lend one. */
    public CompletableFuture<Result> chat(Priority priority, List<Message> messages, double temperature, int maxTokens,
                                          JsonObject jsonSchema, Consumer<String> onDelta, UUID about) {
        Job job = new Job(priority, seq.incrementAndGet(), messages, temperature, maxTokens, jsonSchema, onDelta);
        Lane lane = about == null ? null : lent.get(about);
        (lane != null ? lane.queue : queue).add(job);
        return job.future;
    }

    private void workLoop() {
        while (running) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            if (job.future.isCancelled()) {
                continue;
            }
            try {
                job.future.complete(run(job));
            } catch (Throwable t) {
                job.future.completeExceptionally(t);
            }
        }
    }

    private Result run(Job job) throws IOException, InterruptedException {
        String base = baseUrl.get();
        if (base == null) {
            throw new IOException("LLM server is not running");
        }
        JsonArray msgs = messages(job);
        if (job.jsonSchema != null && !base.equals(noRawCompletion)) {
            Result r = structured(base, job, msgs);
            if (r != null) {
                return r;
            }
        }
        JsonObject body = new JsonObject();
        body.add("messages", msgs);
        body.addProperty("stream", true);
        body.addProperty("temperature", job.temperature);
        body.addProperty("top_p", 0.95);
        body.addProperty("max_tokens", job.maxTokens);
        body.addProperty("cache_prompt", true);
        JsonObject kwargs = new JsonObject();
        kwargs.addProperty("enable_thinking", false);
        body.add("chat_template_kwargs", kwargs);
        if (job.jsonSchema != null) {
            JsonObject rf = new JsonObject();
            rf.addProperty("type", "json_schema");
            JsonObject js = new JsonObject();
            js.addProperty("name", "reply");
            js.add("schema", job.jsonSchema);
            rf.add("json_schema", js);
            body.add("response_format", rf);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        long t0 = System.nanoTime();
        long first = -1;
        int tokens = 0;
        StringBuilder text = new StringBuilder();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("LLM HTTP " + resp.statusCode() + ": " + err);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (job.future.isCancelled()) {
                    break;
                }
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring(6).trim();
                if (data.equals("[DONE]")) {
                    break;
                }
                JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                JsonArray choices = chunk.getAsJsonArray("choices");
                if (choices == null || choices.isEmpty()) {
                    continue;
                }
                JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                JsonElement content = delta == null ? null : delta.get("content");
                if (content == null || content.isJsonNull()) {
                    continue;
                }
                String piece = content.getAsString();
                if (piece.isEmpty()) {
                    continue;
                }
                if (first < 0) {
                    first = (System.nanoTime() - t0) / 1_000_000;
                }
                tokens++;
                text.append(piece);
                if (job.onDelta != null) {
                    try {
                        job.streamed = true;
                        job.onDelta.accept(piece);
                    } catch (Exception e) {
                        LOG.warn("LLM delta handler failed", e);
                    }
                }
            }
        }
        long total = (System.nanoTime() - t0) / 1_000_000;
        requests.incrementAndGet();
        tokensGenerated.addAndGet(tokens);
        if (job.priority == Priority.CONVERSATION) {
            lastFirstTokenMs = first;
            long genMs = Math.max(1, total - Math.max(0, first));
            lastTokensPerSecond = tokens * 1000.0 / genMs;
        }
        return new Result(text.toString(), first, total, tokens);
    }

    private static JsonArray messages(Job job) {
        JsonArray msgs = new JsonArray();
        for (Message m : job.messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            msgs.add(o);
        }
        return msgs;
    }

    /**
     * JSON answers through llama.cpp's raw completion: the schema's grammar holds from the very first token. Through the
     * chat endpoint Gemma 4 sometimes "thinks" before the grammar kicks in, spends the whole token budget on it, and
     * the answer comes back empty. Null when the server doesn't have these endpoints.
     */
    private Result structured(String base, Job job, JsonArray msgs) throws IOException, InterruptedException {
        long t0 = System.nanoTime();
        JsonObject tpl = new JsonObject();
        tpl.add("messages", msgs);
        JsonObject kwargs = new JsonObject();
        kwargs.addProperty("enable_thinking", false);
        tpl.add("chat_template_kwargs", kwargs);
        HttpResponse<String> prompt = post(base + "/apply-template", tpl);
        if (prompt.statusCode() == 404) {
            noRawCompletion = base;
            return null;
        }
        if (prompt.statusCode() != 200) {
            throw new IOException("LLM HTTP " + prompt.statusCode() + ": " + prompt.body());
        }
        JsonObject body = new JsonObject();
        body.addProperty("prompt", JsonParser.parseString(prompt.body()).getAsJsonObject().get("prompt").getAsString());
        body.add("json_schema", job.jsonSchema);
        body.addProperty("n_predict", job.maxTokens);
        body.addProperty("temperature", job.temperature);
        body.addProperty("top_p", 0.95);
        body.addProperty("cache_prompt", true);
        HttpResponse<String> resp = post(base + "/completion", body);
        if (resp.statusCode() == 404) {
            noRawCompletion = base;
            return null;
        }
        if (resp.statusCode() != 200) {
            throw new IOException("LLM HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonObject out = JsonParser.parseString(resp.body()).getAsJsonObject();
        int tokens = out.has("tokens_predicted") ? out.get("tokens_predicted").getAsInt() : 0;
        requests.incrementAndGet();
        tokensGenerated.addAndGet(tokens);
        return new Result(out.get("content").getAsString(), -1, (System.nanoTime() - t0) / 1_000_000, tokens);
    }

    private HttpResponse<String> post(String url, JsonObject body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** A lent GPU's own queue and workers. */
    private final class Lane {
        final Backend backend;
        final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
        final AtomicLong answered = new AtomicLong();
        final List<Thread> threads;
        volatile boolean open = true;

        Lane(Backend backend) {
            this.backend = backend;
            this.threads = java.util.stream.IntStream.range(0, Math.max(1, backend.slots()))
                    .mapToObj(i -> Thread.ofVirtual().name("twt-lent-" + i).start(this::work)).toList();
        }

        private void work() {
            while (open) {
                Job job;
                try {
                    job = queue.take();
                } catch (InterruptedException e) {
                    return;
                }
                if (job.future.isCancelled()) {
                    continue;
                }
                try {
                    Result r = backend.run(new Request(job.messages, job.temperature, job.maxTokens, job.jsonSchema), piece -> {
                        if (job.onDelta != null && !piece.isEmpty()) {
                            job.streamed = true;
                            job.onDelta.accept(piece);
                        }
                    }, job.future::isCancelled);
                    answered.incrementAndGet();
                    lentRequests.incrementAndGet();
                    requests.incrementAndGet();
                    tokensGenerated.addAndGet(r.tokens());
                    job.future.complete(r);
                } catch (Throwable t) {
                    if (!(t instanceof InterruptedException)) {
                        LOG.debug("[llm] {}'s GPU failed a request: {}", backend.name(), t.toString());
                    }
                    fallBack(job, t);
                }
            }
        }

        void close() {
            open = false;
            threads.forEach(Thread::interrupt);
            Job j;
            while ((j = queue.poll()) != null) {
                fallBack(j, new IOException(backend.name() + " stopped lending their GPU"));
            }
        }
    }

    /** A request a lent GPU couldn't answer goes to the server's model, unless part of its answer already went out. */
    private void fallBack(Job job, Throwable why) {
        if (job.future.isDone()) {
            return;
        }
        if (!job.streamed && running && ready()) {
            queue.add(job);
        } else {
            job.future.completeExceptionally(why);
        }
    }

    private static final class Job implements Comparable<Job> {
        final Priority priority;
        final long seq;
        final List<Message> messages;
        final double temperature;
        final int maxTokens;
        final JsonObject jsonSchema;
        final Consumer<String> onDelta;
        final CompletableFuture<Result> future = new CompletableFuture<>();
        /** Whether part of the answer already went out (so it can't be asked again elsewhere). */
        volatile boolean streamed;

        Job(Priority priority, long seq, List<Message> messages, double temperature, int maxTokens, JsonObject jsonSchema,
            Consumer<String> onDelta) {
            this.priority = priority;
            this.seq = seq;
            this.messages = messages;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
            this.jsonSchema = jsonSchema;
            this.onDelta = onDelta;
        }

        @Override
        public int compareTo(Job o) {
            int c = Integer.compare(priority.ordinal(), o.priority.ordinal());
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }
}

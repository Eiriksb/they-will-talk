package dev.eiriksb.theywilltalk.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eiriksb.theywilltalk.TheyWillTalk;

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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * OpenAI-compatible chat client for the bundled llama-server. Requests run through a small priority queue with one
 * worker per llama.cpp slot, so a player waiting for an answer always goes before background work like memory
 * reflection or ambient villager chatter.
 */
public final class LlmClient {
    public enum Priority { CONVERSATION, GREETING, REFLECTION, AMBIENT }

    public record Message(String role, String content) {
        public static Message system(String c) { return new Message("system", c); }
        public static Message user(String c) { return new Message("user", c); }
        public static Message assistant(String c) { return new Message("assistant", c); }
    }

    public record Result(String text, long firstTokenMs, long totalMs, int tokens) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Supplier<String> baseUrl;
    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
    private final AtomicLong seq = new AtomicLong();
    private final List<Thread> workers;
    private volatile boolean running = true;

    // stats for the dashboard
    public final AtomicLong requests = new AtomicLong();
    public final AtomicLong tokensGenerated = new AtomicLong();
    public volatile long lastFirstTokenMs;
    public volatile double lastTokensPerSecond;

    public LlmClient(Supplier<String> baseUrl, int workers) {
        this.baseUrl = baseUrl;
        this.workers = java.util.stream.IntStream.range(0, Math.max(1, workers))
                .mapToObj(i -> Thread.ofPlatform().daemon().name("twt-llm-" + i).start(this::workLoop))
                .toList();
    }

    public void shutdown() {
        running = false;
        workers.forEach(Thread::interrupt);
        Job j;
        while ((j = queue.poll()) != null) {
            j.future.completeExceptionally(new IllegalStateException("shutting down"));
        }
    }

    public int queued() {
        return queue.size();
    }

    /**
     * Streams a chat completion. {@code onDelta} is called on the worker thread with each text fragment.
     *
     * @param jsonSchema optional JSON schema (constrains output via llama.cpp grammar)
     */
    public CompletableFuture<Result> chat(Priority priority, List<Message> messages, double temperature, int maxTokens,
                                          JsonObject jsonSchema, Consumer<String> onDelta) {
        Job job = new Job(priority, seq.incrementAndGet(), messages, temperature, maxTokens, jsonSchema, onDelta);
        queue.add(job);
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
        JsonObject body = new JsonObject();
        JsonArray msgs = new JsonArray();
        for (Message m : job.messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            msgs.add(o);
        }
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
                        job.onDelta.accept(piece);
                    } catch (Exception e) {
                        TheyWillTalk.LOGGER.warn("LLM delta handler failed", e);
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

    private static final class Job implements Comparable<Job> {
        final Priority priority;
        final long seq;
        final List<Message> messages;
        final double temperature;
        final int maxTokens;
        final JsonObject jsonSchema;
        final Consumer<String> onDelta;
        final CompletableFuture<Result> future = new CompletableFuture<>();

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

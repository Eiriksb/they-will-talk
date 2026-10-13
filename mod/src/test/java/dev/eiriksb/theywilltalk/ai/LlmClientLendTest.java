package dev.eiriksb.theywilltalk.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Requests about a player who lends their GPU go to it, and only those; when it fails they fall back to the server. */
class LlmClientLendTest {
    private static final List<LlmClient.Message> HELLO = List.of(LlmClient.Message.user("hello"));
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private HttpServer local;
    private final AtomicInteger localCalls = new AtomicInteger();
    private LlmClient client;

    /** A stand-in for the server's own llama-server that always says "local". */
    private String startLocal() throws IOException {
        local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/v1/chat/completions", ex -> {
            localCalls.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"local\"}}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        local.start();
        return "http://127.0.0.1:" + local.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.shutdown();
        }
        if (local != null) {
            local.stop(0);
        }
    }

    private static final class FakeGpu implements LlmClient.Backend {
        final List<String> seen = new CopyOnWriteArrayList<>();
        volatile boolean fail;
        volatile boolean failAfterStreaming;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public int slots() {
            return 1;
        }

        @Override
        public LlmClient.Result run(LlmClient.Request request, Consumer<String> onDelta, BooleanSupplier cancelled) throws IOException {
            seen.add(request.messages().getLast().content());
            if (fail) {
                throw new IOException("gone");
            }
            onDelta.accept("lent");
            if (failAfterStreaming) {
                throw new IOException("gone halfway");
            }
            return new LlmClient.Result("lent", 5, 10, 1);
        }
    }

    @Test
    void onlyTheLendersOwnRequestsGoToTheirGpu() throws Exception {
        client = new LlmClient(startLocal()::toString, 1);
        FakeGpu gpu = new FakeGpu();
        client.lend(alice, gpu);
        StringBuilder streamed = new StringBuilder();
        assertEquals("lent", client.chat(LlmClient.Priority.CONVERSATION, HELLO, 0.5, 10, null, streamed::append, alice).get(5, TimeUnit.SECONDS).text());
        assertEquals("lent", streamed.toString());
        assertEquals("local", client.chat(LlmClient.Priority.CONVERSATION, HELLO, 0.5, 10, null, null, bob).get(5, TimeUnit.SECONDS).text());
        assertEquals("local", client.chat(LlmClient.Priority.AMBIENT, HELLO, 0.5, 10, null, null).get(5, TimeUnit.SECONDS).text());
        assertEquals(1, gpu.seen.size());
        assertEquals(1, client.lentRequests.get());
        assertEquals(1, client.lenders().size());
        client.unlend(alice);
        assertEquals("local", client.chat(LlmClient.Priority.CONVERSATION, HELLO, 0.5, 10, null, null, alice).get(5, TimeUnit.SECONDS).text());
        assertTrue(client.lenders().isEmpty());
    }

    @Test
    void aFailedLentRequestFallsBackUnlessPartOfItWentOut() throws Exception {
        client = new LlmClient(startLocal()::toString, 1);
        FakeGpu gpu = new FakeGpu();
        client.lend(alice, gpu);
        gpu.fail = true;
        assertEquals("local", client.chat(LlmClient.Priority.CONVERSATION, HELLO, 0.5, 10, null, null, alice).get(5, TimeUnit.SECONDS).text());
        gpu.fail = false;
        gpu.failAfterStreaming = true;
        int before = localCalls.get();
        assertThrows(ExecutionException.class, () -> client.chat(LlmClient.Priority.CONVERSATION, HELLO, 0.5, 10, null, s -> {}, alice)
                .get(5, TimeUnit.SECONDS));
        assertEquals(before, localCalls.get(), "a half-streamed answer mustn't be asked again");
    }

    @Test
    void aServerWithoutAModelStillAnswersPlayersWhoLend() throws Exception {
        client = new LlmClient(() -> null, 1);
        assertFalse(client.ready());
        assertFalse(client.readyFor(alice));
        client.lend(alice, new FakeGpu());
        assertTrue(client.readyFor(alice));
        assertFalse(client.readyFor(bob));
        assertEquals("lent", client.chat(LlmClient.Priority.CONVERSATION, HELLO, 0.5, 10, null, null, alice).get(5, TimeUnit.SECONDS).text());
        assertThrows(ExecutionException.class, () -> client.chat(LlmClient.Priority.CONVERSATION, HELLO, 0.5, 10, null, null, bob).get(5, TimeUnit.SECONDS));
    }
}

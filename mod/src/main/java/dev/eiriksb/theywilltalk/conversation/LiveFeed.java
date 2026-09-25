package dev.eiriksb.theywilltalk.conversation;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** In-memory activity stream (heard / replied / gossip / relationship changes) for the dashboard's live view. */
public final class LiveFeed {
    private static final int KEEP = 200;
    private final Deque<JsonObject> recent = new ArrayDeque<>();
    private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();

    public void publish(String type, JsonObject data) {
        data.addProperty("type", type);
        data.addProperty("ts", System.currentTimeMillis());
        synchronized (recent) {
            recent.addLast(data);
            while (recent.size() > KEEP) {
                recent.removeFirst();
            }
        }
        for (Consumer<JsonObject> l : listeners) {
            try {
                l.accept(data);
            } catch (Exception ignored) {
                // a closed dashboard connection; it unsubscribes itself
            }
        }
    }

    public List<JsonObject> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    public void subscribe(Consumer<JsonObject> l) {
        listeners.add(l);
    }

    public void unsubscribe(Consumer<JsonObject> l) {
        listeners.remove(l);
    }
}

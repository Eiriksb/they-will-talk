package dev.eiriksb.theywilltalk.api;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

/** Posted on the game bus ({@code NeoForge.EVENT_BUS}) once a world is loaded and They Will Talk is running. */
public final class TwtReadyEvent extends Event {
    private final TheyWillTalkApi api;
    private final MinecraftServer server;

    public TwtReadyEvent(TheyWillTalkApi api, MinecraftServer server) {
        this.api = api;
        this.server = server;
    }

    public TheyWillTalkApi api() {
        return api;
    }

    public MinecraftServer server() {
        return server;
    }
}

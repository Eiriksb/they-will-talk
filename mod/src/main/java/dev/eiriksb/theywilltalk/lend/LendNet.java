package dev.eiriksb.theywilltalk.lend;

import dev.eiriksb.theywilltalk.TheyWillTalk;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Messages for lending a GPU (PLAN: "client-lent GPU"). Optional, so players without They Will Talk still join, and
 * handled on the network thread so a lent answer streams in without waiting for server ticks.
 */
public final class LendNet {
    private LendNet() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(TheyWillTalk.MOD_ID, path));
    }

    /** Server → client on joining: whether the server takes lent GPUs, and whether it has a model of its own. */
    public record Welcome(boolean accepting, boolean ownModel) implements CustomPacketPayload {
        public static final Type<Welcome> TYPE = LendNet.type("lend_welcome");
        static final StreamCodec<ByteBuf, Welcome> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Welcome::accepting,
                ByteBufCodecs.BOOL, Welcome::ownModel, Welcome::new);

        @Override
        public Type<Welcome> type() {
            return TYPE;
        }
    }

    /** Client → server: "my GPU is ready" ({@code slots} > 0), or "I've stopped" (0). */
    public record Offer(String model, int slots) implements CustomPacketPayload {
        public static final Type<Offer> TYPE = LendNet.type("lend_offer");
        static final StreamCodec<ByteBuf, Offer> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(200), Offer::model,
                ByteBufCodecs.VAR_INT, Offer::slots, Offer::new);

        @Override
        public Type<Offer> type() {
            return TYPE;
        }
    }

    /** Server → client: a request to run (JSON: messages, temperature, maxTokens, schema). */
    public record Job(long id, String request) implements CustomPacketPayload {
        public static final Type<Job> TYPE = LendNet.type("lend_job");
        static final StreamCodec<ByteBuf, Job> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, Job::id,
                ByteBufCodecs.stringUtf8(1 << 19), Job::request, Job::new);

        @Override
        public Type<Job> type() {
            return TYPE;
        }
    }

    /** Server → client: never mind that request. */
    public record Cancel(long id) implements CustomPacketPayload {
        public static final Type<Cancel> TYPE = LendNet.type("lend_cancel");
        static final StreamCodec<ByteBuf, Cancel> CODEC = ByteBufCodecs.VAR_LONG.map(Cancel::new, Cancel::id);

        @Override
        public Type<Cancel> type() {
            return TYPE;
        }
    }

    /** Client → server: the next piece of an answer. */
    public record Delta(long id, String piece) implements CustomPacketPayload {
        public static final Type<Delta> TYPE = LendNet.type("lend_delta");
        static final StreamCodec<ByteBuf, Delta> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, Delta::id,
                ByteBufCodecs.stringUtf8(2000), Delta::piece, Delta::new);

        @Override
        public Type<Delta> type() {
            return TYPE;
        }
    }

    /** Client → server: the whole answer ({@code ok}), or why there's none. */
    public record Done(long id, boolean ok, String text, int firstTokenMs, int tokens) implements CustomPacketPayload {
        /** Most characters an answer can have (a client → server message holds 32 kB). */
        public static final int MAX_TEXT = 8000;
        public static final Type<Done> TYPE = LendNet.type("lend_done");
        static final StreamCodec<ByteBuf, Done> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, Done::id, ByteBufCodecs.BOOL, Done::ok,
                ByteBufCodecs.stringUtf8(MAX_TEXT), Done::text, ByteBufCodecs.VAR_INT, Done::firstTokenMs, ByteBufCodecs.VAR_INT, Done::tokens, Done::new);

        @Override
        public Type<Done> type() {
            return TYPE;
        }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(LendNet::payloads);
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1").optional().executesOn(HandlerThread.NETWORK);
        r.playToClient(Welcome.TYPE, Welcome.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                LendClient.welcome(p, ctx.connection().getRemoteAddress());
            }
        });
        r.playToClient(Job.TYPE, Job.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                LendClient.job(p);
            }
        });
        r.playToClient(Cancel.TYPE, Cancel.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                LendClient.cancel(p);
            }
        });
        r.playToServer(Offer.TYPE, Offer.CODEC, LendServer::offer);
        r.playToServer(Delta.TYPE, Delta.CODEC, LendServer::delta);
        r.playToServer(Done.TYPE, Done.CODEC, LendServer::done);
    }
}

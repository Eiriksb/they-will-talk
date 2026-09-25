package dev.eiriksb.theywilltalk.conversation;

import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;

/** Visible and audible reactions: particles, head shakes and the classic villager "hmm"s. Server thread only. */
public final class Gestures {
    private Gestures() {}

    /** The villager noticed it's being talked to. */
    public static void listening(Entity e, VillagerKind kind) {
        if (usesVillagerSounds(kind)) {
            sound(e, SoundEvents.VILLAGER_AMBIENT, 0.8f, 1.0f + (e.getRandom().nextFloat() - 0.5f) * 0.2f);
        }
    }

    public static void react(Entity e, VillagerKind kind, String emotion) {
        if (!(e.level() instanceof ServerLevel level)) {
            return;
        }
        boolean sounds = usesVillagerSounds(kind);
        switch (emotion == null ? "neutral" : emotion) {
            case "happy", "excited", "laugh" -> {
                particles(level, e, ParticleTypes.HAPPY_VILLAGER, 6);
                if (sounds) {
                    sound(e, SoundEvents.VILLAGER_YES, 0.7f, 1.05f);
                }
            }
            case "flirty" -> particles(level, e, ParticleTypes.HEART, 3);
            case "angry", "annoyed" -> {
                particles(level, e, ParticleTypes.ANGRY_VILLAGER, emotion.equals("angry") ? 4 : 2);
                if (e instanceof AbstractVillager v) {
                    v.setUnhappyCounter(40); // the vanilla "no" head shake
                }
                if (sounds) {
                    sound(e, SoundEvents.VILLAGER_NO, 0.8f, 0.95f);
                }
            }
            case "sad" -> {
                particles(level, e, ParticleTypes.FALLING_WATER, 4);
                if (sounds) {
                    sound(e, SoundEvents.VILLAGER_NO, 0.5f, 0.8f);
                }
            }
            case "surprised", "scared" -> {
                if (sounds) {
                    sound(e, SoundEvents.VILLAGER_TRADE, 0.8f, 1.25f);
                }
            }
            case "confused" -> particles(level, e, ParticleTypes.ENCHANT, 8);
            case "sleepy" -> {
                if (sounds) {
                    sound(e, SoundEvents.VILLAGER_AMBIENT, 0.5f, 0.7f);
                }
            }
            default -> { }
        }
    }

    private static boolean usesVillagerSounds(VillagerKind kind) {
        return TwtConfig.VILLAGER_SOUNDS.get() && (kind == VillagerKind.VANILLA || kind == VillagerKind.WANDERING_TRADER);
    }

    private static void particles(ServerLevel level, Entity e, ParticleOptions type, int count) {
        level.sendParticles(type, e.getX(), e.getEyeY() + 0.4, e.getZ(), count, 0.3, 0.25, 0.3, 0.02);
    }

    private static void sound(Entity e, SoundEvent s, float volume, float pitch) {
        e.level().playSound(null, e.getX(), e.getY(), e.getZ(), s, SoundSource.NEUTRAL, volume, pitch);
    }
}

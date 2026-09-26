package dev.eiriksb.theywilltalk.villager;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.Optional;

/** Vanilla villagers and wandering traders. Also the base for mods whose villagers extend {@link Villager}. */
public class VanillaAdapter implements VillagerAdapter {
    private static final String[] LEVELS = {"", "novice", "apprentice", "journeyman", "expert", "master"};

    @Override
    public VillagerKind kind() {
        return VillagerKind.VANILLA;
    }

    @Override
    public boolean matches(Entity entity) {
        return entity instanceof Villager || entity instanceof WanderingTrader;
    }

    @Override
    public void collect(Entity entity, ServerPlayer player, VillagerFacts f) {
        f.uuid = entity.getUUID();
        f.dimension = entity.level().dimension().location().toString();
        f.x = entity.getX();
        f.y = entity.getY();
        f.z = entity.getZ();
        f.alive = entity.isAlive();
        if (entity.hasCustomName() && entity.getCustomName() != null) {
            f.name = entity.getCustomName().getString();
        }
        if (entity instanceof WanderingTrader trader) {
            f.kind = VillagerKind.WANDERING_TRADER;
            f.job = "wandering trader";
            f.extra.put("despawns in", trader.getDespawnDelay() / 20 + "s");
            collectOffers(trader, f);
            return;
        }
        if (!(entity instanceof Villager v)) {
            return;
        }
        f.kind = VillagerKind.VANILLA;
        VillagerProfession prof = v.getVillagerData().getProfession();
        String profName = BuiltInRegistries.VILLAGER_PROFESSION.getKey(prof).getPath();
        f.job = switch (profName) {
            case "none" -> "unemployed";
            case "nitwit" -> "nitwit (no job, never will have one)";
            default -> profName;
        };
        f.jobLevel = v.getVillagerData().getLevel();
        if (!"none".equals(profName) && !"nitwit".equals(profName) && f.jobLevel > 0 && f.jobLevel < LEVELS.length) {
            f.extra.put("career level", LEVELS[f.jobLevel]);
        }
        f.biomeType = BuiltInRegistries.VILLAGER_TYPE.getKey(v.getVillagerData().getType()).getPath();
        f.ageGroup = v.isBaby() ? "child" : "adult";
        f.sleeping = v.isSleeping();
        if (player != null) {
            f.reputation = v.getPlayerReputation(player);
        }
        Optional<GlobalPos> meeting = v.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
        meeting.ifPresent(gp -> f.village = vanillaVillage(gp));
        f.extra.put("has a home bed", Boolean.toString(v.getBrain().getMemory(MemoryModuleType.HOME).isPresent()));
        if (!v.isBaby()) {
            collectOffers(v, f);
        }
    }

    static VillagerFacts.Village vanillaVillage(GlobalPos bell) {
        String dim = bell.dimension().location().toString();
        String key = "vanilla:" + dim + ":" + bell.pos().getX() + "," + bell.pos().getY() + "," + bell.pos().getZ();
        return new VillagerFacts.Village(key, "vanilla", Names.village(bell.pos().asLong() ^ dim.hashCode()), dim,
                bell.pos().getX(), bell.pos().getY(), bell.pos().getZ(), -1);
    }

    private static void collectOffers(AbstractVillager v, VillagerFacts f) {
        try {
            int n = 0;
            for (MerchantOffer o : v.getOffers()) {
                if (n++ >= 5) {
                    break;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(stack(o.getCostA()));
                if (!o.getCostB().isEmpty()) {
                    sb.append(" + ").append(stack(o.getCostB()));
                }
                sb.append(" -> ").append(stack(o.getResult()));
                if (o.isOutOfStock()) {
                    sb.append(" (sold out)");
                }
                f.offers.add(sb.toString());
            }
        } catch (Exception ignored) {
            // some modded villagers don't like being asked for offers server-side
        }
    }

    private static String stack(ItemStack s) {
        return s.getCount() + " " + s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public boolean walkTo(Entity entity, Entity target) {
        if (entity instanceof Villager v) {
            // The brain's own walking behaviour follows WALK_TARGET in every activity; a little faster than a stroll.
            v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, 0.65f, 2));
            v.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
            return true;
        }
        if (entity instanceof Mob mob) {
            mob.getNavigation().moveTo(target, 0.55);
            mob.getLookControl().setLookAt(target, 30f, 30f);
            return true;
        }
        return false;
    }

    @Override
    public void stopWalking(Entity entity) {
        if (entity instanceof Villager v) {
            v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        } else if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
        }
    }

    @Override
    public void holdAttention(Entity entity, Entity target) {
        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(target, 30f, 30f);
        }
        if (entity instanceof Villager v) {
            v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            v.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
        }
    }
}

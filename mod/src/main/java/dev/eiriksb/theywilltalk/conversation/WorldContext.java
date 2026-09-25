package dev.eiriksb.theywilltalk.conversation;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** What a villager can perceive right now, captured on the server thread and turned into prompt lines. */
public final class WorldContext {
    private WorldContext() {}

    public static List<String> capture(Entity villager, ServerPlayer player, Function<Entity, String> nameOf) {
        List<String> out = new ArrayList<>();
        ServerLevel level = (ServerLevel) villager.level();
        long time = level.getDayTime() % 24000;
        String tod = time < 1000 ? "early morning (sunrise)" : time < 6000 ? "morning" : time < 9000 ? "midday"
                : time < 12000 ? "afternoon" : time < 13500 ? "evening (sunset)" : time < 18000 ? "night" : time < 23000 ? "late night" : "dawn";
        String weather = level.isThundering() ? "a thunderstorm" : level.isRaining() ? "rain" : "clear skies";
        out.add("It is " + tod + " on day " + (level.getDayTime() / 24000 + 1) + ", with " + weather + ".");

        Holder<Biome> biome = level.getBiome(villager.blockPosition());
        biome.unwrapKey().ifPresent(k -> out.add("You are in a " + k.location().getPath().replace('_', ' ') + " biome"
                + (level.dimension() == net.minecraft.world.level.Level.OVERWORLD ? "." : " in " + level.dimension().location().getPath().replace('_', ' ') + "!")));

        if (level.getRaidAt(villager.blockPosition()) != null) {
            out.add("A PILLAGER RAID is attacking the village right now! You are terrified.");
        }

        AABB box = villager.getBoundingBox().inflate(16);
        List<Entity> near = level.getEntities(villager, box, e -> e instanceof LivingEntity && e.isAlive());
        int monsters = 0;
        int golems = 0;
        List<String> villagers = new ArrayList<>();
        List<String> players = new ArrayList<>();
        for (Entity e : near) {
            if (e instanceof Enemy) {
                monsters++;
            } else if (e instanceof IronGolem) {
                golems++;
            } else if (e instanceof Player p && p != player) {
                players.add(p.getGameProfile().getName());
            } else {
                String n = nameOf.apply(e);
                if (n != null && villagers.size() < 4) {
                    villagers.add(n);
                }
            }
        }
        if (monsters > 0) {
            out.add(monsters == 1 ? "There is a hostile monster nearby!" : "There are " + monsters + " hostile monsters nearby!");
        }
        if (golems > 0) {
            out.add("The village iron golem is standing nearby.");
        }
        if (!villagers.isEmpty()) {
            out.add("Villagers near you: " + String.join(", ", villagers) + ".");
        }
        if (!players.isEmpty()) {
            out.add("Other players nearby: " + String.join(", ", players) + ".");
        }

        if (player != null) {
            List<String> gear = new ArrayList<>();
            ItemStack hand = player.getMainHandItem();
            if (!hand.isEmpty()) {
                gear.add("holding " + itemName(hand));
            }
            ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
            if (!chest.isEmpty()) {
                gear.add("wearing " + itemName(chest));
            }
            ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
            if (!head.isEmpty()) {
                gear.add("with " + itemName(head) + " on their head");
            }
            String who = player.getGameProfile().getName();
            if (!gear.isEmpty()) {
                out.add(who + " is " + String.join(", ", gear) + ".");
            }
            if (player.getHealth() < player.getMaxHealth() * 0.3f) {
                out.add(who + " looks badly hurt.");
            }
            if (player.isInvisible()) {
                out.add(who + " is INVISIBLE - you can hear them but can't see them, which is spooky.");
            }
        }
        return out;
    }

    private static String itemName(ItemStack s) {
        String n = s.getHoverName().getString();
        if (s.getCount() > 1) {
            return s.getCount() + " " + n.toLowerCase(Locale.ROOT);
        }
        String key = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return (key.matches("^[aeiou].*") ? "an " : "a ") + n.toLowerCase(Locale.ROOT);
    }
}

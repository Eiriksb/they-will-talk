package dev.eiriksb.theywilltalk.events;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.function.Predicate;

/** Items changing hands: what players carry, what villagers take and give, and errand letters. Server thread. */
final class ErrandItems {
    private static final String LETTER_TAG = "theywilltalk_errand";

    private ErrandItems() {}

    static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl) ? BuiltInRegistries.ITEM.get(rl) : null;
    }

    static boolean exists(String id) {
        return item(id) != null;
    }

    /** "Wheat", "Iron Ingot": the item's name as players see it. */
    static String name(String id) {
        Item item = item(id);
        return item == null ? id : item.getDescription().getString();
    }

    static int count(ServerPlayer player, String id) {
        Item item = item(id);
        return item == null ? 0 : count(player.getInventory(), s -> s.is(item));
    }

    private static int count(Inventory inv, Predicate<ItemStack> match) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && match.test(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Takes {@code count} of an item from the player; false (and nothing taken) when they don't have that many. */
    static boolean take(ServerPlayer player, String id, int count) {
        Item item = item(id);
        return item != null && take(player, s -> s.is(item), count);
    }

    private static boolean take(ServerPlayer player, Predicate<ItemStack> match, int count) {
        Inventory inv = player.getInventory();
        if (count(inv, match) < count) {
            return false;
        }
        inv.clearOrCountMatchingItems(match, count, player.inventoryMenu.getCraftSlots());
        player.containerMenu.broadcastChanges();
        return true;
    }

    /** Puts items in the player's inventory, dropping what doesn't fit at their feet. */
    static void give(ServerPlayer player, ItemStack stack) {
        ItemStack rest = stack.copy();
        if (!player.getInventory().add(rest) && !rest.isEmpty()) {
            player.drop(rest, false);
        }
        player.containerMenu.broadcastChanges();
    }

    static void give(ServerPlayer player, String id, int count) {
        Item item = item(id);
        if (item == null) {
            return;
        }
        int left = count;
        while (left > 0) {
            int n = Math.min(left, item.getDefaultMaxStackSize());
            give(player, new ItemStack(item, n));
            left -= n;
        }
    }

    static void giveEmeralds(ServerPlayer player, int count) {
        give(player, "emerald", count);
    }

    /** The letter a player carries for a DELIVER errand. */
    static ItemStack letter(Errand e, String village) {
        ItemStack paper = new ItemStack(Items.PAPER);
        paper.set(DataComponents.CUSTOM_NAME, Component.literal("Letter for " + e.targetName)
                .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));
        paper.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("From " + e.villagerName + (village == null ? "" : " of " + village)).withStyle(ChatFormatting.GRAY),
                Component.literal("Deliver it to " + e.targetName + " in person").withStyle(ChatFormatting.DARK_GRAY))));
        CompoundTag tag = new CompoundTag();
        tag.putLong(LETTER_TAG, e.id);
        paper.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        paper.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return paper;
    }

    private static Predicate<ItemStack> isLetter(long errand) {
        return s -> {
            CustomData data = s.get(DataComponents.CUSTOM_DATA);
            return data != null && data.contains(LETTER_TAG) && data.copyTag().getLong(LETTER_TAG) == errand;
        };
    }

    static boolean hasLetter(ServerPlayer player, long errand) {
        return count(player.getInventory(), isLetter(errand)) > 0;
    }

    static boolean takeLetter(ServerPlayer player, long errand) {
        return take(player, isLetter(errand), 1);
    }
}

package dev.eiriksb.theywilltalk.integration;

import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.villager.VanillaAdapter;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import net.conczin.mca.Config;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.entity.ai.Memories;
import net.conczin.mca.entity.ai.Relationship;
import net.conczin.mca.entity.ai.Traits;
import net.conczin.mca.entity.ai.brain.VillagerBrain;
import net.conczin.mca.entity.ai.relationship.AgeState;
import net.conczin.mca.entity.ai.relationship.Gender;
import net.conczin.mca.server.world.data.FamilyTree;
import net.conczin.mca.server.world.data.FamilyTreeNode;
import net.conczin.mca.server.world.data.Village;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.UUID;

/** MCA Reborn villagers: real names, genders, ages, personalities, moods, hearts, marriages and family trees. */
final class McaAdapter extends VanillaAdapter {

    @Override
    public VillagerKind kind() {
        return VillagerKind.MCA;
    }

    @Override
    public boolean matches(Entity entity) {
        return entity instanceof VillagerEntityMCA;
    }

    static void warnIfMcaChatAiEnabled() {
        try {
            if (Config.getInstance().enableVillagerChatAI) {
                TheyWillTalk.LOGGER.warn("MCA's own villager chat AI is enabled (config/mca.json enableVillagerChatAI). "
                        + "MCA villagers may answer typed chat twice; set it to false to let They Will Talk handle them.");
            }
        } catch (Throwable ignored) {
            // older/newer MCA without this field
        }
    }

    @Override
    public void collect(Entity entity, ServerPlayer player, VillagerFacts f) {
        super.collect(entity, player, f); // job, offers, position, vanilla reputation
        VillagerEntityMCA v = (VillagerEntityMCA) entity;
        f.kind = VillagerKind.MCA;
        f.name = v.getName().getString();
        Gender g = v.getGenetics().getGender();
        f.gender = g == Gender.FEMALE ? "f" : g == Gender.MALE ? "m" : null;
        AgeState age = v.getAgeState();
        f.ageGroup = switch (age) {
            case BABY, TODDLER -> "baby";
            case CHILD -> "child";
            case TEEN -> "teen";
            default -> "adult";
        };
        String prof = v.getProfessionId().getPath();
        if (prof.startsWith("mca.")) {
            prof = prof.substring(4);
        }
        f.job = switch (prof) {
            case "none" -> "unemployed";
            case "guard" -> "village guard";
            case "archer" -> "village archer";
            default -> prof.replace('_', ' ');
        };

        VillagerBrain<?> brain = v.getVillagerBrain();
        f.personalityHint = brain.getPersonalityId().getPath();
        f.mood = brain.getMood().getName();
        f.moodLevel = brain.getMoodValue();
        for (Traits.Trait t : v.getTraits().getTraits()) {
            f.traits.add(t.getId().getPath().replace('_', ' '));
        }

        Optional<Village> home = v.getResidency().getHomeVillage();
        home.ifPresent(vil -> f.village = new VillagerFacts.Village("mca:" + entity.level().dimension().location() + ":" + vil.getId(),
                "mca", vil.getName(), entity.level().dimension().location().toString(),
                vil.getCenter().getX(), vil.getCenter().getY(), vil.getCenter().getZ(), vil.getPopulation()));

        var rel = v.getRelationships();
        rel.getPartnerUUID().ifPresent(partner -> f.extra.put("relationship", rel.getRelationshipState().name().toLowerCase().replace('_', ' ')
                + rel.getPartnerName().map(n -> " (" + n.getString() + ")").orElse("")));

        if (player != null) {
            Memories m = brain.getMemoriesForPlayer(player);
            f.hearts = m.getHearts();
            if (Relationship.IS_MARRIED.test(v, player.getUUID())) {
                f.relationToPlayer = "your spouse - you are married to them";
            } else if (Relationship.IS_ENGAGED.test(v, player.getUUID())) {
                f.relationToPlayer = "your fiancé(e) - you are engaged";
            } else if (Relationship.IS_PROMISED.test(v, player.getUUID())) {
                f.relationToPlayer = "the one you've promised yourself to";
            } else if (Relationship.IS_PARENT.test(v, player.getUUID())) {
                f.relationToPlayer = "your parent (they raised you)";
            } else if (Relationship.IS_KID.test(v, player.getUUID())) {
                f.relationToPlayer = "your child";
            } else if (Relationship.IS_RELATIVE.test(v, player.getUUID())) {
                f.relationToPlayer = "a relative of yours";
            }
        }

        if (entity.level() instanceof ServerLevel level) {
            try {
                FamilyTree tree = FamilyTree.get(level);
                tree.getOrEmpty(v.getUUID()).ifPresent(node -> {
                    link(tree, f, "father", node.father());
                    link(tree, f, "mother", node.mother());
                    link(tree, f, "spouse", node.partner());
                    node.children().forEach(c -> link(tree, f, "child", c));
                    node.siblings().forEach(s -> link(tree, f, "sibling", s));
                });
            } catch (Throwable t) {
                TheyWillTalk.LOGGER.debug("MCA family tree unavailable: {}", t.toString());
            }
        }
        f.customPrompt = null; // MCA generates a random one when blank; our own persona is richer
    }

    private static void link(FamilyTree tree, VillagerFacts f, String relation, UUID id) {
        if (id == null || id.equals(Util.NIL_UUID)) {
            return;
        }
        Optional<FamilyTreeNode> n = tree.getOrEmpty(id);
        String name = n.map(FamilyTreeNode::getName).orElse("someone");
        f.family.add(new VillagerFacts.FamilyLink(relation, id, name, n.map(FamilyTreeNode::isPlayer).orElse(false),
                n.map(FamilyTreeNode::isDeceased).orElse(false)));
    }

    @Override
    public void applyOutcome(Entity entity, ServerPlayer player, int affinityDelta) {
        if (entity instanceof VillagerEntityMCA v && affinityDelta != 0) {
            // MCA's own reward path: heart/angry particles, mood change, advancements.
            v.getVillagerBrain().rewardHearts(player, affinityDelta * 2);
        }
    }
}

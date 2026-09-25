package dev.eiriksb.theywilltalk.integration;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.util.Tuple;
import dev.eiriksb.theywilltalk.villager.VillagerAdapter;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.Locale;

/** MineColonies citizens: name, job, happiness, colony, partner, parents, children and siblings. */
final class MineColoniesAdapter implements VillagerAdapter {

    @Override
    public VillagerKind kind() {
        return VillagerKind.MINECOLONIES;
    }

    @Override
    public boolean matches(Entity entity) {
        return entity instanceof AbstractEntityCitizen;
    }

    @Override
    public void collect(Entity entity, ServerPlayer player, VillagerFacts f) {
        AbstractEntityCitizen citizen = (AbstractEntityCitizen) entity;
        f.kind = VillagerKind.MINECOLONIES;
        f.uuid = entity.getUUID();
        f.dimension = entity.level().dimension().location().toString();
        f.x = entity.getX();
        f.y = entity.getY();
        f.z = entity.getZ();
        f.gender = citizen.isFemale() ? "f" : "m";
        ICitizenData data = citizen.getCitizenData();
        if (data == null) {
            f.name = entity.getName().getString();
            return;
        }
        f.name = data.getName();
        f.ageGroup = data.isChild() ? "child" : "adult";
        IJob<?> job = data.getJob();
        f.job = job == null ? "unemployed" : job.getJobRegistryEntry().getKey().getPath().replace('_', ' ');
        f.sleeping = data.isAsleep();
        IColony colony = data.getColony();
        if (colony != null) {
            double happiness = data.getCitizenHappinessHandler().getHappiness(colony, data);
            if (!Double.isNaN(happiness)) {
                f.extra.put("happiness", String.format(Locale.ROOT, "%.1f/10", happiness));
                f.moodLevel = (int) Math.round((happiness - 5) * 3);
                f.mood = happiness >= 8 ? "happy" : happiness >= 5 ? "content" : happiness >= 3 ? "unhappy" : "miserable";
            }
            f.village = new VillagerFacts.Village("minecolonies:" + colony.getDimension().location() + ":" + colony.getID(), "minecolonies",
                    colony.getName(), colony.getDimension().location().toString(), colony.getCenter().getX(), colony.getCenter().getY(),
                    colony.getCenter().getZ(), colony.getCitizenManager().getCitizens().size());
            ICitizenData partner = data.getPartner();
            if (partner != null) {
                f.family.add(new VillagerFacts.FamilyLink("partner", partner.getEntity().map(Entity::getUUID).orElse(null), partner.getName(), false, false));
            }
            Tuple<String, String> parents = data.getParents();
            if (parents != null) {
                if (parents.getA() != null && !parents.getA().isBlank()) {
                    f.family.add(new VillagerFacts.FamilyLink("parent", null, parents.getA(), false, false));
                }
                if (parents.getB() != null && !parents.getB().isBlank()) {
                    f.family.add(new VillagerFacts.FamilyLink("parent", null, parents.getB(), false, false));
                }
            }
            for (Integer id : data.getChildren()) {
                ICitizenData c = colony.getCitizenManager().getCivilian(id);
                if (c != null) {
                    f.family.add(new VillagerFacts.FamilyLink("child", c.getEntity().map(Entity::getUUID).orElse(null), c.getName(), false, false));
                }
            }
            for (Integer id : data.getSiblings()) {
                ICitizenData s = colony.getCitizenManager().getCivilian(id);
                if (s != null) {
                    f.family.add(new VillagerFacts.FamilyLink("sibling", s.getEntity().map(Entity::getUUID).orElse(null), s.getName(), false, false));
                }
            }
        }
        VisibleCitizenStatus status = data.getStatus();
        if (status != null) {
            String key = status.getTranslationKey();
            f.extra.put("current status", key.substring(key.lastIndexOf('.') + 1).replace('_', ' '));
        }
        f.extra.put("citizen id", Integer.toString(data.getId()));
    }

    @Override
    public void holdAttention(Entity entity, Entity target) {
        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(target, 30f, 30f);
        }
    }
}

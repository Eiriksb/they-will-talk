package dev.eiriksb.theywilltalk.integration;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import dev.eiriksb.theywilltalk.faces.FaceSpec;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/** What a MineColonies citizen's face is drawn from: the synced data MineColonies picks their skin by. Only loaded with MineColonies. */
public final class MineColoniesFaces {
    private MineColoniesFaces() {}

    /** Null for anything that isn't a citizen. */
    public static FaceSpec spec(Entity entity) {
        if (!(entity instanceof AbstractEntityCitizen citizen)) {
            return null;
        }
        SynchedEntityData data = citizen.getEntityData();
        ResourceLocation model = ResourceLocation.tryParse(data.get(AbstractEntityCitizen.DATA_MODEL));
        if (model == null) {
            return null;
        }
        String style = data.get(AbstractEntityCitizen.DATA_STYLE);
        return new FaceSpec.Citizen(model.getPath(), citizen.isFemale(), data.get(AbstractEntityCitizen.DATA_TEXTURE),
                data.get(AbstractEntityCitizen.DATA_TEXTURE_SUFFIX), style == null || style.isBlank() ? "default" : style);
    }
}

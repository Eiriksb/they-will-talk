package dev.eiriksb.theywilltalk.villager;

import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.ai.TtsClient;
import dev.eiriksb.theywilltalk.data.Store;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which entities can talk, and who they are. Keeps each villager's {@link VillagerProfile} (generated once,
 * deterministically from its UUID, then stored so admin edits stick) and casts a voice that fits its personality.
 */
public final class VillagerRegistry {
    private final List<VillagerAdapter> adapters = new ArrayList<>();
    private final Map<UUID, VillagerProfile> profiles = new ConcurrentHashMap<>();
    private final Store store;
    private final TtsClient tts;

    public VillagerRegistry(Store store, TtsClient tts) {
        this.store = store;
        this.tts = tts;
    }

    /** Loads every known profile so first contact never has to wait for the database. */
    public void preload() {
        try {
            for (VillagerProfile p : store.db.query("SELECT * FROM villagers", dev.eiriksb.theywilltalk.data.Store::profile).get()) {
                profiles.put(p.uuid(), p);
            }
        } catch (Exception e) {
            TheyWillTalk.LOGGER.warn("Could not preload villager profiles: {}", e.toString());
        }
    }

    /** Adapters are checked in order, so register the most specific (modded villager subclasses) first. */
    public void addAdapter(VillagerAdapter adapter) {
        adapters.add(adapter);
    }

    public VillagerAdapter adapterFor(Entity e) {
        if (e == null || !e.isAlive()) {
            return null;
        }
        for (VillagerAdapter a : adapters) {
            if (a.matches(e)) {
                return enabled(a.kind(), e) ? a : null;
            }
        }
        return null;
    }

    private static boolean enabled(VillagerKind kind, Entity e) {
        return switch (kind) {
            case VANILLA -> TwtConfig.VANILLA_VILLAGERS.get();
            case WANDERING_TRADER -> TwtConfig.WANDERING_TRADERS.get();
            case MCA -> TwtConfig.MCA_VILLAGERS.get();
            case MINECOLONIES -> TwtConfig.MINECOLONIES_CITIZENS.get();
        };
    }

    public boolean canTalk(Entity e) {
        return adapterFor(e) != null;
    }

    /** Collects live facts; server thread only. */
    public VillagerFacts facts(Entity e, ServerPlayer player) {
        VillagerAdapter a = adapterFor(e);
        VillagerFacts f = new VillagerFacts();
        f.uuid = e.getUUID();
        if (a != null) {
            try {
                a.collect(e, player, f);
            } catch (Throwable t) {
                TheyWillTalk.LOGGER.warn("Could not read villager {} ({}): {}", e.getUUID(), a.kind(), t.toString());
            }
            if (f.kind == null) {
                f.kind = a.kind();
            }
        }
        return f;
    }

    /** Profile for a villager, creating (and saving) it on first sight. Server thread. */
    public VillagerProfile profile(Entity e, VillagerFacts facts) {
        VillagerProfile p = profiles.get(e.getUUID());
        if (p == null) {
            p = store.loadProfileNow(e.getUUID()).orElse(null);
            if (p == null) {
                p = generate(facts);
                store.saveProfile(p);
                store.event("met", p.uuid(), null, p.name() + " (" + (facts.job == null ? p.kind().label : facts.job) + ") was discovered");
            }
            profiles.put(e.getUUID(), p);
        }
        // Keep mod-provided identity in sync (MCA renames, MineColonies names, name tags on vanilla villagers).
        String liveName = facts.name != null && !facts.name.isBlank() ? facts.name : p.name();
        String liveGender = facts.gender != null ? facts.gender : p.gender();
        if (!liveName.equals(p.name()) || !liveGender.equals(p.gender()) || facts.kind != p.kind()) {
            p = p.withIdentity(liveName, liveGender, facts.kind);
            profiles.put(p.uuid(), p);
            store.saveProfile(p);
        }
        if (p.voice() == null || p.voice().isBlank() || !voiceExists(p.voice())) {
            VillagerProfile cast = castVoice(p, facts);
            if (cast != p) {
                p = cast;
                profiles.put(p.uuid(), p);
                store.saveProfile(p);
            }
        }
        if (e instanceof Villager v && facts.kind == VillagerKind.VANILLA && TwtConfig.NAME_VANILLA_VILLAGERS.get() && !v.hasCustomName()) {
            v.setCustomName(Component.literal(p.name()));
        }
        return p;
    }

    public VillagerProfile cached(UUID uuid) {
        return profiles.get(uuid);
    }

    /** Admin edits from the dashboard. */
    public void update(VillagerProfile p) {
        profiles.put(p.uuid(), p);
        store.saveProfile(p);
    }

    public void forget(UUID uuid) {
        profiles.remove(uuid);
    }

    private VillagerProfile generate(VillagerFacts f) {
        Random r = new Random(f.uuid.getMostSignificantBits() ^ f.uuid.getLeastSignificantBits());
        String gender = f.gender != null ? f.gender : (r.nextBoolean() ? "f" : "m");
        String name = f.name != null && !f.name.isBlank() ? f.name
                : Names.first(f.biomeType, gender.equals("f"), r) + " " + Names.surname(r);
        Persona persona = Persona.fromMca(f.personalityHint);
        if (persona == null) {
            persona = personaForJob(f, r);
        }
        String quirk = Persona.QUIRKS.get(r.nextInt(Persona.QUIRKS.size()));
        String backstory = f.kind == VillagerKind.WANDERING_TRADER
                ? "travels from village to village with two llamas, never staying more than a few days"
                : Persona.BACKSTORIES.get(r.nextInt(Persona.BACKSTORIES.size()));
        return new VillagerProfile(f.uuid, f.kind, name, gender, persona.key, quirk, backstory, "", 1.0, 1.0, "",
                System.currentTimeMillis(), null);
    }

    private static Persona personaForJob(VillagerFacts f, Random r) {
        String job = f.job == null ? "" : f.job;
        // Nudge some jobs towards fitting archetypes, but keep plenty of surprises.
        if (r.nextFloat() < 0.45f) {
            Persona[] pool = switch (job.split(" ")[0]) {
                case "librarian" -> new Persona[]{Persona.PHILOSOPHICAL, Persona.WISE, Persona.SHY};
                case "cleric" -> new Persona[]{Persona.PEACEFUL, Persona.ODD, Persona.PARANOID};
                case "farmer" -> new Persona[]{Persona.GRUMPY, Persona.CHEERFUL, Persona.LAID_BACK};
                case "armorer", "weaponsmith", "toolsmith" -> new Persona[]{Persona.BOASTFUL, Persona.GRUMPY, Persona.SARCASTIC};
                case "cartographer" -> new Persona[]{Persona.CHATTERBOX, Persona.ODD, Persona.BOASTFUL};
                case "fisherman" -> new Persona[]{Persona.LAID_BACK, Persona.BOASTFUL, Persona.PHILOSOPHICAL};
                case "butcher" -> new Persona[]{Persona.GRUMPY, Persona.SARCASTIC, Persona.CHEERFUL};
                case "shepherd" -> new Persona[]{Persona.PEACEFUL, Persona.SHY, Persona.CHEERFUL};
                case "nitwit" -> new Persona[]{Persona.ODD, Persona.LAID_BACK, Persona.PRANKSTER};
                case "wandering" -> new Persona[]{Persona.GREEDY, Persona.ODD, Persona.DRAMATIC};
                default -> null;
            };
            if (pool != null) {
                return pool[r.nextInt(pool.length)];
            }
        }
        if ("child".equals(f.ageGroup) || "baby".equals(f.ageGroup)) {
            Persona[] kids = {Persona.PRANKSTER, Persona.CHEERFUL, Persona.SHY, Persona.CHATTERBOX, Persona.ANXIOUS, Persona.DRAMATIC};
            return kids[r.nextInt(kids.length)];
        }
        return Persona.random(r);
    }

    private boolean voiceExists(String id) {
        List<TtsClient.Voice> voices = tts.voices();
        return voices.isEmpty() || voices.stream().anyMatch(v -> v.id().equals(id));
    }

    /**
     * Picks a voice whose traits match the personality (grumpy -> gruff/deep, shy -> soft/whispery, ...), with base
     * pitch/speed from personality and age. Returns {@code p} unchanged when the voice server isn't up yet.
     */
    VillagerProfile castVoice(VillagerProfile p, VillagerFacts f) {
        List<TtsClient.Voice> all = tts.voices();
        if (all.isEmpty()) {
            return p;
        }
        String engine = TwtConfig.TTS_ENGINE.get();
        List<TtsClient.Voice> pool = all.stream().filter(v -> v.engine().equals(engine)).toList();
        if (pool.isEmpty()) {
            pool = all;
        }
        boolean child = "child".equals(f.ageGroup) || "baby".equals(f.ageGroup) || "teen".equals(f.ageGroup);
        String gender = p.gender() == null ? "m" : p.gender();
        List<TtsClient.Voice> gendered = pool.stream().filter(v -> v.gender().equals(gender)).toList();
        if (!gendered.isEmpty()) {
            pool = gendered;
        }
        Persona persona = p.personaEnum();
        Set<String> wanted = new HashSet<>(Arrays.asList(persona.voiceTraits.split(" ")));
        if (child) {
            wanted.add("young");
            wanted.remove("old");
        }
        Random r = new Random(p.uuid().getLeastSignificantBits() * 31 + 7);
        Map<String, Double> rank = new java.util.HashMap<>();
        for (TtsClient.Voice v : pool) {
            rank.put(v.id(), score(v, wanted) + r.nextDouble() * 0.9);
        }
        List<TtsClient.Voice> ranked = new ArrayList<>(pool);
        ranked.sort(Comparator.comparingDouble((TtsClient.Voice v) -> -rank.get(v.id())));
        TtsClient.Voice pick = ranked.get(r.nextInt(Math.min(5, ranked.size())));

        double pitch = persona.pitchBias * (1 + r.nextGaussian() * 0.035);
        double speed = persona.speedBias * (1 + r.nextGaussian() * 0.03);
        if (child) {
            pitch *= "teen".equals(f.ageGroup) ? 1.1 : 1.25;
            speed *= 1.05;
        }
        if (f.kind == VillagerKind.WANDERING_TRADER) {
            speed *= 0.95;
        }
        pitch = Math.max(0.8, Math.min(1.45, pitch));
        speed = Math.max(0.8, Math.min(1.25, speed));
        return p.withVoice(pick.id(), round(pitch), round(speed));
    }

    private static double score(TtsClient.Voice v, Set<String> wanted) {
        double s = 0;
        for (String t : v.traits().toLowerCase(Locale.ROOT).split(" ")) {
            if (wanted.contains(t)) {
                s += 1;
            }
        }
        return s;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}

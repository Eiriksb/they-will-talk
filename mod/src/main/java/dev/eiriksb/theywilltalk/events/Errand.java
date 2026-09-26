package dev.eiriksb.theywilltalk.events;

import java.util.Locale;
import java.util.UUID;

/**
 * A favour a villager asked a player for: bring them something, deal with some monsters, or take a letter to another
 * villager. Owned by {@link VillagerEvents} and only changed on the server thread.
 */
public final class Errand {
    public enum Kind {
        /** bring {@code count} of {@code item} */
        FETCH,
        /** kill {@code count} monsters of the {@code item} group (see {@link Needs#hunt}) */
        HUNT,
        /** take a letter to {@code target} */
        DELIVER
    }

    public enum Status {
        OFFERED, ACTIVE, DONE, DECLINED, EXPIRED, ABANDONED, FAILED;

        public boolean open() {
            return this == OFFERED || this == ACTIVE;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    long id = -1;
    final Kind kind;
    final UUID villager;
    final String villagerName;
    final UUID player;
    final String playerName;
    /** FETCH: item id; HUNT: monster group id; DELIVER: unused */
    final String item;
    /** how the villager says it: "wheat", "zombies" */
    final String label;
    final int count;
    int progress;
    final UUID target;
    final String targetName;
    final int reward;
    Status status = Status.OFFERED;
    final long created;
    long updated;
    long deadline;
    /** what the villager said when asking */
    String request = "";

    // not stored
    boolean readyNotified;
    long lastCollectAttempt;
    /** the asking villager's village, for the letter */
    String village;

    Errand(Kind kind, UUID villager, String villagerName, UUID player, String playerName, String item, String label, int count,
           UUID target, String targetName, int reward, long created) {
        this.kind = kind;
        this.villager = villager;
        this.villagerName = villagerName;
        this.player = player;
        this.playerName = playerName;
        this.item = item;
        this.label = label;
        this.count = count;
        this.target = target;
        this.targetName = targetName;
        this.reward = reward;
        this.created = created;
        this.updated = created;
    }

    public long id() {
        return id;
    }

    public Status status() {
        return status;
    }

    /** What the player is asked to do, as the villager would put it: "bring you 12 wheat". */
    String objective() {
        return switch (kind) {
            case FETCH -> "bring you " + count + " " + label;
            case HUNT -> "get rid of " + count + " " + label + " for you";
            case DELIVER -> "take a letter from you to " + targetName;
        };
    }

    /** The same from the player's side: "Bring 12 wheat to David". */
    String task() {
        return switch (kind) {
            case FETCH -> "Bring " + count + " " + label + " to " + villagerName;
            case HUNT -> "Kill " + count + " " + label + " for " + villagerName;
            case DELIVER -> "Take " + villagerName + "'s letter to " + targetName;
        };
    }

    /** From the villager's side, for memories: "bring me 12 wheat". */
    String forMe() {
        return switch (kind) {
            case FETCH -> "bring me " + count + " " + label;
            case HUNT -> "get rid of " + count + " " + label + " for me";
            case DELIVER -> "take my letter to " + targetName;
        };
    }

    /** What the player did, told to the villager who gets it: "brought you the 12 wheat you asked for". */
    String doneForYou() {
        return switch (kind) {
            case FETCH -> "brought you the " + count + " " + label + " you asked for";
            case HUNT -> "got rid of the " + count + " " + label + " you asked them to deal with";
            case DELIVER -> "brought you a letter from " + villagerName;
        };
    }

    /** The same for the villager's memory: "brought me the 12 wheat I asked for". */
    String doneForMe() {
        return switch (kind) {
            case FETCH -> "brought me the " + count + " " + label + " I asked for";
            case HUNT -> "got rid of the " + count + " " + label + " I asked them to deal with";
            case DELIVER -> "delivered my letter to " + targetName;
        };
    }

    String rewardText() {
        return reward + " emerald" + (reward == 1 ? "" : "s");
    }

    /** Whoever gets the finished errand: the letter's recipient, else the villager who asked. */
    UUID receiver() {
        return kind == Kind.DELIVER ? target : villager;
    }

    String receiverName() {
        return kind == Kind.DELIVER ? targetName : villagerName;
    }
}

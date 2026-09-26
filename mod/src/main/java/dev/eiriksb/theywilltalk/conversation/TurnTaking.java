package dev.eiriksb.theywilltalk.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * Who, if anyone, chimes in after a villager answered the player. In a real group whoever was asked does the talking:
 * the others mostly listen, and speak up when they're mentioned, when it's family business, or when it's in their
 * nature (a gossip more than a shy villager). Someone who just spoke, or a group that just had its say, holds back, and
 * nobody cuts in when the player was just asked a question.
 */
final class TurnTaking {
    /**
     * @param talkedRecently lines they said in the last few of the conversation
     */
    record Candidate(String name, String persona, boolean family, int talkedRecently) {}

    /**
     * @param speakers who chimes in, in order (usually nobody, at most one unless everyone was asked)
     * @param answerBack whether the villager who was talking to the player may answer them back
     */
    record Decision(List<String> speakers, boolean answerBack) {
        static final Decision NOBODY = new Decision(List.of(), false);
    }

    /** Personalities that speak up / hold back. */
    private static final Set<String> TALKATIVE = Set.of("chatterbox", "prankster", "boastful", "dramatic", "sarcastic", "grumpy",
            "flirty", "cheerful", "greedy", "odd", "paranoid");
    private static final Set<String> QUIET = Set.of("shy", "laid_back", "peaceful", "philosophical", "gloomy", "wise", "sensitive");
    private static final Pattern WORD = Pattern.compile("[\\p{L}']+");

    private TurnTaking() {}

    /**
     * @param toEveryone     the player spoke to all of them ("what do you two think?")
     * @param turnsSinceChime player turns since someone last chimed in
     */
    static Decision decide(List<Candidate> others, String playerText, String reply, boolean toEveryone, int turnsSinceChime,
                           RandomGenerator random) {
        if (others.isEmpty()) {
            return Decision.NOBODY;
        }
        if (toEveryone) {
            return new Decision(others.stream().map(Candidate::name).toList(), false);
        }
        List<String> said = words(playerText + " " + reply);
        boolean playersTurn = reply.strip().endsWith("?"); // they were just asked something: let them answer
        String chosen = null;
        double best = 0;
        for (Candidate c : others) {
            double p = chance(c, said.contains(c.name().toLowerCase(Locale.ROOT)), playersTurn, turnsSinceChime);
            if (p > 0 && random.nextDouble() < p && p > best) {
                chosen = c.name();
                best = p;
            }
        }
        return chosen == null ? Decision.NOBODY : new Decision(List.of(chosen), random.nextDouble() < 0.25);
    }

    /** How likely this villager is to speak up now. */
    static double chance(Candidate c, boolean mentioned, boolean playersTurn, int turnsSinceChime) {
        if (mentioned) {
            return c.talkedRecently() > 1 ? 0.35 : 0.65; // "Marica says..." - she'll want a word
        }
        if (playersTurn) {
            return 0;
        }
        double p = 0.10;
        if (c.family()) {
            p += 0.08;
        }
        if (TALKATIVE.contains(c.persona())) {
            p *= 1.5;
        } else if (QUIET.contains(c.persona())) {
            p *= 0.5;
        }
        if (c.talkedRecently() > 0) {
            p *= 0.4;
        }
        if (turnsSinceChime < 2) {
            p *= 0.35; // the group just had its say
        }
        return Math.min(p, 0.8);
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        var m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}

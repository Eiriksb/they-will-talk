package dev.eiriksb.theywilltalk.events;

import java.util.regex.Pattern;

/** Recognises a player asking a villager for something to do: "Any work for me?", "Any requests?", "Need anything?". */
final class AsksForWork {
    private static final Pattern PATTERN = Pattern.compile("(?iu)\\b(quests?|tasks?|errands?|missions?|chores?|odd jobs?|"
            + "jobs? for (me|us)|work for (me|us)|requests?|(any|some) (work|jobs?)|anything (for (me|us)|(i|we) can do|to do)|"
            + "need (anything|something)|want anything|what can (i|we) do|can (i|we) (do|get|fetch|bring) (anything|something)|"
            + "need (any |some )?help|can (i|we) help|help you|help out|favou?rs?|"
            + "oppdrag|aufgaben?|auftrag|tareas?|misi[oó]n|qu[eê]tes?|travail|lavoro|incarichi)\\b");

    private AsksForWork() {}

    static boolean test(String text) {
        return PATTERN.matcher(text).find();
    }
}

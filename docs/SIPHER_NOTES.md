# Sipher notes

[Sipher](https://github.com/Eiriksb/Sipher) gives players their voice: it transcribes each player's microphone on their
own client (Simple Voice Chat audio), translates it to English and sends the caption to the server.

## How They Will Talk hooks in

Sipher's server relay posts every caption it accepts as `io.github.eiriksb.sipher.api.PlayerCaptionEvent` on
`NeoForge.EVENT_BUS` (server thread), whether or not relaying captions to other players is enabled.
`integration/SipherBridge` listens for it, ignores live (partial) captions and hands the finished line to the
villagers: `getEnglish()` when Sipher translated it, otherwise `getText()` (the player spoke English). The original
text and language are kept with the message for the dashboard.

* Sipher must be on the **server and the clients**: a client only sends captions to a server that has Sipher, and only
  while *Share my captions* is on (the default).
* Sipher isn't published yet, so the build compiles against `../Sipher/build/libs/sipher-<sipher_version>.jar`
  (override with `-PsipherJar=<path>`). Once it's on Modrinth or CurseForge, switch `mod/build.gradle` to that.
* `/twt relay <player> <lang> <text> | <english>` pushes a caption through Sipher's real relay without a microphone.

## Idea: caption bubbles above villagers

Sipher draws caption bubbles above players only (`SipherClient.onRenderNameTag`) and translates every caption into the
reader's language. If it drew them above any entity with captions, They Will Talk could send each villager reply as a
`CaptionPayload(villagerUuid, line, false, "en", text, "")` to the players in earshot, and everyone would read the
villagers in their own language.

## Speakers and open microphones

With voice activation and speakers (no headphones), villager voices can be picked up by the microphone and
transcribed as the player's own words. They Will Talk filters these echoes (it ignores voice lines while a nearby
villager is talking and lines that repeat what a villager just said), but push-to-talk is still the nicest setup.

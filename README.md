# They Will Talk

A NeoForge 1.21.1 server mod: **villagers listen and answer out loud**, in character, with their own voice, mood and
memories - powered by local AI running on the server's own NVIDIA GPU. Works with vanilla villagers, wandering
traders, **MCA Reborn** villagers and **MineColonies** citizens. Players talk with their voice through
**Simple Voice Chat** + **EN Translator Core**, or by typing. An admin **dashboard** shows every villager, their
conversations, relationships, family trees and a map (with **BlueMap**).

Server admins: see [docs/SERVER_INSTALL.md](docs/SERVER_INSTALL.md).

```
 player's mic ──► Simple Voice Chat ──► EN Translator Core (client: Whisper + translation to English)
                                              │ relay packet
                                              ▼
 ┌────────────────────────── Minecraft server (NeoForge) ─────────────────────────────┐
 │ They Will Talk                                                                       │
 │  EnTranslatorRelayMixin ─► ConversationManager ─► LlmClient ──► llama-server (GPU)  │
 │   typed chat / commands ─┘   │  targeting, memory,     ▲         Gemma 4 E2B         │
 │                              │  gossip, relationships  │ streamed tokens             │
 │                              ▼                         │                             │
 │                       SentenceStream ─► SpeechRenderer ─► qwentts tts-server (GPU)   │
 │                                             │            Qwen3-TTS 1.7B VoiceDesign  │
 │                                             │  fallback ─► voice-server (CPU, Kokoro)│
 │                                             ▼                                        │
 │                     Simple Voice Chat entity channel (positional voice) + subtitles  │
 │  SQLite (world/theywilltalk) ◄── Store          DashboardServer :8765 ──► admin web  │
 │  MCA / MineColonies adapters                    BlueMap markers                      │
 └──────────────────────────────────────────────────────────────────────────────────────┘
```

## How it works

* **Hearing.** EN Translator Core transcribes each player's microphone on their own client and relays the (English)
  text through the server; a `@Pseudo` mixin on its relay hands finished lines to the villagers. Typed chat and
  `/twt talk` work too.
* **Who is being talked to.** The villager you look at, the one whose name you say, or the one you're already
  talking to (while you still roughly face it). Players chatting with each other near villagers are left alone.
  Villager voices picked up by open microphones are recognised and ignored (echo filter).
* **Thinking.** A persona prompt per villager: generated name (biome-flavoured), personality archetype (MCA
  personalities map onto them), quirk, backstory, job and trades, mood, family (MCA family tree / MineColonies),
  village, time/weather/biome/nearby mobs/raids, the player's gear, how well they know the player, what they
  remember about them and what the rest of the village gossips about them. The LLM streams its reply and tags it
  with an emotion.
* **Speaking.** Sentences go to TTS as soon as they're complete, so villagers start talking after ~0.2 s.
  Qwen3-TTS VoiceDesign gives every villager a voice described in words ("an elderly man ... gravelly voice ...
  grumpy") plus the emotion of the line; the seed is fixed per villager so the voice stays consistent. Audio streams
  into a Simple Voice Chat entity channel, so it comes from the villager and fades with distance.
* **Remembering.** After each exchange a structured LLM call rates how the villager's opinion changed and what to
  remember; hitting, trading, killing villagers in front of others all become memories. MCA hearts and moods are
  updated through MCA's own API.
* **Being lively.** Villagers stop and look at you, nod/shake their heads, show particles, greet players they like,
  and occasionally chat with each other.

## Layout

| Path | |
|---|---|
| `mod/` | the NeoForge mod (Java 21, ModDevGradle) |
| `mod/src/main/resources/assets/theywilltalk/web/` | the dashboard (vanilla JS, no build step) |
| `voice-server/` | CPU TTS sidecar (sherpa-onnx: Kokoro), launched by the mod |
| `scripts/fetch-runtime.sh` | assembles `runtime/` (llama.cpp, CUDA libs, models, voice servers) |
| `scripts/build-qwentts.sh` | builds qwentts.cpp `tts-server` with CUDA (user-space toolchain, no sudo) |
| `scripts/package.sh` | builds `dist/TheyWillTalk-<version>-<platform>.zip` |
| `scripts/rcon.py` | drive a running dev server from the terminal |
| `docs/` | server install guide, third-party notices, EN Translator notes |

## Development

Requirements: JDK 21 (`~/.local/opt/jdk-21` is used by the scripts), an NVIDIA GPU, Linux x64.

```bash
scripts/fetch-runtime.sh linux-x64          # once: runtime/ with binaries + models (~6 GB)
./gradlew :mod:test                          # unit tests
./gradlew :mod:runServer                     # dev server (all integrations, RCON on 25575, dashboard on 8765)
./gradlew :mod:runClientJoin                 # dev client that joins it as "Eirik"
scripts/rcon.py "twt status"                 # talk to the dev server
```

Dev runs load Simple Voice Chat, MCA Reborn, MineColonies, BlueMap and EN Translator Core from Maven (switch any off
with `-PwithMca=false`, `-PwithMinecolonies=false`, `-PwithBluemap=false`, `-PwithVoicechat=false`,
`-PwithEnTranslator=false`). In dev the dashboard doesn't ask for a login from localhost and serves the web files
straight from `src/`, so UI edits only need a browser reload.

Testing voice without a microphone: `/twt relay <player> <lang> <text> | <english>` pushes a line through EN
Translator's real relay, exactly like a transcribed voice line.

## Packaging

```bash
scripts/package.sh linux-x64      # -> dist/TheyWillTalk-0.1.0-linux-x64.zip
```

Windows bundles need a Windows build of qwentts.cpp; llama.cpp ships official Windows CUDA builds and the voice
server is plain Java, so without it Windows servers fall back to the Kokoro voices.

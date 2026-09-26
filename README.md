# They Will Talk

A NeoForge 1.21.1 server mod: **villagers listen and answer out loud**, in character, with their own voice, mood and
memories - powered by local AI running on the server's own GPU (NVIDIA, or AMD/Intel through Vulkan). Works with vanilla villagers, wandering
traders, **MCA Reborn** villagers and **MineColonies** citizens. Players talk with their voice through
**Simple Voice Chat** + **[Sipher](https://github.com/Eiriksb/Sipher)**, or by typing. An admin **dashboard** shows
every villager, their conversations, relationships, family trees and a map (with **BlueMap**).

Server admins: see [docs/SERVER_INSTALL.md](docs/SERVER_INSTALL.md).

```
 player's mic ──► Simple Voice Chat ──► Sipher (client: speech recognition + translation to English)
                                              │ caption packet
                                              ▼
 ┌────────────────────────── Minecraft server (NeoForge) ─────────────────────────────┐
 │ They Will Talk                                                                       │
 │            SipherBridge ─► ConversationManager ─► LlmClient ──► llama-server (GPU)  │
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

* **Hearing.** Sipher transcribes each player's microphone on their own client, translates it to English and sends
  the caption to the server, which posts it as a `PlayerCaptionEvent`; finished lines go to the villagers. Typed
  chat and `/twt talk` work too. Villagers answer in the language the player speaks when the Qwen3-TTS voices can
  speak it (English, German, French, Spanish, Italian, Portuguese, Russian, Japanese, Korean, Chinese), else in English.
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
  grumpy"). VoiceDesign imagines the voice anew for every request, so it's designed once per villager (a reference
  clip, saved with the world) and the Qwen3-TTS Base model speaks every line cloning it; each mood (happy, angry,
  sad, scared) gets its own clip read in that same voice (`VoiceBank`). Admins can give a villager a real voice
  instead: a clip uploaded or recorded on the dashboard replaces the designed one. The VoiceDesign server only runs
  while new voices are being designed and stops after a few idle minutes, leaving the GPU memory to the brain.
  Audio streams into a Simple Voice Chat entity channel (Opus in AUDIO mode), so it comes from the villager and fades
  with distance. What villagers say also shows as a Sipher caption bubble above them (Sipher 0.2.0+), translated
  into each player's reading language, and as a subtitle.
* **Swearing.** With *crude language* on, villagers may swear. By default it's bleeped YouTube style: `f***` in
  subtitles, bubbles and the map, and a beep in the voice (`Profanity`). Turning *bleep swear words* off lets them
  swear uncensored. Slurs are always censored.
* **Remembering.** After each exchange a structured LLM call rates how the villager's opinion changed and what to
  remember; hitting, trading, killing villagers in front of others all become memories. MCA hearts and moods are
  updated through MCA's own API.
* **Being lively.** Villagers stop and look at you, nod/shake their heads, show particles, and occasionally chat with
  each other.
* **Taking the initiative.** Villagers walk up to players they want to talk to (`events/VillagerEvents`): to greet a
  friend, give a gift, ask a favour, or collect one that's done. Errands fit the villager: bring them something their
  job needs (`Needs`: wheat for a farmer, iron for a smith, flowers for a child), deal with some monsters, or take a
  letter to someone else in the village. The villager asks in their own words; players answer by voice or chat (an LLM
  call decides yes or no, in any language) or click [Accept]. Progress is checked against the game (inventory, kills,
  the letter item), finished errands are paid in emeralds, and the villager remembers, likes you more, gives MCA
  hearts or cheaper trades. Asking a villager "any work for me?" gets you one too.
* **The map.** The dashboard's map is BlueMap, proxied at `/bluemap/` behind the dashboard login, with every talking
  villager and village as a marker. Villagers appear on BlueMap and in the dashboard with their real faces, drawn on the server from their skins the
  way the game's models draw them (`faces/`: the vanilla villager head with biome and profession layers; MCA's
  layered skin, eyes, clothing and hair with MCA's gene-based tints; MineColonies' own face icon for each citizen's
  job, skin tone and the colony's style pack).
* **Installing the AI.** The mod jar holds no models. The dashboard's **Models** page downloads llama.cpp, the
  language model and the voices on one click, from a catalogue compiled into the jar
  (`assets/theywilltalk/runtime/catalog.json`): every file is pinned to a GitHub or Hugging Face URL and a SHA-256.
  With an NVIDIA GPU that's the CUDA build of llama.cpp plus the Qwen3-TTS voices; otherwise the Vulkan build (AMD and
  Intel GPUs, or the CPU) with the Kokoro voices. Admins pick the brain (Gemma 4 E2B/E4B, or their uncensored
  variants) and voice engine there, and can let villagers swear (*crude language*, off by default). Archives are
  unpacked by the voice server jar (`Unpack`), which ships inside the mod.
* **Settings.** The dashboard's **Settings** page edits every option in `config/theywilltalk-common.toml` (except
  the ones that could lock the admin out, like the dashboard's address), saved as you change them.

## Layout

| Path | |
|---|---|
| `mod/` | the NeoForge mod (Java 21, ModDevGradle) |
| `mod/src/main/resources/assets/theywilltalk/web/` | the dashboard (vanilla JS, no build step) |
| `mod/src/main/java/.../runtime/` | AI runtime: process supervision, model catalogue, downloader |
| `voice-server/` | CPU TTS sidecar (sherpa-onnx: Kokoro) and archive unpacker, shipped inside the mod jar |
| `scripts/build-qwentts.sh` | builds qwentts.cpp `tts-server` with CUDA (user-space toolchain, no sudo) |
| `scripts/release-qwentts.sh` | packages (and publishes) that build for the Models page; prints its catalogue values |
| `scripts/fetch-runtime.sh` | optional: assembles `runtime/` ahead of time, for servers without internet |
| `scripts/package.sh` | optional: offline bundle `dist/TheyWillTalk-<version>-<platform>.zip` (mod + runtime) |
| `scripts/rcon.py` | drive a running dev server from the terminal |
| `docs/` | server install guide, third-party notices, Sipher notes |

## Development

Requirements: JDK 21 (`~/.local/opt/jdk-21` is used by the scripts), a GPU (NVIDIA for the Qwen3-TTS voices), Linux x64, and
[Sipher](https://github.com/Eiriksb/Sipher) built next to this repository (`../Sipher`, `./gradlew assemble`), since
it isn't published yet (or point `-PsipherJar=<path>` at its jar).

```bash
./gradlew :mod:test :voice-server:test      # unit tests
./gradlew :mod:runServer                     # dev server (all integrations, RCON on 25575, dashboard on 8765)
./gradlew :mod:runClientJoin                 # dev client that joins it as "Eirik"
scripts/rcon.py "twt status"                 # talk to the dev server
```

Dev runs load Simple Voice Chat, MCA Reborn, MineColonies and BlueMap from Maven, and Sipher from its jar (switch any
off with `-PwithMca=false`, `-PwithMinecolonies=false`, `-PwithBluemap=false`, `-PwithVoicechat=false`,
`-PwithSipher=false`). In dev the dashboard doesn't ask for a login from localhost and serves the web files
straight from `src/`, so UI edits only need a browser reload.

Testing voice without a microphone: `/twt relay <player> <lang> <text> | <english>` pushes a line through Sipher's
real caption relay, exactly like a transcribed voice line.

Testing without a game client: `/twt dev bot <name>` (dev server only) adds a stand-in player at the command's
position; what it's told goes to the server log. It doesn't load chunks, so `forceload` the village you test in. Then,
for example:

```bash
scripts/rcon.py "execute in minecraft:overworld positioned 620 64 -490 run twt dev bot Tester"
scripts/rcon.py "twt event <villager uuid> Tester fetch" "twt hear Tester Sure, I'll get them!"
scripts/rcon.py "give Tester minecraft:wheat 16"          # the villager comes to collect
```

The dev runs keep their AI runtime in `runtime/` (git-ignored). Install it from http://127.0.0.1:8765/#/models, like a
server admin would.

## Packaging

`./gradlew :mod:build` builds the mod jar (with the voice server inside); that's all a server needs, the rest comes
from the Models page. GitHub Actions (`.github/workflows/build.yml`) builds it on every push, against Sipher at a pinned
commit; download it from the run's artifacts, or push a `v*` tag to publish it as a release. For servers without internet access, an all-in-one bundle:

```bash
scripts/package.sh linux-x64      # -> dist/TheyWillTalk-0.1.0-linux-x64.zip
```

The Qwen3-TTS server has no upstream binaries, so this project publishes its own (release `qwentts-6a3e912`). To
update it: bump `COMMIT` in `scripts/build-qwentts.sh`, run `scripts/release-qwentts.sh --publish` and pin the printed
url/sha256/size in `catalog.json`. There is no Windows build yet, so Windows servers use the Kokoro voices.

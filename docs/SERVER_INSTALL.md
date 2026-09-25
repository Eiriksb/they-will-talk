# They Will Talk - server install

Villagers that listen and answer out loud. Players talk to villagers with their voice (through Simple Voice Chat +
EN Translator Core) or by typing in chat, and each villager answers in character, with their own voice and mood,
remembering you and gossiping about you. Everything runs on your own server's GPU. Nothing is sent to the internet,
and nothing is downloaded when the mod runs.

## Requirements

| | |
|---|---|
| Minecraft / loader | 1.21.1, NeoForge 21.1.227 or newer |
| GPU | NVIDIA, 8 GB VRAM or more (RTX 3060 / 4060 Ti or better). The AI uses about 4.5 GB. |
| NVIDIA driver | 570 or newer on Linux (any recent driver on Windows) |
| RAM / disk | about 2 GB extra RAM, about 6 GB disk |
| CPU | the fallback voice engine and the helper processes run at lower priority than the Minecraft server |

## Install

1. Stop the server.
2. Unzip this bundle **into the server folder** (the folder with `server.properties`). You get
   `mods/theywilltalk-<version>.jar` and `theywilltalk/runtime/`.
3. Also install these mods on the server (and tell players to install them, see below):
   * **Simple Voice Chat** (NeoForge 1.21.1) - so players hear the villagers.
   * **EN Translator Core** (CurseForge) - so players can talk to villagers with their voice.
   * optional: **MCA Reborn**, **MineColonies**, **BlueMap**.
4. Start the server. The first start takes a few seconds longer while the AI loads. The log says:
   `[llm] ready`, `[qwen-tts] ready`, `[voice] ready`.
5. In game, as an operator: `/twt status` shows whether everything is running.

## For players

* Install **Simple Voice Chat** and **EN Translator Core** on the client.
* Walk up to a villager, **look at it** (or say its name) and talk. Keep talking for a while without looking,
  as long as you're still roughly facing it.
* Use **push-to-talk** or headphones: with open speakers and voice activation, the microphone can pick up villager
  voices (They Will Talk filters these echoes, but push-to-talk is nicer).
* No mic? Type in chat while looking at a villager, or use `/twt talk <message>`.

## Admin dashboard

`http://<server>:8765/` - only reachable from the server machine by default.

* In game: `/twt dashboard` gives a one-click login link.
* Or log in with the token in `config/theywilltalk-admin-token.txt`.
* To reach it from another PC, set `bindAddress = "0.0.0.0"` in `config/theywilltalk-common.toml` (keep the token
  secret) - or better, use an SSH tunnel.

It shows all villagers (search by name, job, village), each villager's conversations, memories, relationships
with players (affinity, MCA hearts, marriages), family trees (MCA / MineColonies), villages and colonies, a live
activity feed, a map (with BlueMap's 3D map embedded if you run BlueMap), GPU stats, and a voice lab where you can
design and audition voices. You can edit any villager's personality, backstory, voice description and extra
instructions.

## Commands

| Command | Who | What |
|---|---|---|
| `/twt talk <message>` | everyone | talk to the villager you're looking at, by typing |
| `/twt status` | op | AI status, GPU, latency |
| `/twt dashboard` | op | dashboard login link |
| `/twt info <villager>` | op | who is this villager? |
| `/twt say <villager> <message>` | op | make a villager answer you (or the console) |
| `/twt hear <player> <message>` | op | pretend a player said something by voice |
| `/twt relay <player> <lang> <text>` | op | push a line through EN Translator's relay (testing without a mic) |
| `/twt restart` | op | restart the AI processes |

## Configuration (`config/theywilltalk-common.toml`)

The most useful settings:

* `ttsEngine` - `auto` (Qwen3-TTS expressive voices on the GPU), `kokoro` (54 CPU voices, lighter on the GPU).
* `llmModel` - drop another GGUF into `theywilltalk/runtime/models/llm/` and name it here, e.g. Gemma 4 E4B for
  richer replies (about 3 GB VRAM instead of 1.6 GB).
* `listenRadius`, `voiceDistance`, `requireLookOrName`, `activeConversationSeconds` - who hears what.
* `maxReplyWords`, `maxRepliesPerMinute` - how chatty villagers are.
* `ambientChatter`, `ambientIntervalSeconds`, `greetings` - villagers talking on their own.
* `vanillaVillagers`, `wanderingTraders`, `mcaVillagers`, `minecoloniesCitizens` - who can talk.
* `processNice` (Linux) - how politely the AI shares the CPU with the Minecraft server.

## Troubleshooting

* `/twt status` and the dashboard's **AI & voices** page show each process's state and log.
* Logs: `logs/theywilltalk/llm.log`, `qwen-tts.log`, `voice.log`.
* "AI starting" forever: check the NVIDIA driver (`nvidia-smi`) and `logs/theywilltalk/llm.log`.
* Villagers answer in chat but you hear nothing: the player needs Simple Voice Chat installed and connected.
* Villagers don't react to voice: the player needs EN Translator Core, and has to look at the villager or say its
  name.
* MCA villagers answer typed chat twice: turn off MCA's own chat AI (`enableVillagerChatAI` in `config/mca.json`).

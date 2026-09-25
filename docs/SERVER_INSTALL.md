# They Will Talk - server install

Villagers that listen and answer out loud. Players talk to villagers with their voice (through Simple Voice Chat +
Sipher) or by typing in chat, and each villager answers in character, with their own voice and mood,
remembering you and gossiping about you. Everything runs on your own server's GPU: nothing players say is sent to
the internet. The AI programs and models are downloaded once, when you click *Install* in the admin dashboard.

## Requirements

| | |
|---|---|
| Minecraft / loader | 1.21.1, NeoForge 21.1.227 or newer |
| GPU | NVIDIA, 8 GB VRAM or more (RTX 3060 / 4060 Ti or better). The AI uses about 4.5 GB. |
| NVIDIA driver | 570 or newer on Linux (any recent driver on Windows) |
| RAM / disk | about 2 GB extra RAM, 4-10 GB disk depending on the models you pick |
| CPU | the fallback voice engine and the helper processes run at lower priority than the Minecraft server |

## Install

1. Stop the server and put `theywilltalk-<version>.jar` in its `mods/` folder.
2. Also install these mods on the server (and tell players to install them, see below):
   * **Simple Voice Chat** (NeoForge 1.21.1) - so players hear the villagers.
   * **[Sipher](https://github.com/Eiriksb/Sipher)** - so players can talk to villagers with their voice. It must be
     on the server as well as on the players' clients: players' captions only reach servers that have it.
   * optional: **MCA Reborn**, **MineColonies**, **BlueMap**.
3. Start the server, then open the admin dashboard (in game as an operator: `/twt dashboard`) and go to **Models**.
4. Click **Install recommended**: llama.cpp with the NVIDIA CUDA runtime, the Gemma 4 E2B villager brain, the
   Kokoro voices and, on Linux, the expressive Qwen3-TTS voices with voice cloning (about 6.5 GB on Linux, 3.7 GB on
   Windows). When it's done the AI starts by itself; the log says `[llm] ready`, `[qwen-tts] ready`,
   `[qwen-clone] ready` and `[voice] ready`.
5. `/twt status` (or the dashboard) shows whether everything is running.

Everything on the Models page comes from GitHub or Hugging Face and is checked against a SHA-256 checksum built into
the mod; nothing is downloaded until you click. The files go to `theywilltalk/runtime/` in the server folder.
No internet on the server? Build an offline bundle with `scripts/package.sh` and unzip it into the server folder.

### Models

* **Villager brain:** Gemma 4 E2B (default, fast, about 1.6 GB of GPU memory) or Gemma 4 E4B (smarter, about 3 GB).
  Download more than one and switch with **Use this**.
* **Uncensored brains:** Gemma 4 E2B/E4B with their refusals removed (by huihui-ai). Together with **Crude language**
  (under *Behaviour*) villagers swear like sailors. They can say offensive things: only use them where every player
  is fine with that. With crude language off, villagers are told to keep it clean, whichever brain you use.
* **Voices:** Kokoro (54 voices on the CPU, the default), Supertonic (lighter), or Qwen3-TTS (expressive voices on
  the GPU, about 2.5 GB of GPU memory; Linux only for now). Add **Qwen3-TTS voice cloning** (about 1.5 GB more) to
  keep each villager's voice exactly the same in every line: their voice is designed once and saved with the world
  (`world/theywilltalk/voices/`), then every line and mood is spoken in it.
* **Your own voices:** with voice cloning installed, open a villager in the dashboard and use **Their voice**: upload a
  clip (5-20 seconds of one person speaking clearly, any audio format your browser plays) or record one with your
  microphone, add what is said in it for the closest match, and the villager speaks every line and mood in that voice.
  **Hear their in-game voice** plays exactly what players hear. Only use voices you have permission to use.

## For players

* Install **Simple Voice Chat** and **Sipher** on the client, and keep Sipher's *Share my captions* on (the default).
  Speaking another language than English? Press **O**, open *Languages*, pick it under *I speak* and download its
  pack; Sipher translates what you say to English for the villagers.
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
activity feed, the BlueMap 3D map with every villager on it (if you run BlueMap), GPU stats, and a voice lab where you can
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
| `/twt relay <player> <lang> <text>` | op | push a line through Sipher's caption relay (testing without a mic) |
| `/twt restart` | op | restart the AI processes |

## Configuration (`config/theywilltalk-common.toml`)

The most useful settings:

* `llmModel`, `ttsEngine`, `crudeLanguage` - easiest to set on the dashboard's Models page. `llmModel` can also name
  any GGUF you drop into `theywilltalk/runtime/models/llm/` yourself.
* `externalLlmUrl` - use your own OpenAI-compatible server (llama.cpp, Ollama, LM Studio...) instead of the built-in
  one.
* `listenRadius`, `voiceDistance`, `requireLookOrName`, `activeConversationSeconds` - who hears what.
* `maxReplyWords`, `maxRepliesPerMinute` - how chatty villagers are.
* `ambientChatter`, `ambientIntervalSeconds`, `greetings` - villagers talking on their own.
* `vanillaVillagers`, `wanderingTraders`, `mcaVillagers`, `minecoloniesCitizens` - who can talk.
* `processNice` (Linux) - how politely the AI shares the CPU with the Minecraft server.

## Troubleshooting

* `/twt status` and the dashboard's **AI & voices** page show each process's state and log.
* Logs: `logs/theywilltalk/llm.log`, `qwen-tts.log`, `qwen-clone.log`, `voice.log`.
* "AI not installed": open the dashboard's **Models** page and click **Install recommended**.
* A download failed: click **Download** again; it resumes where it stopped. "Checksum mismatch" means the file
  arrived corrupted (or changed upstream) and was thrown away.
* "AI starting" forever: check the NVIDIA driver (`nvidia-smi`) and `logs/theywilltalk/llm.log`.
* Villagers answer in chat but you hear nothing: the player needs Simple Voice Chat installed and connected.
* Villagers don't react to voice: the server and the player both need Sipher (with *Share my captions* on), and the
  player has to look at the villager or say its name.
* MCA villagers answer typed chat twice: turn off MCA's own chat AI (`enableVillagerChatAI` in `config/mca.json`).
* The map is BlueMap (install it on the server). It only starts once its resource download is accepted: `accept-download: true` in `config/bluemap/core.conf`, then `/bluemap reload`. The dashboard serves
  BlueMap at `/bluemap/`, so its own port doesn't need to be reachable.
* Villager faces (on BlueMap and in the dashboard) are drawn from each villager's skin when they're loaded. MCA
  villagers always get theirs; vanilla villagers need Minecraft's own textures, which a dedicated server only has once
  BlueMap has downloaded them. Villagers without a face show a generic icon or their initials.

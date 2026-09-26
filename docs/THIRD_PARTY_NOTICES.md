# Third-party components

The mod jar contains They Will Talk, the voice server and the libraries marked "inside". Everything else is downloaded
from its original publisher when an admin clicks *Install* on the dashboard's Models page (or bundled by
`scripts/package.sh` for offline servers), and keeps its own licence.

| Component | Used for | License |
|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) (`llama-server`, build b11160, CUDA or Vulkan build) | runs the villager brain (LLM) on the GPU | MIT |
| NVIDIA CUDA runtime (`libcudart`, `libcublas`, `libcublasLt` 12.8) | GPU acceleration | NVIDIA CUDA EULA - redistributable runtime components |
| [Gemma 4 E2B-it / E4B-it](https://huggingface.co/google/gemma-4-E2B-it) (QAT GGUFs by Unsloth) | the villager brain | Apache-2.0 |
| Huihui Gemma 4 E2B/E4B abliterated ([huihui-ai](https://huggingface.co/huihui-ai)) | optional uncensored villager brain | Apache-2.0 |
| [qwentts.cpp](https://github.com/ServeurpersoCom/qwentts.cpp) (`tts-server`, built from 6a3e912 and published on this project's GitHub releases) | runs Qwen3-TTS on the GPU | MIT |
| [Qwen3-TTS 12Hz 1.7B VoiceDesign + Base](https://huggingface.co/Qwen) + 12Hz tokenizer (GGUF by Serveurperso) | expressive villager voices, voice cloning | Apache-2.0 |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + [ONNX Runtime](https://github.com/microsoft/onnxruntime) | fallback voice server | Apache-2.0 / MIT |
| [Kokoro-82M v1.0](https://huggingface.co/hexgrad/Kokoro-82M) (sherpa-onnx export) | fallback voices | Apache-2.0 |
| espeak-ng data (inside the Kokoro folder) | phonemization for Kokoro | GPL-3.0 |
| [Supertonic 3](https://github.com/k2-fsa/sherpa-onnx) (sherpa-onnx export) | optional light voices | OpenRAIL-M |
| [Gson](https://github.com/google/gson) (inside voice-server.jar) | JSON | Apache-2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) (inside voice-server.jar) | unpacks downloaded archives | Apache-2.0 |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) (inside the mod jar) | the villager database | Apache-2.0 |

They Will Talk works with, but does not include: Simple Voice Chat, Sipher, MCA Reborn, MineColonies
and BlueMap - install those from their own pages.

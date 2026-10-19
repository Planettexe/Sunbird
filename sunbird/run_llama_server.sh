#!/usr/bin/env bash
# One-click llama.cpp backend for the whole Minecraft server.
# Usage: ./run_llama_server.sh [model.gguf] [port]
# Tip: pick an uncensored/community GGUF (e.g. from HuggingFace) if you want the
# bird to truly talk about anything - the mod itself adds no filters at all.
set -euo pipefail
MODEL="${1:-./models/unsloth.Qwen3-8B-Uncensored.gguf}"
PORT="${2:-8080}"

if ! command -v llama-server >/dev/null 2>&1; then
  echo "llama-server not found. Build it:"
  echo "  git clone https://github.com/ggml-org/llama.cpp && cd llama.cpp"
  echo "  cmake -B build -DLLAMA_CURL=OFF && cmake --build build --config Release -j"
  echo "  sudo cp build/bin/llama-server /usr/local/bin/"
  exit 1
fi

echo "Starting llama.cpp server on 0.0.0.0:${PORT} with ${MODEL}"
echo "Set Sunbird config apiBaseUrl to http://<this-host>:${PORT}/v1"
exec llama-server -m "$MODEL" \
  --host 0.0.0.0 --port "$PORT" \
  --ctx-size 8192 --n-gpu-layers auto \
  --api-key "" --parallel 4

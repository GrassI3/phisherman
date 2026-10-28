#!/usr/bin/env bash
# Convert the fine-tuned Gemma 3 270M (ml/llm/merged) to a MediaPipe .task file.
# Runs inside WSL Ubuntu with the env from wsl_setup (litert-torch + mediapipe).
#   wsl -d Ubuntu -- bash /mnt/c/.../phisherman/ml/convert_llm.sh
set -euo pipefail
export PATH="$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin"
ML="$(cd "$(dirname "$0")" && pwd)"
WORK="$HOME/phisherman-convert"
. "$WORK/.venv/bin/activate"
mkdir -p "$WORK/out"
# copy the checkpoint into Linux storage: reading 0.5 GB over /mnt/c is slow
rm -rf "$WORK/merged" && cp -r "$ML/llm/merged" "$WORK/merged"
[ -f "$WORK/merged/tokenizer.model" ] || curl -sL -o "$WORK/merged/tokenizer.model" \
  "https://huggingface.co/unsloth/gemma-3-270m-it/resolve/main/tokenizer.model"

python -m litert_torch.generative.examples.gemma3.convert_gemma3_to_tflite \
  --model_size=270m \
  --checkpoint_path="$WORK/merged" \
  --output_path="$WORK/out" \
  --output_name_prefix=phisherman_scam \
  --quantize=dynamic_int8 \
  --prefill_seq_lens=384 \
  --kv_cache_max_len=512 \
  --mask_as_input=True

TFLITE="$(ls -t "$WORK"/out/phisherman_scam*.tflite | head -1)"
echo "tflite: $TFLITE ($(du -h "$TFLITE" | cut -f1))"

python - "$TFLITE" "$WORK/merged/tokenizer.model" "$WORK/out/phisherman_scam.task" <<'PY'
import sys
from mediapipe.tasks.python.genai import bundler
bundler.create_bundle(bundler.BundleConfig(
    tflite_model=sys.argv[1], tokenizer_model=sys.argv[2],
    start_token="<bos>", stop_tokens=["<eos>", "<end_of_turn>"],
    output_filename=sys.argv[3],
    # no prompt_prefix / prompt_suffix: the app writes the full Gemma turn template itself
    # (sms/ScamLlm.kt), byte for byte the same as ml/llm_data.py
))
print("task:", sys.argv[3])
PY
cp "$WORK/out/phisherman_scam.task" "$ML/llm/phisherman_scam.task"
ls -la "$ML/llm/phisherman_scam.task"

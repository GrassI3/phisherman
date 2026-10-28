#!/usr/bin/env bash
# Package the converted .tflite + Gemma tokenizer as a LiteRT-LM file (.litertlm) for the phone
# (runtime: com.google.ai.edge.litertlm:litertlm-android). Runs inside WSL after convert_llm.sh.
# The prompt template below must match ml/llm_data.py (PROMPT_PREFIX / PROMPT_SUFFIX).
set -euo pipefail
export PATH="$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin"
ML="$(cd "$(dirname "$0")" && pwd)"
WORK="$HOME/phisherman-convert"
. "$WORK/.venv/bin/activate"
TFLITE="$(ls -t "$WORK"/out/phisherman_scam*.tflite | head -1)"
mkdir -p "$WORK/lm_work"
mkdir -p "$WORK/lm_out" && rm -f "$WORK"/lm_out/*.litertlm
python - "$TFLITE" "$WORK/merged/tokenizer.model" "$WORK/lm_out" "$WORK/lm_work" <<'PY' 2>&1 | grep -v -E "^[WI][0-9]{4}|oneDNN|AVX|absl"
import sys
from litert_torch.generative.utilities import litertlm_builder
litertlm_builder.build_litertlm(
    tflite_model_path=sys.argv[1], tokenizer_model_path=sys.argv[2],
    output_path=sys.argv[3], workdir=sys.argv[4], context_length=512,
    start_token="<bos>", stop_tokens=["<eos>", "<end_of_turn>"],
    user_prompt_prefix="<start_of_turn>user\n", user_prompt_suffix="<end_of_turn>\n",
    model_prompt_prefix="<start_of_turn>model\n", model_prompt_suffix="<end_of_turn>\n",
)
print("litertlm written to", sys.argv[3])
PY
mkdir -p "$ML/llm"
cp "$(ls -t "$WORK"/lm_out/*.litertlm | head -1)" "$ML/llm/phisherman_scam.litertlm"
ls -la "$ML/llm/phisherman_scam.litertlm"

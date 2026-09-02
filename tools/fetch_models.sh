#!/usr/bin/env bash
# 下载验证用模型到 $MODELS_DIR（默认 /tmp/models）。仅用于本机验证与算 SHA256，不进 git。
set -euo pipefail

MODELS_DIR="${MODELS_DIR:-/tmp/models}"
mkdir -p "$MODELS_DIR"
SHERPA_BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
NLLB_BASE="https://huggingface.co/JustFrederik/nllb-200-distilled-600M-ct2-int8/resolve/main"

fetch() { # fetch <url> <outfile>
  local url="$1" out="$2"
  if [ -s "$out" ]; then echo "skip (exists): $out"; return 0; fi
  echo "==> $(basename "$out")"
  curl -fL --retry 3 --retry-delay 2 -C - -o "$out.part" "$url" || { rm -f "$out.part"; return 1; }
  mv "$out.part" "$out"
}

echo "==> [1/5] silero VAD int8"
fetch "$SHERPA_BASE/silero_vad.int8.onnx" "$MODELS_DIR/silero_vad.int8.onnx"

echo "==> [2/5] SenseVoice int8 (tar.bz2)"
fetch "$SHERPA_BASE/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2" \
      "$MODELS_DIR/sense-voice-int8.tar.bz2"
if [ ! -d "$MODELS_DIR/sense-voice-int8" ]; then
  mkdir -p "$MODELS_DIR/sense-voice-int8"
  tar -xjf "$MODELS_DIR/sense-voice-int8.tar.bz2" -C "$MODELS_DIR/sense-voice-int8" --strip-components=1
fi

echo "==> [3/5] Whisper small int8 (tar.bz2)"
fetch "$SHERPA_BASE/sherpa-onnx-whisper-small.tar.bz2" "$MODELS_DIR/whisper-small.tar.bz2"
if [ ! -d "$MODELS_DIR/whisper-small" ]; then
  mkdir -p "$MODELS_DIR/whisper-small"
  tar -xjf "$MODELS_DIR/whisper-small.tar.bz2" -C "$MODELS_DIR/whisper-small" --strip-components=1
fi

echo "==> [4/5] NLLB-200-distilled-600M ct2 int8"
mkdir -p "$MODELS_DIR/nllb-600m-ct2-int8"
for f in model.bin sentencepiece.bpe.model shared_vocabulary.txt config.json; do
  fetch "$NLLB_BASE/$f" "$MODELS_DIR/nllb-600m-ct2-int8/$f"
done

echo "==> [5/5] 清单与 SHA256"
cd "$MODELS_DIR"
find . -maxdepth 2 -type f \( -name '*.onnx' -o -name '*.bin' -o -name '*.txt' -o -name '*.json' -o -name '*.model' \) \
  -printf '%10s  %p\n' | sort -k2
echo "------------------------------- 体积 -------------------------------"
du -sh "$MODELS_DIR"/* 2>/dev/null
echo "------------------------------ SHA256 ------------------------------"
find . -maxdepth 2 -type f \( -name '*.onnx' -o -name '*.bin' -o -name '*.txt' -o -name '*.model' \) \
  -exec sha256sum {} \; | sort -k2 | tee "$MODELS_DIR/SHA256SUMS.txt"
echo
echo "MODELS_DIR=$MODELS_DIR"

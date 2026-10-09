#!/usr/bin/env bash
# Download the required ASR and English-to-Chinese translation models.
set -euo pipefail

cd "$(dirname "$0")/.."

OUT="${MODELS_DIR:-app/src/main/assets/models}"
WORK="${WORK_DIR:-.models}"
CHUNKDL="python tools/chunkdl.py"
WORKERS="${WORKERS:-24}"
CHUNK="${CHUNK:-2M}"
SHERPA="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
HF="https://huggingface.co/jiangzhuo9357/opus-mt-en-zh-ct2/resolve/06fb49e2f6cb0485043ae703a4c2afddd4e700d7"

get() {
  $CHUNKDL "$1" "$2" --workers "$WORKERS" --chunk "$CHUNK" ${3:+--sha256 "$3"}
}

msys_path() {
  cygpath -u "$1" 2>/dev/null || printf '/%s%s' \
    "$(printf '%s' "${1:0:1}" | tr 'A-Z' 'a-z')" "${1:2}"
}

echo "==> Output directory: $OUT"
mkdir -p "$OUT/vad" "$OUT/zipformer-en" "$WORK"

echo "==> Silero VAD v5 (int8)"
get "$SHERPA/silero_vad.int8.onnx" "$OUT/vad/silero_vad.int8.onnx" \
    c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20

echo "==> Streaming English Zipformer (int8)"
ZF_PKG="$WORK/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2"
get "$SHERPA/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2" "$ZF_PKG"
ZF="$OUT/zipformer-en"
if [ ! -f "$ZF/tokens.txt" ]; then
  mkdir -p "$ZF" "$WORK/zipformer-en"
  tar -xjf "$(msys_path "$ZF_PKG")" -C "$(msys_path "$WORK/zipformer-en")" --strip-components=1
  cp "$WORK/zipformer-en/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx" "$ZF/"
  cp "$WORK/zipformer-en/decoder-epoch-99-avg-1-chunk-16-left-128.onnx" "$ZF/"
  cp "$WORK/zipformer-en/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx" "$ZF/"
  cp "$WORK/zipformer-en/tokens.txt" "$ZF/"
  rm -rf "$WORK/zipformer-en"
fi

echo "==> OPUS-MT English-to-Chinese CTranslate2 int8 (offline model)"
MT_WORK="$WORK/opus-mt-en-zh"
MT_OUT="$OUT/opus-mt-en-zh"
LICENSE_DIR="${LICENSE_DIR:-$(dirname "$OUT")/licenses}"
mkdir -p "$MT_WORK" "$MT_OUT" "$LICENSE_DIR"
get "$HF/model.bin" "$MT_WORK/model.bin" \
    327584c20bb83c7e89d595bcfa30b6ef3771c10816f707e892c4bbb1f808a8fb
get "$HF/config.json" "$MT_WORK/config.json" \
    8f6496adfc930cbfecbe8281112197705c488fab47d34b4829b06d7f478909af
get "$HF/shared_vocabulary.json" "$MT_WORK/shared_vocabulary.json" \
    37314a6abb25ed8f8497498aeeb31fcea98de892bf00ff7c2e8c966b26fe0b82
get "$HF/source.spm" "$MT_WORK/source.spm" \
    5775ddc9e3ff2fae91554da56468ad35ff56edaba870fea74447bc7234bfdaa8
get "$HF/target.spm" "$MT_WORK/target.spm" \
    81dc94efa84e4025ef38d25d5d07429fe41e3eb29d44003f1db6fe98487b0052
for f in model.bin config.json shared_vocabulary.json source.spm target.spm; do
  cp "$MT_WORK/$f" "$MT_OUT/$f"
done

LICENSE="$LICENSE_DIR/Apache-2.0.txt"
if [ ! -s "$LICENSE" ]; then
  curl -fL --retry 3 \
    https://www.apache.org/licenses/LICENSE-2.0.txt -o "$LICENSE"
fi

echo
echo "------------------------- assets/models -------------------------"
find "$OUT" -type f -printf '%10s  %p\n' | sort -k2
echo
du -sh "$OUT"

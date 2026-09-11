#!/usr/bin/env bash
# 下载离线模型，并直接摆成 APK 里 assets/models/ 的样子（见 ModelsManifest.ASSET_ROOT）。
#
#   ./tools/fetch_models.sh            # 必备模型（VAD + SenseVoice + NLLB）
#   ./tools/fetch_models.sh --whisper  # 再加可选的 Whisper-small
#
# 下载走 tools/chunkdl.py：本机代理每条连接只放行约 2 秒就掐断，
# 普通 curl 单线程下不完，必须分片并行 + 断点续传。
# 需要代理时先 export https_proxy=http://127.0.0.1:7897
set -euo pipefail

cd "$(dirname "$0")/.."

OUT="${MODELS_DIR:-app/src/main/assets/models}"   # 最终目录，直接被打包进 APK
WORK="${WORK_DIR:-.models}"                       # 压缩包与临时解压区（.gitignore 已忽略）
CHUNKDL="python tools/chunkdl.py"
WORKERS="${WORKERS:-24}"
CHUNK="${CHUNK:-2M}"
WITH_WHISPER=0
for a in "$@"; do [ "$a" = "--whisper" ] && WITH_WHISPER=1; done

SHERPA="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
NLLB="https://huggingface.co/JustFrederik/nllb-200-distilled-600M-ct2-int8/resolve/main"

# get <url> <outfile> [sha256]
get() {
  $CHUNKDL "$1" "$2" --workers "$WORKERS" --chunk "$CHUNK" ${3:+--sha256 "$3"}
}

# tar 是 MSYS 版：给 D:/xxx 会被当成「远程主机:路径」，必须转成 /d/xxx
msys_path() {
  cygpath -u "$1" 2>/dev/null || printf '/%s%s' \
    "$(printf '%s' "${1:0:1}" | tr 'A-Z' 'a-z')" "${1:2}"
}

echo "==> 输出目录: $OUT"
mkdir -p "$OUT/vad" "$OUT/sense-voice" "$OUT/nllb" "$WORK"

echo "==> [1/4] Silero VAD (int8)"
get "$SHERPA/silero_vad.int8.onnx" "$OUT/vad/silero_vad.int8.onnx" \
    c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20

echo "==> [2/4] SenseVoice-Small (int8)"
SV_PKG="$WORK/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2"
get "$SHERPA/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2" "$SV_PKG" \
    7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63
if [ ! -f "$OUT/sense-voice/model.int8.onnx" ]; then
  rm -rf "$WORK/sense-voice"
  mkdir -p "$WORK/sense-voice"
  tar -xjf "$(msys_path "$SV_PKG")" -C "$(msys_path "$WORK/sense-voice")" --strip-components=1
  cp "$WORK/sense-voice/model.int8.onnx" "$OUT/sense-voice/"
  cp "$WORK/sense-voice/tokens.txt" "$OUT/sense-voice/"
  rm -rf "$WORK/sense-voice"
fi

echo "==> [3/4] NLLB-200-distilled-600M (CT2 int8)"
get "$NLLB/model.bin" "$OUT/nllb/model.bin" \
    ed1beaf75134de7505315a5223162f56acff397eff6b50638a500d3936fe707b
# config.json 是 CTranslate2 加载模型时要读的配置，缺了它就只会报一句「模型加载失败」
get "$NLLB/config.json" "$OUT/nllb/config.json"
get "$NLLB/sentencepiece.bpe.model" "$OUT/nllb/sentencepiece.bpe.model" \
    14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a
get "$NLLB/shared_vocabulary.txt" "$OUT/nllb/shared_vocabulary.txt" \
    a132a83330f45514c2476eb81d1d69b3c41762264d16ce0a7ea982e5d6c728e5

if [ "$WITH_WHISPER" = "1" ]; then
  echo "==> [4/4] Whisper-small (int8，可选)"
  WH_PKG="$WORK/sherpa-onnx-whisper-small.tar.bz2"
  get "$SHERPA/sherpa-onnx-whisper-small.tar.bz2" "$WH_PKG" \
      486a46afbb7ba798507190ffe02fea2dd726049af212e774537efac6afb210a6
  if [ ! -f "$OUT/whisper/small-encoder.int8.onnx" ]; then
    mkdir -p "$OUT/whisper" "$WORK/whisper"
    tar -xjf "$(msys_path "$WH_PKG")" -C "$(msys_path "$WORK/whisper")" --strip-components=1
    cp "$WORK/whisper/small-encoder.int8.onnx" "$OUT/whisper/"
    cp "$WORK/whisper/small-decoder.int8.onnx" "$OUT/whisper/"
    cp "$WORK/whisper/small-tokens.txt" "$OUT/whisper/"
    rm -rf "$WORK/whisper"
  fi
else
  echo "==> [4/4] 跳过 Whisper（可选模型，加 --whisper 才会下）"
fi

echo
echo "------------------------- assets/models -------------------------"
find "$OUT" -type f -printf '%10s  %p\n' | sort -k2
echo
du -sh "$OUT"

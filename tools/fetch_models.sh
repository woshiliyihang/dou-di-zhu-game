#!/usr/bin/env bash
# 下载离线模型，并直接摆成 APK 里 assets/models/ 的样子（见 ModelsManifest.ASSET_ROOT）。
#
#   ./tools/fetch_models.sh            # 必备模型（VAD + SenseVoice + NLLB）
#   ./tools/fetch_models.sh --whisper  # 再加可选的 Whisper-small
#   ./tools/fetch_models.sh --streaming-en   # 再加英文流式 Zipformer（英→中同传推荐）
#   ./tools/fetch_models.sh --mt-en-zh # 再加英→中专用翻译模型（比 NLLB 快 5~8 倍，要重编 .so）
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
WITH_STREAMING=0
WITH_MT_EN_ZH=0
for a in "$@"; do
  [ "$a" = "--whisper" ] && WITH_WHISPER=1
  [ "$a" = "--streaming-en" ] && WITH_STREAMING=1
  [ "$a" = "--mt-en-zh" ] && WITH_MT_EN_ZH=1
done

SHERPA="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
NLLB="https://huggingface.co/JustFrederik/nllb-200-distilled-600M-ct2-int8/resolve/main"
# 英→中专用的 CT2 模型（约 59M 参数）。想换仓库就 export MT_REPO=...
MT_REPO="${MT_REPO:-gaudi/opus-mt-en-zh-ctranslate2}"

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

if [ "$WITH_STREAMING" = "1" ]; then
  # 流式英文 Zipformer：边说边出字，句尾由 endpoint 规则判，不再需要「说完 → 整段重跑识别」。
  # 英文专用（LibriSpeech 系），做英→中时比 SenseVoice 准得多，而且不花额外的墙钟时间。
  echo "==> 流式英文 Zipformer (chunk-16 left-128, int8)"
  ZF_PKG="$WORK/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2"
  get "$SHERPA/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2" "$ZF_PKG"
  ZF="$OUT/zipformer-en"
  if [ ! -f "$ZF/tokens.txt" ]; then
    mkdir -p "$ZF" "$WORK/zipformer-en"
    tar -xjf "$(msys_path "$ZF_PKG")" -C "$(msys_path "$WORK/zipformer-en")" --strip-components=1
    # 只拷推理要用的四个文件：test_wavs 与 fp32 版本不进 APK（APK 已经 900MB 了）
    cp "$WORK/zipformer-en/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx" "$ZF/"
    cp "$WORK/zipformer-en/decoder-epoch-99-avg-1-chunk-16-left-128.onnx" "$ZF/"
    cp "$WORK/zipformer-en/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx" "$ZF/"
    cp "$WORK/zipformer-en/tokens.txt" "$ZF/"
    rm -rf "$WORK/zipformer-en"
  fi
fi

if [ "$WITH_MT_EN_ZH" = "1" ]; then
  # 为什么要换掉 NLLB：CT2 解码是内存带宽受限的——每生成一个 token 都要把整个
  # 权重读一遍。NLLB-600M int8 约 600MB → 实测每 token ≈45ms；OPUS-MT 只有 ~60MB
  # → 个位数 ms。它又是专门在几十万对英/中句对上训的，准确率不掉。
  # 代价：只认这一对语言，且没有 NLLB 那套语言码体系（要配套重编 .so，见下方提示）。
  echo "==> 英→中专用翻译模型 $MT_REPO"
  MT="$OUT/opus-mt-en-zh"
  mkdir -p "$MT"

  # 同一个 CT2 目录在不同仓库里文件名不统一（spm 可能叫 sentencepiece.model /
  # source.spm，词表可能叫 vocab.txt）。先拿真实文件列表再下，不要猜——猜错会把
  # 404 的 HTML 错误页当模型存下来，到手机上才炸。
  MT_FILES="$(curl -sSL --fail "https://huggingface.co/api/models/$MT_REPO/tree/main" \
      | python -c 'import json,sys;[print(o["path"]) for o in json.load(sys.stdin) if o.get("type")=="file"]' 2>/dev/null || true)"
  if [ -z "$MT_FILES" ]; then
    echo "    拿不到仓库文件列表（离线或 API 变更），按常见名字直接试" >&2
  fi

  # pick <regex> [fallback]：从文件列表里挑第一个匹配的远端路径
  pick() {
    local hit
    hit="$(printf '%s\n' "$MT_FILES" | grep -m1 -E "$1" || true)"
    [ -n "$hit" ] && printf '%s' "$hit" || printf '%s' "${2:-}"
  }

  # fetch_mt <本地名> <远端正则> <猜不到时的默认名>
  fetch_mt() {
    name="$(pick "$2" "$3")"
    [ -s "$MT/$1" ] && { echo "    已有 $1"; return 0; }
    get "https://huggingface.co/$MT_REPO/resolve/main/$name" "$MT/$1"
    echo "    $name → $1"
  }

  fetch_mt model.bin        '^model\.bin$'                        model.bin
  fetch_mt config.json      '^config\.json$'                      config.json
  # 落地名必须叫 sentencepiece.model：native 侧拿「有没有 sentencepiece.bpe.model」
  # 当作「该不该加 NLLB 语言码前缀」的判据，存成那个名字会被误判成 NLLB。
  fetch_mt sentencepiece.model '(sentencepiece[^/]*\.model|source\.spm|vocab\.spm)$' sentencepiece.model
  fetch_mt vocabulary.txt     '(vocab(ulary)?\.txt|shared_vocabulary\.txt)$'  vocabulary.txt
  # 源/目标各一份 spm 的模型（M2M100 那类）才需要 target.spm，没有就跳过
  # （写成 if 而不是 「[ ] && cmd」：后者在 set -e 下遇到空列表会把脚本直接带崩）
  tgt="$(printf '%s\n' "$MT_FILES" | grep -m1 -E '^target\.spm$' || true)"
  if [ -n "$tgt" ]; then
    get "https://huggingface.co/$MT_REPO/resolve/main/$tgt" "$MT/target.spm"
    echo "    $tgt → target.spm"
  fi

  echo
  echo "   ‼ 要用这个模型必须重编 libvtrans-mt.so（它没有 NLLB 的语言码体系）："
  echo "       native/build_android.sh && native/build_vtrans_android.sh"
  echo "   旧 .so 加载它会失败，App 会自动退回 NLLB——功能不受影响，只是仍然慢。"
fi

echo
echo "------------------------- assets/models -------------------------"
find "$OUT" -type f -printf '%10s  %p\n' | sort -k2
echo
du -sh "$OUT"

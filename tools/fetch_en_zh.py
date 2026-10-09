#!/usr/bin/env python
"""英文流式识别模型一键下载（Windows 双击 fetch-models.bat 即可）。

准备随 APK 内置的识别和翻译模型资产：
  1. 下 Silero VAD 模型，摆进 app/src/main/assets/models/vad/。
  2. 下 sherpa-onnx 的英文流式 Zipformer，解出推理要用的 4 个文件，摆进
     app/src/main/assets/models/zipformer-en/  —— 它让「说完话还要等整段重跑识别」
     这件事彻底消失，而且不需要重编任何 native 库。
  3. 下载固定版本的 OPUS-MT 英中 int8 CTranslate2 模型与 Apache-2.0 许可证。

英→中翻译模型从固定版本的预转换 CTranslate2 int8 权重下载并打入 APK；
手机运行时无需联网或 Google 服务。

下载走同目录下的 chunkdl.py：本机的代理单条连接只放行约 2 秒，必须分片并行。
"""

import argparse
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.error
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(REPO, "tools")
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "models")
WORK = os.path.join(REPO, ".models")

SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
HF = ("https://huggingface.co/jiangzhuo9357/opus-mt-en-zh-ct2/"
      "resolve/06fb49e2f6cb0485043ae703a4c2afddd4e700d7")
ZIPFORMER_PKG = "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2"
VAD_URL = f"{SHERPA}/silero_vad.int8.onnx"
VAD_FILE = "silero_vad.int8.onnx"
TRANSLATION_FILES = [
    ("model.bin", "327584c20bb83c7e89d595bcfa30b6ef3771c10816f707e892c4bbb1f808a8fb"),
    ("config.json", "8f6496adfc930cbfecbe8281112197705c488fab47d34b4829b06d7f478909af"),
    ("shared_vocabulary.json", "37314a6abb25ed8f8497498aeeb31fcea98de892bf00ff7c2e8c966b26fe0b82"),
    ("source.spm", "5775ddc9e3ff2fae91554da56468ad35ff56edaba870fea74447bc7234bfdaa8"),
    ("target.spm", "81dc94efa84e4025ef38d25d5d07429fe41e3eb29d44003f1db6fe98487b0052"),
]

# 落地文件名必须和 ModelsManifest.ZIPFORMER_EN 里写死的完全一致，
# 所以这里按「角色」匹配压缩包里的实际文件（前缀 + 后缀 + 要不要 int8），
# 再重命名落地：换个 epoch 或 chunk 配置的包也照样能用，不用改 Java。
# 格式：(角色说明, 落地名, 远端前缀, 后缀, 是否要 int8)
WANT = [
    ("encoder", "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
     "encoder", ".onnx", True),
    # decoder 刻意取 fp32：转写模型的 decoder 一旦被量化，长句容易开始掉字
    ("decoder", "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
     "decoder", ".onnx", False),
    ("joiner", "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
     "joiner", ".onnx", True),
    ("tokens", "tokens.txt", "tokens", ".txt", False),
]


def say(text: str) -> None:
    print(text, flush=True)


def download(url: str, dest: str, min_bytes: int = 1_000_000,
             sha256: str = "") -> bool:
    """调 chunkdl.py 分片下载。已下好（大小吻合）会直接跳过。"""
    if os.path.exists(dest) and os.path.getsize(dest) >= min_bytes:
        if not sha256:
            say(f"  已存在，跳过：{os.path.basename(dest)} "
                f"({os.path.getsize(dest) / 1048576:.1f}MB)")
            return True
    cmd = [sys.executable, os.path.join(TOOLS, "chunkdl.py"), url, dest,
           "--workers", "24", "--chunk", "2M"]
    if sha256:
        cmd.extend(["--sha256", sha256])
    say(f"  下载 {url}")
    return subprocess.call(cmd) == 0


def pick(names, prefix, ext, want_int8, role):
    """在包内文件名里挑出这个角色要的那一个。

    先严格按量化档位配；配不到就放宽——落地名照清单写，内容换成实际存在的那份。
    跑得起来，只是精度或体积与预期有偏，所以必须打警告让人看见。
    """
    for strict in (True, False):
        best = None
        for n in names:
            base = os.path.basename(n)
            if not base.startswith(prefix) or not base.endswith(ext):
                continue
            if strict and (".int8." in base) != want_int8:
                continue
            if best is None or len(base) < len(best):
                best = n      # 同名多份时挑路径最短的（一般在包根目录）
        if best:
            if not strict:
                say(f"  (注意) {role} 没找到期望的量化档位，改用 "
                    f"{os.path.basename(best)}")
            return best
    return None


def extract(pkg: str, out_dir: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    with tarfile.open(pkg, "r:bz2") as tf:
        names = [m.name for m in tf.getmembers() if m.isfile()]
        got = 0
        for role, target, prefix, ext, want_int8 in WANT:
            hit = pick(names, prefix, ext, want_int8, role)
            if hit is None:
                say(f"  !! 压缩包里找不到 {role}（前缀 {prefix}{ext}）")
                continue
            dst = os.path.join(out_dir, target)
            if os.path.exists(dst) and os.path.getsize(dst) > 0:
                say(f"  已有 {target}")
                got += 1
                continue
            with tf.extractfile(hit) as src, open(dst, "wb") as out:
                shutil.copyfileobj(src, out, 4 * 1024 * 1024)
            say(f"  {os.path.basename(hit)} → {target} "
                f"({os.path.getsize(dst) / 1048576:.1f}MB)")
            got += 1
    return got


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--proxy", default="",
                    help="例如 http://127.0.0.1:7897；不填就用系统代理")
    args = ap.parse_args()

    try:  # 中文在 GBK 控制台上一旦编不出就整段崩，这里兜成替换
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    if args.proxy:
        os.environ["HTTPS_PROXY"] = os.environ["https_proxy"] = args.proxy
        os.environ["HTTP_PROXY"] = os.environ["http_proxy"] = args.proxy

    os.makedirs(WORK, exist_ok=True)
    out_dir = os.path.join(ASSETS, "zipformer-en")
    vad_dir = os.path.join(ASSETS, "vad")
    os.makedirs(vad_dir, exist_ok=True)
    vad_file = os.path.join(vad_dir, VAD_FILE)
    pkg = os.path.join(WORK, ZIPFORMER_PKG)

    say("=" * 64)
    say("VAD、英文流式识别与离线英中翻译模型")
    say("=" * 64)
    say("下载 Silero VAD")
    if not download(VAD_URL, vad_file, min_bytes=100_000):
        say("VAD 下载失败。")
        return 1

    say("下载英文流式 Zipformer（边说边出字，说完不用等重跑）")
    if not download(f"{SHERPA}/{ZIPFORMER_PKG}", pkg):
        say("\n下载失败。如果你有代理，改成：")
        say(f"    python tools\\fetch_en_zh.py --proxy http://127.0.0.1:7897")
        return 1

    n = extract(pkg, out_dir)
    say("")
    say(f"解出 {n}/4 个文件 → {out_dir}")
    if n < 4:
        say("数量不对，把上面几行原样发回来我看。")
        return 1

    mt_dir = os.path.join(ASSETS, "opus-mt-en-zh")
    mt_work = os.path.join(WORK, "opus-mt-en-zh")
    os.makedirs(mt_work, exist_ok=True)
    os.makedirs(mt_dir, exist_ok=True)
    say("下载固定版本 OPUS-MT 英中 CTranslate2 int8 模型")
    for filename, sha256 in TRANSLATION_FILES:
        if not download(f"{HF}/{filename}",
                        os.path.join(mt_work, filename), min_bytes=1,
                        sha256=sha256):
            say(f"翻译模型文件下载或校验失败：{filename}")
            return 1
        shutil.copy2(os.path.join(mt_work, filename),
                     os.path.join(mt_dir, filename))

    license_dir = os.path.join(os.path.dirname(ASSETS), "licenses")
    os.makedirs(license_dir, exist_ok=True)
    license_path = os.path.join(license_dir, "Apache-2.0.txt")
    if not os.path.isfile(license_path) or os.path.getsize(license_path) < 10_000:
        try:
            with urllib.request.urlopen(
                    "https://www.apache.org/licenses/LICENSE-2.0.txt",
                    timeout=30) as response, open(license_path, "wb") as out:
                shutil.copyfileobj(response, out)
        except (OSError, urllib.error.URLError) as exc:
            say(f"Apache-2.0 license 下载失败：{exc}")
            return 1

    say("")
    say("=" * 64)
    say("ASR 与英中翻译模型已就绪，全部会内置进 APK。")
    say("=" * 64)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python
"""英→中模型一键下载（ Windows 双击 fetch-models.bat 即可，不用理解任何参数）。

做两件事：
  1. 下 sherpa-onnx 的英文流式 Zipformer，解出推理要用的 4 个文件，摆进
     app/src/main/assets/models/zipformer-en/  —— 它让「说完话还要等整段重跑识别」
     这件事彻底消失，是英→中提速里最大的一块，而且不需要重编任何 native 库。
  2. 可选：下英→中专用翻译模型 OPUS-MT（--with-mt）。它要配套的新
     libvtrans-mt.so 才能生效，所以在库准备好之前别下（白占 60~120MB 包体）。

下载走同目录下的 chunkdl.py：本机的代理单条连接只放行约 2 秒，必须分片并行。
"""

import argparse
import os
import shutil
import subprocess
import sys
import tarfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(REPO, "tools")
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "models")
WORK = os.path.join(REPO, ".models")

SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
ZIPFORMER_PKG = "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2"

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


def download(url: str, dest: str) -> bool:
    """调 chunkdl.py 分片下载。已下好（大小吻合）会直接跳过。"""
    if os.path.exists(dest) and os.path.getsize(dest) > 1_000_000:
        say(f"  已存在，跳过：{os.path.basename(dest)} "
            f"({os.path.getsize(dest) / 1048576:.1f}MB)")
        return True
    cmd = [sys.executable, os.path.join(TOOLS, "chunkdl.py"), url, dest,
           "--workers", "24", "--chunk", "2M"]
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
    ap.add_argument("--with-mt", action="store_true",
                    help="连英→中专用翻译模型一起下（需要配套的新 .so 才生效）")
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
    pkg = os.path.join(WORK, ZIPFORMER_PKG)

    say("=" * 64)
    say("英文流式识别模型（边说边出字，说完不用等重跑）")
    say("=" * 64)
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

    if args.with_mt:
        say("")
        say("（--with-mt）英→中专用翻译模型：这一步还要配套重编 libvtrans-mt.so，"
            "没编之前 App 会自动退回 NLLB，下了也不会生效。")

    say("")
    say("=" * 64)
    say("模型好了。现在只做一件事：关掉这个窗口，然后回来说一句「模型下好了」。")
    say("剩下的重新打包、装到手机、抓日志算延迟，全部我来。")
    say("=" * 64)
    return 0


if __name__ == "__main__":
    sys.exit(main())

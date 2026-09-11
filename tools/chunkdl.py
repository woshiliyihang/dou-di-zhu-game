#!/usr/bin/env python
"""分段并行下载器（代理友好）。

本机/某些代理会在单条连接上只放行约 2 秒（几百 KB）就掐断，
单线程 curl -C - 续传要跑好几个小时。这里把文件切成小块，
多线程各自带 Range 续传，最后按序拼成完整文件。

用法：
    python tools/chunkdl.py <url> <outfile> [--workers 16] [--chunk 4M] [--sha256 HEX]

- 已存在且大小吻合的目标文件直接跳过。
- 断点：每块的进度落在 <outfile>.parts/NNNNNN.part，重跑时继续。
- 退出码 0 表示文件大小正确（给了 --sha256 时还要求校验通过）。
"""

import argparse
import hashlib
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests

CHUNK_READ = 64 * 1024
RETRY_SLEEP = 0.4


def human(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f}{unit}" if unit != "B" else f"{int(n)}B"
        n /= 1024
    return f"{n:.1f}GB"


def parse_size(text: str) -> int:
    text = text.strip().upper()
    mult = 1
    for suffix, m in (("G", 1024 ** 3), ("M", 1024 ** 2), ("K", 1024),
                      ("GB", 1024 ** 3), ("MB", 1024 ** 2), ("KB", 1024)):
        if text.endswith(suffix):
            mult, text = m, text[: -len(suffix)]
            break
    return int(float(text) * mult)


def probe(session: requests.Session, url: str) -> tuple[int, bool]:
    """返回 (文件总长度, 服务端是否支持 Range)。

    以「带 Range 的 GET」为准：HuggingFace 的 307 跳转经过代理时，
    HEAD 偶尔会拿到一个错的 Content-Length（实测拿到过 1 字节），
    而 Content-Range 里的总长度是服务端给的，可信。
    """
    try:
        r = session.get(url, headers={"Range": "bytes=0-0"}, stream=True, timeout=30)
        content_range = r.headers.get("Content-Range", "")
        r.close()
        if r.status_code == 206 and "/" in content_range:
            return int(content_range.rsplit("/", 1)[1]), True
    except requests.RequestException:
        pass

    r = session.head(url, allow_redirects=True, timeout=30)
    length = int(r.headers["Content-Length"])
    accept = r.headers.get("Accept-Ranges", "").lower() == "bytes"
    return length, accept


def fetch_range(session: requests.Session, url: str, start: int, end: int,
                part: str, state: dict, lock: threading.Lock) -> None:
    """下载 [start, end] 这一段，断点续传直到写满。"""
    total = end - start + 1
    done = os.path.getsize(part) if os.path.exists(part) else 0
    while done < total:
        headers = {"Range": f"bytes={start + done}-{end}"}
        try:
            r = session.get(url, headers=headers, stream=True, timeout=30)
            # 200 说明服务端不认 Range：只能整段重下，交给调用方处理
            if r.status_code == 200:
                r.close()
                raise RuntimeError("服务端忽略 Range，无法分片")
            r.raise_for_status()
            with open(part, "ab") as f:
                for buf in r.iter_content(CHUNK_READ):
                    if not buf:
                        continue
                    f.write(buf)
                    with lock:
                        state["bytes"] += len(buf)
                    done += len(buf)
        except Exception:  # 连接被掐断 / 超时，续传重试
            time.sleep(RETRY_SLEEP)
        finally:
            try:
                r.close()
            except Exception:
                pass
    if os.path.getsize(part) != total:
        raise RuntimeError(f"{part} 写入不完整：{os.path.getsize(part)}/{total}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("out")
    ap.add_argument("--workers", type=int, default=16)
    ap.add_argument("--chunk", default="4M")
    ap.add_argument("--sha256", default=None)
    args = ap.parse_args()

    out = args.out
    os.makedirs(os.path.dirname(os.path.abspath(out)) or ".", exist_ok=True)

    session = requests.Session()  # 走环境变量 http_proxy/https_proxy
    session.trust_env = True
    length, accept_ranges = probe(session, args.url)
    print(f"{os.path.basename(out)}: {human(length)} (Range={'yes' if accept_ranges else 'no'})")
    if not accept_ranges:
        return 1

    # 大小吻合才算下完了（探测到过 1 字节的错误长度，别把一个残缺文件当完成）
    if os.path.exists(out) and os.path.getsize(out) == length:
        print(f"skip (exists): {out} ({human(length)})")
        if args.sha256:
            return 0 if verify_sha256(out, args.sha256) else 1
        return 0

    chunk = parse_size(args.chunk)
    parts_dir = out + ".parts"
    os.makedirs(parts_dir, exist_ok=True)
    ranges = []
    for start in range(0, length, chunk):
        end = min(start + chunk - 1, length - 1)
        ranges.append((start, end, os.path.join(parts_dir, f"{start:09d}.part")))

    state = {"bytes": 0}
    lock = threading.Lock()
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(fetch_range, session, args.url, s, e, p, state, lock)
                   for s, e, p in ranges]
        while any(not f.done() for f in futures):
            time.sleep(2)
            with lock:
                got = state["bytes"]
            speed = got / max(time.time() - t0, 0.1)
            print(f"  {human(got)}/{human(length)}  {human(speed)}/s", flush=True)
        for f in futures:
            f.result()  # 抛异常就退出

    with open(out, "wb") as dst:
        for _, _, part in ranges:
            with open(part, "rb") as src:
                while True:
                    buf = src.read(4 * 1024 * 1024)
                    if not buf:
                        break
                    dst.write(buf)
            os.remove(part)
    os.rmdir(parts_dir)

    size = os.path.getsize(out)
    print(f"done: {out} ({human(size)}) in {time.time() - t0:.0f}s")
    if size != length:
        print("文件大小不符，下载失败", file=sys.stderr)
        return 1
    if args.sha256:
        return 0 if verify_sha256(out, args.sha256) else 1
    return 0


def verify_sha256(path: str, expected: str) -> bool:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for buf in iter(lambda: f.read(4 * 1024 * 1024), b""):
            h.update(buf)
    got = h.hexdigest()
    print(f"sha256 {'OK' if got.lower() == expected.lower() else 'MISMATCH ' + got}")
    return got.lower() == expected.lower()


if __name__ == "__main__":
    sys.exit(main())

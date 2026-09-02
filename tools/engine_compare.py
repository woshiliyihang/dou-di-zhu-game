#!/usr/bin/env python3
"""
对比 SenseVoice int8 与 Whisper small int8 在同一批测试音频上的准确率与延迟。
用于决定默认档位与各语种的最佳引擎。

用法:
  python3 tools/engine_compare.py --models /tmp/models
"""
import argparse
import os
import time
import wave

import numpy as np
import sherpa_onnx

# (wav, 提示语种, 参考文本)
CASES = [
    ("zh.wav", "zh", "开放时间早上九点至下午五点"),
    ("en.wav", "en",
     "THE TRIVIAL CHIEF THEN CALLED FOR THE BOY AND PRESENTED HIM WITH FIFTY "
     "PIECES OF GOLD"),
    ("ja.wav", "ja", "家中では皆、それぞれの弁当を持参し、学校の売店では借り"),
    ("ko.wav", "ko", "그는 아침에 늦게 일어나면서도 훨씬 더"),
    ("yue.wav", "yue", "呢幾個字都表達唔到我想講嘅意思"),
]


def read_wav(path):
    with wave.open(path, "rb") as f:
        s = np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16)
        return s.astype(np.float32) / 32768.0, f.getframerate()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="/tmp/models")
    ap.add_argument("--threads", type=int, default=4)
    args = ap.parse_args()

    sv = os.path.join(args.models, "sense-voice-int8")
    wh = os.path.join(args.models, "whisper-small")
    wav_dir = os.path.join(sv, "test_wavs")

    rec_sv = sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=os.path.join(sv, "model.int8.onnx"),
        tokens=os.path.join(sv, "tokens.txt"),
        num_threads=args.threads, use_itn=True, language="auto", provider="cpu")
    rec_wh = {}
    # whisper 不接受 "auto"，且 small 词表里没有 yue → 回退到 zh
    wh_lang = {"zh": "zh", "en": "en", "ja": "ja", "ko": "ko", "yue": "zh"}
    for wav, lang, _ref in CASES:
        lg = wh_lang.get(lang)
        if lg is None or lg in rec_wh:
            continue
        rec_wh[lg] = sherpa_onnx.OfflineRecognizer.from_whisper(
                encoder=os.path.join(wh, "small-encoder.int8.onnx"),
                decoder=os.path.join(wh, "small-decoder.int8.onnx"),
                tokens=os.path.join(wh, "small-tokens.txt"),
                num_threads=args.threads, language=lg, task="transcribe",
                provider="cpu")

    print(f"{'wav':10s} {'引擎':12s} {'耗时':>8s} {'RTF':>7s}  文本")
    print("-" * 100)
    for wav, lang, ref in CASES:
        p = os.path.join(wav_dir, wav)
        if not os.path.exists(p):
            continue
        samples, sr = read_wav(p)
        dur = len(samples) / sr
        for name, rec in (("sensevoice", rec_sv), ("whisper", rec_wh.get(wh_lang.get(lang, "zh")))):
            if rec is None:
                continue
            st = rec.create_stream()
            t0 = time.perf_counter()
            st.accept_waveform(sr, samples)
            rec.decode_stream(st)
            dt = (time.perf_counter() - t0) * 1000
            print(f"{wav:10s} {name:12s} {dt:7.0f}ms {dt/1000/dur:7.3f}  "
                  f"{st.result.text}")
        print(f"{'':10s} {'参考':12s} {'':8s} {'':7s}  {ref}")
        print("-" * 100)


if __name__ == "__main__":
    main()

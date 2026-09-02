#!/usr/bin/env python3
"""
ASR + VAD 基线：用 sherpa-onnx Python 跑 SenseVoice int8 / Whisper small int8 / Silero VAD。

确认点：
  1. VAD 能正确切出语音段
  2. SenseVoice 的 "auto" 语种输出格式（是否带 <|zh|> 之类的标签）——决定 Java 侧要不要去标签
  3. 各档位在本机 x86 上的延迟基线

用法:
  python3 tools/asr_probe.py --models /tmp/models
"""
import argparse
import os
import sys
import time
import wave

import numpy as np
import sherpa_onnx


def read_wav(path):
    with wave.open(path, "rb") as f:
        assert f.getnchannels() == 1, path
        assert f.getsampwidth() == 2, path
        samples = np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16)
        return samples.astype(np.float32) / 32768.0, f.getframerate()


def build_recognizer(models, engine, threads):
    if engine == "sensevoice":
        d = os.path.join(models, "sense-voice-int8")
        return sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=os.path.join(d, "model.int8.onnx"),
            tokens=os.path.join(d, "tokens.txt"),
            num_threads=threads,
            use_itn=True,
            language="auto",
            debug=False,
            provider="cpu",
        )
    d = os.path.join(models, "whisper-small")
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=os.path.join(d, "small-encoder.int8.onnx"),
        decoder=os.path.join(d, "small-decoder.int8.onnx"),
        tokens=os.path.join(d, "small-tokens.txt"),
        num_threads=threads,
        language="auto",
        task="transcribe",
        provider="cpu",
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="/tmp/models")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--engine", default="sensevoice",
                    choices=["sensevoice", "whisper"])
    args = ap.parse_args()

    wav_dir = os.path.join(args.models, "sense-voice-int8", "test_wavs")
    wavs = ["zh.wav", "en.wav", "ja.wav", "ko.wav", "yue.wav"]
    wavs = [w for w in wavs if os.path.exists(os.path.join(wav_dir, w))]
    if not wavs:
        wavs = sorted(os.listdir(wav_dir))[:5]

    print(f"engine={args.engine} threads={args.threads}")
    rec = build_recognizer(args.models, args.engine, args.threads)

    print("=" * 78)
    print("ASR（整段识别）")
    print("=" * 78)
    for w in wavs:
        p = os.path.join(wav_dir, w)
        samples, sr = read_wav(p)
        dur = len(samples) / sr
        t0 = time.perf_counter()
        stream = rec.create_stream()
        stream.accept_waveform(sr, samples)
        rec.decode_stream(stream)
        dt = (time.perf_counter() - t0) * 1000
        print(f"[{w}] {dur:.2f}s 音频 -> {dt:.0f}ms  (RTF {dt/1000/dur:.3f})")
        print(f"   text = {stream.result.text!r}")
        if getattr(stream.result, "lang", ""):
            print(f"   lang = {stream.result.lang!r}")

    print()
    print("=" * 78)
    print("VAD（silero v5 int8）")
    print("=" * 78)
    vad_cfg = sherpa_onnx.VadModelConfig()
    vad_cfg.silero_vad.model = os.path.join(args.models, "silero_vad.int8.onnx")
    vad_cfg.silero_vad.min_silence_duration = 0.25
    vad_cfg.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(vad_cfg, buffer_size_in_seconds=100)

    # 合成一段：1s 静音 + 语音 + 1s 静音，检验端点检测
    for w in wavs[:2]:
        samples, sr = read_wav(os.path.join(wav_dir, w))
        if sr != 16000:
            n = int(len(samples) * 16000 / sr)
            samples = np.interp(np.linspace(0, len(samples), n),
                                np.arange(len(samples)), samples).astype(np.float32)
        audio = np.concatenate([np.zeros(16000, dtype=np.float32), samples,
                                np.zeros(24000, dtype=np.float32)])
        vad.reset()
        t0 = time.perf_counter()
        step = 512  # 32ms @16k
        for i in range(0, len(audio), step):
            vad.accept_waveform(audio[i:i + step])
        while not vad.empty():
            seg = vad.front
            vad.pop()
            print(f"[{w}] 段 {seg.start/16000:.2f}s ~ "
                  f"{(seg.start+len(seg.samples))/16000:.2f}s "
                  f"({len(seg.samples)/16000:.2f}s)")
        dt = (time.perf_counter() - t0) * 1000
        print(f"   VAD 总耗时 {dt:.0f}ms / 音频 {len(audio)/16000:.2f}s "
              f"(RTF {dt/1000/(len(audio)/16000):.4f})")


if __name__ == "__main__":
    sys.exit(main())

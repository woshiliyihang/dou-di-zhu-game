#!/usr/bin/env python3
"""
确定 NLLB-200 (CTranslate2 int8) 的正确 token 配方，并给出延迟基线。

用法:
  python3 tools/recipe_probe.py --models /tmp/models
"""
import argparse
import os
import sys
import time

import ctranslate2
import sentencepiece as spm

# (文本, 源 NLLB 码, 目标 NLLB 码, 参考译文) —— 参考译文仅用于人眼核对配方是否正确
CASES = [
    ("今天天气很好，我们一起去公园散步吧。", "zho_Hans", "eng_Latn",
     "The weather is great today, let's go for a walk in the park together."),
    ("请问最近的地铁站在哪里？", "zho_Hans", "eng_Latn",
     "Excuse me, where is the nearest subway station?"),
    ("The meeting has been rescheduled to three o'clock tomorrow afternoon.",
     "eng_Latn", "zho_Hans", "会议已改到明天下午三点。"),
    ("Could you please speak a little slower?", "eng_Latn", "zho_Hans",
     "您能说慢一点吗？"),
    ("これはテストです。音声翻訳の品質を確認しています。", "jpn_Jpan", "zho_Hans",
     "这是测试。正在确认语音翻译的质量。"),
    ("Bonjour, je voudrais réserver une table pour deux personnes.",
     "fra_Latn", "eng_Latn", "Hello, I would like to book a table for two."),
]


def detok(sp: spm.SentencePieceProcessor, pieces):
    """去掉语言码与 </s>，再解码。"""
    out = []
    for p in pieces:
        if p.startswith("▁▁") or p in ("</s>", "<pad>"):
            continue
        if len(p) == 7 and p[0] == "▁" and "_" in p:  # ▁zho_Hans 形式的语言码
            continue
        out.append(p)
    return sp.decode(out)


def run_variant(translator, sp, text, src, tgt, add_eos, use_prefix, beam):
    src_pieces = [src] + sp.encode_as_pieces(text)
    if add_eos:
        src_pieces.append("</s>")
    target_prefix = [[tgt]] if use_prefix else None
    t0 = time.perf_counter()
    res = translator.translate_batch(
        [src_pieces],
        target_prefix=target_prefix,
        beam_size=beam,
        max_decoding_length=256,
        return_scores=False,
    )[0]
    dt = (time.perf_counter() - t0) * 1000
    return detok(sp, res.hypotheses[0]), dt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="/tmp/models")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--beam", type=int, default=1)
    args = ap.parse_args()

    mt_dir = os.path.join(args.models, "nllb-600m-ct2-int8")
    sp_path = os.path.join(mt_dir, "sentencepiece.bpe.model")
    for p in (mt_dir, sp_path):
        if not os.path.exists(p):
            sys.exit(f"缺少模型: {p}（先跑 tools/fetch_models.sh）")

    sp = spm.SentencePieceProcessor(model_file=sp_path)
    translator = ctranslate2.Translator(
        mt_dir, device="cpu", compute_type="int8",
        intra_threads=args.threads, inter_threads=1,
    )
    print(f"vocab={sp.get_piece_size()}  ct2={ctranslate2.__version__} "
          f"threads={args.threads} beam={args.beam}\n")

    variants = [
        ("A  src+[</s>], prefix", True, True),
        ("B  src(无</s>), prefix", False, True),
        ("C  src+[</s>], 无prefix", True, False),
        ("D  src(无</s>), 无prefix", False, False),
    ]
    for name, add_eos, use_prefix in variants:
        print("=" * 78)
        print(f"配方 {name}")
        print("=" * 78)
        for text, src, tgt, ref in CASES:
            out, dt = run_variant(translator, sp, text, src, tgt,
                                  add_eos, use_prefix, args.beam)
            print(f"[{src}->{tgt}] {dt:7.1f}ms")
            print(f"   src : {text}")
            print(f"   hyp : {out}")
            print(f"   ref : {ref}")
        print()

    # 延迟基线：单句 beam=1 / beam=4
    print("=" * 78)
    print("延迟基线（每档跑 5 次取中位数）")
    print("=" * 78)
    for beam in (1, 4):
        for text, src, tgt, _ in CASES[:4]:
            lats = []
            for _ in range(5):
                _, dt = run_variant(translator, sp, text, src, tgt,
                                    True, True, beam)
                lats.append(dt)
            lats.sort()
            print(f"beam={beam} [{src}->{tgt}] median={lats[len(lats)//2]:.1f}ms "
                  f"min={lats[0]:.1f}ms max={lats[-1]:.1f}ms  ({text[:24]}…)")


if __name__ == "__main__":
    main()

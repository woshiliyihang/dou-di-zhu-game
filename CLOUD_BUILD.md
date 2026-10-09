# 云端编译任务：交叉编译 libvtrans-mt.so（arm64-v8a）

把本文件整份贴给 Codespaces / 云端 AI。目标只有一个：产出可用的
`app/src/main/jniLibs/arm64-v8a/libvtrans-mt.so`，按第 4 节自检并推到 `native-build` 分支。

---

## 0. 背景（为什么要在云端做）

这是一个离线英→中语音翻译 App（Android，arm64-v8a）。翻译引擎是 CTranslate2 + NLLB，
现在要换成英/中语对专用模型 OPUS-MT（权重只有 NLLB 的十分之一，解码受内存带宽限制，
因而能快好几倍）。为此必须重编 JNI 封装 `libvtrans-mt.so`。

委托人的 Windows 机器**有 NDK、有 CMake，编译器不缺**，缺的是依赖源码：
GitHub 的 `/archive/*.zip` 会 302 跳到 codeload，不支持 Range 分片，而他本地代理只放行
约 2 秒的单连接，`git clone` 大仓反复断。云端没有这两个限制，所以交给云端。

## 1. 直接执行（不要自己另写构建流程）

仓库里已经把全部构建知识固化成脚本了，照跑即可：

```bash
bash native/cloud_build.sh
```

它会依次：装 cmake/ninja/file → 下 NDK r26d → 钉版本 clone sentencepiece v0.2.0 与
CTranslate2 v3.24.0 → 跑 `native/build_android.sh` 交叉编 arm64 静态库 →
跑 `native/build_vtrans_android.sh` 编出 `.so` 并放进 jniLibs → 打印第 4 节的自检。
CTranslate2 编译约 10~20 分钟，属正常，不要因为慢就中断。

## 2. 硬性约束（违反就白干）

- **CTranslate2 必须是 3.x（钉 v3.24.0），绝对不要升到 4.x。**
  `app/src/main/cpp/mt_engine.cpp` 用的是 3.x 独有的构造签名
  `Translator(model_dir, Device, ComputeType, device_indices, max_queued_batches, ReplicaPoolConfig)`，
  而 4.0 把 `ReplicaPoolConfig` 和 `max_queued_batches` 整个删了，升版本必然编译失败。
- **不要改 `mt_engine.cpp` 的业务逻辑。** 只有两种情况允许动它，且必须把 diff 贴回来：
  1. CT2/sentencepiece 头文件里的 API 名字与 3.24 实际有出入（改名、挪命名空间）；
  2. 编译需要补 `#include`。
  改动范围只限"让它编过"，不许顺手重写翻译流程。
- **不要动任何 Java 文件、`build.gradle`、`assets/` 下的模型。**
- **不要把模型文件 commit 进仓库**（委托人会用 `tools/chunkdl.py` 在本机下载）。
- 链接参数别改：`native/build_android.sh` 与 `app/src/main/cpp/CMakeLists.txt` 里
  `-Wl,--start-group`、`-static-openmp`、`-Wl,-z,max-page-size=16384`、
  以及"刻意不开 `--gc-sections`"都是有意为之（CT2 的算子靠静态对象注册，裁剪会悄悄丢算子）。

## 3. 如果编译失败

先按报错定位，再决定：

- 报 `ReplicaPoolConfig` / `max_queued_batches` / `no member named` → 八成是源码不是 3.24，
  用 `git -C /tmp/src/ctranslate2 describe --tags` 确认，必要时重 clone 到 v3.24.0。
- 报找不到 `Eigen/Dense`、`ruy/...`、`cpu_features/...` 头 → 是 CT2 的 `deps` target 没跑，
  确认 `native/build_android.sh` 里 configure 之后有
  `cmake --build ... --target deps` 这一行（脚本已补上，别删）。
- 链接报 `undefined reference to GOMP_xxx` → OpenMP 被排到了库之前，检查
  `-fopenmp -static-openmp` 是否还在 `--end-group` 之后。
- 报 `liblog.so` 相关（`__android_log_print`）→ `target_link_libraries(vtrans-mt PRIVATE log android)` 被挪动了。

改完请重跑第 1 节那条命令，别只编一半。

## 4. 交付前自检（输出请原样贴回给委托人）

`cloud_build.sh` 末尾会自动打印，等价的手工命令：

```bash
SO=app/src/main/jniLibs/arm64-v8a/libvtrans-mt.so
BIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
ls -l "$SO"
"$BIN/llvm-readelf" -d "$SO" | grep -E "NEEDED|SONAME"
"$BIN/llvm-readelf" -l "$SO" | grep -E "LOAD|Align"
"$BIN/llvm-nm" -D --defined-only "$SO" | grep "Java_com_example_vtrans"
"$BIN/llvm-nm" -D --defined-only "$SO" | grep -c ctranslate2
```

合格标准：

| 项 | 期望 |
|---|---|
| `NEEDED` | 只有 `liblog.so libandroid.so libm.so libdl.so libc.so`，出现其它 `.so` 就是静态没吃干净 |
| LOAD 段 `Align` | `0x4000`（16384），Android 15+ 强制要求 |
| JNI 符号 | 7 个 `Java_com_example_vtrans_MtEngine_*` |
| `ctranslate2` 符号数 | 上千（旧包实测 2172），接近 0 说明根本没链进去 |
| 文件大小 | 几 MB 到十几 MB 都算正常 |

## 5. 顺手做一件比编译更值钱的事（强烈建议，几分钟）

交叉编译只能证明"能编出来"，证明不了"新模型能被正确加载和翻译"。云端有 x86 Linux，
先用 Python 把配方验一遍，能提前抓出命名/前缀这类只有到手机上才会暴露的问题：

```bash
pip install ctranslate2==3.24.0 sentencepiece
python3 - <<'PY'
import os, json, shutil, urllib.request
import sentencepiece as spm, ctranslate2 as ct2

d = "/tmp/opus-mt-en-zh"; os.makedirs(d, exist_ok=True)
repo = "gaudi/opus-mt-en-zh-ctranslate2"      # 也可换别的 CT2 转换版
tree = json.load(urllib.request.urlopen(f"https://huggingface.co/api/models/{repo}/tree/main"))
for o in tree:
    if o["type"] != "file":
        continue
    name = o["path"].split("/")[-1]
    dst = os.path.join(d, name)
    if os.path.exists(dst):
        continue
    urllib.request.urlretrieve(f"https://huggingface.co/{repo}/resolve/main/{o['path']}", dst)
    print("got", name)

# native 侧的判据：目录里有 sentencepiece.bpe.model 就当它是 NLLB、去加语言码前缀。
# 语对模型必须避开这个名字，所以源分词器落地成 sentencepiece.model、目标落地成 target.spm
# （mt_engine.cpp 的 firstExisting 列表认的就是这两个名字）。这里用 copy 不 rename，
# 是为了让目录里同时保留仓库原名，方便对比。
files = sorted(os.listdir(d))
print("dir:", files)
cands = [f for f in files if f.endswith((".model", ".spm"))]
src = next(f for f in cands if "target" not in f)
tgt = next((f for f in cands if "target" in f), src)
shutil.copyfile(os.path.join(d, src), os.path.join(d, "sentencepiece.model"))
if tgt != src:
    shutil.copyfile(os.path.join(d, tgt), os.path.join(d, "target.spm"))

sp = spm.SentencePieceProcessor(model_file=os.path.join(d, "sentencepiece.model"))
sp_t = sp if tgt == src else spm.SentencePieceProcessor(model_file=os.path.join(d, "target.spm"))
print("ct2", ct2.__version__, "| src spm =", src, "| tgt spm =", tgt, "| vocab", sp.get_piece_size())

tr = ct2.Translator(d, device="cpu", compute_type="int8", intra_threads=4)
for s in ("The quick brown fox jumps over the lazy dog.",
          "We don't have enough time to finish this today."):
    toks = sp.encode(s, out_type=str)
    # 关键：语对模型不传任何语言码 prefix（native 侧 nllb_style_=false 时传的也是空 prefix）
    hyp = tr.translate_batch([toks])[0].hypotheses[0]
    print("EN:", s)
    print("ZH:", sp_t.decode(hyp), "| tokens", len(toks), "->", len(hyp))
PY
```

要报给委托人的结论：**(a)** 中文译文是否通顺；**(b)** 同一句用 NLLB-600M int8 各跑一次，
两者的 `秒/千字` 对比（x86 上的比例参考，不用和手机绝对值比）；**(c)** 目录里实际有哪些文件
（把 `dir:` 那一行原样贴回来）。

## 6. 交付

自检通过就推回来，委托人会自己 `git fetch` 取走，不需要他碰命令行：

```bash
git checkout -B native-build
git add -f app/src/main/jniLibs/arm64-v8a/libvtrans-mt.so
git commit -m "native: rebuild libvtrans-mt.so (CT2 3.24 + OPUS-MT pair model)"
git push -f origin native-build
```

如果第 5 节做过 Python 侧验证，请把它打印的那两行（`ZH:` 和耗时对比）一并写进提交信息或
贴回对话——委托人要用它判断是否值得直接切默认模型。

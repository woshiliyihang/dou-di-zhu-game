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
  `Translator(model_dir, Device, ComputeType, device_indices, ReplicaPoolConfig)`，
  队列上限通过 `ReplicaPoolConfig.max_queued_batches` 设置；4.0 移除了该配置，升版本可能编译失败。
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
- 报找不到 `Eigen/Dense`、`ruy/...`、`cpu_features/...` 头 → 检查 CT2 子模块是否完整，
  并确认 `native/build_android.sh` 对配置后的目录执行了 `cmake --build ... --target install`。
  v3.24.0 没有名为 `deps` 的汇总 target，其第三方库由 `install` 的依赖图构建。
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
# SGEMM 后端（上一版就是漏了这两条，才让一个会崩的库过关了）
"$BIN/llvm-nm" -u "$PREFIX/lib/libctranslate2.a" | grep -c dnnl
strings -a "$SO" | grep -ci "dnnl\|onednn"
```

合格标准：

| 项 | 期望 |
|---|---|
| `NEEDED` | 只有 `liblog.so libandroid.so libm.so libdl.so libc.so`，出现其它 `.so` 就是静态没吃干净 |
| LOAD 段 `Align` | `0x4000`（16384），Android 15+ 强制要求 |
| JNI 符号 | 7 个 `Java_com_example_vtrans_pipeline_MtEngine_*` |
| `ctranslate2` 符号数 | 上千（旧包实测 2172），接近 0 说明根本没链进去 |
| **SGEMM 后端** | **上面两条计数都必须 > 0**。为 0 就是废品：库能加载、能识别、一切看起来正常，但第一次调用翻译就 `abort`。上一版正是从这里逐关验过、却在真机爆掉 |
| 文件大小 | 几 MB 到十几 MB 都算正常；带 oneDNN 后应比无后端版（4,738,672）明显变大 |

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

## 7. 本次编译记录（2026-10-09）

- 工具链：Android NDK r26d；CTranslate2 v3.24.0；SentencePiece v0.2.0。
- 目标 ABI：`arm64-v8a`。
- 产物：`app/src/main/jniLibs/arm64-v8a/libvtrans-mt.so`，4,738,672 字节。
- 静态库：`libctranslate2.a` 80,428,272 字节；`libsentencepiece.a` 25,704,662 字节；均已静态链接进 JNI so。
- 完整执行 `bash native/cloud_build.sh` 成功。`NEEDED` 仅有 `liblog.so`、`libandroid.so`、`libm.so`、`libdl.so`、`libc.so`；所有 `LOAD` 段对齐为 `0x4000`；导出 7 个 `Java_com_example_vtrans_pipeline_MtEngine_*` 符号和 2035 个 `ctranslate2` 符号。
- 未执行第 5 节的 Hugging Face 模型下载、实际翻译质量检查及 NLLB/OPUS-MT 速度对比；本记录只证明交叉编译与 ELF 自检通过。

## 8. 第二轮任务：上一版产物在真机上崩了，原因已定位

上一版（`native-build` 分支）把第 4 节的 ELF 自检逐关过掉了，装到真机上也能加载模
型、识别也能出字，但**第一次调用翻译就整个进程死掉**：

```
Abort message: 'terminating due to uncaught exception of type
               std::runtime_error: No SGEMM backend on CPU'
#01 ... base.apk!libvtrans-mt.so
```

根因在 `native/build_android.sh` 的 cmake 参数，上一版这么写的：

```bash
-DWITH_MKL=OFF -DWITH_DNNL=OFF -DWITH_OPENBLAS=OFF -DWITH_ACCELERATE=OFF
-DWITH_RUY=ON
```

四个 SGEMM 后端全关，只留一个 RUY。但 **RUY 在 CTranslate2 里只做 INT8 GEMM（量
化算子），不提供 FP32 的 SGEMM**，于是 `src/cpu/sgemm.cc` 走到「没有后端」的分支，
运行时抛 `std::runtime_error` → `abort()` → Java 层 `catch(Throwable)` 抢不到（
因为进程直接退出了）。INT8 模型一样要跑浮点矩阵乘，所以症状是：加载能过、一解码就死。

这一行我已经改成 `-DWITH_DNNL=ON`，并另外做了三道防呆（**不达标脚本会直接 exit 1，
不会再把一个会崩的库推回来**）：

1. `build_android.sh`：配置完就查 `libdnnl.a` 是否存在、`libctranslate2.a` 里是否真的
   引用了 `dnnl_*`（用 `llvm-nm -u`，因为 oneDNN 是个独立静态库，CT2 只“引用”它）。
2. `build_android.sh`：把 `libdnnl.a` 拷进 `$PREFIX/lib`。这一步不能省：
   `app/src/main/cpp/CMakeLists.txt` 是用 `file(GLOB $PREFIX/lib/*.a)` 拉依赖库的，
   不放进 prefix 就链不进去。
3. `cloud_build.sh`：交付前再查一次最终 `.so` 里的 oneDNN 字符串（`strings` 的
   好处是符号表被 strip 也照样能查）。

### 你要做的

```bash
git fetch origin
git checkout -B native-build origin/native-build   # 分支会被委托人重写，用 -B 硬对齐，不要 merge
bash native/cloud_build.sh
```

不要自己重写构建流程，也不要“顺手优化”那几个 cmake 开关。

### C++ 侧有一处你必须再改一次（不改就编译失败）

委托人这次把 `app/src/main/cpp/mt_engine.cpp` **退回了仓库旧 `.so` 配套的那份写法**（为了
让真机先恢复可用），它是**六参**构造；而第 2 节里记的是 v3.24 的**五参**。所以请重复你
上一轮做过的那个改动，只删掉一个参数：

```cpp
// 仓库现在长这样（倒数第二个 false 是 max_queued_batches，v3.24 没这个位置参数）
translator_.reset(new ctranslate2::Translator(
    model_dir_, ctranslate2::Device::CPU,
    ctranslate2::ComputeType::INT8, std::vector<int>{0},
    false, cfg));

// 改成这样（就是你上一轮编 v3.24 用的写法）
translator_.reset(new ctranslate2::Translator(
    model_dir_, ctranslate2::Device::CPU,
    ctranslate2::ComputeType::INT8, std::vector<int>{0}, cfg));
```

这是本轮**唯一允许你改的 C++ 代码**，改完把 diff 贴回来；第 2 节“不许动业务逻辑”的约束仍然有效。

### 如果 oneDNN 交叉到 arm64 编译失败

**先定位、再动手，不要猜选项名**（把命令输出贴回给我）：

```bash
grep -rn "No SGEMM backend" "$SRC/ctranslate2/src"        # 看它外层 #if/#elif 到底判的是哪个宏
grep -rn "WITH_DNNL\|WITH_MKL\|WITH_EIGEN\|WITH_OPENBLAS" \
     "$SRC/ctranslate2/CMakeLists.txt" "$SRC/ctranslate2/cmake"   # 看选项是否存在、是否在 Android 下被强制关掉
git -C "$SRC/ctranslate2" submodule status                # 行首带 - 表示子模块没初始化
```

按优先级尝试，并在回报里写清最终用的是哪个：

- oneDNN（`WITH_DNNL=ON`）——首选，CT2 在 ARM 上的推荐后端；
- 如果发现 CMake 在非 Linux 系统上硬性把 `WITH_DNNL` 抬回 OFF，不要魔改 CT2 源码，
  把那段 CMake 原文贴回来；
- 实在编不出带 SGEMM 后端的 arm64 库，**就不要交付**：无后端的版本比没有版本更糟
  （仓库里现在那颗旧 `.so` 是能跑的，只是慢），拿不准就停下来问我。

### 交付要求

除了第 4 节的全部输出，额外必贴（这是本轮真正的验收点）：

```bash
ls -l "$PREFIX"/lib/libdnnl*.a
"$BIN/llvm-nm" -u "$PREFIX/lib/libctranslate2.a" | grep -c dnnl
strings -a "$SO" | grep -ci "dnnl\|onednn"
ls -l "$SO"     # 体积应当比 4,738,672 明显变大
```

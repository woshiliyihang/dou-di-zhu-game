#pragma once
// NLLB-200 (CTranslate2 int8) 翻译引擎 —— host 与 Android 共用同一份源码。
//
// 输入/输出都是 std::string，不含任何 JNI，便于在 x86 上跑通验证后再交叉编译。

#include <memory>
#include <string>
#include <vector>

namespace vtrans {

// 语言码 -> NLLB 码。未知语言原样返回（NLLB 码形如 zho_Hans）。
// 支持传入 "zh"/"zho_Hans"/"zh_CN" 等常见写法。
std::string toNllbCode(const std::string& lang);

// 从文本推断源语言 NLLB 码（脚本检测）。
// 中文与日文共用汉字，靠假名区分；拉丁脚本默认 eng_Latn。
std::string detectSourceLang(const std::string& text);

class MtEngine {
 public:
  // model_dir 下需含 model.bin / sentencepiece.bpe.model / shared_vocabulary.txt
  static std::unique_ptr<MtEngine> create(const std::string& model_dir,
                                          int threads = 4,
                                          int beam_size = 1);

  virtual ~MtEngine() = default;

  // 返回是否成功；out 为译文（已去掉语言码与 </s>）
  virtual bool translate(const std::string& text,
                         const std::string& src_lang,
                         const std::string& tgt_lang,
                         std::string& out) = 0;

  // 批量翻译（逐句独立解码，避免长句互相拖累）
  virtual bool translateBatch(const std::vector<std::string>& texts,
                              const std::string& src_lang,
                              const std::string& tgt_lang,
                              std::vector<std::string>& outs) = 0;

  virtual void setBeamSize(int beam) = 0;
  virtual void setThreads(int threads) = 0;
  virtual bool ready() const = 0;
};

}  // namespace vtrans

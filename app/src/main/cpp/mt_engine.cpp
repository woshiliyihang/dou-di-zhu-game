// NLLB-200 (CTranslate2 int8) 翻译引擎实现 —— host 与 Android 共用。
#include "mt_engine.h"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <fstream>
#include <map>
#include <mutex>
#include <stdlib.h>
#include <vector>

#include <ctranslate2/translator.h>
#include <sentencepiece_processor.h>

namespace vtrans {
namespace {

// 常见写法 -> NLLB 码
const std::map<std::string, std::string>& langTable() {
  static const std::map<std::string, std::string> k = {
      {"zh", "zho_Hans"},   {"zh_cn", "zho_Hans"}, {"zh-cn", "zho_Hans"},
      {"zh_hans", "zho_Hans"}, {"cmn", "zho_Hans"}, {"yue", "yue_Hant"},
      {"zh_tw", "zho_Hant"}, {"zh_hk", "zho_Hant"}, {"zh-hant", "zho_Hant"},
      {"en", "eng_Latn"},    {"en_us", "eng_Latn"}, {"en_gb", "eng_Latn"},
      {"ja", "jpn_Jpan"},    {"jp", "jpn_Jpan"},    {"ko", "kor_Hang"},
      {"kr", "kor_Hang"},    {"fr", "fra_Latn"},    {"de", "deu_Latn"},
      {"es", "spa_Latn"},    {"pt", "por_Latn"},    {"ru", "rus_Cyrl"},
      {"it", "ita_Latn"},    {"nl", "nld_Latn"},    {"ar", "arb_Arab"},
      {"hi", "hin_Deva"},    {"th", "tha_Thai"},    {"vi", "vie_Latn"},
      {"id", "ind_Latn"},    {"tr", "tur_Latn"},    {"pl", "pol_Latn"},
      {"uk", "ukr_Cyrl"},    {"sv", "swe_Latn"},    {"fa", "pes_Arab"},
      {"he", "heb_Hebr"},    {"ms", "zsm_Latn"},    {"tl", "tgl_Latn"},
  };
  return k;
}

std::string lower(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(),
                 [](unsigned char c) { return std::tolower(c); });
  return s;
}

// 极简 UTF-8 解码：返回下一个码点并推进 i
uint32_t nextCodepoint(const std::string& s, size_t& i) {
  const unsigned char c = static_cast<unsigned char>(s[i]);
  size_t n = 1;
  uint32_t cp = c;
  if (c >= 0xF0) { n = 4; cp = c & 0x07; }
  else if (c >= 0xE0) { n = 3; cp = c & 0x0F; }
  else if (c >= 0xC0) { n = 2; cp = c & 0x1F; }
  else if (c >= 0x80) { ++i; return 0xFFFD; }  // 孤立续字节
  if (i + n > s.size()) { ++i; return 0xFFFD; }
  for (size_t k = 1; k < n; ++k) cp = (cp << 6) | (static_cast<unsigned char>(s[i + k]) & 0x3F);
  i += n;
  return cp;
}

enum Script { kLatin = 0, kHan, kKana, kHangul, kCyrillic, kArabic, kThai, kDevanagari, kOther, kCount };

Script scriptOf(uint32_t cp) {
  if (cp >= 0xAC00 && cp <= 0xD7AF) return kHangul;   // 韩文音节
  if (cp >= 0x1100 && cp <= 0x11FF) return kHangul;   // 韩文字母
  if (cp >= 0x3040 && cp <= 0x30FF) return kKana;     // 平/片假名
  if (cp >= 0x4E00 && cp <= 0x9FFF) return kHan;      // 汉字（中日共用）
  if (cp >= 0x3400 && cp <= 0x4DBF) return kHan;
  if (cp >= 0x0400 && cp <= 0x04FF) return kCyrillic;
  if (cp >= 0x0600 && cp <= 0x06FF) return kArabic;
  if (cp >= 0x0E00 && cp <= 0x0E7F) return kThai;
  if (cp >= 0x0900 && cp <= 0x097F) return kDevanagari;
  if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') ||
      (cp >= 0x00C0 && cp <= 0x024F) || (cp >= 0x1E00 && cp <= 0x1EFF))
    return kLatin;
  return kOther;
}

}  // namespace

std::string toNllbCode(const std::string& lang) {
  const std::string key = lower(lang);
  const auto& t = langTable();
  auto it = t.find(key);
  if (it != t.end()) return it->second;
  // 已经是 NLLB 码（形如 xxx_Yyyy）就原样返回
  if (key.size() == 8 && key[3] == '_') return lang;
  return lang;
}

std::string detectSourceLang(const std::string& text) {
  int counts[kCount] = {0};
  size_t i = 0;
  while (i < text.size()) {
    const uint32_t cp = nextCodepoint(text, i);
    ++counts[scriptOf(cp)];
  }
  if (counts[kHangul] > 0) return "kor_Hang";
  if (counts[kKana] > 0) return "jpn_Jpan";        // 有假名 → 日语
  if (counts[kCyrillic] > 0) return "rus_Cyrl";
  if (counts[kArabic] > 0) return "arb_Arab";
  if (counts[kThai] > 0) return "tha_Thai";
  if (counts[kDevanagari] > 0) return "hin_Deva";
  if (counts[kHan] > 0) return "zho_Hans";         // 纯汉字 → 中文
  return "eng_Latn";                               // 拉丁/其它 → 英语
}

class MtEngineImpl : public MtEngine {
 public:
  MtEngineImpl(const std::string& model_dir, int threads, int beam)
      : model_dir_(model_dir), threads_(threads), beam_(beam) {
    const std::string sp_path = model_dir + "/sentencepiece.bpe.model";
    std::ifstream f(sp_path);
    if (!f.good()) { last_error_ = "sp model not found: " + sp_path; return; }
    f.close();

    auto st = sp_.Load(sp_path);
    if (!st.ok()) { last_error_ = "sp load failed: " + st.ToString(); return; }
    if (!buildTranslator()) return;
    ready_ = true;
  }

  bool buildTranslator() {
    try {
      ctranslate2::ReplicaPoolConfig cfg;
      cfg.num_threads_per_replica = threads_ > 0 ? threads_ : 4;
      translator_.reset(new ctranslate2::Translator(
          model_dir_, ctranslate2::Device::CPU,
          ctranslate2::ComputeType::INT8, std::vector<int>{0},
          false, cfg));
      return true;
    } catch (const std::exception& e) {
      last_error_ = std::string("ct2 init failed: ") + e.what();
      translator_.reset();
      return false;
    }
  }

  bool translate(const std::string& text, const std::string& src_lang,
                 const std::string& tgt_lang, std::string& out) override {
    std::vector<std::string> outs;
    if (!translateBatch({text}, src_lang, tgt_lang, outs) || outs.empty()) return false;
    out = outs[0];
    return true;
  }

  bool translateBatch(const std::vector<std::string>& texts,
                      const std::string& src_lang, const std::string& tgt_lang,
                      std::vector<std::string>& outs) override {
    if (!ready_ || !translator_) return false;
    if (texts.empty()) { outs.clear(); return true; }

    const std::string src = toNllbCode(src_lang);
    const std::string tgt = toNllbCode(tgt_lang);

    std::vector<std::vector<std::string>> source;
    std::vector<std::vector<std::string>> prefix;
    source.reserve(texts.size());
    for (const auto& t : texts) {
      std::vector<std::string> toks;
      toks.push_back(src);
      std::vector<std::string> pieces;
      {
        std::lock_guard<std::mutex> lk(sp_mu_);
        pieces = sp_.EncodeAsPieces(t);
      }
      toks.insert(toks.end(), pieces.begin(), pieces.end());
      toks.push_back("</s>");
      source.push_back(std::move(toks));
      prefix.push_back({tgt});
    }

    ctranslate2::TranslationOptions opt;
    opt.beam_size = beam_ > 0 ? beam_ : 1;
    opt.max_decoding_length = 256;
    opt.max_input_length = 512;
    opt.return_end_token = false;
    // 抑制重复（int8 模型在高温/长句时偶发复读）
    opt.repetition_penalty = 1.05f;
    opt.no_repeat_ngram_size = 0;

    std::vector<ctranslate2::TranslationResult> res;
    try {
      res = translator_->translate_batch(source, prefix, opt);
    } catch (const std::exception& e) {
      last_error_ = std::string("ct2 translate failed: ") + e.what();
      return false;
    }

    outs.clear();
    outs.reserve(res.size());
    for (auto& r : res) {
      if (r.hypotheses.empty()) { outs.emplace_back(); continue; }
      auto& hyp = r.hypotheses[0];
      // 去掉开头的目标语言码与结尾的 </s>
      if (!hyp.empty() && hyp[0] == tgt) hyp.erase(hyp.begin());
      while (!hyp.empty() && (hyp.back() == "</s>" || hyp.back() == "<pad>")) hyp.pop_back();
      std::lock_guard<std::mutex> lk(sp_mu_);
      std::string text_out;
      sp_.Decode(hyp, &text_out);
      outs.push_back(text_out);
    }
    return true;
  }

  void setBeamSize(int beam) override { beam_ = beam > 0 ? beam : 1; }
  void setThreads(int threads) override {
    if (threads == threads_) return;
    threads_ = threads > 0 ? threads : 4;
    if (ready_) buildTranslator();
  }
  bool ready() const override { return ready_; }
  std::string lastError() const { return last_error_; }

 private:
  std::string model_dir_;
  int threads_;
  int beam_;
  bool ready_ = false;
  std::string last_error_;
  sentencepiece::SentencePieceProcessor sp_;
  std::mutex sp_mu_;
  std::unique_ptr<ctranslate2::Translator> translator_;
};

namespace {

// LLVM libomp 的忙等控制接口。用 weak 声明：链接到 GNU libgomp（host 验证环境）时
// 这两个符号不存在，地址为 0，下面的判空调用会自动跳过。
extern "C" {
__attribute__((weak)) void kmp_set_blocktime(int);
__attribute__((weak)) void kmp_set_defaults(char const*);
}

void configureOpenMpWaitPolicy() {
  // OpenMP 默认"忙等"（spin-wait）：线程跑完一批活会空转一会儿等下一批。
  // 在手机上这既白耗电又跟音频线程抢核，实测 4 核上能把解码拖慢 3~4 倍。
  // 必须在 libomp 首次初始化之前设置才有效。
  setenv("OMP_WAIT_POLICY", "passive", 0);
  setenv("KMP_BLOCKTIME", "0", 0);
  setenv("GOMP_SPINCOUNT", "0", 0);  // GNU libgomp（host 用，需在进程启动前设才生效）
  if (kmp_set_defaults) kmp_set_defaults("KMP_BLOCKTIME=0");
  if (kmp_set_blocktime) kmp_set_blocktime(0);
}

}  // namespace

std::unique_ptr<MtEngine> MtEngine::create(const std::string& model_dir,
                                           int threads, int beam) {
  static std::once_flag kOnce;
  std::call_once(kOnce, configureOpenMpWaitPolicy);
  return std::unique_ptr<MtEngine>(new MtEngineImpl(model_dir, threads, beam));
}

}  // namespace vtrans

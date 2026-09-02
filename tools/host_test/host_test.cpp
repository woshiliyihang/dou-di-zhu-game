// host (x86) 端到端验证：直接跑 mt_engine.cpp（与 Android 同一份源码），
// 输出译文与延迟，供 tools/host_test/run.sh 与 Python 基线逐条比对。
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "mt_engine.h"

// (文本, 源语言, 目标语言)
struct Case {
  const char* text;
  const char* src;
  const char* tgt;
};

int main(int argc, char** argv) {
  if (argc < 2) {
    std::cerr << "用法: host_test <模型目录> [threads] [beam]" << std::endl;
    return 2;
  }
  const std::string model_dir = argv[1];
  const int threads = argc > 2 ? std::atoi(argv[2]) : 4;
  const int beam = argc > 3 ? std::atoi(argv[3]) : 1;

  auto t0 = std::chrono::steady_clock::now();
  auto engine = vtrans::MtEngine::create(model_dir, threads, beam);
  auto t1 = std::chrono::steady_clock::now();
  if (!engine || !engine->ready()) {
    std::cerr << "引擎初始化失败: " << model_dir << std::endl;
    return 1;
  }
  std::cout << "init_ms="
            << std::chrono::duration<double, std::milli>(t1 - t0).count()
            << " threads=" << threads << " beam=" << beam << std::endl;

  const std::vector<Case> cases = {
      {"今天天气很好，我们一起去公园散步吧。", "zho_Hans", "eng_Latn"},
      {"请问最近的地铁站在哪里？", "zho_Hans", "eng_Latn"},
      {"我们要不要先吃饭再看电影？", "zho_Hans", "eng_Latn"},
      {"The meeting has been rescheduled to three o'clock tomorrow afternoon.",
       "eng_Latn", "zho_Hans"},
      {"Could you please speak a little slower?", "eng_Latn", "zho_Hans"},
      {"Artificial intelligence is changing the way we work.", "eng_Latn",
       "zho_Hans"},
      {"これはテストです。音声翻訳の品質を確認しています。", "jpn_Jpan",
       "zho_Hans"},
      {"この製品の価格はいくらですか。", "jpn_Jpan", "eng_Latn"},
      {"Bonjour, je voudrais réserver une table pour deux personnes.",
       "fra_Latn", "eng_Latn"},
      {"그는 아침에 일찍 일어났다.", "kor_Hang", "eng_Latn"},
  };

  int failed = 0;
  for (const auto& c : cases) {
    const std::string lang = vtrans::detectSourceLang(c.text);
    std::string out;
    auto s = std::chrono::steady_clock::now();
    const bool ok = engine->translate(c.text, c.src, c.tgt, out);
    auto e = std::chrono::steady_clock::now();
    const double ms =
        std::chrono::duration<double, std::milli>(e - s).count();
    if (!ok || out.empty()) ++failed;
    std::cout << "[" << c.src << "->" << c.tgt << "] detect=" << lang
              << " " << ms << "ms\n"
              << "SRC\t" << c.text << "\n"
              << "OUT\t" << out << std::endl;
  }

  // 批量接口 + 吞吐（同一源语言才可成批，这里用前三句中文）
  std::vector<std::string> batch;
  for (int i = 0; i < 3; ++i) batch.emplace_back(cases[i].text);
  std::vector<std::string> outs;
  auto s = std::chrono::steady_clock::now();
  const bool ok = engine->translateBatch(batch, "zho_Hans", "eng_Latn", outs);
  auto e = std::chrono::steady_clock::now();
  std::cout << "batch_ok=" << ok << " n=" << outs.size() << " total_ms="
            << std::chrono::duration<double, std::milli>(e - s).count()
            << std::endl;
  for (size_t i = 0; i < outs.size(); ++i)
    std::cout << "BATCH[" << i << "]\t" << outs[i] << std::endl;

  std::cout << (failed ? "RESULT=FAIL" : "RESULT=OK")
            << " failed=" << failed << std::endl;
  return failed ? 1 : 0;
}

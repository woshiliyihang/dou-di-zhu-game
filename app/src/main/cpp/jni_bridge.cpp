// JNI 桥接层：仅 Android 编译。宿主工程用 mt_engine.h 直接调用，不经过 JNI。
#include <jni.h>

#include <string>
#include <vector>

#include "mt_engine.h"

namespace {

vtrans::MtEngine* asEngine(jlong h) {
  return reinterpret_cast<vtrans::MtEngine*>(h);
}

jstring toJString(JNIEnv* env, const std::string& s) {
  return env->NewStringUTF(s.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_vtrans_pipeline_MtEngine_nativeInit(JNIEnv* env, jclass,
                                                     jstring model_dir,
                                                     jint threads, jint beam) {
  const char* c = env->GetStringUTFChars(model_dir, nullptr);
  if (!c) return 0;
  std::string dir(c);
  env->ReleaseStringUTFChars(model_dir, c);

  auto engine = vtrans::MtEngine::create(dir, threads, beam);
  if (!engine || !engine->ready()) return 0;
  return reinterpret_cast<jlong>(engine.release());
}

JNIEXPORT jstring JNICALL
Java_com_example_vtrans_pipeline_MtEngine_nativeTranslate(JNIEnv* env, jclass,
                                                          jlong handle,
                                                          jstring text,
                                                          jstring src_lang,
                                                          jstring tgt_lang) {
  auto* e = asEngine(handle);
  if (!e) return nullptr;

  const char* t = env->GetStringUTFChars(text, nullptr);
  const char* s = env->GetStringUTFChars(src_lang, nullptr);
  const char* g = env->GetStringUTFChars(tgt_lang, nullptr);
  if (!t || !s || !g) return nullptr;

  std::string out;
  const bool ok = e->translate(t, s, g, out);
  env->ReleaseStringUTFChars(text, t);
  env->ReleaseStringUTFChars(src_lang, s);
  env->ReleaseStringUTFChars(tgt_lang, g);
  return ok ? toJString(env, out) : nullptr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_vtrans_pipeline_MtEngine_nativeTranslateBatch(
    JNIEnv* env, jclass, jlong handle, jobjectArray texts, jstring src_lang,
    jstring tgt_lang) {
  auto* e = asEngine(handle);
  if (!e || !texts) return nullptr;

  const jsize n = env->GetArrayLength(texts);
  std::vector<std::string> in;
  in.reserve(n);
  for (jsize i = 0; i < n; ++i) {
    auto jstr = reinterpret_cast<jstring>(env->GetObjectArrayElement(texts, i));
    const char* c = env->GetStringUTFChars(jstr, nullptr);
    in.emplace_back(c ? c : "");
    env->ReleaseStringUTFChars(jstr, c);
    env->DeleteLocalRef(jstr);
  }

  const char* s = env->GetStringUTFChars(src_lang, nullptr);
  const char* g = env->GetStringUTFChars(tgt_lang, nullptr);
  std::vector<std::string> outs;
  const bool ok = e->translateBatch(in, s, g, outs);
  env->ReleaseStringUTFChars(src_lang, s);
  env->ReleaseStringUTFChars(tgt_lang, g);
  if (!ok || outs.size() != in.size()) return nullptr;

  jclass str_cls = env->FindClass("java/lang/String");
  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(outs.size()),
                                         str_cls, nullptr);
  for (jsize i = 0; i < static_cast<jsize>(outs.size()); ++i) {
    jstring js = toJString(env, outs[i]);
    env->SetObjectArrayElement(arr, i, js);
    env->DeleteLocalRef(js);
  }
  return arr;
}

JNIEXPORT void JNICALL
Java_com_example_vtrans_pipeline_MtEngine_nativeSetBeam(JNIEnv*, jclass,
                                                        jlong handle, jint beam) {
  if (auto* e = asEngine(handle)) e->setBeamSize(beam);
}

JNIEXPORT void JNICALL
Java_com_example_vtrans_pipeline_MtEngine_nativeSetThreads(JNIEnv*, jclass,
                                                           jlong handle,
                                                           jint threads) {
  if (auto* e = asEngine(handle)) e->setThreads(threads);
}

JNIEXPORT void JNICALL
Java_com_example_vtrans_pipeline_MtEngine_nativeDestroy(JNIEnv*, jclass,
                                                        jlong handle) {
  delete asEngine(handle);
}

JNIEXPORT jstring JNICALL
Java_com_example_vtrans_pipeline_MtEngine_nativeDetectLang(JNIEnv* env, jclass,
                                                           jstring text) {
  const char* t = env->GetStringUTFChars(text, nullptr);
  if (!t) return nullptr;
  std::string code = vtrans::detectSourceLang(t);
  env->ReleaseStringUTFChars(text, t);
  return toJString(env, code);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }

}  // extern "C"

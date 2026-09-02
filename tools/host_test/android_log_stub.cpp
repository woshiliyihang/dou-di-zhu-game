// 仅供 arm64 静态可执行文件（qemu 验证用）链接：Android 的 liblog 只有 .so，
// 静态链接时找不到，这里用桩函数顶掉 protobuf-lite 的日志调用。
// 正常 Android 构建（libvtrans-mt.so）不会编进这个文件，走的是真正的 liblog。
#include <stdarg.h>
#include <stdio.h>

extern "C" {

int __android_log_write(int prio, const char* tag, const char* text) {
  (void)prio;
  fprintf(stderr, "[%s] %s\n", tag ? tag : "", text ? text : "");
  return 0;
}

int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
  (void)prio;
  va_list ap;
  va_start(ap, fmt);
  fprintf(stderr, "[%s] ", tag ? tag : "");
  vfprintf(stderr, fmt, ap);
  fprintf(stderr, "\n");
  va_end(ap);
  return 0;
}

int __android_log_vprint(int prio, const char* tag, const char* fmt, va_list ap) {
  (void)prio;
  fprintf(stderr, "[%s] ", tag ? tag : "");
  vfprintf(stderr, fmt, ap);
  fprintf(stderr, "\n");
  return 0;
}

// Bionic 的静态可执行文件不支持 dlopen。这里唯一会用到 dl 的是 libomp 的 OMPT
// （OpenMP Tools）初始化：只有设置了 OMP_TOOL_LIBRARIES 才会去加载工具库。
// 返回空指针 = "没挂工具库"，走的是和真机上一样的正常分支。
void* dlopen(const char*, int) { return nullptr; }
void* dlsym(void*, const char*) { return nullptr; }
int dlclose(void*) { return 0; }
char* dlerror(void) { return nullptr; }

}  // extern "C"

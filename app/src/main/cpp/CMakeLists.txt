# libvtrans-mt：NLLB 翻译引擎的 JNI 封装。
# 由 native/build_vtrans_android.sh（交叉编译）与 native/build_host.sh（x86 验证）共用。
cmake_minimum_required(VERSION 3.22)
project(vtrans_mt CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(VTRANS_PREFIX "" CACHE PATH "sentencepiece / ctranslate2 安装前缀")
option(VTRANS_BUILD_JNI "编译 JNI 桥接层（Android 用）" ON)
option(VTRANS_BUILD_HOST_TEST "编译 host 验证程序" OFF)

if(NOT VTRANS_PREFIX)
  message(FATAL_ERROR "请用 -DVTRANS_PREFIX=<prefix> 指定依赖前缀")
endif()

set(MT_SOURCES mt_engine.cpp)
if(VTRANS_BUILD_JNI)
  list(APPEND MT_SOURCES jni_bridge.cpp)
endif()

add_library(mt_engine STATIC ${MT_SOURCES})
target_include_directories(mt_engine PUBLIC
  "${CMAKE_CURRENT_SOURCE_DIR}" "${VTRANS_PREFIX}/include")
# 配合链接期裁剪丢弃未引用的代码（Android 侧刻意不裁剪，见下）
target_compile_options(mt_engine PUBLIC -ffunction-sections -fdata-sections)

find_package(OpenMP)

# libctranslate2.a 依赖的第三方静态库（ruy 被拆成很多小 .a，cpu_features 单独一个）
file(GLOB VTRANS_DEPS "${VTRANS_PREFIX}/lib/libruy_*.a"
                      "${VTRANS_PREFIX}/lib/libcpuinfo.a"
                      "${VTRANS_PREFIX}/lib/libclog.a"
                      "${VTRANS_PREFIX}/lib/libcpu_features.a")

# 注意链接顺序：libctranslate2.a 与其依赖放进同一个 group（互相引用），
# OpenMP 放在最后，否则 GOMP 符号会被丢弃。
# 注意：链接顺序很关键。这批静态库互相引用（ct2 → ruy → cpuinfo），
# 用 --start-group/--end-group 包住让 ld 反复扫描；OpenMP 放在最后，否则 GOMP 符号会被丢弃。
# （必须写成 target_link_libraries 的原始 flag 项，用 target_link_options 会被排到库前面而失效）
target_link_libraries(mt_engine PUBLIC -Wl,--start-group)
target_link_libraries(mt_engine PUBLIC
  "${VTRANS_PREFIX}/lib/libctranslate2.a"
  "${VTRANS_PREFIX}/lib/libsentencepiece.a")
target_link_libraries(mt_engine PUBLIC ${VTRANS_DEPS})
target_link_libraries(mt_engine PUBLIC -Wl,--end-group)

if(ANDROID)
  # 不能用 OpenMP::OpenMP_CXX：它会解析成 libomp.so 的绝对路径，
  # 导致后面的 -static-openmp 失效。这里直接自己写 flag，把 libomp 静态吃进来。
  target_compile_options(mt_engine PUBLIC -fopenmp)
  target_link_libraries(mt_engine PUBLIC -fopenmp -static-openmp)
  # 刻意不开 --gc-sections：CT2 的算子靠静态对象注册，裁剪有可能悄悄丢掉
  # 只有在运行期才会被查表用到的算子，宁可牺牲体积换确定性。
  target_link_libraries(mt_engine PUBLIC
    -Wl,-z,max-page-size=16384 -Wl,-z,noexecstack)
elseif(OpenMP_CXX_FOUND)
  target_link_libraries(mt_engine PUBLIC OpenMP::OpenMP_CXX)
endif()

if(VTRANS_BUILD_JNI)
  add_library(vtrans-mt SHARED jni_bridge.cpp)
  target_link_libraries(vtrans-mt PRIVATE mt_engine)
  # protobuf-lite（打进 libsentencepiece.a）的日志句柄要用 liblog
  if(ANDROID)
    target_link_libraries(vtrans-mt PRIVATE log android)
  endif()
  set_target_properties(vtrans-mt PROPERTIES
    OUTPUT_NAME "vtrans-mt"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out")
endif()

if(VTRANS_BUILD_HOST_TEST)
  # Android 下用静态链接的可执行文件 + qemu 验证 arm64 产物，
  # 此时没有 liblog.so，用桩函数顶掉 protobuf-lite 的日志调用。
  if(ANDROID)
    add_executable(host_test
      "${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/host_test/host_test.cpp"
      "${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/host_test/android_log_stub.cpp")
  else()
    add_executable(host_test
      "${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/host_test/host_test.cpp")
  endif()
  target_link_libraries(host_test PRIVATE mt_engine)
endif()

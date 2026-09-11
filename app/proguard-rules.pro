# 保留 sherpa-onnx 的 Java API（Kotlin 编译产物，JNI 反射要用）
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class kotlin.** { *; }
-keep class org.jetbrains.annotations.** { *; }

# JNI 回调类与 native 方法不能被重命名
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.example.vtrans.pipeline.MtEngine { *; }

# 出错时要能看懂栈
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

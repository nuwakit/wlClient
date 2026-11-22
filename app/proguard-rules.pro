# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.**
-dontwarn groovy.lang.GroovyShell

# ---- web3j / Gson：release 包必需的反射保留规则 ----
# 症状：统计加载报「3 个 RPC 节点都连不上」，底层其实是
# Gson: Class xxx has no default (no arg) constructor —— 混淆剥掉了
# JSON-RPC 响应 DTO 的无参构造器，三个节点健康检查全部被误判失败。
# web3j 4.12.x 的序列化走 Gson 反射，类名/构造器/字段都不能动。
-keep class org.web3j.** { *; }
-keep interface org.web3j.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.fasterxml.jackson.** { *; }

# 传输层依赖：okhttp/okio/rx 自带 consumer 规则，这里只兑静默剩余警告
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.reactivex.**
-dontwarn javax.annotation.**

# 上面整包 keep 了 org.web3j 后，R8 会保留它对三个 Android 上不存在/
# 已被 exclude 的可选依赖的引用（java.beans 属 java.desktop 模块，
# jnr-unixsocket 与 AWS KMS 在 build.gradle 里被排除），运行时走不到，静默即可
-dontwarn java.beans.**
-dontwarn jnr.unixsocket.**
-dontwarn software.amazon.awssdk.**

# 批量任务的断点状态靠 Gson 读写 SP：字段被改名后进度恢复会静默读出 null
-keep class com.lgns.task.TaskAccountState { *; }

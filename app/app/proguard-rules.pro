# Started by name through app_process (see Remote.startInjector), never referenced from code.
-keep class com.lagoon.home.Injector { public static void main(java.lang.String[]); }
# Keep stack traces readable in logcat.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

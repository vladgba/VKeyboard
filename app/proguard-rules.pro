# VKeyboard has no libraries, so these rules only squeeze the app's own code.

# Move every class into one package with short names (smaller dex and string table).
-repackageclasses ''
-allowaccessmodification

# Strip debug/info/verbose logging (and the strings built for it) from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Kotlin's leftover null checks on parameters/platform values (also off at compile time in
# build.gradle.kts). `!!` (checkNotNull) is kept so it still fails where it is written.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
}

# Uncomment to keep line numbers in crash reports (adds a little size).
#-keepattributes SourceFile,LineNumberTable
#-renamesourcefileattribute SourceFile

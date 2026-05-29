# MiDun ProGuard / R8 rules.
#
# Scope (M8): keep this file minimal — the codebase has no reflection /
# serialization / JNI consumers today, and Hilt / CameraX / MLKit / Compose
# ship their own consumer rules via AAR. Add targeted -keep entries here
# only when assembleRelease's R8 step actually complains.

# FSShell SDK placeholder. The seczure.fsudisk package does not exist yet
# (M10 will add the real .jar/.so); this rule is a forward-looking guard
# so we don't forget. R8 silently ignores keeps on non-existent classes.
-keep class seczure.fsudisk.** { *; }

# Strip verbose / info / debug logging from release builds. The repo
# currently has zero Log calls, so this is purely forward-looking: future
# diagnostic logging at d/v/i levels will be removed from release APKs.
# WARNING: any future production telemetry must use Log.w or Log.e (or a
# dedicated logging facade) to survive R8.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
# ShizukuHelper reaches Shizuku.newProcess by reflection because it is private
# in the 13.x API. Without this rule R8 renames it in release builds and shell
# execution fails at runtime while still passing every build.
-keepclassmembers class rikka.shizuku.Shizuku {
    static java.lang.Process newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

# Keep ShellBridgeServer entry point for app_process execution via Shizuku/Root
-keep class com.dbl.arabiclauncher.ShellBridgeServer {
    public static void main(java.lang.String[]);
    public static *;
}
-keep class com.dbl.arabiclauncher.LauncherConfig { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

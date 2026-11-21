package com.dbl.arabiclauncher;

import android.os.Build;
import android.os.Process;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * خادم الجسر المحلي (يعمل بصلاحية Shizuku أو Root عبر app_process، كما تُستخدم دواله مباشرة
 * في وضع الوصول المباشر على أندرويد 10 وما قبله).
 *
 * مسؤول عن:
 * - تحديد مسار /Android/data/com.bandainamcoent.dblegends_ww/files على مختلف إصدارات أندرويد.
 * - إيقاف اللعبة بأمان قبل التعديل (am force-stop).
 * - النسخ والاستبدال الذري مع أخذ نسخة احتياطية أصلية تلقائية لكل ملف.
 * - استعادة الملفات الأصلية بدقة البايت وحذف أي ملفات مضافة.
 */
public class ShellBridgeServer {

    private static final Object MUTATE_LOCK = new Object();
    private static volatile boolean shellStorageGranted = false;
    private static volatile String expectedAuthToken = "";

    public interface ProgressListener {
        void onProgress(int percent, String message);
    }

    public static final class BackupEntry {
        public final String relPath;
        public final long patchedSize;
        public final boolean hadOriginal;

        public BackupEntry(String relPath, long patchedSize, boolean hadOriginal) {
            this.relPath = relPath;
            this.patchedSize = patchedSize;
            this.hadOriginal = hadOriginal;
        }
    }

    public static final class DuplicateEntry {
        public final String absPath;
        public final long patchedSize;
        public final String backupName;

        public DuplicateEntry(String absPath, long patchedSize, String backupName) {
            this.absPath = absPath;
            this.patchedSize = patchedSize;
            this.backupName = backupName;
        }
    }

    public static final class BackupManifest {
        public String patchVersion = "";
        public String targetSubpath = "";
        public long installedAt = 0L;
        public final List<BackupEntry> entries = new ArrayList<>();
        public final Map<String, BackupEntry> byPath = new HashMap<>();
        public final List<DuplicateEntry> duplicates = new ArrayList<>();
        public final Map<String, DuplicateEntry> dupByAbsPath = new HashMap<>();
    }

    public static void main(String[] args) {
        if (args != null && args.length > 0 && args[0] != null) {
            expectedAuthToken = args[0].trim();
        }
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), LauncherConfig.BRIDGE_PORT), 12);

            new Thread(ShellBridgeServer::grantShellStorageAccess).start();
            System.out.println("DBL_ARABIC_BRIDGE_READY:" + LauncherConfig.BRIDGE_PORT);

            while (true) {
                Socket client = server.accept();
                new Thread(() -> handleClient(client)).start();
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    public static void grantShellStorageAccess() {
        if (shellStorageGranted) {
            return;
        }
        shellStorageGranted = true;
        try {
            Runtime.getRuntime().exec(new String[]{
                    "sh", "-c",
                    "appops set com.android.shell MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1; " +
                            "appops set --uid 2000 MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1"
            }).waitFor();
        } catch (Throwable ignored) {
        }
    }

    public static void stopGameSafely() {
        for (String pkg : LauncherConfig.GAME_PACKAGES) {
            try {
                Runtime.getRuntime().exec(new String[]{"am", "force-stop", pkg}).waitFor();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean canReadDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        try {
            return dir.list() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * يحل مسار مجلد files/ الخاص بلعبة Dragon Ball Legends مع دعم مسارات أندرويد 11-15 المتعددة.
     */
    public static File resolveGameFilesDir(File hintDir) {
        String userId = "0";
        String hintBase = null;

        if (hintDir != null) {
            String normalized = hintDir.getAbsolutePath().replace('\\', '/');
            int idx = normalized.indexOf("/Android/data/");
            if (idx > 0) {
                hintBase = normalized.substring(0, idx);
                int lastSlash = hintBase.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash + 1 < hintBase.length()) {
                    String maybeUid = hintBase.substring(lastSlash + 1);
                    if (maybeUid.matches("\\d+")) {
                        userId = maybeUid;
                    }
                }
            }
        }

        List<String> storageRoots = new ArrayList<>();
        storageRoots.add("/mnt/pass_through/" + userId + "/emulated/" + userId);
        storageRoots.add("/mnt/user/" + userId + "/emulated/" + userId);
        if (hintBase != null) {
            storageRoots.add(hintBase);
        }
        storageRoots.add("/storage/emulated/" + userId);
        if (!"0".equals(userId)) {
            storageRoots.add("/storage/emulated/0");
        }
        storageRoots.add("/sdcard");
        storageRoots.add("/mnt/sdcard");

        // 1) أولوية قصوى للمجلد الذي يحتوي مجلد أصول DBL الحقيقي (streamingassetbundles أو as) وقابل للقراءة
        for (String root : storageRoots) {
            for (String pkg : LauncherConfig.GAME_PACKAGES) {
                File f = new File(root + "/Android/data/" + pkg + "/files");
                if ((new File(f, LauncherConfig.DEFAULT_BUNDLES_SUBDIR).isDirectory()
                        || new File(f, LauncherConfig.CATALOG_SUBDIR).isDirectory())
                        && canReadDir(f)) {
                    return f;
                }
            }
        }
        // 2) أولوية ثانية للمجلد الذي يمكن قراءته ويحتوي ملفات داخلية
        for (String root : storageRoots) {
            for (String pkg : LauncherConfig.GAME_PACKAGES) {
                File f = new File(root + "/Android/data/" + pkg + "/files");
                if (canReadDir(f)) {
                    String[] list = f.list();
                    if (list != null && list.length > 0) {
                        return f;
                    }
                }
            }
        }
        // 2) أي مجلد files قابل للقراءة
        for (String root : storageRoots) {
            for (String pkg : LauncherConfig.GAME_PACKAGES) {
                File f = new File(root + "/Android/data/" + pkg + "/files");
                if (canReadDir(f)) {
                    return f;
                }
            }
        }
        // 3) أي مجلد files موجود فعلياً
        for (String root : storageRoots) {
            for (String pkg : LauncherConfig.GAME_PACKAGES) {
                File f = new File(root + "/Android/data/" + pkg + "/files");
                if (f.isDirectory()) {
                    return f;
                }
            }
        }

        String fallbackRoot = hintBase != null ? hintBase : "/storage/emulated/0";
        return new File(fallbackRoot + "/Android/data/" + LauncherConfig.GAME_PACKAGE + "/files");
    }

    public static boolean canDirectReadWriteGameDir(File hintDir) {
        try {
            int myUid = Process.myUid();
            // على أندرويد 11+ التطبيق العادي لا يملك وصولاً مباشراً لـ Android/data
            if (Build.VERSION.SDK_INT >= 30 && (myUid % 100000) >= 10000) {
                return false;
            }
            File filesDir = resolveGameFilesDir(hintDir);
            if (!filesDir.isDirectory()) {
                return false;
            }
            File probe = new File(filesDir, ".dbl_rw_probe");
            try (FileOutputStream fos = new FileOutputStream(probe)) {
                fos.write(1);
                fos.flush();
            }
            boolean ok = probe.isFile() && probe.length() == 1;
            probe.delete();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

            String line = reader.readLine();
            if (line == null) {
                return;
            }
            String[] parts = line.trim().split("\\t", -1);
            if (parts.length < 2) {
                writer.println("ERR|BAD_PROTOCOL");
                return;
            }

            String token = parts[0];
            if (!expectedAuthToken.isEmpty() && !expectedAuthToken.equals(token)) {
                writer.println("ERR|UNAUTHORIZED");
                return;
            }

            String cmd = parts[1];
            if ("PING".equals(cmd)) {
                writer.println("OK|PONG|uid=" + Process.myUid()
                        + "|ver=" + LauncherConfig.APP_VERSION
                        + "|rev=" + LauncherConfig.BRIDGE_REV);
            } else if ("STATUS".equals(cmd)) {
                File hint = (parts.length > 2 && !parts[2].isEmpty()) ? new File(parts[2]) : null;
                writer.println(getStatus(hint));
            } else if ("APPLY_INSTALL".equals(cmd)) {
                if (parts.length < 4) {
                    writer.println("ERR|BAD_ARGS");
                    return;
                }
                File stageDir = new File(parts[2]);
                String patchVer = parts[3];
                String subpath = parts.length > 4 ? parts[4] : "";
                synchronized (MUTATE_LOCK) {
                    stopGameSafely();
                    String result = applyInstall(stageDir, patchVer, subpath,
                            (pct, msg) -> writer.println("PROG|" + pct + "|" + msg));
                    writer.println(result);
                }
            } else if ("APPLY_RESTORE".equals(cmd)) {
                File hint = (parts.length > 2 && !parts[2].isEmpty()) ? new File(parts[2]) : null;
                synchronized (MUTATE_LOCK) {
                    stopGameSafely();
                    String result = applyRestore(hint,
                            (pct, msg) -> writer.println("PROG|" + pct + "|" + msg));
                    writer.println(result);
                }
            } else if ("SCAN_DIAG".equals(cmd)) {
                File hint = (parts.length > 2 && !parts[2].isEmpty()) ? new File(parts[2]) : null;
                writer.println(runDiagnosticScan(hint));
            } else if ("STOP".equals(cmd)) {
                writer.println("OK|BYE");
                System.exit(0);
            } else {
                writer.println("ERR|UNKNOWN_CMD:" + cmd);
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
        }
    }

    private static boolean isGameInitialized(File filesDir) {
        if (filesDir == null || !filesDir.isDirectory()) {
            return false;
        }
        if (LauncherConfig.FIRST_LAUNCH_ANY_OF_PATHS.length == 0) {
            String[] children = filesDir.list();
            return children != null && children.length > 0;
        }
        for (String rel : LauncherConfig.FIRST_LAUNCH_ANY_OF_PATHS) {
            String norm = LauncherConfig.normalizeSubpath(rel);
            if (!norm.isEmpty() && new File(filesDir, norm).exists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * يحدد المسار النسبي النهائي للملف داخل files/:
     * - إذا كان الملف داخل مجلد في الـ ZIP (مثل streamingassetbundles/... أو as/...) يحافظ عليه.
     * - إذا كان الملف في جذر الـ ZIP مباشرة وهو هاش حزمة من 40 خانة (بدون مجلد)، يوجّهه تلقائياً
     *   إلى streamingassetbundles/ (أو as/ إذا كان هو فهرس AssetBundleCatalog أو موجوداً في as/).
     */
    private static String resolveTargetRelativePath(File filesDir, String normSubpath, String relInStage) {
        if (!normSubpath.isEmpty()) {
            return normSubpath + "/" + relInStage;
        }
        if (!relInStage.contains("/") && LauncherConfig.isSha1BundleFilename(relInStage)) {
            if (LauncherConfig.CATALOG_BUNDLE_HASH.equalsIgnoreCase(relInStage)
                    || new File(filesDir, LauncherConfig.CATALOG_SUBDIR + "/" + relInStage).isFile()) {
                return LauncherConfig.CATALOG_SUBDIR + "/" + relInStage;
            }
            return LauncherConfig.DEFAULT_BUNDLES_SUBDIR + "/" + relInStage;
        }
        return relInStage;
    }

    /**
     * يعيد حالة اللعبة والتعريب بصيغة:
     * OK|<gameReady>|<patchInstalled>|<installedVersion>|<fileCount>
     */
    public static String getStatus(File hintDir) {
        try {
            File filesDir = resolveGameFilesDir(hintDir);
            boolean gameReady = isGameInitialized(filesDir);
            if (!gameReady) {
                return "OK|false|false||0";
            }

            File manifestFile = new File(new File(filesDir, LauncherConfig.BACKUP_DIR_NAME), LauncherConfig.BACKUP_MANIFEST_FILE);
            if (!manifestFile.isFile()) {
                return "OK|true|false||0";
            }

            BackupManifest manifest = parseManifest(readText(manifestFile));
            if (manifest.entries.isEmpty()) {
                return "OK|true|false||0";
            }

            boolean allMatch = true;
            for (BackupEntry entry : manifest.entries) {
                File target = new File(filesDir, entry.relPath);
                if (!target.isFile() || target.length() != entry.patchedSize) {
                    allMatch = false;
                    break;
                }
            }
            return "OK|true|" + allMatch + "|" + manifest.patchVersion + "|" + manifest.entries.size();
        } catch (Throwable t) {
            return "ERR|" + t.getMessage();
        }
    }

    /**
     * يثبّت ملفات التعريب الموجودة في stagingDir داخل مجلد اللعبة مع النسخ الاحتياطي التلقائي.
     */
    public static String applyInstall(
            File stagingDir,
            String patchVersion,
            String targetSubpath,
            ProgressListener listener
    ) {
        try {
            if (listener != null) {
                listener.onProgress(60, "جارٍ فحص مجلد لعبة Dragon Ball Legends...");
            }
            grantShellStorageAccess();

            File filesDir = resolveGameFilesDir(stagingDir);
            if (!isGameInitialized(filesDir)) {
                return "ERR|NEEDS_FIRST_LAUNCH";
            }

            if (stagingDir == null || !stagingDir.isDirectory()) {
                return "ERR|MISSING_STAGE_DIR";
            }

            List<File> stagedFiles = new ArrayList<>();
            collectFilesRecursively(stagingDir, stagedFiles);
            if (stagedFiles.isEmpty()) {
                return "ERR|EMPTY_STAGE";
            }

            Collections.sort(stagedFiles, (a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));

            stopGameSafely();

            String normSubpath = LauncherConfig.normalizeSubpath(targetSubpath);
            String stageBase = stagingDir.getCanonicalPath() + File.separator;

            File backupDir = new File(filesDir, LauncherConfig.BACKUP_DIR_NAME);
            File origDir = new File(backupDir, "orig");
            File manifestFile = new File(backupDir, LauncherConfig.BACKUP_MANIFEST_FILE);
            if (!origDir.exists()) {
                origDir.mkdirs();
            }

            // قراءة الفهرس السابق إن وُجد للتعامل السليم مع التحديثات المتكررة
            BackupManifest prevManifest = manifestFile.isFile()
                    ? parseManifest(readText(manifestFile))
                    : new BackupManifest();

            // حساب المسارات النسبية للملفات الجديدة
            Set<String> newRelPaths = new HashSet<>();
            List<String> orderedRelPaths = new ArrayList<>(stagedFiles.size());
            for (File f : stagedFiles) {
                String canonical = f.getCanonicalPath();
                if (!canonical.startsWith(stageBase)) {
                    throw new SecurityException("ملف خارج مجلد التجهيز: " + canonical);
                }
                String rel = canonical.substring(stageBase.length()).replace('\\', '/');
                String fullRel = resolveTargetRelativePath(filesDir, normSubpath, rel);
                newRelPaths.add(fullRel);
                orderedRelPaths.add(fullRel);
            }

            // إذا كان هناك ملفات من إصدار تعريب قديم ولم تعد موجودة في الإصدار الجديد، نستعيد أصلها أولاً
            for (BackupEntry oldEntry : prevManifest.entries) {
                if (!newRelPaths.contains(oldEntry.relPath)) {
                    File oldTarget = new File(filesDir, oldEntry.relPath);
                    File oldBackup = new File(origDir, oldEntry.relPath);
                    if (oldEntry.hadOriginal && oldBackup.isFile()) {
                        copyFileAtomically(oldBackup, oldTarget);
                        oldBackup.delete();
                    } else if (!oldEntry.hadOriginal && oldTarget.isFile()) {
                        oldTarget.delete();
                    }
                }
            }

            BackupManifest nextManifest = new BackupManifest();
            nextManifest.patchVersion = (patchVersion != null && !patchVersion.isEmpty()) ? patchVersion : "1.0.0";
            nextManifest.targetSubpath = normSubpath;
            nextManifest.installedAt = System.currentTimeMillis();

            int total = stagedFiles.size();
            for (int i = 0; i < total; i++) {
                File src = stagedFiles.get(i);
                String relFromFiles = orderedRelPaths.get(i);
                File targetFile = new File(filesDir, relFromFiles);
                File backupFile = new File(origDir, relFromFiles);

                BackupEntry prev = prevManifest.byPath.get(relFromFiles);
                boolean hadOriginal;

                if (prev != null) {
                    if (prev.hadOriginal && backupFile.isFile()) {
                        // إذا قامت اللعبة بتحديث الملف الأصلي بحجم جديد مختلف، نحدث النسخة الاحتياطية منه
                        if (targetFile.isFile()
                                && targetFile.length() != prev.patchedSize
                                && targetFile.length() != backupFile.length()) {
                            copyFileAtomically(targetFile, backupFile);
                        }
                        hadOriginal = true;
                    } else if (!prev.hadOriginal) {
                        if (targetFile.isFile() && targetFile.length() != prev.patchedSize) {
                            copyFileAtomically(targetFile, backupFile);
                            hadOriginal = true;
                        } else {
                            hadOriginal = false;
                        }
                    } else {
                        hadOriginal = targetFile.isFile();
                        if (hadOriginal) {
                            copyFileAtomically(targetFile, backupFile);
                        }
                    }
                } else {
                    if (targetFile.isFile()) {
                        if (!backupFile.isFile()) {
                            copyFileAtomically(targetFile, backupFile);
                        }
                        hadOriginal = true;
                    } else {
                        hadOriginal = false;
                    }
                }

            // نسخ ملف التعريب الجديد بشكل ذري وآمن
                copyFileAtomically(src, targetFile);

                BackupEntry newEntry = new BackupEntry(relFromFiles, targetFile.length(), hadOriginal);
                nextManifest.entries.add(newEntry);
                nextManifest.byPath.put(relFromFiles, newEntry);

                // البحث التلقائي عن أي نسخ مكررة لنفس اسم الملف في cache / UnityCache / as / streamingassetbundles / المسار الداخلي واستبدالها جميعاً
                if (LauncherConfig.isSha1BundleFilename(src.getName())) {
                    Set<String> exclude = new HashSet<>();
                    try {
                        exclude.add(targetFile.getCanonicalPath());
                    } catch (Throwable ignored) {
                        exclude.add(targetFile.getAbsolutePath());
                    }
                    File origDupsDir = new File(backupDir, "orig_dups");
                    List<File> duplicates = findDuplicateCopies(filesDir, src.getName(), exclude);
                    for (File dupFile : duplicates) {
                        String dupAbs = dupFile.getAbsolutePath();
                        DuplicateEntry prevDup = prevManifest.dupByAbsPath.get(dupAbs);
                        String bakName = (prevDup != null && !prevDup.backupName.isEmpty())
                                ? prevDup.backupName
                                : ("dup_" + Math.abs(dupAbs.hashCode()) + "_" + src.getName());
                        File dupBackup = new File(origDupsDir, bakName);
                        if (!dupBackup.isFile()) {
                            copyFileAtomically(dupFile, dupBackup);
                        }
                        copyFileAtomically(src, dupFile);
                        DuplicateEntry nextDup = new DuplicateEntry(dupAbs, dupFile.length(), bakName);
                        nextManifest.duplicates.add(nextDup);
                        nextManifest.dupByAbsPath.put(dupAbs, nextDup);
                    }
                }

                if (listener != null && (i % 2 == 0 || i == total - 1)) {
                    int pct = 64 + ((i + 1) * 32) / total;
                    listener.onProgress(pct, "جارٍ تثبيت ملفات التعريب والنسخ الاحتياطي (" + (i + 1) + "/" + total + ")...");
                }
            }

            if (listener != null) {
                listener.onProgress(98, "جارٍ حفظ سجل النسخ الاحتياطي والتثبيت...");
            }
            writeTextAtomically(manifestFile, serializeManifest(nextManifest));

            return "OK|INSTALLED|" + nextManifest.patchVersion + "|" + (total + nextManifest.duplicates.size());
        } catch (Throwable t) {
            return "ERR|" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * يستعيد جميع الملفات الأصلية من النسخة الاحتياطية ويحذف الملفات المضافة ومجلد النسخ الاحتياطي.
     */
    public static String applyRestore(File hintDir, ProgressListener listener) {
        try {
            stopGameSafely();
            File filesDir = resolveGameFilesDir(hintDir);
            File backupDir = new File(filesDir, LauncherConfig.BACKUP_DIR_NAME);
            if (!backupDir.isDirectory()) {
                return "OK|RESTORED|0";
            }

            File origDir = new File(backupDir, "orig");
            File manifestFile = new File(backupDir, LauncherConfig.BACKUP_MANIFEST_FILE);
            int restoredCount = 0;

            if (manifestFile.isFile()) {
                BackupManifest manifest = parseManifest(readText(manifestFile));
                int total = Math.max(1, manifest.entries.size());
                for (int i = 0; i < manifest.entries.size(); i++) {
                    BackupEntry entry = manifest.entries.get(i);
                    File target = new File(filesDir, entry.relPath);
                    File backup = new File(origDir, entry.relPath);

                    if (entry.hadOriginal && backup.isFile()) {
                        copyFileAtomically(backup, target);
                        restoredCount++;
                    } else if (!entry.hadOriginal && target.isFile()) {
                        target.delete();
                        restoredCount++;
                    }

                    if (listener != null && (i % 2 == 0 || i == manifest.entries.size() - 1)) {
                        int pct = 30 + ((i + 1) * 65) / total;
                        listener.onProgress(pct, "جارٍ استعادة الملفات الأصلية (" + (i + 1) + "/" + total + ")...");
                    }
                }

                // استعادة أي نسخ مكررة تم استبدالها في cache أو UnityCache أو المسار الداخلي
                File origDupsDir = new File(backupDir, "orig_dups");
                for (DuplicateEntry dup : manifest.duplicates) {
                    File dupBackup = new File(origDupsDir, dup.backupName);
                    File dupTarget = new File(dup.absPath);
                    if (dupBackup.isFile()) {
                        copyFileAtomically(dupBackup, dupTarget);
                        restoredCount++;
                    }
                }
            }

            deleteRecursive(backupDir);
            return "OK|RESTORED|" + restoredCount;
        } catch (Throwable t) {
            return "ERR|" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    // =========================================================================
    // دوال مساعدة لإدارة الفهرس والملفات
    // =========================================================================

    public static BackupManifest parseManifest(String text) {
        BackupManifest m = new BackupManifest();
        if (text == null || text.isEmpty()) {
            return m;
        }
        for (String rawLine : text.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("patch_version=")) {
                m.patchVersion = line.substring("patch_version=".length()).trim();
            } else if (line.startsWith("target_subpath=")) {
                m.targetSubpath = line.substring("target_subpath=".length()).trim();
            } else if (line.startsWith("installed_at=")) {
                try {
                    m.installedAt = Long.parseLong(line.substring("installed_at=".length()).trim());
                } catch (Throwable ignored) {
                }
            } else if (line.startsWith("entry=")) {
                String payload = line.substring("entry=".length());
                String[] parts = payload.split("\\|");
                if (parts.length >= 3) {
                    try {
                        String rel = parts[0];
                        long size = Long.parseLong(parts[1]);
                        boolean hadOrig = Boolean.parseBoolean(parts[2]);
                        BackupEntry entry = new BackupEntry(rel, size, hadOrig);
                        m.entries.add(entry);
                        m.byPath.put(rel, entry);
                    } catch (Throwable ignored) {
                    }
                }
            } else if (line.startsWith("dup=")) {
                String payload = line.substring("dup=".length());
                String[] parts = payload.split("\\|");
                if (parts.length >= 3) {
                    try {
                        String abs = parts[0];
                        long size = Long.parseLong(parts[1]);
                        String bakName = parts[2];
                        DuplicateEntry dup = new DuplicateEntry(abs, size, bakName);
                        m.duplicates.add(dup);
                        m.dupByAbsPath.put(abs, dup);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return m;
    }

    public static String serializeManifest(BackupManifest m) {
        StringBuilder sb = new StringBuilder();
        sb.append("patch_version=").append(m.patchVersion).append('\n');
        sb.append("target_subpath=").append(m.targetSubpath).append('\n');
        sb.append("installed_at=").append(m.installedAt).append('\n');
        for (BackupEntry e : m.entries) {
            sb.append("entry=")
                    .append(e.relPath).append('|')
                    .append(e.patchedSize).append('|')
                    .append(e.hadOriginal).append('\n');
        }
        for (DuplicateEntry d : m.duplicates) {
            sb.append("dup=")
                    .append(d.absPath).append('|')
                    .append(d.patchedSize).append('|')
                    .append(d.backupName).append('\n');
        }
        return sb.toString();
    }

    // =========================================================================
    // البحث عن النسخ المكررة والتشخيص السريع عبر Shizuku / Root
    // =========================================================================

    private static List<File> getGameSearchRoots(File filesDir) {
        List<File> roots = new ArrayList<>();
        if (filesDir != null) {
            File baseDir = filesDir.getParentFile();
            if (baseDir != null && baseDir.isDirectory()) {
                roots.add(filesDir);
                File cacheDir = new File(baseDir, "cache");
                if (cacheDir.isDirectory()) {
                    roots.add(cacheDir);
                }
            } else {
                roots.add(filesDir);
            }
        }
        // المسار الداخلي /data/user/0/<pkg> لا يُقرأ إلا بصلاحية Root (UID 0)
        if (Process.myUid() == 0) {
            for (String pkg : LauncherConfig.GAME_PACKAGES) {
                File internal = new File("/data/user/0/" + pkg);
                if (canReadDir(internal)) {
                    roots.add(internal);
                }
            }
        }
        return roots;
    }

    public static List<File> findDuplicateCopies(File filesDir, String targetName, Set<String> excludeCanonical) {
        List<File> found = new ArrayList<>();
        for (File root : getGameSearchRoots(filesDir)) {
            scanForExactFilename(root, targetName, excludeCanonical, found, 0);
        }
        return found;
    }

    private static void scanForExactFilename(File dir, String targetName, Set<String> excludeCanonical, List<File> out, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 6) {
            return;
        }
        if (LauncherConfig.BACKUP_DIR_NAME.equals(dir.getName())) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                scanForExactFilename(child, targetName, excludeCanonical, out, depth + 1);
            } else if (child.isFile() && child.getName().equalsIgnoreCase(targetName)) {
                String canon;
                try {
                    canon = child.getCanonicalPath();
                } catch (Throwable t) {
                    canon = child.getAbsolutePath();
                }
                if (!excludeCanonical.contains(canon)) {
                    excludeCanonical.add(canon);
                    out.add(child);
                }
            }
        }
    }

    /**
     * فحص تشخيصي سريع يعرض جميع مجلدات اللعبة وكل النسخ الموجودة لملف اللغة والفهرس وكاش UnityCache.
     */
    public static String runDiagnosticScan(File hintDir) {
        try {
            grantShellStorageAccess();
            File filesDir = resolveGameFilesDir(hintDir);
            File baseDir = filesDir.getParentFile();
            StringBuilder sb = new StringBuilder();

            sb.append("UID=").append(Process.myUid())
                    .append(Process.myUid() == 0 ? " (Root)" : (Process.myUid() == 2000 ? " (Shizuku Shell)" : " (App)"))
                    .append('\n');
            sb.append("filesDir=").append(filesDir.getAbsolutePath()).append('\n');

            if (baseDir != null && baseDir.isDirectory()) {
                sb.append("\n[مجلدات Android/data/").append(baseDir.getName()).append("]\n");
                File[] top = baseDir.listFiles();
                if (top != null) {
                    for (File f : top) {
                        sb.append(" • ").append(f.getName());
                        if (f.isDirectory()) {
                            String[] c = f.list();
                            sb.append("/ (").append(c != null ? c.length : -1).append(" عنصر)");
                        } else {
                            sb.append(" (").append(f.length()).append(" B)");
                        }
                        sb.append('\n');
                    }
                }
            }

            sb.append("\n[المجلدات الفرعية داخل files/]\n");
            File[] fChildren = filesDir.listFiles();
            if (fChildren != null) {
                for (File f : fChildren) {
                    if (f.isDirectory()) {
                        String[] c = f.list();
                        sb.append(" • files/").append(f.getName()).append("/ (").append(c != null ? c.length : -1).append(" عنصر)\n");
                    }
                }
            }

            sb.append("\n[نسخ ملف اللغة 5efd73ed...]\n");
            List<File> langCopies = findDuplicateCopies(filesDir, LauncherConfig.LANG_ENG_BUNDLE_HASH, new HashSet<>());
            if (langCopies.isEmpty()) {
                sb.append(" • لم يُعثر على أي نسخة!\n");
            } else {
                for (File f : langCopies) {
                    sb.append(" • ").append(f.getAbsolutePath()).append(" (").append(f.length()).append(" B)\n");
                }
            }

            sb.append("\n[نسخ فهرس الكتالوج 88856e7c...]\n");
            List<File> catCopies = findDuplicateCopies(filesDir, LauncherConfig.CATALOG_BUNDLE_HASH, new HashSet<>());
            if (catCopies.isEmpty()) {
                sb.append(" • لم يُعثر على أي نسخة!\n");
            } else {
                for (File f : catCopies) {
                    sb.append(" • ").append(f.getAbsolutePath()).append(" (").append(f.length()).append(" B)\n");
                }
            }

            sb.append("\n[فحص UnityCache و cache/]\n");
            List<String> cacheFindings = new ArrayList<>();
            if (baseDir != null) {
                scanCacheIndicators(new File(baseDir, "cache"), cacheFindings, 0);
                scanCacheIndicators(new File(filesDir, "UnityCache"), cacheFindings, 0);
            }
            if (cacheFindings.isEmpty()) {
                sb.append(" • لا توجد ملفات UnityCache / __data بحجم ~1.4 م.ب\n");
            } else {
                for (String s : cacheFindings) {
                    sb.append(" • ").append(s).append('\n');
                }
            }

            File internalDir = new File("/data/user/0/" + LauncherConfig.GAME_PACKAGE);
            sb.append("\n[المسار الداخلي /data/user/0/]\n");
            if (canReadDir(internalDir)) {
                sb.append(" • متاح للقراءة (Root)\n");
            } else {
                sb.append(" • محمي بواسطة أندرويد (يحتاج Root — Shizuku UID 2000 لا يصل إليه)\n");
            }

            File manifestFile = new File(new File(filesDir, LauncherConfig.BACKUP_DIR_NAME), LauncherConfig.BACKUP_MANIFEST_FILE);
            if (manifestFile.isFile()) {
                sb.append("\n[سجل النسخ الاحتياطي manifest.txt]\n");
                sb.append(readText(manifestFile).trim()).append('\n');
            }

            // فحص محتويات hc و Scenario و Unity و cache
            sb.append("\n[محتويات hc / Scenario / Unity / cache]\n");
            String[] extraDirs = {"hc", "Scenario", "Unity"};
            for (String d : extraDirs) {
                File sub = new File(filesDir, d);
                if (sub.isDirectory()) {
                    File[] list = sub.listFiles();
                    if (list != null) {
                        for (File item : list) {
                            sb.append(" • files/").append(d).append('/').append(item.getName())
                                    .append(item.isDirectory() ? "/" : (" (" + item.length() + " B)")).append('\n');
                        }
                    }
                }
            }
            if (baseDir != null) {
                File cDir = new File(baseDir, "cache");
                if (cDir.isDirectory()) {
                    File[] list = cDir.listFiles();
                    if (list != null) {
                        for (File item : list) {
                            sb.append(" • cache/").append(item.getName())
                                    .append(item.isDirectory() ? "/" : (" (" + item.length() + " B)")).append('\n');
                        }
                    }
                }
            }

            // البحث عن أي ملفات أخرى في streamingassetbundles بحجم مشابه لملف اللغة (1.35 - 1.65 م.ب)
            sb.append("\n[ملفات streamingassetbundles بحجم 1.35 - 1.65 م.ب]\n");
            File sabDir = new File(filesDir, LauncherConfig.DEFAULT_BUNDLES_SUBDIR);
            if (sabDir.isDirectory()) {
                File[] sabFiles = sabDir.listFiles();
                int nearCount = 0;
                if (sabFiles != null) {
                    for (File sf : sabFiles) {
                        if (sf.isFile()) {
                            long l = sf.length();
                            if (l >= 1350000L && l <= 1650000L) {
                                sb.append(" • ").append(sf.getName()).append(" (").append(l).append(" B)\n");
                                nearCount++;
                                if (nearCount >= 20) {
                                    break;
                                }
                            }
                        }
                    }
                }
                if (nearCount == 0) {
                    sb.append(" • لا يوجد\n");
                }
            }

            // فحص مسارات APK اللعبة (Split APKs / Asset Packs)
            sb.append("\n[مسارات APK اللعبة]\n");
            String pmOut = runShellCommand("pm path " + LauncherConfig.GAME_PACKAGE, 15);
            sb.append(pmOut.isEmpty() ? " • غير متاح\n" : pmOut);

            // قراءة آخر أسطر logcat الخاصة بـ Unity (يكشف فوراً أي خطأ CRC أو مسار التحميل الفعلي)
            sb.append("\n[آخر سجلات Unity في logcat]\n");
            String logOut = runShellCommand("logcat -d -s Unity:V | tail -n 35", 35);
            sb.append(logOut.isEmpty() ? " • لا توجد سجلات Unity حالياً (افتح اللعبة ثم ارجع للفحص)\n" : logOut);

            String escaped = sb.toString().replace("\r", "").replace("\n", "\\n");
            return "OK|" + escaped;
        } catch (Throwable t) {
            return "ERR|" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static String runShellCommand(String cmd, int maxLines) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                while ((line = br.readLine()) != null && count < maxLines) {
                    out.append(" • ").append(line).append('\n');
                    count++;
                }
            }
            p.waitFor();
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    private static void scanCacheIndicators(File dir, List<String> out, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 5 || out.size() >= 25) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (out.size() >= 25) {
                return;
            }
            if (child.isDirectory()) {
                String n = child.getName();
                if (n.equalsIgnoreCase("UnityCache") || n.equalsIgnoreCase("Shared") || n.contains("740e8431")) {
                    out.add("DIR: " + child.getAbsolutePath());
                }
                scanCacheIndicators(child, out, depth + 1);
            } else if (child.isFile()) {
                long len = child.length();
                if ("__data".equals(child.getName()) || (len >= 1350000L && len <= 1600000L)) {
                    out.add("FILE: " + child.getAbsolutePath() + " (" + len + " B)");
                }
            }
        }
    }

    public static void collectFilesRecursively(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectFilesRecursively(child, out);
            } else if (child.isFile()) {
                out.add(child);
            }
        }
    }

    public static void copyFileAtomically(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(parent, dst.getName() + ".dbl_tmp");
        if (tmp.exists()) {
            tmp.delete();
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
            out.flush();
            out.getFD().sync();
        }
        try {
            tmp.setReadable(true, false);
            tmp.setWritable(true, false);
        } catch (Throwable ignored) {
        }
        if (dst.exists()) {
            try {
                dst.setWritable(true, false);
            } catch (Throwable ignored) {
            }
            dst.delete();
        }
        if (!tmp.renameTo(dst)) {
            // fallback للكتابة المباشرة إذا لم يدعم نظام الملفات rename
            try (FileInputStream in = new FileInputStream(tmp);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int read;
                while ((read = in.read(buf)) > 0) {
                    out.write(buf, 0, read);
                }
                out.flush();
                out.getFD().sync();
            } finally {
                tmp.delete();
            }
        }
        try {
            dst.setReadable(true, false);
            dst.setWritable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32768];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeTextAtomically(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(parent, file.getName() + ".dbl_tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (file.exists()) {
            file.delete();
        }
        if (!tmp.renameTo(file)) {
            copyFileAtomically(tmp, file);
            tmp.delete();
        }
    }

    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}

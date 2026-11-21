package com.dbl.arabiclauncher;

import android.graphics.Color;

/**
 * ملف الإعدادات المركزي لتطبيق تعريب Dragon Ball Legends.
 * عدّل القيم هنا فقط لتغيير مسار الملفات داخل اللعبة أو روابط GitHub Releases دون الحاجة لتعديل الكود الداخلي.
 */
public final class LauncherConfig {

    private LauncherConfig() {
    }

    // =========================================================================
    // 1) معلومات التطبيق وهويته
    // =========================================================================
    public static final String APP_VERSION = "1.0.2";
    public static final String APP_TITLE = "Dragon Ball Legends — التعريب العربي";
    public static final String APP_SUBTITLE = "مثبّت ذكي مع نسخ احتياطي واستعادة أصلية (Shizuku & SAF)";
    public static final String BADGE_LABEL = "مشروع تعريب DBL";
    public static final String FOOTER_TEXT = "DBL Arabic Launcher • الإصدار " + APP_VERSION;

    // =========================================================================
    // 2) إعدادات اللعبة المستهدفة ومسار ملفات التعريب
    // =========================================================================
    /** معرّف الحزمة الرئيسي للعبة Dragon Ball Legends العالمية */
    public static final String GAME_PACKAGE = "com.bandainamcoent.dblegends_ww";

    /**
     * قائمة الحزم المدعومة (يمكنك إضافة النسخة اليابانية أو غيرها مستقبلاً هنا).
     */
    public static final String[] GAME_PACKAGES = {
            "com.bandainamcoent.dblegends_ww"
    };

    /**
     * المسار الفرعي داخل مجلد /Android/data/<GAME_PACKAGE>/files/
     * - مضبوط على "" (جذر مجلد files/) ليتطابق مع البنية الكاملة للعبة Dragon Ball Legends:
     *   • حزم الأصول واللغة (40 خانة SHA-1) تُنسخ داخل files/streamingassetbundles/
     *   • فهرس الحزم AssetBundleCatalog (40 خانة SHA-1) يُنسخ داخل files/as/
     *   • إذا احتوى الـ ZIP على ملف حزمة (40 خانة) مباشرة بدون مجلد، يوجّهه التطبيق تلقائياً إلى
     *     streamingassetbundles/ (أو as/ إذا كان موجوداً هناك مثل الفهرس).
     */
    public static final String GAME_TARGET_SUBPATH = "";

    /** المجلد الافتراضي لحزم الأصول واللغة في Dragon Ball Legends */
    public static final String DEFAULT_BUNDLES_SUBDIR = "streamingassetbundles";

    /** المجلد الخاص بفهرس AssetBundleCatalog */
    public static final String CATALOG_SUBDIR = "as";

    /** اسم هاش فهرس AssetBundleCatalog الثابت */
    public static final String CATALOG_BUNDLE_HASH = "88856e7ceebe5d6d518bd89010cb17f16a36a5ab";

    /**
     * مسارات للتحقق من أن لعبة Dragon Ball Legends تم تشغيلها وتنزيل بياناتها الأولية
     * (يكفي وجود أحدها داخل files/).
     */
    public static final String[] FIRST_LAUNCH_ANY_OF_PATHS = {
            "streamingassetbundles",
            "as"
    };

    /**
     * أسماء المجلدات الفرعية الحقيقية داخل مجلد files/ الخاص بلعبة Dragon Ball Legends.
     * تُستخدم لتمييز مجلدات اللعبة الحقيقية عن أي مجلد غلاف داخل ملف الـ ZIP.
     */
    public static final String[] KNOWN_GAME_SUBDIRS = {
            "streamingassetbundles",
            "as",
            "Scenario",
            "il2cpp",
            "Unity",
            "UnityCache",
            "assets",
            "cache"
    };

    /** يتحقق هل اسم الملف عبارة عن هاش SHA-1 من 40 خانة (حزمة UnityFS في DBL) */
    public static boolean isSha1BundleFilename(String name) {
        if (name == null || name.length() != 40) {
            return false;
        }
        for (int i = 0; i < 40; i++) {
            char c = name.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** اسم مجلد النسخ الاحتياطي داخل files/ لحفظ الملفات الأصلية قبل الاستبدال */
    public static final String BACKUP_DIR_NAME = ".dbl_arabic_backup";

    /** اسم ملف الفهرس الذي يحفظ قائمة الملفات المثبتة وأحجامها للتحقق والاستعادة */
    public static final String BACKUP_MANIFEST_FILE = "manifest.txt";

    // =========================================================================
    // 3) إعدادات التحديث والتحميل أونلاين (GitHub Releases)
    // =========================================================================
    /**
     * رابط ملف manifest.json الذي يصف أحدث إصدار للتعريب ورابط ملف الـ ZIP.
     * مثال باستخدام GitHub Releases:
     * https://github.com/USERNAME/REPO/releases/latest/download/manifest.json
     * أو عبر Raw GitHub:
     * https://raw.githubusercontent.com/USERNAME/REPO/main/manifest.json
     */
    public static final String MANIFEST_URL =
            "https://github.com/fgfasff-ux/DBL-Arabic-Launcher/releases/latest/download/manifest.json";

    /**
     * رابط مباشر اختياري لملف ZIP احتياطي (يُستخدم فقط إذا تركت MANIFEST_URL فارغاً أو فشل جلب الـ manifest).
     * مثال: https://github.com/fgfasff-ux/DBL-Arabic-Launcher/releases/latest/download/dbl_arabic_patch.zip
     */
    public static final String FALLBACK_ZIP_URL = "";

    /** رابط صفحة المشروع أو القناة/المجتمع لزر المتابعة */
    public static final String COMMUNITY_URL =
            "https://github.com/fgfasff-ux/DBL-Arabic-Launcher/releases";

    public static final int HTTP_CONNECT_TIMEOUT_MS = 15000;
    public static final int HTTP_READ_TIMEOUT_MS = 45000;
    public static final int HTTP_MAX_REDIRECTS = 6;

    // =========================================================================
    // 4) إعدادات جسر Shizuku المحلي
    // =========================================================================
    public static final int BRIDGE_PORT = 38912;
    public static final String BRIDGE_REV = "r1";
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    // =========================================================================
    // 5) ألوان الواجهة (ثيم داكن مستوحى من Dragon Ball Legends)
    // =========================================================================
    public static final int COL_BG = Color.parseColor("#070B16");
    public static final int COL_CARD = Color.parseColor("#10182C");
    public static final int COL_CARD_INNER = Color.parseColor("#0B1120");
    public static final int COL_BORDER = Color.parseColor("#223254");
    public static final int COL_ACCENT = Color.parseColor("#F97316");     // برتقالي طاقة
    public static final int COL_GOLD = Color.parseColor("#FACC15");       // ذهبي سوبر سايان
    public static final int COL_CYAN = Color.parseColor("#38BDF8");       // أزرق سماوي
    public static final int COL_TEXT = Color.parseColor("#F8FAFC");
    public static final int COL_MUTED = Color.parseColor("#94A3B8");
    public static final int COL_SUCCESS = Color.parseColor("#22C55E");
    public static final int COL_WARN = Color.parseColor("#F59E0B");
    public static final int COL_DANGER = Color.parseColor("#EF4444");

    /** يتحقق هل قام المطور بتعديل رابط GitHub الافتراضي أم لا يزال Placeholder */
    public static boolean isOnlineUrlConfigured() {
        if (MANIFEST_URL != null && !MANIFEST_URL.trim().isEmpty()
                && !MANIFEST_URL.contains("YOUR_USERNAME")) {
            return true;
        }
        return FALLBACK_ZIP_URL != null && !FALLBACK_ZIP_URL.trim().isEmpty()
                && !FALLBACK_ZIP_URL.contains("YOUR_USERNAME");
    }

    /** تنظيف وتوحيد المسار الفرعي (إزالة الشرطات المائلة الزائدة) */
    public static String normalizeSubpath(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}

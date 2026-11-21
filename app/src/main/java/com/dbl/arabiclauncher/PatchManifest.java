package com.dbl.arabiclauncher;

import org.json.JSONObject;

/**
 * نموذج بيانات يمثّل ملف manifest.json المستضاف على GitHub Releases.
 */
public final class PatchManifest {

    public final String patchVersion;
    public final String gameVersion;
    public final String releaseDate;
    public final String changelog;
    public final String downloadUrl;
    public final String sha256;
    public final long sizeBytes;
    public final String targetSubpath;

    public PatchManifest(
            String patchVersion,
            String gameVersion,
            String releaseDate,
            String changelog,
            String downloadUrl,
            String sha256,
            long sizeBytes,
            String targetSubpath
    ) {
        this.patchVersion = patchVersion != null ? patchVersion.trim() : "unknown";
        this.gameVersion = gameVersion != null ? gameVersion.trim() : "";
        this.releaseDate = releaseDate != null ? releaseDate.trim() : "";
        this.changelog = changelog != null ? changelog.trim() : "";
        this.downloadUrl = downloadUrl != null ? downloadUrl.trim() : "";
        this.sha256 = sha256 != null ? sha256.trim().toLowerCase() : "";
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.targetSubpath = LauncherConfig.normalizeSubpath(targetSubpath);
    }

    public static PatchManifest fromJson(String jsonText) throws Exception {
        JSONObject obj = new JSONObject(jsonText);
        String downloadUrl = obj.optString("download_url", "").trim();
        if (downloadUrl.isEmpty()) {
            throw new IllegalArgumentException("حقل download_url مفقود في ملف manifest.json");
        }
        return new PatchManifest(
                obj.optString("patch_version", "1.0.0"),
                obj.optString("game_version", ""),
                obj.optString("release_date", ""),
                obj.optString("changelog", ""),
                downloadUrl,
                obj.optString("sha256", ""),
                obj.optLong("size_bytes", 0L),
                obj.optString("target_subpath", "")
        );
    }

    /** يعيد المسار الفرعي الفعلي: إما من manifest.json أو من LauncherConfig */
    public String resolveEffectiveSubpath() {
        if (!targetSubpath.isEmpty()) {
            return targetSubpath;
        }
        return LauncherConfig.normalizeSubpath(LauncherConfig.GAME_TARGET_SUBPATH);
    }
}

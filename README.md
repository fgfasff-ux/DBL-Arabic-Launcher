# DBL Arabic Launcher — مشغّل ومثبّت تعريب Dragon Ball Legends

تطبيق أندرويد خفيف وآمن لتثبيت وتحديث تعريب لعبة **Dragon Ball Legends (`com.bandainamcoent.dblegends_ww`)** بالاعتماد على النسخ والاستبدال المباشر مع نظام **نسخ احتياطي واستعادة أصلية (Backup & Restore)** كامل.

## المميزات الأساسية
- **محرك وصول متعدد الطبقات لـ `Android/data`:**
  1. **Shizuku Bridge (`ShellBridgeServer`)**: تشغيل جسر محلي آمن عبر `app_process` بصلاحية Shizuku (يدعم أندرويد 11 حتى 15) مع إيقاف اللعبة بأمان (`am force-stop`) قبل النسخ، ومصادقة عبر Token محلي.
  2. **Storage Access Framework (`SafHelper`)**: دعم كامل عبر `DocumentsContract` بدون Shizuku أو Root على الأجهزة المدعومة.
  3. **الوصول المباشر (`Direct FS`)** لأندرويد 10 وما قبله، و**Root (`su`)** كخيار احتياطي.
- **نسخ احتياطي واستعادة بدقة البايت:**
  - قبل استبدال أي ملف، يتم حفظ نسخته الأصلية في `.dbl_arabic_backup/orig/` وتسجيل جميع الملفات في `.dbl_arabic_backup/manifest.txt`.
  - عند التحديث من إصدار تعريب قديم لجديد، يحافظ التطبيق على النسخة الأصلية الحقيقية ويزيل أي ملفات لم تعد مستخدمة.
  - زر **استعادة الأصل** يعيد جميع الملفات الأصلية ويحذف أي ملفات مضافة وينظّف مجلد النسخ الاحتياطي.
- **تحديثات أونلاين عبر GitHub Releases:**
  - جلب ملف `manifest.json`، فحص التحديثات، تحميل حزمة الـ `ZIP` مع تتبع التقدم، التحقق الاختياري من بصمة `SHA-256`، وفك الضغط الآمن ضد ثغرات Zip-Slip.
- **وضع الاختبار المحلي (`ZIP`):**
  - زر «تثبيت من ملف ZIP محلي» يتيح لك تجربة ملفات التعريب مباشرة من ذاكرة الهاتف قبل رفعها على GitHub.

## بنية حزمة التعريب (`dbl_arabic_patch.zip`)
يجب أن يعكس ملف الـ `ZIP` نفس البنية الداخلية لمجلد `/Android/data/com.bandainamcoent.dblegends_ww/files/` مباشرة:
```text
dbl_arabic_patch.zip
├── 02a02c65c2527456a8958812c5205841   (ملفات النصوص/البيانات المعرّبة - أسماء هاش 32 خانة بدون امتداد)
├── 7be69f3b932f536080e26c0598da01bc
├── ...
├── as/
│   └── 88856e7ceebe5d6d518bd89010cb17f16a36a5ab   (ملف الخط/الأصول - اسم هاش 40 خانة بدون امتداد)
└── Scenario/   (اختياري — إذا كان لديك ملفات سيناريو/قصة معرّبة داخل هذا المجلد)
```
> **ملاحظة:** لا تضع إلا الملفات التي قمت بتعريبها/تعديلها فقط داخل الـ `ZIP` لتقليل حجم التحميل. التطبيق يحفظ نسخة احتياطية لكل ملف يتم استبداله تلقائياً، ويدعم فك مجلد الغلاف تلقائياً إذا ضغطت مجلداً واحداً مثل `files/` أو `Language files DB/`.

## الإعداد السريع (تعديل ملف واحد فقط)
افتح الملف [`LauncherConfig.java`](app/src/main/java/com/dbl/arabiclauncher/LauncherConfig.java) وعدّل المتغيرات التالية:
- `GAME_TARGET_SUBPATH`: المسار الفرعي داخل `/Android/data/com.bandainamcoent.dblegends_ww/files/` (اتركه `""` إذا كان أرشيف الـ ZIP يبدأ من جذر `files/`).
- `FIRST_LAUNCH_CHECK_PATHS`: مسار مجلد/ملف اختياري داخل `files/` للتأكد من تشغيل اللعبة مسبقاً.
- `MANIFEST_URL`: رابط `manifest.json` على GitHub Releases (انظر النموذج في [`docs/manifest.example.json`](docs/manifest.example.json)).
- `COMMUNITY_URL`: رابط صفحة التحديثات أو مجتمعك.
- *(اختياري)* أضف خط `Cairo.ttf` داخل `app/src/main/assets/Cairo.ttf` ليستخدمه التطبيق تلقائياً.

## بناء ملف الـ APK
- **عبر Android Studio:** افتح مجلد المشروع مباشرة واضغط **Build > Build APK(s)**.
- **تلقائياً عبر GitHub Actions:** ارفع المشروع على مستودع GitHub وسيقوم ملف [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) ببناء ملف `app-debug.apk` تلقائياً وتوفيره للتحميل من تبويب **Actions**.

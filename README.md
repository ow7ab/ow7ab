# PMP PAINTS ERP — الموقع الرسمي ومنظومة البناء والإصدار الآلي (`ow7ab/ow7ab`)

- **الموقع الرسمي ومركز التحديثات (GitHub Pages):** `https://ow7ab.github.io/ow7ab/`
- **صفحة الإصدارات الرسمية (GitHub Releases):** `https://github.com/ow7ab/ow7ab/releases`
- **معرّف الحزمة الرسمي (Application ID):** `com.aistudio.pmppaints.erp`
- **بصمة شهادة التوقيع المعتمدة (SHA-256):** `1F:F7:75:2A:F4:96:A6:A7:4F:DF:5F:62:07:10:C7:47:83:03:1E:83:4B:2D:6D:A0:5A:AD:DB:57:A1:0D:11:05`

---

## 1. معمارية النشر الآلي والموقع الرسمي (Automated APK Release & Official Website)

يعمل مسار `.github/workflows/build-release.yml` تلقائياً عند كل `push` إلى الفرع `main` (أو عند التشغيل اليدوي عبر `workflow_dispatch`):

1. **اكتشاف نطاق التغييرات الذكي (Change Scope Detection):**
   - إذا كانت التعديلات مقتصرة على ملفات الموقع فقط (`public/**` أو `README.md`)، يقوم المسار بمزامنة الشعارات والصور الرسمية إلى `public/assets/` وإعادة نشر الموقع الرسمي على **GitHub Pages** فوراً دون إعادة بناء تطبيق أندرويد.
   - إذا شملت التعديلات كود أندرويد أو ملفات البناء (`app/**`, `gradle/**`, `build.gradle.kts`)، يعمل المسار الكامل المكون من 9 مراحل.
2. **استراتيجية الترقيم التصاعدي التلقائي (`Monotonic Version Code`):**
   - يفحص المسار أحدث إصدار منشور في **GitHub Releases API** وملف `version.json` وإعدادات `app/build.gradle.kts`، ويضمن أن `versionCode` الجديد أكبر دائماً من أي إصدار منشور سابقاً لمنع تعارض الإصدارات.
3. **بوابة الاختبارات الآلية (`Unit Tests Gate`):**
   - يشغّل كافة اختبارات الوحدة (`:app:compileDebugKotlin :app:testDebugUnitTest`) قبل بناء ملف الـ APK. في حال فشل أي اختبار، يتوقف المسار فوراً ويبقى الإصدار المستقر السابق معروضاً على الموقع دون تغيير.
4. **بناء وتوقيع وفحص حزمة الـ APK (`aapt2` & `apksigner`):**
   - يتحقق من تطابق `packageName` (`com.aistudio.pmppaints.erp`)، و`versionCode`، و`versionName`، وبصمة شهادة التوقيع (`1F:F7:75:2A...A1:0D:11:05`)، ويحسب بصمة الملف `SHA-256`.
5. **النشر المزدوج في GitHub Releases و GitHub Pages:**
   - ينشر حزمة `pmp-paints-v<version>-b<code>.apk` و`pmp-paints-latest.apk` و`version.json` في **GitHub Releases**.
   - يحدّث `public/version.json` و`public/releases-history.json` وينشر الموقع الرسمي المحدّث على `https://ow7ab.github.io/ow7ab/`.

---

## 2. تفعيل استضافة GitHub Pages في المستودع (إعداد لمرة واحدة)

لضمان عمل النشر التلقائي للموقع الرسمي على الرابط `https://ow7ab.github.io/ow7ab/`:
1. افتح مستودع GitHub (`ow7ab/ow7ab`).
2. انتقل إلى **Settings** → **Pages**.
3. تحت قسم **Build and deployment**، اختر في قائمة **Source** الخيار: **`GitHub Actions`**.

---

## 3. إعداد مفتاح التوقيع (`DEBUG_KEYSTORE_BASE64`) في GitHub Actions

يعتمد مسار بناء التحديثات في `.github/workflows/build-release.yml` على توقيع حزمة `APK` بنفس مفتاح التوقيع الأصلي (`debug.keystore` - Alias: `androiddebugkey`) المستخدم في الإصدارات المثبتة لدى المستخدمين حالياً.

> **تنبيه أمني وهندسي هام:**
> لا يجوز أبداً إنشاء ملف `keystore` جديد أو عشوائي بدلاً من المفتاح الأصلي. في نظام Android، إذا تغيّرت شهادة توقيع التطبيق (`Signing Certificate`)، سيرفض نظام التشغيل تثبيت التحديث فوق النسخة الحالية بخطأ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`، مما يمنع المستخدمين من التحديث دون حذف التطبيق وبياناته المحلية. إذا لم يكن المفتاح الأصلي متوفراً، فلا يمكن الحفاظ على توافق التحديثات التلقائية بإنشاء مفتاح بديل.

### خطوات إعداد `DEBUG_KEYSTORE_BASE64` في مستودع GitHub

#### 1. التحقق من ملف `debug.keystore` الأصلي محلياً
قبل ترميز الملف، تأكد من أنه يحتوي على الـ Alias المتوقع (`androiddebugkey`) وبصمة `SHA-256` الصحيحة:

```bash
keytool -list -v -keystore debug.keystore -storepass android -alias androiddebugkey
```

#### 2. تحويل ملف `debug.keystore` إلى نص Base64 بدون فواصل أسطر

- **على Linux (GNU coreutils):**
  ```bash
  base64 -w 0 debug.keystore > debug_keystore_b64.txt
  ```

- **على macOS:**
  ```bash
  base64 -i debug.keystore | tr -d '\n\r ' > debug_keystore_b64.txt
  ```

- **على Windows (PowerShell):**
  ```powershell
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("debug.keystore")) | Set-Content -NoNewline debug_keystore_b64.txt
  ```

*(أو إذا كان لديك ملف `debug.keystore.base64` الأصلي محلياً من تصدير المشروع كملف ZIP، يمكنك نسخ محتواه النصي مباشرةً).*

#### 3. إضافة القيمة في GitHub Actions Secrets
1. افتح مستودع GitHub (`ow7ab/ow7ab`).
2. انتقل إلى:
   **Settings** → **Secrets and variables** → **Actions** → **New repository secret**
3. في حقل **Name** أدخل الاسم التالي بدقة:
   ```text
   DEBUG_KEYSTORE_BASE64
   ```
4. في حقل **Secret** الصق النص الناتج من الخطوة (2)، ثم اضغط **Add secret**.
5. احذف الملف النصي المؤقت (`debug_keystore_b64.txt`) من جهازك المحلي، ولا تقم برفع المفتاح السري في أي سجل عام.

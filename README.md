# PMP PAINTS — إعداد التوقيع وبناء التحديثات في GitHub Actions (`DEBUG_KEYSTORE_BASE64`)

يعتمد مسار بناء التحديثات في `.github/workflows/build-release.yml` على توقيع حزمة `APK` بنفس مفتاح التوقيع الأصلي (`debug.keystore` - Alias: `androiddebugkey`) المستخدم في الإصدارات المثبتة لدى المستخدمين حالياً.

> **تنبيه أمني وهندسي هام:**
> لا يجوز أبداً إنشاء ملف `keystore` جديد أو عشوائي بدلاً من المفتاح الأصلي. في نظام Android، إذا تغيّرت شهادة توقيع التطبيق (`Signing Certificate`)، سيرفض نظام التشغيل تثبيت التحديث فوق النسخة الحالية بخطأ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`، مما يمنع المستخدمين من التحديث دون حذف التطبيق وبياناته المحلية. إذا لم يكن المفتاح الأصلي متوفراً، فلا يمكن الحفاظ على توافق التحديثات التلقائية بإنشاء مفتاح بديل.

---

## خطوات إعداد `DEBUG_KEYSTORE_BASE64` في مستودع GitHub

### 1. التحقق من ملف `debug.keystore` الأصلي محلياً
قبل ترميز الملف، تأكد من أنه يحتوي على الـ Alias المتوقع (`androiddebugkey`) وبصمة `SHA-256` الصحيحة:

```bash
keytool -list -v -keystore debug.keystore -storepass android -alias androiddebugkey
```

### 2. تحويل ملف `debug.keystore` إلى نص Base64 بدون فواصل أسطر

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

### 3. إضافة القيمة في GitHub Actions Secrets
1. افتح مستودع GitHub (`ow7ab/ow7ab`).
2. انتقل إلى:
   **Settings** → **Secrets and variables** → **Actions** → **New repository secret**
3. في حقل **Name** أدخل الاسم التالي بدقة:
   ```text
   DEBUG_KEYSTORE_BASE64
   ```
4. في حقل **Secret** الصق النص الناتج من الخطوة (2)، ثم اضغط **Add secret**.
5. احذف الملف النصي المؤقت (`debug_keystore_b64.txt`) من جهازك المحلي، ولا تقم برفع المفتاح السري في أي سجل عام.

---

## آلية التحقق التلقائي داخل GitHub Actions
عند تشغيل الـ Workflow (`.github/workflows/build-release.yml`):
1. تستقبل خطوة `Prepare Environment Config & Verify Compatible Signing Keystore (debug.keystore)` المتغير السري `DEBUG_KEYSTORE_BASE64` عبر متغيرات البيئة دون طباعة قيمته في السجلات.
2. تفك ترميز Base64 بأمان إلى `debug.keystore` بصلاحيات `0600` (أو تستعيده من `debug.keystore.base64` إن وُجد في بيئة العمل).
3. تفحص صلاحية الملف عبر `keytool` للتأكد من وجود الـ Alias `androiddebugkey` واستخراج بصمة الشهادة `SHA-256`.
4. بعد بناء ملف `APK`، تتحقق خطوة `Verify Compiled APK Binary` عبر `apksigner` من تطابق بصمة توقيع الـ `APK` مع بصمة شهادة `debug.keystore` قبل السماح بنشر الإصدار.

# بناء نسخة الإصدار الموقّعة (من الجوال بلا حاسوب)

الخطوات كلها عبر متصفح الجوال (فعّل «موقع سطح المكتب» إن لزم) وTermux.

## 1) أسرار GitHub (مرة واحدة)
في مستودع FileMind: **Settings ← Secrets and variables ← Actions ← New repository secret**. أنشئ أربعة:

| الاسم | القيمة |
|---|---|
| `KEYSTORE_BASE64` | ملف المفتاح `.jks` مشفّراً base64 (الخطوة التالية) |
| `KEYSTORE_PASSWORD` | كلمة مرور ملف المفتاح |
| `KEY_ALIAS` | اسم المفتاح (alias) |
| `KEY_PASSWORD` | كلمة مرور المفتاح |

**تحويل الملف في Termux:**
```
base64 -w0 /المسار/إلى/المفتاح.jks > ~/key.txt
```
افتح `~/key.txt` بمحرر نصوص، انسخ كل محتواه (سطر واحد طويل) والصقه في قيمة السر `KEYSTORE_BASE64`. بعدها احذف `~/key.txt`.

**لا ترفع ملف `.jks` ولا كلمات المرور إلى المستودع أبداً.** (`.gitignore` يمنع `*.jks` و`keystore.properties`.)

## 2) تشغيل البناء
**Actions ← release ← Run workflow**، واكتب:
- `version_code`: رقم صحيح، **يجب أن يزيد عن آخر رفع في Play** (1 ثم 2 ثم 3…).
- `version_name`: مثل `1.0.0`.

عند الانتهاء افتح تشغيل البناء ونزّل الـ artifact باسم **filemind-release** (ملف ZIP) وفيه:
- `*.aab` ← هذا الذي يُرفع إلى Google Play.
- `*.apk` ← للتجربة على جهازك.
- `mapping.txt` ← احتفظ به لقراءة الأعطال (Crashlytics يستلمه تلقائياً أيضاً إن توفّرت الشروط).

## 3) تحقق من التوقيع
في خطوة «Show signing fingerprints» ستظهر بصمتا SHA1 وSHA256 للـ APK. قارنهما بالمسجّلتين في `docs/PERMISSIONS.md`. إن اختلفتا فالمفتاح المرفوع ليس مفتاحك المقصود.

## 4) الرفع إلى Google Play
- أنشئ التطبيق في Play Console وأكمل «App content» (سياسة الخصوصية، Data Safety، الإعلانات، تصريح الخدمة الأمامية، الفئة العمرية).
- ارفع ملف `.aab` إلى مسار اختبار (Internal testing) أولاً.
- مع Play App Signing تحتفظ Google بمفتاح التوقيع النهائي، ومفتاحك هو «مفتاح الرفع». بصمات التطبيق النهائية تأخذها من Play Console ← App integrity (للـ Firebase).
- قد يشترط Play على الحسابات الشخصية الجديدة اختباراً مغلقاً بعدد من المختبرين لمدة محددة قبل الإنتاج. تحقق من شروط حسابك الحالية في Play Console.

## تنبيه
أول بناء release قد يكشف أخطاء التصغير (R8) التي لا تظهر في بناء debug. إن فشل فأرسل لي سجل الخطوة الفاشلة.

## 5) نشر سياسة الخصوصية (مطلوبة في Play)
1. البريد الإلكتروني مُدرج في الصفحة وجاهزة للنشر.
2. في GitHub: **Settings ← Pages ← Build and deployment**: Source = **Deploy from a branch**، Branch = **main**، المجلد **/docs** ثم Save. (في الحساب المجاني يلزم أن يكون المستودع عاماً.)
3. بعد دقائق يكون الرابط: `https://belalmomani.github.io/FileMind/` وهو الذي يوضع في Play Console كرابط سياسة الخصوصية.
4. اختر في Play Console أن التطبيق **غير موجّه للأطفال** ليتوافق مع السياسة.

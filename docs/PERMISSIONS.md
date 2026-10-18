# الصلاحيات المستخدمة فعلياً (Manifest)
| الصلاحية | السبب |
|---|---|
| READ_MEDIA_IMAGES / VIDEO / AUDIO (Android 13+) | التصنيفات والبحث في الوسائط عبر MediaStore |
| READ_EXTERNAL_STORAGE (حتى Android 12) | نفس الغرض في الإصدارات الأقدم |
| INTERNET / ACCESS_NETWORK_STATE | إعلانات Start.io (والتعرف الصوتي يتم عبر خدمة النظام عبر Intent دون إذن ميكروفون) |

**MANAGE_EXTERNAL_STORAGE غير مُعلنة عمداً.** الوصول لبقية المجلدات يتم عبر Storage Access Framework.
إن قررت لاحقاً أن مستكشف الملفات الكامل هو الوظيفة الأساسية، فاطلبها فقط بعد مراجعة سياسة Google Play الحالية (All files access) وتعبئة نموذج التصريح.

# المهام الخلفية
لا توجد مهام خلفية منفذة بعد. المخطط: WorkManager لاستكمال العمليات، قابلة للإيقاف من مركز العمليات.

# بصمات شهادة التوقيع (علنية، ليست سراً)
SHA-1: BD:18:EE:26:0B:CB:C2:AA:7D:02:C6:BE:9A:26:AF:C3:E5:E5:6B:5D
SHA-256: A6:E6:B7:DA:63:D0:CB:3C:92:A6:1A:5B:36:E1:0A:A1:29:BB:06:55:9E:86:A0:AB:CA:2C:58:64:AA:55:CC:89
ملف الـ keystore نفسه وكلمات المرور يبقون خارج المستودع (keystore.properties).

# FS-0.6a — Error Handling & Bilingual Messages (Work Rules 2 & 3)

> **Task:** KH-0.6a (الشق الأول من KH-0.6 بعد تقسيمها) · **Repo:** khatm-platform · **Status:** APPROVED
> **Sources of truth:** CLAUDE.md work rules 2 & 3 (نصّاهما هما العقد الأصلي) · CONVENTIONS §3/§4 · SEC §9 (لا PII/claims في اللوغات) · KH-0.4 (أكواد رفض التحقق الوليدة)
> **الشق الثاني (KH-0.6b):** auth الجلسات + API-key filter + مسار audit الكامل — spec مستقلة لاحقاً.
> **اللغة:** شرح عربي، عقود وكود إنجليزية.

---

## 1. الهدف والنطاق

تنفيذ قاعدتي العمل 2 و3 دفعة واحدة لأنهما متشابكتان بنيوياً: الـ error envelope
يحمل رسالة مترجمة، والترجمة تحتاج بنية الرسائل، وكلاهما ينتظر منذ Phase 0
المبكرة بينما تراكم زبائن فعليون (أكواد رفض SD-JWT من KH-0.4 تولد اليوم كنصوص
خام بلا envelope ولا ترجمة، و`CredentialController.issue()` ما زال يحمل
`throws Exception` عارية).

**النطاق:** هرمية `KhatmException` + سجل `ErrorCode` + `@RestControllerAdvice`
الوحيد + الـ envelope الموحد + حزمتا الرسائل EN/AR + حسم اللغة + اللوغ البنيوي
JSON مع `traceId` + ترحيل كل مسارات الأخطاء القائمة + توليد `error-codes.md` آلياً.
**خارج النطاق:** المصادقة والتفويض (KH-0.6b)، رسائل الكونسول/المحفظة (لكل repo
حزمه — هذا الـ spec يثبت **المفاتيح** التي سيستهلكانها)، صفحات أخطاء HTML.

## 2. قرارات التصميم

| # | القرار | التبرير |
|---|---|---|
| D1 | **أخطاء API ≠ نتائج تحقق.** فشل التحقق (توقيع/عبث/حجب إلزامي/إبطال) **نتيجة domain** تعود `200` مع `{valid:false, reason}` — ليس exception ولا envelope. الـ envelope حصري لأخطاء الطلب (404/400/401/409/500...) | المستهلك الآلي (بوابة جهة حكومية) يفحص وثائق بالجملة؛ "وثيقة مزورة" جواب ناجح لسؤاله لا عطل في طلبه. يطابق تصميم `VerifyResult` sealed في المحفظة (CLAUDE.md wallet) — عقد واحد عبر المنظومة |
| D2 | سجلان منفصلان: `ErrorCode` enum (أخطاء API، صيغة `KH-<MOD>-<NNNN>`) و`VerifyReason` enum (مفردات التحقق: `valid`, `bad_signature`, `revoked`, `expired`, `forged_disclosure`, `duplicate_disclosure`, `withheld_mandatory_claim`, `unknown_kid`, `malformed`...) — **وكلاهما يترجَم من نفس الحزمتين** (`error.<key>` / `verify.reason.<code>`) | فصل المفردات مع توحيد قناة الترجمة: قاعدة العمل 2 تُرضى مرة واحدة |
| D3 | صيغة NNNN: **الخانات الثلاث الأخيرة = HTTP status، الأولى = تسلسل داخل الوحدة/الحالة** (`KH-CRD-0404` أول 404 في credential، `KH-CRD-1404` الثاني...) | تحافظ على أمثلة CLAUDE.md حرفياً وتحل مشكلة التعدد بلا إعادة ترقيم أبداً |
| D4 | الـ envelope (نص CLAUDE.md حرفياً): `{code, messageKey, message, traceId, timestamp, details[]}` — `message` بلغة الطلب، `details[]` لأخطاء الحقول (`{field, messageKey, message}`) | العملاء يعيدون الترجمة بـ `messageKey` إن أرادوا، والـ `message` الجاهزة تكفي البسطاء |
| D5 | حسم اللغة: `Accept-Language` فقط (`en` افتراضي، المدعوم `en|ar`، أي قيمة أخرى → `en` بصمت — لا خطأ على لغة غير مدعومة) | أبسط عقد للعملاء الآليين؛ تفضيل المستخدم المخزَّن (`app_user.preferred_lang`) شأن الكونسول عند إرسال الترويسة، لا شأن المنصة |
| D6 | اللوغ: `logstash-logback-encoder` — JSON في كل البروفايلات **عدا `local`** (console نمط مقروء)، `traceId` عبر MDC يولَّده `TraceIdFilter` (يقبل `X-Request-Id` الوارد وإلا UUID) ويعيده في ترويسة الاستجابة وفي الـ envelope. **اللوغات إنجليزية دائماً** (CONVENTIONS §3) | قاعدة العمل 3 نصاً؛ قبول `X-Request-Id` يمهد للتتبع عبر console→platform |
| D7 | `docs/error-codes.md` **يولَّد من الـ enum بواسطة اختبار** يفشل إن كان الملف متقادماً (يعيد التوليد ويقارن) — لا يُحرَّر يدوياً أبداً | CLAUDE.md work rule 1 حرفياً؛ الفشل-عند-التقادم هو ما يجعله حياً |
| D8 | **الترحيل شامل في نفس الجلسة:** كل `throws Exception` عارية تُزال، كل بناء استجابة خطأ ad-hoc في الـ controllers يُحذف، أكواد KH-0.4 الخام تتحول `VerifyReason` — لا مرحلة انتقالية بنمطين (قاعدة العمل 4) | نصف ترحيل يعني نمطين متعايشين — بالضبط ما تمنعه القاعدة |

## 3. الهرمية والسجل

```java
// shared/error/ — الهرمية من CLAUDE.md حرفياً:
KhatmException (abstract: ErrorCode code, String messageKey, Object[] args)
├─ NotFoundException        → 404    ├─ AuthenticationException → 401
├─ ConflictException        → 409    ├─ AuthorizationException  → 403
├─ ValidationException      → 400/422├─ IntegrityException      → 500
```
- `ErrorCode` enum: `KH_<MOD>_<NNNN>(httpStatus, "message.key")` — الوسوم من
  CONVENTIONS §2 (TEN, KEY, SCH, CRD, STS, LDG, HLD, CNS, RBC, CON, SYS).
  الدفعة الأولى تغطي المسارات القائمة فقط (تقديرياً: `KH_CRD_0404` credential
  not found، `KH_SCH_0404`، `KH_CRD_0400` malformed presentation، `KH_CRD_0409`
  already consumed/idempotency conflict، `KH_KEY_0500` signing failure،
  `KH_SYS_0500` fallback) — **يضاف ولا يُعاد ترقيم أبداً**.
- `GlobalExceptionHandler` (`@RestControllerAdvice`, في `shared/web/`):
  يعالج `KhatmException` وأبناءها، `MethodArgumentNotValidException` (→
  `details[]` من Bean Validation بمفاتيح `validation.<constraint>`)، و`Exception`
  الشامل (→ `KH-SYS-0500`، رسالة عامة، stack trace كامل إلى اللوغ مع traceId،
  **لا شيء داخلي للعميل**).
- `/verify` (D1): يبقى `200` دائماً عند طلب سليم الشكل؛ الاستجابة تكسب
  `reason` (كود `VerifyReason`) و`reasonMessage` المترجمة. الطلب المشوه بنيوياً
  (ليس SD-JWT أصلاً) هو الوحيد الذي يرمي `ValidationException`.

## 4. الحزمتان وقواعدهما

- `src/main/resources/i18n/messages_en.properties` + `messages_ar.properties`
  (UTF-8 صراحة في إعداد `MessageSource` — ملفات properties الافتراضية ISO-8859-1
  وستكسر العربية بصمت). مفاتيح dot-notation حسب CONVENTIONS §3.
- **`MessageBundleParityTest`** (الاسم المحجوز في CONVENTIONS §7): مجموعتا
  المفاتيح متطابقتان تماماً في الاتجاهين + لا قيمة فارغة + كل `messageKey` في
  `ErrorCode` و`VerifyReason` له مدخل — أي نقص يفشل البناء.
- العربية تُكتب سليمة لا حرفية-مترجمة: مراجعتك أنت (الناطق) على ملف `ar` قبل
  الدمج جزء من بوابة الـ PR لهذه الجلسة تحديداً.

## 5. معايير القبول (DoD)

1. طلب لوثيقة غير موجودة → `404` بالـ envelope الكامل (كود، مفتاح، رسالة، traceId،
   timestamp)، **بلا stack trace** — واختبار مطابق لـ `500` مصطنع (الرسالة عامة).
2. نفس الطلب بـ `Accept-Language: ar` → `message` عربية فعلاً (assertion على
   محارف عربية)، وبـ `de` أو بلا ترويسة → إنجليزية — بلا خطأ.
3. Bean Validation على `IssueRequest` بحقل ناقص → `400` مع `details[]` معبأة
   ومترجمة.
4. `/verify` على وثيقة معبوث بها → `200` مع `valid:false` و`reason:forged_disclosure`
   و`reasonMessage` بلغة الطلب (اختبار بالحالتين en/ar) — **وليس** envelope.
5. `traceId`: قيمة `X-Request-Id` مرسلة تظهر نفسها في ترويسة الاستجابة وفي الـ
   envelope وفي أسطر اللوغ الملتقطة (ListAppender)؛ وبلا ترويسة يولَّد UUID.
6. `MessageBundleParityTest` أخضر، واختبار توليد `error-codes.md` يفشل فعلاً عند
   تقادم الملف (يُثبت بتجربة داخل الاختبار نفسه أو باختبار سلبي موثق).
7. `grep` صفري: لا `throws Exception` عارية في أي controller، لا بناء استجابة
   خطأ خارج `GlobalExceptionHandler`، أكواد KH-0.4 الخام كلها عبر `VerifyReason`.
8. بروفايل غير `local` يخرج لوغ JSON (اختبار يلتقط سطراً ويفككه JSON)؛
   `local` يبقى مقروءاً. اختبار `NoDisclosureContentInLogsTest` القائم يبقى أخضر
   فوق الـ encoder الجديد.
9. المعتاد: Javadoc + README لـ `shared` محدّث + OpenAPI يعرّف الـ envelope
   schema على الاستجابات المعلَّمة + البناء وCI أخضران.

## 6. أثر على بقية المنظومة

- **الكونسول:** طبقة الأخطاء الموحدة فيه (CLAUDE.md console) تبنى فوق هذا
  الـ envelope حرفياً — `errors.<messageKey>` محلياً ثم `message` الخادم؛ يصبح
  قابلاً للتنفيذ فور دمج هذا.
- **المحفظة:** `AppFailure.api(code, messageKey)` يفكك نفس الـ envelope؛
  و`VerifyReason` هنا هو المفردات التي تحاكيها `VerifyResult` هناك.
- **KH-0.6b:** `AuthenticationException`/`AuthorizationException` جاهزتان
  فارغتين — الشق الثاني يملؤهما ويضيف أكواد `KH-RBC-*`.
- **KH-1.6:** الـ envelope يدخل الـ OpenAPI المنشور كـ schema مشترك وحيد.

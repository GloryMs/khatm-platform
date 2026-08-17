# SESSION-KH-2.4x-BE — إغلاق الديون التعاقدية والتدقيقية المتراكمة

> **المستودع:** `khatm-platform` — IntelliJ / Claude Code.
> **الفرع:** `feat/KH-2.4x-BE-contract-closeouts`.
> **النوع:** جلسة تنفيذ قياسية (كود + اختبارات + عقد + توثيق).
> **المرجع:** `docs/STATE.md` — أقسام: platform ask من C9 (أكواد KH-ATT الغائبة عن العقد)،
> platform asks من C7c/C8 (MeResponse: tenant slug + إشارة TOTP)، دين A7 المُنطَّق
> (`KEY_RETIRE_REJECTED`)، والتعليقان القديمان لصلاحيات transit.
> **قواعد حاكمة:** العقد additive-only (أي كسر ← SELF-STOP)؛ Work rules 2 & 3
> (`docs/CONVENTIONS.md §7.1`)؛ انضباط Spring Security لكل endpoint (§7.2)؛
> "the code is the reference" — تحقق من كل سلوك على الكود الفعلي لا من هذا الملف.

---

## Preamble (بوابة قبل أي كود)

1. `git fetch`؛ تأكد أن `origin/main` يتقدم على أو يساوي `c7c3d1b` (دمج PR #56) وأن chore
   الـ Vault-record مدموج (ملف `khatm-transit-app.hcl` على `main` يمنح
   `create, update, read`). **SELF-STOP** إن لم يكن.
2. صفر PRs مفتوحة على المستودع، أو سجّلها وتأكد أنها لا تتقاطع مع هذا النطاق.
3. `mvn verify` أخضر على `main` قبل التفريع (خط أساس).

---

## Scope — أربعة deliverables

### D1 — إظهار أكواد `KH-ATT-*` في العقد المولَّد

`KH-ATT-0400`/`0401`/`0402` موجودة ومربوطة فعلياً (`ErrorCode.java`،
`CredentialService#issue`، `BulkIssuanceService#bulkIssue`) لكنها غائبة عن
`docs/api/openapi.json` لغياب `@ApiResponse` على الـ endpoints المعنية — فجوة C9 المسجَّلة.

- أضف توثيق `@ApiResponse` لاستجابات 400 الحاملة لهذه الأكواد على endpoints الإصدار المعنية
  (المفرد والـ bulk) **بنفس النمط القائم** لبقية أكواد الأخطاء الموثَّقة في العقد — اقرأ
  مثالاً قائماً أولاً وطابقه، لا تخترع شكلاً جديداً.
- أعد توليد العقد عبر آلية `OpenApiContractTest` نفسها؛ تحقق عبر `git diff` أن التغيير
  **إضافي بحت** (لا مسار ولا schema حُذف).
- تحقق أن `docs/error-codes.md` يحوي الأكواد الثلاثة أصلاً (متوقَّع أنها أُضيفت في KH-2.4-BE)
  — أعد توليده فقط إن كانت الآلية تتطلب ذلك.

### D2 — إثراء `MeResponse`: `tenantSlug` + حالة TOTP

يغلق ثلاث فجوات كونسول مسجَّلة: (1) تأكيد rotate بكتابة slug المستأجر بدل `kid` (فجوة C8)؛
(2) شارة حالة TOTP في Security Settings (فجوة C7c: «لا حقل يكشف هل TOTP مفعَّل»)؛
(3) عرض "Reset 2FA" بشكل مشروط بدل غير المشروط.

- أضف إلى `GET /api/v1/auth/me` (استجابة `MeResponse`): `tenantSlug` (string) و`totpEnabled`
  (boolean). **إضافة فقط** — لا تغيير لأي حقل قائم.
- `totpEnabled` يُشتق من حالة enrollment الفعلية للمستخدم الحالي (اقرأ آلية
  `TotpService`/جداول `user_totp_*` القائمة لتحديد المصدر الصحيح — لا تفترض).
- تذكير: `/me` معفى من بوابة forced-password-change (chore/forced-change-discoverability) —
  لا تمس هذا الإعفاء.
- **خارج النطاق صراحةً (veto V1):** إشارة «TOTP إلزامي لنطاقاتك» (forced enrollment) — المنصة
  تفرض TOTP كـ opt-in فقط حالياً (FS-2.2 V1)؛ إضافة إشارة إلزام تغيير سياسة لا حقل عقد.
- عقد: additive-only، إعادة توليد + `git diff` كما في D1. اختبارات: توسيع اختبارات `/me`
  القائمة بحالتَي `totpEnabled` (مفعَّل/غير مفعَّل) وبوجود `tenantSlug` الصحيح لمستأجر
  غير افتراضي.

### D3 — `AuditAction.KEY_RETIRE_REJECTED` (إغلاق دين A7)

QS-A7-GITCHECK أثبت أن مسار رفض `KeyLifecycleService#retire` (حارس `!force && elapsed <
minRetiringAge`، رمي `KH-KEY-0422` قبل أي `audit.record`) **صامت بالبناء**. المطلوب جعل الرفض
مسموعاً في audit trail — إجراء إداري حساس أمنياً:

- قيمة enum جديدة `KEY_RETIRE_REJECTED` في `shared/audit/AuditAction.java`.
- استدعاء `audit.record(...)` على فرع الرفض قبل/عند رمي `KH-KEY-0422`، حاملاً `kid` وسبب
  الرفض (العمر المنقضي مقابل الحد الأدنى).
- **⚠️ نقطة التصميم الوحيدة الحقيقية (veto V2):** الرمي داخل transaction يعني rollback يبتلع
  سطر التدقيق إن كُتب في نفس الـ transaction. تحقق من السلوك الفعلي للـ transaction boundaries
  في `retire` ومن نمط `AuditService` القائم، وطبّق الحل الأدنى الذي يضمن ثبات السطر
  (`REQUIRES_NEW` على مسار تسجيل الرفض، أو ما يكافئه في أعراف المشروع إن وُجد نمط قائم
  لتدقيق-رغم-الفشل — ابحث عنه أولاً). **إثبات الثبات جزء من الاختبار، لا افتراض.**
- Regression test يحاكي أسلوب توكيدات `KeyLifecycleServiceTest` القائمة لـ `KEY_RETIRED`:
  retire مرفوض (بلا force، قبل min-retiring-age) ← `KH-KEY-0422` **و**سطر
  `KEY_RETIRE_REJECTED` موجود فعلاً في `audit_log` بعد انتهاء الطلب.
- لا كود خطأ جديد (`KH-KEY-0422` قائم) ← لا تغيير في `docs/error-codes.md` متوقَّع؛ تحقق.

### D4 — تصحيح تعليقين قديمين (سطران)

- `src/main/resources/application.yml:116` و
  `src/main/java/sy/khatm/platform/key/domain/VaultTransitProvider.java:50` — كلاهما ما يزال
  يذكر `create+read` لصلاحيات `transit/keys/*`؛ صحّح إلى `create, update, read` مع إشارة
  موجزة للاستنتاج التجريبي (2026-08-15). (الأسطر قد تكون زحفت — ابحث عن النص لا الرقم.)

---

## Veto points (الافتراضيات تسري إن لم يجب مجد قبل الجلسة)

- **V1 — إشارة forced-TOTP-enrollment في `MeResponse`:** الافتراضي **لا** — حالة فقط
  (`totpEnabled`)، لا إشارة إلزام. تُفتح لاحقاً كبند سياسة مستقل إن أُريدت.
- **V2 — آلية ثبات سطر `KEY_RETIRE_REJECTED` عبر rollback:** الافتراضي: اتباع نمط قائم في
  المشروع إن وُجد؛ وإلا `REQUIRES_NEW` على تسجيل الرفض حصراً. أي حل ثالث ← SELF-STOP.
- **V3 — اسم حقل حالة TOTP:** الافتراضي `totpEnabled`. إن كشف الكود اسماً أدق متسقاً مع
  المصطلحات القائمة (مثل enrollment vs enabled)، اختر الأدق وسجّل السبب.

## خارج النطاق صراحةً

- أي عمل واجهة (يذهب لجلسة C10 المقابلة).
- إشارة إلزام TOTP (V1).
- إعادة تطبيق policy على أي Vault قديم (بند تشغيلي، جلسة الـ housekeeping).
- أي مساس بـ RLS أو مسارات التحقق العامة.

## DoD

- [ ] `mvn verify` أخضر، كل الاختبارات القائمة + الجديدة (D2: ≥3، D3: ≥1).
- [ ] `git diff` على `openapi.json`: إضافي بحت (D1 + D2)، موثَّق في وصف الـ PR.
- [ ] لا نصوص مواجهة للمستخدم جديدة (backend فقط) ← بوابة العربية غير مفعَّلة هذه الجلسة؛
      إن ظهر خلاف ذلك ← SELF-STOP.
- [ ] PR مفتوح على `khatm-platform`، مجد يراجع ويدمج.
- [ ] `docs/STATE.md` محدَّث عند إغلاق الجلسة، مع سطر يعلم جلسة الكونسول C10 أن
      `tenantSlug`/`totpEnabled` صارا متاحين.

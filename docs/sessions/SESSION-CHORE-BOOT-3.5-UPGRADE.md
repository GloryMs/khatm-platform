# SESSION-CHORE-BOOT-3.5-UPGRADE — ترقية Spring Boot 3.3.13 → 3.5.x (إغلاق CVE spring-data-commons)

> **المستودع:** `khatm-platform` — IntelliJ / Claude Code.
> **الفرع:** `chore/boot-3.5-upgrade`.
> **النوع:** جلسة بنيوية (dependency-line upgrade) — **لا عمل وظيفي يُخلط بها إطلاقاً**.
> **الدافع:** CVE في spring-data-commons لا patch له على خط 3.3.x (EOL للـ OSS support).
> القرار المعتمد (مجد): الخيار 1 — ترقية الخط كاملاً، اتساقاً مع سابقة المشروع
> «bump the whole line together» من إصلاح spring-security. **الخيار 2 (override معزول
> لـ spring-data-commons وحده) مرفوض صراحةً** — تركيبة غير مدعومة خطرها المعلَن في
> JPA repository proxies وnative-query handling، وهي بالضبط الطبقة الحاملة لـ RLS.
> **قواعد حاكمة:** "the code is the reference" — release notes تحدد أين تنظر، والسلوك يُثبت
> بالتشغيل الفعلي لا بالقراءة؛ العقد additive-only؛ RLS isolation is an invariant؛
> fail-closed everywhere؛ **الجولة الحية على compose إلزامية لهذه الجلسة — لا بديل عنها**
> (السابقة المسجَّلة: التقطت أخطاء غير مرئية لـ Testcontainers أكثر من مرة).

---

## Preamble (بوابة قبل أي تعديل)

1. `git fetch`؛ تأكد أن `origin/main` يحوي دمج جلستَي KH-2.4x-BE وchore الـ Vault
   (بما فيها `khatm-transit-app.hcl` المصحَّح). **SELF-STOP** إن لم يكن.
2. صفر PRs مفتوحة على `khatm-platform` (لا تُركَّب ترقية بنيوية فوق عمل معلَّق).
3. خط أساس: `mvn verify` أخضر على `main` قبل التفريع، وسجّل عدد الاختبارات الحالي
   (المرجع الأخير المسجَّل 434 قبل KH-2.4x — الرقم الفعلي بعدها هو الأساس).
4. سجّل النسخ الفعلية الحالية قبل أي تغيير (من `mvn dependency:tree` أو الـ BOM الفعّال):
   Boot، spring-modulith، Hibernate، Flyway، springdoc، Testcontainers، spring-data-*
   — هذه هي «قائمة القفزات» التي يُبنى عليها تقرير المرحلة 0.

---

## المرحلة 0 — تحقيق قابلية الاستغلال (report-only، قبل لمس أي pom)

الهدف: معايرة درجة الاستعجال وتسجيلها، لا تغيير القرار (الترقية ماضية بأي حال).

1. حدّد الـ CVE بدقة (المعرّف، المكوّن/الصنف المصاب داخل spring-data-commons، شرط
   الاستغلال المنشور).
2. grep/فحص فعلي على المستودع: هل المسار المصاب قابل للوصول من كود المنصة؟ (انتبه للوصول
   غير المباشر عبر spring-data-jpa/redis، لا الاستيراد المباشر فقط.)
3. النتيجة تُسجَّل في تقرير الجلسة وفي STATE عند الإغلاق:
   - **غير قابل للوصول** ← المخاطرة الانتقالية مقبولة، الترقية تمضي بإيقاعها الطبيعي.
   - **قابل للوصول** ← يُرفع العلم لمجد فوراً، وتصير الترقية «التالي مباشرة» مع تسريع
     الجدولة، لكن **بلا اختصار للبوابات** — لا تُدمج ترقية بنيوية على عجل.

---

## المرحلة 1 — الترقية الميكانيكية

1. اقرأ release notes / upgrade guides الرسمية لـ Boot **3.4 و3.5 معاً** (القفزة تعبرهما).
   استخرج قائمة breaking changes الملامسة فعلياً لهذا المستودع فقط — لا نسخاً عاماً للقائمة.
2. ارفع `spring-boot-starter-parent` (أو الـ BOM) إلى أحدث patch GA على خط 3.5
   (veto V1). **spring-modulith يُرفع معه في نفس الالتزام** إلى الخط المتوافق مع Boot 3.5
   (تحقق من مصفوفة التوافق الرسمية — لا تفترض أن النسخة الحالية تعمل).
3. springdoc وTestcontainers وأي تبعية مثبَّتة صراحةً في الـ pom: تُرفع **فقط** إن كسرت
   النسخة الحالية التوافق مع Boot 3.5 (veto V4) — الافتراضي ترك ما يديره الـ BOM للـ BOM.
4. `mvn verify` — أصلح الانكسارات الميكانيكية (APIs متقاعدة، تواقيع متغيرة، خصائص
   configuration أُعيدت تسميتها في `application.yml`). **SELF-STOP** إن تطلّب أي إصلاح
   تغييراً **سلوكياً** لا ميكانيكياً (أي شيء يغيّر دلالة أمنية أو تعاقدية — انظر بوابات
   المرحلة 2).

---

## المرحلة 2 — بوابات التحقق المركَّزة (مناطق الانتباه الأربع)

لكل منطقة: تحقق موجَّه + توكيد أن الاختبارات القائمة تغطيها فعلاً بعد القفزة.

### 2a — طبقة RLS/JPA (الأخطر)
- قفزة Hibernate الضمنية مع Boot 3.5: تحقق أن كل native queries تعمل، وأن سلوك
  RLS predicates لم يتغير — **العزل invariant**: غياب tenant context = صفر صفوف، لا
  default-tenant fallback على أي مسار مصادَق.
- شغّل كامل حزمة اختبارات RLS/العزل القائمة وتأكد أنها خضراء **وأنها ما تزال تختبر ما
  تدّعيه** (اختبار يخضرّ لأن السلوك المفحوص لم يعد يُنفَّذ أصلاً = أسوأ من فشل).
- `ConsumingPartyEnsureRaceTest` وسباق الـ 10-concurrent-callers في
  `VaultKeyLifecycleAcceptanceTest`: سلوك الـ transactions/proxies تحت الخط الجديد.
- سطر تدقيق `KEY_RETIRE_REJECTED` (KH-2.4x): تحقق أن آلية ثباته عبر rollback
  (`REQUIRES_NEW` أو ما اعتُمد) ما تزال تعمل تحت Hibernate/Spring-tx الجديدين —
  regression test الجلسة السابقة هو الحكم.

### 2b — Redis Streams والـ workers
- `StreamEventDispatcher` بخريطته `Map<String, List<StreamEventHandler>>`: أعد تشغيل
  `RedisStreamWorkerTest` كاملاً، خصوصاً
  `dispatch_twoHandlersRegisteredForTheSameType_bothReceiveIt` — مستهلكا `KeyRotated`
  (status resign + tenant provider sync) كلاهما يجب أن يستقبل.

### 2c — Spring Security ومسارات permitAll
- مسارات `permitAll` الخمسة وسلوك «principal حقيقي على endpoint مجهول»
  (`runAsDefaultTenant`): شغّل `AuthenticatedCallerOnAnonymousEndpointsTest` و
  `PublicEndpointsNoCredentialsTest` — إصلاح 2026-08-11 يجب أن يصمد تحت أي تغيير في
  ترتيب/دلالة filter chain في Boot 3.5.
- انضباط per-endpoint (CONVENTIONS §7.2): لا endpoint فقد بوابته بصمت.

### 2d — بوابة العقد (springdoc)
- أعد توليد `docs/api/openapi.json` عبر آلية `OpenApiContractTest`. **فرّق بصرامة بين:**
  - **diff شكلي** (إعادة ترتيب مفاتيح، صياغة أوصاف، تفاصيل serialization لا تغيّر الشكل
    الدلالي) ← مقبول، يُعاد vendor مع توثيق صريح في وصف الـ PR أنه cosmetic-only
    (veto V2).
  - **diff دلالي** (مسار/schema/حقل حُذف أو تغيّر نوعه أو nullability أو أكواد استجابة
    اختفت) ← **SELF-STOP فوراً** — هذا كسر تعاقدي، والعقد additive-only بوابة مطلقة.
- `docs/error-codes.md`: أعد التوليد إن كانت الآلية تتطلبه؛ صفر تغيير دلالي متوقَّع.

---

## المرحلة 3 — الجولة الحية على compose **[MAJD]** (إلزامية، حاجبة للدمج)

Claude Code يجهّز البيئة (rebuild الصور محلياً من الفرع) ويتوقف؛ التنفيذ لمجد:

1. تدفق كامل: schema → إصدار → claim/redeem → verify → consume (بما فيه idempotency).
2. الإصدار المُوثَّق (attested, KH-2.4): hash في المتصفح، `SCAN_ATTESTED` قبل
   `CREDENTIAL_ISSUED` في نفس الـ transaction — تحقق من الترتيب في `audit_log` فعلياً.
3. دوران مفتاح على SOFT، ثم دوران بتبديل صريح إلى VAULT (compose المحلي بـ Vault)، ثم
   دوران وراثي يبقى على VAULT.
4. **fail-closed**: أوقف حاوية Vault ← إصدار على tenant مُرحَّل يفشل `503 KH-KEY-0503`
   بلا تراجع صامت، بينما verify/JWKS/status-list تبقى تعمل كلها (المسار DB-only).
5. عيّنة تحقق لاعتماد قديم (مُصدَر قبل الترقية) — يتحقق بلا تغيير.

أي انحراف في أيٍّ منها ← يعود لـ Claude Code كتحقيق قبل أي دمج.

---

## Veto points (الافتراضيات تسري إن لم يجب مجد قبل الجلسة)

- **V1 — نسخة الهدف:** الافتراضي أحدث patch GA على خط 3.5 وقت الجلسة. لا milestones/RCs.
- **V2 — التعامل مع diff العقد الشكلي:** الافتراضي قبول إعادة الـ vendor مع توثيق
  cosmetic-only في الـ PR. البديل (b): تثبيت إعدادات springdoc لكبح الـ diff — فقط إن كان
  الضجيج كبيراً يعمي المراجعة.
- **V3 — إصلاح يتجاوز الميكانيكي:** لا افتراضي — أي breaking change يتطلب تغييراً سلوكياً
  (أمن، transactions، serialization للعقد) ← **SELF-STOP** وقرار مجد لكل حالة.
- **V4 — التبعيات المثبَّتة صراحةً:** الافتراضي: ما يديره BOM يُترك للـ BOM؛ المثبَّت صراحةً
  يُرفع فقط عند كسر توافق فعلي، ويُسجَّل كل رفع وسببه في تقرير الجلسة.

## خارج النطاق صراحةً

- أي override معزول لـ spring-data-commons (الخيار 2 المرفوض) — حتى كخطوة انتقالية.
- أي عمل وظيفي أو تحسين انتهازي («ما دمنا نلمس الملف») — الترقية نظيفة أو لا تكون.
- الكونسول والمحفظة: لا عمل فيهما هذه الجلسة. **متابعة لاحقة تُسجَّل في STATE:** بعد الدمج،
  `npm run contract:update` كونسول-side وre-vendor المحفظة للتأكد من صفر drift دلالي
  (متوقَّع أن يكون شكلياً بحتاً إن وُجد).
- Java 21 يبقى كما هو — لا قفزة JDK ضمن هذه الجلسة.

## DoD

- [ ] تقرير المرحلة 0 (قابلية استغلال الـ CVE) مكتوب ومسجَّل.
- [ ] Boot 3.5.x + spring-modulith المتوافق على الفرع؛ `mvn verify` أخضر بكامل عدد
      اختبارات خط الأساس (صفر اختبارات معطَّلة/مُتخطّاة لتمرير البناء — أي `@Disabled`
      جديد = SELF-STOP).
- [ ] بوابات المرحلة 2 الأربع موثَّقة نتائجها في وصف الـ PR (وخصوصاً حكم بوابة العقد:
      cosmetic-only أو zero-diff).
- [ ] **[MAJD]** الجولة الحية (المرحلة 3) مكتملة بكل بنودها الخمسة.
- [ ] لا نصوص مستخدم جديدة ← بوابة العربية غير مفعَّلة؛ إن ظهر خلاف ذلك ← SELF-STOP.
- [ ] PR مفتوح على `khatm-platform` بعنوان يذكر الـ CVE؛ مجد يدمج بعد الجولة.
- [ ] **[MAJD] بعد الدمج (تشغيلي، خارج الجلسة لكن يُسجَّل):** إعادة بناء صور staging من
      `main` وإعادة النشر ← **تذكير: إعادة نشر الـ pod تعيد ختم Vault** ← unseal يدوي
      (`unseal-staging-vault.sh`) قبل أول إصدار، وفحص `sys/seal-status` أولاً عند أي
      `KH-KEY-0503`.
- [ ] `docs/STATE.md` محدَّث: الترقية، حكم المرحلة 0، حكم بوابة العقد، وبند المتابعة
      الكونسول/المحفظة (re-vendor) مفتوحاً باسمه.

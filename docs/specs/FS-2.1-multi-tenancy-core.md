# FS-2.1 — Multi-Tenancy Core (RLS + Tenant Plane + Per-Tenant Trust Endpoints)

> **Task:** KH-2.1 (KH-2.1.1 → KH-2.1.4) · **Repo:** khatm-platform · **Status:** Approved
> **Sources of truth:** SEC 21 §5 (جدول عزل المستأجرين) · SAD 20 §4.1/§6/§7 · doc 05 · FS-0.2 (المخطط القائم) · WBS 31 · CONVENTIONS §7
> **اللغة:** شرح عربي، DDL ومعرّفات إنجليزية — حسب الاصطلاح.
> **مستوى النموذج:** Sonnet حصراً (RLS = وحدة أمنية-حرجة).

---

## 1. الهدف والنطاق

جعل العزل بين المستأجرين **حقيقة مفروضة في قاعدة البيانات** لا مجرد انضباط في طبقة
الخدمات، وفتح سطح HTTP لإدارة المستأجرين، ونشر نقاط الثقة (JWKS/قوائم الحالة) لكل
مستأجر على حدة. معيار النجاح النهائي هو NFR-07: **صفر قراءة عابرة للمستأجرين تحت
جناح اختبار آلي في CI**.

**ما هو موجود أصلاً (لا يُعاد بناؤه):**
- جدول `tenant` كامل منذ `V1__baseline.sql` (slug, name_i18n, type, did, deploy_mode, status) + صف المستأجر الافتراضي الثابت (`TenantContext.DEFAULT_TENANT_ID`).
- `tenant_id uuid NOT NULL` على كل جدول أعمال + فهارس مركّبة تبدأ به (FS-0.2 D2).
- `issuer_key` لكل مستأجر مع قيد «مفتاح فعّال واحد لكل tenant» (فهرس جزئي).
- مسار قائمة الحالة `GET /sl/{tenantSlug}/{listCode}` — tenant-scoped أصلاً (FS-1.3 D2).
- `KeyProvider` SPI بتواقيع تأخذ `TenantId` أصلاً (SEC §3).

**داخل النطاق:** سياسات RLS + دور تشغيلي بلا BYPASSRLS، تمرير سياق المستأجر من الـ
principal إلى الـ transaction، سطح إدارة المستأجرين (create/list/get/suspend/activate
+ onboarding)، `GET /t/{slug}/.well-known/jwks.json`، جناح اختبار التسرّب الإلزامي.

**خارج النطاق:** إدارة مستخدمي المستأجر وأدواره (KH-2.2)، KMS (KH-2.3)، object
storage للـ artifacts (يبقى التقديم من القاعدة كما قرّر FS-1.3 D4 — يُراجع مع
KH-2.5)، الانضمام الذاتي Self-onboarding (Phase 4)، `did:web` التفعيلي (يبقى العمود
كما هو)، حدود المعدّل per-tenant (بند SEC §5 «API» — يُجدول لاحقاً مع KH-2.5).

---

## 2. القرارات D1–D10

| # | القرار | التبرير |
|---|---|---|
| D1 | **حلّ سياق المستأجر من الـ principal الموثَّق حصراً** — `app_user.tenant_id` لجلسات الكونسول، `api_key.tenant_id` لمفاتيح API؛ **أبداً** من body أو header أو query. `TenantContextFilter` جديد (بعد فلاتر المصادقة) يملأ `TenantContext` request-scoped. `DEFAULT_TENANT_ID` الثابت يبقى للـ seeders والبروفايل `local` فقط — كل موقع استدعاء runtime له يُستبدل بالسياق المحلول، ويُضاف اختبار grep-gate يمنع استخدامه خارج الحزم المسموحة | SEC §5 نصاً: «Tenant context resolved from authenticated principal only — never from request body» |
| D2 | **RLS مع `FORCE`:** على الجداول الأعمالية الـ 13: `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` + سياسة `tenant_isolation USING (tenant_id = current_setting('app.tenant_id', true)::uuid)` (الـ `true` الثانية = لا استثناء عند غياب المتغير — يُرجع NULL فلا يطابق شيئاً، **فشل مغلق**). جدول `tenant` نفسه خارج RLS (سطح الإدارة يقرؤه عبر بوابة admin) | SEC §5؛ `FORCE` يضمن أن حتى مالك الجداول لا يتجاوز — دفاع في العمق |
| D3 | **دوران التشغيل بدور DB منفصل:** دور `khatm_app` جديد (لا BYPASSRLS، ليس مالكاً، `GRANT SELECT/INSERT/UPDATE` صريح لكل جدول — لا DELETE على مسارات الأعمال، اتساقاً مع FS-0.2 D6)؛ Flyway يبقى على دور المالك الحالي. **إنشاء الدورين provisioning وقت النشر لا Flyway migration** (الأدوار cluster-level؛ managed PG لن يقبلها في migration): سكربت init في compose محلياً + توثيق staging في `docs/deploy-staging.md`. الـ GRANTs نفسها تعيش في الـ migration (الدور موجود قبلها بحكم الترتيب) | فصل صلاحيات الهجرة عن التشغيل هو الشرط الفعلي لعمل RLS؛ خبزها في Flyway يكسر البيئات المُدارة |
| D4 | **تمرير المتغير transaction-scoped حصراً:** `SELECT set_config('app.tenant_id', :id, true)` عند بداية كل transaction (الوسيط الثالث `true` = local للـ tx). الآلية الدقيقة (hook على `JpaTransactionManager` أو `ConnectionPreparer`) قرار المنفّذ، **بقيد صلب: ممنوع `SET SESSION`** — التسريب عبر Hikari pool بين طلبات مستأجرين مختلفين هو الثغرة الأولى في هذا النمط | connection pooling يعيد استخدام الجلسات؛ scope الـ tx يجعل التنظيف تلقائياً حتى عند الاستثناءات |
| D5 | **المسارات العامة/غير الموثَّقة** (redeem، verify، `/sl/**`، JWKS): سياسة ثانية لكل جدول يلزمها `system_access USING (current_setting('app.khatm_system', true) = 'on')`، يضبطها wrapper واحد (`SystemAccessExecutor` في `shared`) تستدعيه **قائمة خدمات محصورة بالاسم** (redeem lookup، verify lookup، status-list read، JWKS read، وworkers الـ Redis Streams). اختبار يثبت أن قائمة المستدعين تطابق المعدَّد حرفياً — نفس نمط اختبار public-path القائم. **workers**: الأحداث المُصدَّرة يجب أن تحمل `tenantId` في الحمولة؛ consumer يستعيد السياق منها — **التحقق من حمولات الأحداث القائمة (`StatusListChanged` وأخواتها) ضد الكود أول واجبات الجلسة** (الكود مرجع فوق افتراضات الـ spec) | هذه المسارات لا principal لها بطبيعتها (P2: التحقق لا يُحتجز خلف حساب)، وقفل RLS الكامل يكسرها؛ الحصر بالاسم + الاختبار يمنع الزحف الصامت |
| D6 | **سطح إدارة المستأجرين** (نمط سطح consuming-party حرفياً): `POST /api/v1/admin/tenants` (create + onboarding)، `GET /api/v1/admin/tenants` (قائمة)، `GET /{id}`، `POST /{id}/suspend`، `POST /{id}/activate`. تحت scope `admin` القائم (KH-2.2 يفتّته لاحقاً — نفس قرار V1 المسجَّل). **الإنشاء = onboarding**: صف tenant + مفتاح ACTIVE أول عبر `KeyProvider.rotate` + قائمة حالة افتراضية (`list_code = '<slug>-<year>'`، السعة الافتراضية 131072 كما هي). فشل أي خطوة = لا tenant جزئي (تُحدَّد ذرّية التنفيذ في الـ brief مع مراعاة أن `rotate` قد يلمس keystore خارج الـ tx — نمط تعويضي مقبول إن لزم، موثَّقاً) | SAD §6 يسمّي `POST /api/v1/admin/tenants` نصاً؛ مستأجر بلا مفتاح/قائمة كيان ميت — الـ onboarding الكامل هو الوحدة الصحيحة |
| D7 | **SUSPENDED يعضّ في مسار المصادقة** (نمط KH-1.4.4 D4 نفسه): principal تابع لمستأجر SUSPENDED → 401/403 بنفس مسارات الفشل القائمة؛ **مساراته العامة تبقى حيّة** — قوائم الحالة وJWKS تُقدَّم لوثائق أُصدرت قبل التعليق (الإيقاف يمنع الإصدار الجديد لا التحقق من القديم — اتساقاً مع «لا أخضر كاذب» و P2) | تعليق جهة لا يجوز أن يجعل وثائق مواطنيها غير قابلة للتحقق فجأة |
| D8 | **JWKS لكل مستأجر:** `GET /t/{tenantSlug}/.well-known/jwks.json` عام جديد؛ المسار القديم `/.well-known/jwks.json` **يبقى alias للمستأجر الافتراضي** موسوماً deprecated في OpenAPI — **قطعه يكسر trust bootstrap لكل محفظة W2 منشورة ووثائق مُصدرة** (قيد صلب: لا إزالة في هذه المرحلة). `PublicUrlBuilder` يكتسب البناء الواعي بالـ slug؛ الوثائق الجديدة تُصدر بمراجع المسار الجديد | KH-2.1.3 نصاً؛ العقد إضافي-فقط بروتوكول مثبت |
| D9 | **الأخطاء والتدقيق:** `KH-TNT-0400` (slug غير صالح — regex الـ slug نفسه المعتمد للـ consuming-party code)، `KH-TNT-0404`، `KH-TNT-0409` (slug مكرر — 409 لا صف ثانٍ، بإثبات صف-واحد)، `KH-TNT-0422` (onboarding فشل جزئياً إن اعتُمد النمط التعويضي). `AuditAction.TENANT_{CREATED,SUSPENDED,ACTIVATED}` (entityRef = slug). المفاتيح الجديدة في الحزمتين EN/AR بنفس الـ commit — **بوابة المراجعة العربية (FS-0.6a §4) قبل الدمج** | CONVENTIONS §7.1؛ النمط المستقر |
| D10 | **جناح التسرّب اختبار إلزامي مسمّى** — `CrossTenantIsolationTest` ينضم للأربعة الإلزامية (Migration/Bundle/Modulith/ConcurrentConsume): يزرع مستأجرَين A/B بكيانات كاملة (schema, credential, claim, consuming party, key, status list)، ثم (١) **طبقة HTTP**: principal A ضد كل مورد مُعدَّد لـ B → 403/404، صفر صفوف؛ (٢) **طبقة الدفاع في العمق**: استعلام repository خام كـ `khatm_app` بسياق A على جدول فيه صفوف B → صفوف A فقط حتى **بلا** أي فلتر خدمي — يثبت أن RLS نفسها تعضّ لا الانضباط الخدمي؛ (٣) **غياب السياق** → صفر صفوف (فشل D2 المغلق) | NFR-07 حرفياً + بند SEC §5 الأخير؛ الطبقة (٢) هي الفارق بين «مصفّى» و«معزول» |

---

## 3. الهجرة — `V7__rls_policies.sql` (إضافية-فقط)

- `ALTER TABLE <t> ENABLE ROW LEVEL SECURITY; ALTER TABLE <t> FORCE ROW LEVEL SECURITY;` + السياستان (D2, D5) لكل جدول أعمالي من جداول FS-0.2 الثلاثة عشر **عدا `tenant`** — والقائمة تُملأ بالتحقق من المخطط الحي لا من هذا الـ spec (أعمدة/جداول أُضيفت بعده: V5 code، V6 index…).
- `GRANT SELECT, INSERT, UPDATE ON ... TO khatm_app` (لا DELETE أعمالياً؛ الاستثناءات الموثقة — مثل تصفير `disclosures_enc` — هي UPDATE أصلاً). `GRANT USAGE` على الـ sequences إن وُجدت.
- V1–V6 لا تُمَسّ؛ `MigrationImmutabilityTest` + `MigrationCleanBootTest` أخضران؛ checksum جديد في `db/migration-checksums.lock`.
- compose: خدمة postgres تكتسب init script ينشئ `khatm_app` قبل أول boot؛ `khatm-api`/`khatm-worker` يتحولان للاتصال بـ `khatm_app`؛ Flyway وحده على دور المالك (datasource هجرة منفصل — Boot يدعم `spring.flyway.user/password` المنفصلين نصاً).

## 4. تقسيم الجلسات (briefs تُشتق من هذا الـ spec)

| جلسة | المحتوى | لماذا هذا الترتيب |
|---|---|---|
| **KH-2.1a-BE** | D1 + D6 + D7 + D8 + D9: سياق المستأجر، سطح الإدارة والـ onboarding، JWKS لكل مستأجر، الأخطاء/التدقيق. **بلا RLS بعد** | يوصل السطح الوظيفي كاملاً على الانضباط الخدمي القائم؛ قابل للاختبار والدمج مستقلاً |
| **KH-2.1b-BE** | D2 + D3 + D4 + D5 + D10: الهجرة V7، الدوران، تمرير المتغير، سياسة system، جناح التسرّب | أخطر جزء معزولاً في جلسة نظيفة فوق سياق يعمل — لو انكسر شيء فالمشتبه الوحيد هو RLS |
| **C5 (كونسول، لاحقاً)** | شاشة إدارة المستأجرين — **تُرجَّح لدمجها في KH-2.2.2** (لوحة إدارة المنصة) بدل جلسة يتيمة الآن | scope الـ admin سيتغير في KH-2.2 على أي حال |

**دليل خروج Phase 2 (بذرته هنا):** بعد KH-2.1b، تشغيل حي بثلاثة مستأجرين معزولين على compose (الافتراضي + اثنان جديدان عبر السطح الجديد) موثَّقاً في STATE — نصف معيار الخروج؛ نصفه الآخر (دوران المفتاح) عند KH-2.3.3.

## 5. القيود الصلبة (تُنسخ في الـ briefs حرفياً)

1. العقد إضافي-فقط؛ المسار القديم للـ JWKS لا يُزال (D8).
2. ممنوع `SET SESSION` لمتغير السياق (D4).
3. قائمة مستدعي `SystemAccessExecutor` معدَّدة ومُختبَرة (D5)، وكل endpoint جديد يصرّح بـ scope + اختبار قائمة المسارات العامة — القاعدة القائمة.
4. الكود مرجع فوق هذا الـ spec عند أي تعارض (حمولات الأحداث، قائمة الجداول، أسماء الأعمدة) — يُسجَّل الفارق في STATE.
5. لا PR يُدمج قبل مراجعة مجد؛ بوابة المراجعة العربية على مفاتيح `tenant.*` قبل الدمج.

## 6. نقاط الفيتو (قرارات تحتاج كلمة مجد قبل كتابة الـ briefs)

| # | السؤال | الافتراضي المقترح |
|---|---|---|
| V1 | دور DB منفصل + datasource هجرة منفصل — تغيير بنيوي في compose وstaging لاحقاً | نعم — بدونه RLS ديكور |
| V2 | alias المسار القديم للـ JWKS: يبقى إلى متى؟ | يبقى طوال Phase 2؛ يُراجع عند KH-3 مع trust bundle (SEC §6) |
| V3 | onboarding ذرّي أم تعويضي (بسبب keystore خارج الـ tx)؟ | قرار المنفّذ ضمن D6، موثَّقاً في الـ PR |
| V4 | هل تعليق المستأجر يوقف consume لوثائقه القائمة أيضاً، أم الإصدار فقط (D7 الحالي: التحقق والاستهلاك يبقيان)؟ | الإصدار فقط — الاستهلاك عقد بين الحامل والجهة المستهلكة، لا يُعاقَب المواطن بتعليق جهته |

# FS-1.5.4 — Dashboard v2 Read Endpoints (لوحة التحكم — نسخة 2)

> **Task:** **KH-1.1.5-BE** (مؤكَّد من مجد 2026-07-25 — انظر D0) · **Repo:** khatm-platform · **Status:** APPROVED (مجد، 2026-07-25 — القرارات D0/D1/D5 مؤكَّدة صراحة، انظر D5 لتعديل مهم بعد الموافقة)
> **Sources of truth:** brief الجلسة (2026-07-25، مجد) · CLAUDE.md (حدود Modulith، قواعد العمل 1–4) · `docs/STATE.md` (سياق KH-1.1.3-BE/KH-1.4.4-BE) · SEC §9 (لا PII/claims في اللوغ أو الاستجابة)
> **يخدم:** khatm-console Dashboard v2 (أربع لوحات placeholder فارغة اليوم، مبنية سلفاً في الكونسول — هذا الـ spec هو الـ backend الذي يغذّيها).
> **اللغة:** شرح عربي، عقود إنجليزية.

---

## 1. الهدف والنطاق

أربع لوحات في Dashboard v2 (الكونسول) جاهزة كـ shells فارغة لعدم وجود بيانات
خادمية. المطلوب: أربع نقاط قراءة (#1–#4 أدناه) + نقطة خامسة مشتقة (consuming-
parties stats) تُبنى فوق نفس حل التصميم الذي تحتاجه #2.

**خارج النطاق:** أي كتابة جديدة (كل شيء هنا قراءة فقط فوق بيانات موجودة أصلاً)،
أي جدول جديد (لا migration في هذا الـ spec سوى فهرس اختياري إن لزم الأداء)،
تخصيص per-consumer quotas (يبقى عملاً مستقبلياً كما توثّقه `consumer/package-
info.java`).

**اكتشاف معماري غيّر اقتراح البريف نفسه (يُقرأ أولاً):** البريف اقترح أن تعيش
#2/#3/consuming-parties stats كـ "controller جديد في `shared.web` بجانب
`StatsController`". هذا غير قابل للبناء كما هو: توثيق `shared/package-info.java`
ينص صراحة أن هذه الوحدة **لا تعتمد خارجياً على أي وحدة Khatm أخرى**، وكل من
`credential`/`consumer`/`rbac`/`key` يصرّح بالفعل بـ `shared` كـ
`allowedDependencies`. لو اعتمدت `shared` على أيٍّ منها الآن، ينتج cycle بنيوي
بين وحدتين يرصده `ModulithBoundariesTest#moduleStructureIsValid` (`modules
.verify()`) فوراً — هذه ليست مسألة ذوق، بل بناء يفشل فعلياً. انظر D1.

## 2. القرارات D0–D9

| # | القرار | التبرير |
|---|---|---|
| D0 | **رقم WBS: `KH-1.1.5-BE`، فرع `feat/KH-1.1.5-BE-dashboard-stats-v2` — مؤكَّد من مجد (2026-07-25).** بحثت في `docs/STATE.md`: نقطة الإحصاء الحالية (`GET /api/v1/stats`) رقمها **KH-1.1.3-BE**، وليس أي "KH-1.5.x"؛ `FS-1.5.3` (المذكورة في `AuditAction`/`StatsController` الحاليين) هي رقم **feature-spec طرف الكونسول** — محور ترقيم مختلف تماماً عن WBS هذا المستودع. مستند WBS الأصلي (`31-work-breakdown-structure.md`) غير موجود فعلياً ضمن `docs/specs/` هنا (مرآة من khatm-docs لم تُنسخ). سلسلة الجلسات الداعمة الأخيرة (`KH-1.1.3-BE`, `KH-1.1.4`, `KH-1.4.3`, `KH-1.4.4-BE`) اقترحت `KH-1.1.5-BE` كامتداد طبيعي — مجد أكّد استخدامه. | لا يمكن التحقق من الرقم الحقيقي محلياً؛ مجد اختار المتابعة بالاقتراح الطبيعي بدل الانتظار |
| D1 | **توزيع الـ endpoints على الوحدات القائمة بدل وحدة جديدة أو تعديل قاعدة `shared`:** <br>• `#1 GET /api/v1/stats/daily` → **`shared.web`** (بجانب `StatsController` فعلاً — اعتماد وحيد على `audit`، نفس الوحدة، صفر حافة اعتماد جديدة). <br>• `#4 GET /api/v1/admin/signing-keys` → **`key.web`** (وحدة `key` تقرأ بيانات `issuer_key` المملوكة لها هي، تماماً مثل `JwksController` — صفر اعتماد جديد أيضاً). <br>• `#2 GET /api/v1/activity`, `#3 GET /api/v1/attention`, و`GET /api/v1/stats/consuming-parties` (الإضافية) → **`credential.web`** — الوحدة الوحيدة التي تصرّح أصلاً بكل الاعتمادات الثلاث اللازمة معاً: `key :: api`، `consumer :: api`، `rbac :: api`، `shared :: audit`، وتملك هي نفسها جدول `credential` (حل D2/نقطة أ مباشرة، بلا استدعاء عابر للوحدات). | حل بديل (تعديل قاعدة `shared` "لا اعتماد خارجي"، أو فتح وحدة Modulith جديدة خارج قائمة SAD §4.1 المجمّدة في CLAUDE.md) كلاهما يمسّ عقوداً موثّقة صراحة — بينما `credential` تحل المشكلة بصفر تغيير بنيوي جديد، فقط توسعتان صغيرتان في `rbac :: api`/`key :: api` (D2/D5) هما أصلاً النمط المعتاد ("وسّع واجهة إن لم يوجد lookup تحتاجه" — تعليمات البريف نفسها) |
| D1b | **تحفظ نطاق:** بذلك يصبح `credential.web` مضيفاً لعارض أحداث audit يغطي فقط الأفعال ذات الصلة بدورة حياة الوثيقة (`CREDENTIAL_ISSUED/CONSUMED/REVOKED`, `CONSUME_SCHEMA_DENIED`, `CLAIM_CODE_REDEEMED`, `CREDENTIAL_VERIFY_OK/FAILED`) — ليس عارض audit عام لكل الوحدات (`AUTH_*`, `KEY_ROTATED`, `CONSUMING_PARTY_*` تبقى خارج `#2`/`#3` كما يقترح البريف نفسه ضمنياً بأمثلته). لو احتاج الكونسول لاحقاً لوحة "سجل دخول المشرفين" مثلاً، ذلك يستحق وحدة `dashboard`/`reporting` منفصلة مع تعديل صريح لقائمة SAD §4.1 — خارج نطاق هذه الجلسة | توسيع النطاق الآن إلى "كل الأحداث" يحوّل `credential` لوحدة تجميع عامة تتجاوز مسؤوليتها الموثّقة؛ تثبيت النطاق بصريح العبارة أفضل من انجراف صامت |
| D2 | **نقطة (ب) — إسناد consuming-party:** واجهة جديدة في `rbac :: api` (module-private impl في `rbac.domain`، خلف `ApiKeyRepository.findAllById` الموروثة أصلاً من `JpaRepository`، صفر migration): <br>`interface ApiKeyOwnerLookup { Map<UUID, ApiKeyOwnerRef> resolveOwners(Collection<UUID> apiKeyIds); }` <br>`record ApiKeyOwnerRef(OwnerKind kind, UUID ownerId)` مع `enum OwnerKind { TENANT, CONSUMING_PARTY }`. `credential` (تصرّح أصلاً بـ `rbac :: api`) يستدعيها بدفعة على كل `actor_id` من صفوف `audit_log` حيث `actor_type='API_KEY'`، يفلتر `CONSUMING_PARTY`، ثم يحل `ownerId` (= `consuming_party.id`) إلى اسم عرض عبر D4 | لا مسار عابر-للوحدات لهذا اليوم (`CurrentActorResolver` يحل actor **الطلب الحالي** فقط، ليس `actor_id` تاريخياً من صف قديم) — بالضبط الفجوة التي حددها البريف؛ حل批 batch بدل استدعاء فردي لكل صف يتفادى N+1 |
| D3 | **نقطة (أ) — حل `entity_ref`:** فحص الكود يؤكد أن `CREDENTIAL_ISSUED`, `CONSUME_SCHEMA_DENIED` (`CredentialService` سطر ~687 يحل الـ ref قبل الكتابة), `CLAIM_CODE_REDEEMED`, `CREDENTIAL_VERIFY_OK/FAILED` **جميعها تخزّن `ref` جاهزاً للعرض بالفعل** — فقط `CREDENTIAL_CONSUMED`/`CREDENTIAL_REVOKED` تخزّن `id` (UUID) حرفياً (`CredentialService` سطر 714 وموقع consume). حل هذين فقط، بدفعة، عبر method جديدة (`CredentialRepository.findAllById` الموروثة + تحويل لـ `Map<UUID,String>`) — داخل نفس الوحدة، صفر اعتماد عابر | تبسيط حقيقي: 5 من أصل 7 أفعال لا تحتاج أي حل إضافي؛ التعميم "حل كل شيء دائماً" كان سيضيف استعلاماً غير لازم لكل صف |
| D4 | **اسم عرض الجهة المستهلكة:** إعادة استخدام `ConsumingPartyAdmin#list()` الموجودة أصلاً (بلا method جديدة في `consumer :: api`) — بناء خريطة `id → ConsumingPartyView` بالذاكرة لكل طلب. حجم الجهات المستهلكة صغير (MVP/tenant واحد)، فـ `list()` كاملة أرخص من إضافة `getBatch` جديدة لا سابقة لها | ميكانيكي حقيقي بلا قرار تصميم إضافي — الواجهة الموجودة تكفي |
| D5 | **مرفوض من مجد (2026-07-25) — `key :: api` يبقى بلا مساس، بلا `KeyStatusLookup` أو أي سطح جديد.** إذن عنصر "مفتاح يقترب من التدوير" في `#3` **يُستبعد من نطاق هذه الجلسة** — `GET /api/v1/attention` يشحن بعنصرين فقط (schema-denied، verify-failure-rate)، لا ثلاثة. `#4 GET /api/v1/admin/signing-keys` غير متأثر (يبقى في `key.web`، يقرأ بيانات `key` من داخل الوحدة نفسها، لا يحتاج أي سطح جديد أصلاً). عنصر التدوير يبقى **عملاً مستقبلياً موثَّقاً**، بانتظار نقاش منفصل حول حدود `key :: api` قبل أي محاولة أخرى لحله | مجد فضّل عدم توسعة `key :: api` حتى بشكل حالة-فقط؛ إسقاط عنصر واحد من ثلاثة starter items أوضح وأصدق من محاولة الالتفاف عليه بحل بديل (مثل استدعاء `#4` HTTP داخلياً من `credential` — سيكون تركيباً مصطنعاً يضيف زمن استجابة لخدمة نداء داخل نفس العملية، وسيبقى بالمعنى العملي نفس الاعتماد العابر الذي رُفض) |
| D6 | **عتبات #3 (needs-attention) — مؤكَّدة من مجد (2026-07-25) كقيم افتراضية أولية، قابلة للـ config لاحقاً بلا تغيير شكل الاستجابة:** <br>• **schema-denied**: آخر `CONSUME_SCHEMA_DENIED` ضمن نافذة `khatm.stats.attention.window` (افتراضي 24h)، مُفصَّلة (item لكل صف لا عدّاد فقط)، سقف 20 الأحدث، طرف/سكيما محلولان لاسم عرض. <br>• **verify-failure-rate**: نافذتان متتاليتان بنفس الطول عبر `countActionsInWindow` مرتين (الحالية مقابل السابقة مباشرة)؛ يُرفع تنبيه إذا (نسبة الفشل الحالية ≥ `khatm.stats.attention.verify-failure-multiplier`×الأساس، افتراضي 3) **و** (إجمالي محاولات التحقق بالنافذة الحالية ≥ `khatm.stats.attention.verify-min-volume`، افتراضي 5) — الشرط الثاني يمنع ضجيجاً من مقام شبه-صفري. <br>• ~~key-expiring~~ — **مُسقَط من هذه الجلسة (انظر D5)**. <br>محسوبة عند الطلب (on-read) لا Job مجدول — لوحة مشرف قليلة الحركة، بلا حاجة تخزين جديد | نفس ملاحظة البريف حرفياً: هذا قرار منتج بقدر ما هو هندسي؛ القيم أعلاه بداية معقولة معتمدة من مجد |
| D7 | **بوابات الأمان (`SecurityConfig`):** <br>• `/api/v1/stats/**` (كان `/api/v1/stats` تطابقاً حرفياً) — نفس `ScopeGuard.requireUserSession()` القائمة، تحقّقت لا مسار آخر يبدأ بـ `/api/v1/stats` يتأثر بالتوسعة. <br>• `/api/v1/activity`, `/api/v1/attention` — إدخالان جديدان، نفس `requireUserSession()` (أداة مشغّل كونسول، لا مفتاح API من أي نوع — نفس حكم `/api/v1/stats`/بحث الوثائق). <br>• `/api/v1/admin/signing-keys` — **بلا إدخال جديد**، يقع أصلاً تحت `ADMIN_PATH` (`/api/v1/admin/**` → `requireScope("admin")`) كما اقترح البريف | اتساق مع الحكم الموجود لكل نقطة قراءة تشغيلية مشابهة سابقاً (`/stats`, بحث الوثائق) |
| D8 | **شكل استجابة `/api/v1/admin/signing-keys`:** كما حدده البريف حرفياً — `{ "keys": [ {"kid","state","validFrom","validTo"} ] }`، يشمل **كل** الحالات (`PENDING/ACTIVE/RETIRING/RETIRED`) خلافاً لـ JWKS العام (`ACTIVE`+`RETIRING` فقط) | لا تصميم إضافي مطلوب — ميكانيكي بالكامل |
| D9 | **جلسة واحدة أم عدة؟** الجلسة تُبنى كـ WBS task واحدة (نفس سابقة KH-1.1.3-BE التي جمعت bulk-issue + stats + OpenAPI schemes في جلسة واحدة) رغم أنها 5 endpoints عبر 3 وحدات — لأن D2/D5 (التوسعتان العابرتان للوحدات) تُستخدَمان من أكثر من endpoint، ففصلها لجلسات منفصلة يكرر نفس التصميم. قابل لإعادة النظر إن فضّل مجد تقسيمها (مثلاً D5+#3+#4 في جلسة، البقية في أخرى) | يطابق نمط "brief الجلسة هو الـ spec" المعتمد فعلاً في KH-1.1.3-BE/KH-1.4.4-BE لمهام مركّبة مشابهة |

## 3. الشكل التنفيذي (لكل endpoint)

- **`GET /api/v1/stats/daily?from=&to=`** — `shared.web.DailyStatsController` (أو method
  إضافية على `StatsController` نفسها)، `AuditLogRepository` تكتسب استعلام
  `GROUP BY date_trunc('day', occurred_at), action` جديد بنفس فلتر tenant/window
  الموجود، `AuditService.dailyActionCounts(...)` جديدة تُرجع
  `Map<LocalDate, Map<String,Long>>` أو شكلاً مسطحاً مكافئاً.
- **`GET /api/v1/activity?limit=20&event=issued,consumed,revoked`** —
  `credential.web.ActivityController` جديد، يستدعي `AuditService` method جديدة
  تُرجع DTO عام جديد (مثلاً `shared.audit.AuditEventView`، `record` عام لأن
  `AuditLogEntry` نفسها module-private عمداً) — `ORDER BY occurred_at DESC
  LIMIT`، فلتر `action IN (:actions)` اختياري. `credential.web` يطبّق D2/D3/D4
  لتحويل كل صف لشكل جاهز للعرض قبل الإرجاع.
- **`GET /api/v1/attention`** — `credential.web.AttentionController` جديد،
  `credential.domain.AttentionService` module-private جديدة تجمع نوعين فقط (D6،
  بعد إسقاط عنصر التدوير في D5) عبر `AuditService`(الجديدة) وحدها — لا اعتماد
  على `key` في هذه الجلسة.
- **`GET /api/v1/admin/signing-keys`** — `key.web.SigningKeyStatusController`
  جديد يقرأ عبر method جديدة module-private على `KeyLifecycleService` (أو
  استعلام جديد على `IssuerKeyRepository` مباشرة) تُرجع كل الحالات (خلاف
  `publishableKeys` القائمة التي تُقصر على `ACTIVE`+`RETIRING`)، مطابقاً D8 —
  بالكامل داخل وحدة `key`، بلا أي سطح `api` جديد.
- **`GET /api/v1/stats/consuming-parties?from=&to=`** —
  `credential.web`(مكان مقترح، أو جزء من `ActivityController`)، استعلام جديد
  `AuditLogRepository` يجمّع `actor_id, action, COUNT(*)` لـ
  `CREDENTIAL_CONSUMED`/`CONSUME_SCHEMA_DENIED` ضمن نافذة، ثم D2+D4 لحل كل
  `actor_id` إلى اسم جهة وحساب نسبة النجاح.

## 4. معايير القبول (DoD)

1. الأربعة + الإضافية تعمل حياً عبر `docker compose` (بيانات حقيقية: إصدار،
   استهلاك، رفض schema، تحقق فاشل، تدوير مفتاح — كل واحدة تُغذّي endpoint واحداً
   على الأقل).
2. `ModulithBoundariesTest` أخضر — لا حافة اعتماد جديدة غير D2 (`rbac :: api`
   الجديدة) المعلنة صراحة في التوثيق؛ `key :: api` يبقى دون أي تغيير (D5).
3. `/api/v1/activity` يعرض اسم جهة مستهلكة صحيحاً لصف `CREDENTIAL_CONSUMED`
   حقيقي (لا `actor_id` خاماً)، ويعرض `ref` لا `id` لكل من `CONSUMED`/`REVOKED`.
4. `/api/v1/attention` لا يكرر أرقام `/api/v1/stats` — كل عنصر actionable
   (مرجع/طرف/سكيما) لا عدّاد مجرد؛ يشحن بعنصرين (schema-denied،
   verify-failure-rate) — لا عنصر تدوير مفتاح في هذه الجلسة (D5).
5. `/api/v1/admin/signing-keys` يعرض مفتاحاً `RETIRED` واحداً على الأقل في بيئة
   اختبار فيها تدوير (`KeyLifecycleService#rotate` مستدعى من test) — منفصل تماماً
   عن `#3`، بلا أي اعتماد بينهما.
6. المعتاد (قواعد العمل 1–4): Javadoc، مفاتيح EN/AR للنصوص البشرية الجديدة
   (إن وُجدت أخطاء جديدة)، `docs/api/openapi.json` مُجدَّد إضافياً فقط،
   Spotless/Checkstyle أخضر، `docs/STATE.md` مُحدَّث.
7. بوابة مراجعة عربية لأي `messageKey` جديد (إن استُحدث `ErrorCode` جديد — غير
   متوقَّع هنا لأن كل شيء قراءة ناجحة، لكن يُتحقَّق).

## 5. الأثر

- يفكّ حجب الشاشات الأربع في Dashboard v2 (الكونسول) — لا شيء آخر مطلوب هناك
  بعد `npm run contract:update`.
- `rbac :: api` يكتسب سطحاً جديداً ضيّقاً (D2، `ApiKeyOwnerLookup`) قابلاً
  لإعادة استخدام أي جلسة مستقبلية تحتاج نفس النوع من الحل (مثلاً KH-2.2 RBAC
  الكاملة). `key :: api` يبقى دون أي تغيير — عنصر "مفتاح يقترب من التدوير"
  يبقى عملاً مستقبلياً مفتوحاً (D5)، وليس جزءاً من هذه الجلسة.
- **لا شيء من هذا يُغلق نهائياً أي عائق قائم** (خلافاً لـ FS-1.2.1/FS-1.3) — هذه
  إضافة قراءة بحتة.

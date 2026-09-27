# SESSION-KH-2.8.2-BE — idempotency الإصدار + تسليم claim code عبر M2M + `holderRef` للقناة البشرية

> **Repo:** khatm-platform · **Spec:** FS-2.7a D5–D7 (+ errata §1 أدناه تُلتزم أولاً) · **يبني على:** PR #69 (KH-2.8.1-BE، `main == 4948e0f`)
> **المنفِّذ:** Claude Code · **المراجع/الدامج:** مجد · **PR يُفتح ولا يُدمج**
> **يحسم هنا (بقرار STATE):** فجوة الـ claim code في مسار M2M، وشكل `holderRef` للإصدار البشري (يؤثر على نطاق C13a)
> **Veto V1–V6: معتمدة بالافتراضيات كلها (مجد، 2026-09-27)** — §2 مرجع لا نقاش. V1 = (ب)، V2 = (أ)، V3 = الافتراضي.
> **اللغة:** سرد عربي، كود وعقود إنجليزية.

---

## 0. بوابات ما قبل البدء

1. `main` نظيف عند `4948e0f` أو بعده، صفر PRs مفتوحة، `mvn verify` أخضر 509/509.
2. `docs/STATE.md` يذكر تجديد توكن Vault حتى 2026-10-25 ✔ (لا chore معلّق).
3. **لا تنتظر C13a** — هذه الجلسة مستقلة عن الكونسول؛ لكن أي تغيير عقد هنا إضافي-فقط حتى تبنى C13a على العقد النهائي.
4. compose المحلي يقلع، `demo-m2m.sh` (PR #69) يعمل كما تُرك: هذا خط الأساس للجولة الحية.

## 1. Errata على FS-2.7a تُلتزم كأول commit مستقل (نمط A1)

تُضاف فقرة «Errata (2026-09-27, من KH-2.8.1)» إلى `docs/specs/FS-2.7a-issuer-m2m-foundation.md`:
- مسار الإصدار الفعلي `POST /api/v1/credentials/issue` (لا `/credentials`)؛ الـ bulk `POST /api/v1/credentials/bulk` مسار API فعلي وداخل النطاق.
- جسم المفتاح `khi_<10 base32>_<43 base64url>` (32 بايت مشفَّرة = 43 حرفاً)؛ مفاتيح الاستهلاك بادئتها `khk_` لا `khc_`.
- خطة الـ org مفتاحها `{tenantId}` لا `{slug}` (اتساقاً مع `/api/v1/org/**`).
- `key:manage` كان موجوداً ومزروعاً (V10) — لا migration بيانات.
- D7 كُتب على افتراض أن `/issue` يعيد `claimCode`؛ الواقع: يعيد `sdJwt`، والكود يُسكّ منفصلاً. **يُصحَّح بـ D-CC أدناه.**
- لا `VaultTemplate`؛ Vault عبر `RestClient` مباشر.

## 2. نقاط الـ veto (تُعتمد قبل البدء؛ الافتراضي يسري إن لم تُجَب)

| # | السؤال | الافتراضي | الأثر |
|---|---|---|---|
| **V1** | **`holderRef` للإصدار من جلسة بشر (القناة C — جهة غير مؤتمتة بلا نظام يحسب HMAC):** (أ) يبقى إلزامياً 64-hex ويُحسب خارج ختم؛ (ب) **اختياري لجلسات البشر**: إن غاب، تولّده المنصة عشوائياً (32 بايت `SecureRandom` → 64 hex) وتعيده في الاستجابة بحقل `holderRef`؛ إن وُجد يُتحقَّق من شكله. **لعملاء M2M يبقى إلزامياً** | **(ب)** | يقلّص C13a: الحقل يصير اختيارياً مع تلميح «اتركه فارغاً ليُولَّد»، وصفوف CSV بلا `pseudoRef` تعود تُصدَر (بمرجع عشوائي فريد لا `"holder-demo"` المشترك). القيمة العشوائية = «لا ربط بحامل» — وهذا مقبول للقناة C لأن الوثيقة تُعرض ولا تُستدعى بالحامل |
| **V2** | تسليم الكود في M2M: (أ) `mintClaimCode:boolean` على `IssueRequest` والاستجابة تحمل `claimCode`+`claimCodeExpiresAt` (مرآة `mintClaimCodes` في الـ bulk)؛ (ب) إضافة `POST /{id}/claim-code` لقائمة D4 | **(أ)** | (ب) يجعل «إصدار + تسليم» نداءَين غير أتوميين ولا يمكن جعلهما idempotent معاً |
| **V3** | replay في **وضع sdJwt المباشر** (بلا `mintClaimCode`): sdJwt لا يُخزَّن (P1) فلا يمكن إعادته. الافتراضي: الاستجابة تعيد البيانات الوصفية + `sdJwt: null` + `deliveryLost: true`؛ الـ connector يقرر (إبطال + إعادة إصدار بمفتاح جديد). البديل: سكّ claim code على الـ replay إن كانت الـ disclosures ما تزال متاحة (التحقيق §3.1 يحسم إمكانه) | **الافتراضي**، مع توثيق «connectors M2M SHOULD use `mintClaimCode`» في README والـ OpenAPI | البديل يُدرَس فقط إن أظهر التحقيق أن الـ disclosures تُحتفظ بعد الإصدار المباشر |
| **V4** | `Idempotency-Key` للـ bulk: مفتاح واحد للدفعة؛ الـ replay يعيد نفس `results[]` (index/status/id/ref/error) من لقطة مخزَّنة **بلا أكواد**، ثم يُعاد سكّ الكود لكل صف لم يُطالَب به | نعم | اللقطة لا تحوي PII (ids/refs/أكواد أخطاء فقط) |
| **V5** | مدة الاحتفاظ بصفوف idempotency 30 يوماً، تنظيف بـ worker دوري (ADR-09) | نعم | — |
| **V6** | `Idempotency-Key` لجلسات البشر: يُكرَّم إن أُرسل، غير إلزامي (الكونسول لا يرسله) | يُكرَّم | — |

## 3. مرحلة التحقيق (قراءة فقط، `investigation.md` مؤقت)

### 3.1 دورة حياة الـ disclosures — **السؤال الحاسم لـ V3**
- كيف يحصل `CredentialService#mintClaimCode` على الـ disclosures؟ من الـ transaction نفسها (الـ bulk يستدعيه فور `issue`)؟ من صف `claim_code` قائم (`disclosures_enc`) بالنسخ؟ أم يُعاد بناؤها (غير ممكن — الملح عشوائي)؟
- ماذا يفعل `POST /credentials/{id}/claim-code` (الكونسول) لوثيقة أُصدرت بوضع sdJwt المباشر ولا صف `claim_code` لها؟ إن كان يفشل/يستحيل → V3 الافتراضي مؤكَّد.
- هل يمكن لصف `claim_code` واحد أن يُعاد تعيينه (كود جديد، `code_hash` جديد، `expires_at` ممدَّد) دون المسّ بـ `disclosures_enc`؟ قيود الجدول وسلوك `ClaimCodeExpiryWorker` (يصفّر عند الانتهاء — يجب ألا يصفّر صفاً مُدَّد أجله).

### 3.2 شكل الإصدار الحالي
- `IssueRequest`/`IssueResponse` (PR #69: `claimed`, `issuerClientId`)؛ `BulkIssueRequest.mintClaimCodes` وشكل `results[]`.
- هل `CredentialService#issue` `@Transactional`؟ حدود الـ transaction تحدد أين يُدرَج صف الـ idempotency (D6: إدراج-أولاً في نفس الـ transaction).
- `Clock` bean (PR #69) — يُستخدم لـ `expires_at`.

### 3.3 مسار `holderRef` (V1)
- أين يعيش الحارس الشكلي `^[0-9a-f]{64}$` (PR #69)؟ كيف يُميَّز الـ principal (بشر vs `IssuerClientPrincipal`) في نقطة التحقق؟ Bean Validation على الـ DTO أم في الخدمة؟ (إن كان `@Pattern` على الـ DTO فلا يمكن جعله شرطياً — ينتقل إلى الخدمة.)
- `BulkIssuanceService`: كيف يمر `pseudoRef` لكل صف؟

### 3.4 آخر migration: V18 → الجديدة V19.

**بوابة نهاية التحقيق:** إن تبيّن أن إعادة تعيين صف `claim_code` تصطدم بقيد يمنعها، أو أن الـ disclosures لا تعيش خارج transaction الإصدار بأي شكل (فيستحيل حتى وضع `mintClaimCode` على الـ replay) → **توقف وأبلغ** قبل أي كود.

## 4. التنفيذ (بالترتيب)

### 4.1 `V19__issuance_idempotency.sql` (إضافية-فقط)
```sql
CREATE TABLE issuance_idempotency (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL REFERENCES tenant(id),
  issuer_client_id uuid NULL REFERENCES issuer_client(id),   -- NULL = جلسة بشر
  idempotency_key  text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
  scope            text NOT NULL CHECK (scope IN ('ISSUE','BULK')),
  request_hash     bytea NOT NULL,                            -- SHA-256(canonical JSON body)
  state            text NOT NULL CHECK (state IN ('IN_PROGRESS','DONE')),
  credential_id    uuid NULL REFERENCES credential(id),       -- ISSUE
  result_snapshot  jsonb NULL,                                -- BULK: results بلا أكواد
  created_at       timestamptz NOT NULL DEFAULT now(),
  expires_at       timestamptz NOT NULL
);
CREATE UNIQUE INDEX issuance_idem_key
  ON issuance_idempotency (tenant_id, COALESCE(issuer_client_id, '00000000-0000-0000-0000-000000000000'::uuid), scope, idempotency_key);
-- RLS بالمساواة الصارمة + فهرس (expires_at) للـ sweeper
```

### 4.2 `credential.domain.IssuanceIdempotencyGuard`
- يُستدعى من `CredentialService#issue` و`BulkIssuanceService#bulkIssue` **قبل** أي عمل: يقرأ الـ header من `IssuanceContext` (يُملأ في الـ controller؛ لا `HttpServletRequest` داخل الـ domain).
- `IssuerClientPrincipal` بلا header → `400 KH-IDEM-0400`. جلسة بشر بلا header → لا idempotency (سلوك اليوم).
- مسار جديد: `INSERT ... IN_PROGRESS` ثم الإصدار ثم `UPDATE ... DONE, credential_id/result_snapshot` في **نفس الـ transaction**؛ فشل الإصدار = rollback الصف. السباق يُحسم بالفهرس الفريد: الخاسر يلتقط `DataIntegrityViolationException` على connection نظيف (نمط `ConsumingPartyRegistryService#ensure`) ثم يقرأ صف الفائز:
  - `IN_PROGRESS` → `409 KH-IDEM-0409` (+ `Retry-After: 2`).
  - `DONE` + hash مختلف → `422 KH-IDEM-0422`.
  - `DONE` + hash مطابق → **replay** (4.3).
- canonical JSON: Jackson مع `ORDER_MAP_ENTRIES_BY_KEYS` + إزالة `null`؛ الـ hash على البايتات الناتجة. مفتاح الـ `Idempotency-Key` لا يدخل الـ hash.

### 4.3 دلالة الـ replay (D7 مُصحَّح بـ V2/V3)
- header `Idempotent-Replayed: true` على كل استجابة replay، HTTP 200 (لا 201).
- **ISSUE بوضع `mintClaimCode:true`:** إن `claim_code.claimed_at IS NULL` → إعادة تعيين الصف نفسه ذرّياً (`SELECT ... FOR UPDATE`، نفس قفل `ClaimRedemptionService#redeem` ليأمن السباق مع الـ redeem والـ sweeper): كود جديد، `code_hash` جديد، `expires_at = now()+TTL`؛ `disclosures_enc` كما هو؛ الاستجابة تحمل الكود الجديد + `claimCodeReissued: true`. إن `claimed_at IS NOT NULL` → بلا `claimCode`, `claimed: true`.
- **ISSUE بوضع sdJwt المباشر (V3):** `sdJwt: null`, `deliveryLost: true`, `claimed: false`.
- **BULK (V4):** `results[]` من اللقطة؛ لكل صف `SUCCESS` مع `mintClaimCodes` → نفس منطق إعادة التعيين لكل صف.
- كل replay يكتب `ISSUANCE_REPLAYED` (detail: `sha256(idempotencyKey)`, `scope`, `claimCodeReissued`, `deliveryLost`).

### 4.4 D-CC — `mintClaimCode` على الإصدار المفرد (V2)
- `IssueRequest.mintClaimCode: Boolean` (افتراضي `false`؛ إضافي). عند `true`: سكّ الكود **داخل transaction الإصدار** (نفس ما يفعله الـ bulk)؛ `IssueResponse` يكسب `claimCode`, `claimCodeExpiresAt` (nullable)، ويعطي `claimed` معناه الفعلي (`claim_code.claimed_at IS NOT NULL`) في كل الاستجابات التي تعيد `IssueResponse`.
- إن كان `mintClaimCode:true` لا يكون هناك سبب لإعادة `sdJwt` للمُصدِر؟ **قرار:** يُعاد كما هو اليوم (المُصدِر قد يطبع QR ويسلّم كوداً معاً)؛ لا تغيير في P1 — التسليم عابر.
- `POST /{id}/claim-code` يبقى **خارج** قائمة D4 (V2-ب مرفوض).

### 4.5 V1 — `holderRef` اختياري لجلسات البشر
- في الخدمة (لا على الـ DTO): إن كان الـ principal بشرياً و`holderRef` فارغ → `HolderRefs.random()` (32 بايت `SecureRandom` → hex صغير) ويُستقبل في `IssueResponse.holderRef` (حقل جديد، يُعاد دائماً لأي إصدار — للـ M2M يعكس المُرسَل). إن كان الـ principal `IssuerClientPrincipal` وفارغ → `400 KH-ISS-0400` كما اليوم.
- `BulkIssuanceService`: نفس القاعدة لكل صف؛ `results[i]` يكسب `holderRef`.
- الحارس الشكلي على القيمة المُقدَّمة يبقى عاماً بلا تغيير.
- **إن اختار مجد V1-(أ):** تُحذف هذه الخطوة كلها ويبقى C13a كما صاغه Claude Code.

### 4.6 `IdempotencyRetentionSweeper` (worker role، ADR-09)
- `DELETE WHERE expires_at < now()` كل ساعة؛ `expires_at = created_at + khatm.issuance.idempotency.retention` (افتراضي `P30D`). audit غير مطلوب (تنظيف).

### 4.7 عقد وأخطاء وتوثيق
- `openapi.json` إضافي-فقط: header `Idempotency-Key` (موثَّق كـ parameter على `/issue` و`/bulk`)، `Idempotent-Replayed`/`Retry-After` كـ response headers، الحقول الجديدة. **operationId صريح** على أي عملية جديدة (درس PR #69).
- `docs/error-codes.md`: `KH-IDEM-0400`, `KH-IDEM-0409`, `KH-IDEM-0422` (+ رسائل ar/en — **بوابة العربية**).
- README `credential/`: قسم Idempotency + قسم Delivery modes (claim code vs direct) + توصية M2M.
- `scripts/demo-m2m.sh`: يتحول إلى `mintClaimCode:true` على `/issue` (بدل الـ bulk الحالي) + سيناريو replay.

## 5. الاختبارات الإلزامية

1. `MigrationImmutabilityTest`/`MigrationCleanBootTest` أخضران؛ RLS على الجدول الجديد (يُضاف لحزمة `CrossTenantIsolationTest`: مفتاح مستأجر A لا يرى/لا يصطدم بصف مستأجر B بنفس `Idempotency-Key`).
2. M2M بلا header → `KH-IDEM-0400`؛ جلسة بشر بلا header → 201 عادي.
3. **التنافس:** 20 خيطاً، نفس المفتاح والجسم، `mintClaimCode:true` → وثيقة واحدة، صف `claim_code` واحد، 1×201 والباقي 200-replay أو 409→200 عند الإعادة؛ في النهاية كود واحد صالح فقط.
4. replay قبل المطالبة → كود جديد يعمل عبر `/claims/redeem`، القديم `KH-CLM-0404`؛ `claimCodeReissued:true`; `disclosures_enc` غير متغير (bytes-equal).
5. replay بعد المطالبة → `claimed:true` بلا كود؛ `disclosures_enc` صُفِّر (لم يُلمس).
6. replay بعد انتهاء الكود لكن قبل التصفير → كود جديد وتمديد؛ **سباق sweeper**: sweeper وreplay متزامنان → لا يصفَّر صف مُدَّد (اختبار بـ `Clock` مُحقَن).
7. hash مختلف → `KH-IDEM-0422`؛ hash متطابق مع ترتيب مفاتيح JSON مختلف وحقول `null` زائدة → replay (canonicalization).
8. وضع مباشر replay → `sdJwt:null`, `deliveryLost:true`، صف audit `ISSUANCE_REPLAYED` بـ `deliveryLost:true`.
9. bulk: نفس المفتاح → نفس `results[]` (indices/ids/refs/errors) + أكواد جديدة للصفوف غير المُطالَب بها؛ لقطة `result_snapshot` لا تحوي أي كود (اختبار نصي).
10. V1: جلسة بشر بلا `holderRef` → 201 و`holderRef` 64-hex في الاستجابة ومخزَّن؛ M2M بلا `holderRef` → `KH-ISS-0400`؛ قيمة مقدَّمة غير صالحة → `KH-ISS-0400` للطرفين؛ bulk صف بلا `pseudoRef` من جلسة بشر → يُصدَر بمرجع فريد لكل صف (لا تكرار بين الصفوف).
11. `mintClaimCode:true` عبر M2M → 201 مع `claimCode`؛ `POST /{id}/claim-code` بمفتاح M2M → `KH-AUTH-0403` (اختبار الجدول القائم يلتقطه تلقائياً).
12. sweeper يحذف الصفوف المنتهية فقط.
13. لا مفتاح idempotency خام ولا كود ولا `holderRef` في اللوغات (`ListAppender`).
14. `ModulithBoundariesTest` + اختبار حصر القائمة العامة + اختبار جدول النطاق (PR #69) أخضران بلا تغيير في عددها.

## 6. `[MAJD]` — الجولة الحية على compose

Claude Code يحدّث `scripts/demo-m2m.sh` ويتوقف. مجد:
1. `[MAJD]` إصدار M2M بـ `mintClaimCode:true` و`Idempotency-Key: demo-001` → 201، كود.
2. `[MAJD]` كرّر نفس الطلب حرفياً → 200 + `Idempotent-Replayed: true` + كود **مختلف**؛ امسح الكود **القديم** بالمحفظة → رفض؛ امسح الجديد → الوثيقة تظهر.
3. `[MAJD]` كرّر مرة ثالثة → `claimed:true` بلا كود.
4. `[MAJD]` نفس المفتاح بجسم مختلف → `KH-IDEM-0422`؛ بلا header → `KH-IDEM-0400`.
5. `[MAJD]` (إن V1-ب) من الكونسول: إصدار مُصادَق **بحقل `holderRef` فارغاً** عبر `curl` بجلسة (الكونسول نفسه سيرفضه حتى C13a) → 201 و`holderRef` مولَّد.
6. `[MAJD]` `docker compose logs api | grep -E "demo-001|khi_"` → لا شيء غير البادئات.

## 7. DoD

- [ ] commit الـ errata مستقل وأول.
- [ ] V19 إضافية-فقط؛ `openapi.json` إضافي-فقط؛ operationIds صريحة.
- [ ] الاختبارات 1–14 خضراء؛ العدّ > 509.
- [ ] بوابة العربية: 3 مفاتيح `KH-IDEM-*` مراجَعة من مجد في تعليق الـ PR.
- [ ] README/error-codes/STATE محدَّثة؛ STATE يسجّل قرارات V1–V6 كما اعتُمدت و**أثر V1 على C13a** صريحاً.
- [ ] الجولة §6 موقَّعة.

## 8. خارج النطاق

- شاشة الكونسول `/clients` (C13) وتعديلات C13a — repo آخر.
- قراءة `platform:admin` العابرة للمستأجرين لعملاء الإصدار (بند مفتوح من PR #69، يُضم إلى C13 لأنه احتياج شاشة).
- خطوات KV على staging (تشغيل، عند النشر).
- إصلاح `SoftKeyProvider` CN-length قبل رفع BouncyCastle (chore مستقل).
- الـ connector المرجعي والمحاكيات (FS-2.7b).

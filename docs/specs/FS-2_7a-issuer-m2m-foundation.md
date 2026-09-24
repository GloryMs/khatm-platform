# FS-2.7a — Issuer M2M Foundation (مصادقة الجهات المُصدِرة + idempotency الإصدار)

> **Tasks:** KH-2.8.1 (platform: `issuer_client` + auth) · KH-2.8.2 (platform: idempotency) · C13 (console) — انظر V0 لترقيم المهام
> **Repos:** khatm-platform · khatm-console · khatm-docs
> **Status:** APPROVED — 2026-09-10 (V0–V6 بالافتراضيات كلها، قرار مجد)
> **Sources of truth:** SEC 21 §7 (Issuer systems: tenant API key, hashed, prefix-identifiable, rotatable) · §5 (tenant context from principal only) · §9 (no secrets in logs) · SAD 20 §5.1 (automated issuance) · §5.3 (idempotency pattern: persistent unique index) · FS-0.2 §3.7 (`claim_code`) / §3.8 (`consuming_party` كنموذج) / §3.11 (`audit_log`) / D9 (P1) · KH-2.6 (`OnBehalfOfExecutor`) · STATE-central-2026-08-23 §التالي-2 (الحاجبان المطلقان) · قرارات مجد 2026-09-10 (الافتراضيات الأربعة + repo المحاكيات)
> **اللغة:** شرح عربي، عقود وكود إنجليزية.

---

## 1. الهدف والنطاق

كل الإصدار في ختم اليوم يمرّ عبر جلسات بشر في الكونسول. هذا الـ spec يفتح **القناة A**
(M2M) بأقل سطح ممكن وبأعلى انضباط: كيان `issuer_client` بمفتاح API مُهشَّر قابل
للتعريف بالبادئة وللتدوير، نطاق `issue` حصراً، و**idempotency مثابرة على الإصدار**
بحيث تكون إعادة محاولة أي connector آمنة بالبناء. الكونسول يمنح `tenant:admin`
إدارة عملاء مستأجره دون أن نكون نحن عنق الزجاجة.

ما يُبنى هنا هو **عقد الدخول** الذي ستستخدمه كل الـ connectors اللاحقة (FS-2.7b/c)
وكل المحاكيات في repo المحاكيات المستقل. لا شيء هنا يعرف بأي نظام خارجي.

**داخل النطاق:** `issuer_client` + N–N مخططات مسموحة · مصادقة API key على
`/api/v1/credentials` (مفرد + bulk إن وُجد مسار API له) · نطاق `issue` · idempotency
مثابرة مع إعادة إصدار `claim_code` عند الـ replay · إجراءات audit · أكواد أخطاء ·
شاشة إدارة العملاء في الكونسول · سر HMAC للحامل على مستوى المستأجر (V3).

**خارج النطاق (صريحاً):** الـ Sidecar Connector المرجعي وجدول outbox (FS-2.7b) ·
المحاكيات (repo مستقل، FS-2.7b/c) · نطاق `revoke` عبر M2M (إضافة لاحقة، لا يغيّر
الشكل) · OAuth2 client credentials (Phase 2 حسب SEC §7؛ D2 يضمن أنها إضافة) ·
rate limiting لكل عميل (KH-2.5) · mTLS · IP allow-list (تُسجَّل كأفكار، لا تُبنى).

## 2. القرارات D1–D12

| # | القرار | التبرير |
|---|---|---|
| D1 | **جدول `issuer_client`** (migration إضافية-فقط `V{next}__issuer_client.sql`) بنفس روح `consuming_party`: `id`, `tenant_id`, `name_i18n` (ar+en CHECK), `key_prefix text NOT NULL UNIQUE`, `api_key_hash bytea NOT NULL`, `scopes text[] NOT NULL` (v1: `{issue}` فقط، CHECK يحصرها في القائمة المسموحة), `status` (`ACTIVE`/`SUSPENDED`/`RETIRING`/`REVOKED`), `rotated_from uuid NULL REFERENCES issuer_client(id)`, `retire_after timestamptz NULL`, `expires_at timestamptz NULL`, `last_used_at timestamptz NULL`, `created_by uuid`, `created_at`. جدول `issuer_client_schema` N–N مطابق لـ `consuming_party_schema`. سياسة RLS بالمساواة الصارمة كباقي الجداول (لا استثناء) | نفس النمط الذي أثبت نفسه في KH-1.4.3؛ الـ prefix يمنع مسح hash على كل الصفوف؛ `rotated_from`/`retire_after` يعطيان تدويراً بلا انقطاع |
| D2 | **شكل المفتاح:** `khi_<prefix:10 chars base32>_<secret:32 bytes base64url>`. يُولَّد من `SecureRandom`، يُعرض **مرة واحدة** في استجابة الإنشاء/التدوير ثم لا يعود قابلاً للاسترجاع. التخزين: `key_prefix` صريح للبحث، `api_key_hash` = argon2id بمعاملات كلمات المرور القائمة على الـ secret فقط. المصادقة: `Authorization: Bearer khi_...` — نفس آلية KH-1.4.3 إن كانت header واحداً (مرحلة التحقيق تحسم؛ إن اختلفت، يُوحَّد على `Bearer` والقديم يبقى مقبولاً) | البادئة `khi_` تميّز مفاتيح الإصدار عن مفاتيح الاستهلاك في اللوغات والدعم دون كشف السر؛ Bearer يجعل الانتقال إلى OAuth2 لاحقاً تغييراً في طريقة الحصول على الرمز لا في طريقة إرساله |
| D3 | **Principal جديد `IssuerClientPrincipal`** (`clientId`, `tenantId`, `scopes`) يُبنى في فلتر المصادقة ويضبط سياق المستأجر **منه حصراً** — لا `tenantId` في جسم الطلب ولا في المسار. `actor_type='API_KEY'`, `actor_id=client.id` في كل صف audit ينتج عن الطلب | SEC §5: «Tenant context resolved from authenticated principal only — never from request body» |
| D4 | **النطاق `issue` هو المسموح الوحيد لهذا الـ principal**: `POST /api/v1/credentials` (+ مسار الـ bulk إن كان API لا كونسولاً فقط — التحقيق يحسم). أي مسار آخر → `403 KH-AUTH-0403` حتى لو كان النطاق منطقياً «قريباً» (قراءة الوثيقة `GET /credentials/{id}` **مسموحة** لعميل أصدرها — التحقق من `issuer_client_id` على الوثيقة، انظر D6). `revoke` عبر M2M يُضاف لاحقاً بقيمة جديدة في `scopes` لا بتغيير شكل | deny-by-default (SEC §7)؛ القراءة مطلوبة للـ connector ليتحقق من نتيجة replay (D7) |
| D5 | **`Idempotency-Key` header إلزامي** لأي طلب إصدار من `IssuerClientPrincipal` (غياب → `400 KH-IDEM-0400`)؛ **اختياري ويُكرَّم** لطلبات جلسات المستخدمين (الكونسول لا يرسله اليوم؛ لا كسر). طول 1–128، ASCII مطبوع | الـ connector هو من يعيد المحاولة؛ البشر يرون النتيجة بأعينهم |
| D6 | **جدول `issuance_idempotency`**: `tenant_id`, `issuer_client_id NULL` (NULL لجلسات البشر), `idempotency_key text`, `request_hash bytea` (SHA-256 لجسم الطلب بعد canonical JSON), `credential_id uuid NULL`, `state` (`IN_PROGRESS`/`DONE`), `created_at`, `expires_at` (= created + 30d). `UNIQUE (tenant_id, COALESCE(issuer_client_id, uuid_nil()), idempotency_key)`. **الإدراج يسبق الإصدار** في نفس الـ transaction: إدراج `IN_PROGRESS` → إصدار → تحديث `credential_id`+`DONE` → commit. فشل الإصدار = rollback للصف كله. عمود `issuer_client_id uuid NULL` يُضاف إلى `credential` (إضافي-فقط) | إدراج-أولاً + قيد unique يجعل السباق يُحسم في القاعدة لا في الكود (نفس درس `ConcurrentConsumeTest`)؛ لا Redis: حجم الإصدار لا يبرّر مساراً سريعاً وNFR-01 غير مهدَّد؛ ربط الوثيقة بعميلها يخدم D4 والتقارير |
| D7 | **دلالة الـ replay (P1-safe):** طلب بمفتاح موجود + `request_hash` مطابق + `DONE` → `200` بنفس شكل استجابة الإصدار مع header `Idempotent-Replayed: true`. لأن `claim_code` لا يُخزَّن (hash فقط) فلا يمكن إعادة إرسال الكود الأصلي؛ بدلاً من ذلك: **إن لم يُطالَب بالوثيقة بعد** (`claimed_at IS NULL`) يُعاد تعيين صف `claim_code` نفسه ذرّياً (كود جديد، `code_hash` جديد، `expires_at` ممدَّد؛ `disclosures_enc` كما هو) ويُرجع الكود الجديد — الكود القديم يموت بذلك؛ **إن كانت مُطالَباً بها** يُرجع الجسم بلا `claimCode` ومع `claimed: true`. مفتاح موجود + hash مختلف → `422 KH-IDEM-0422`. مفتاح موجود + `IN_PROGRESS` → `409 KH-IDEM-0409` (العميل يعيد بعد قليل) | المواطن يحصل على كود صالح مهما انقطع الاتصال بين ختم والـ connector، وختم لا يخزّن أي كود بنص صريح؛ الكود المميت لا يُسرَّب لأنه لم يصل لأحد أصلاً |
| D8 | **`holderRef` عقد شكلي فقط:** 64 hex (SHA-256 HMAC) يتحقق ختم من شكله لا من معناه. الرقم الوطني لا يظهر في أي عقد، وأي طلب يحوي حقلاً باسم `nationalId`/`nid`/`ssn` في `claims` **يُرفض** `400 KH-ISS-0400` بقائمة أسماء محظورة قابلة للتهيئة (`khatm.issuance.forbidden-claim-names`) | «National ID never enters Khatm — HMAC happens at the connector»؛ الرفض الشكلي شبكة أمان رخيصة ضد connector مكتوب على عجل |
| D9 | **سر HMAC الحامل على مستوى المستأجر (قرار مجد):** يُولَّد (32 byte) عند إنشاء **أول** `issuer_client` للمستأجر الجذري إدارياً (الوزارة في هرمية KH-2.6)، ويُخزَّن في Vault **KV v2** تحت `khatm/tenants/{root-slug}/holder-hmac` (لا في PostgreSQL)، ويُعرض مرة واحدة مع المفتاح. المستأجرون الأبناء يرثونه (نفس المسار) ولا يولَّد لهم سر مستقل. ختم **لا يستخدمه في أي حساب** — هو للـ connectors فقط؛ دوره في ختم: مرجعية واحدة للتدوير والاسترجاع. إن لم يكن KV مفعَّلاً في Vault staging → V3 يقرّر | يحقق الافتراضي المعتمد (سر لكل مستأجر، وراثة من الجذر الإداري)؛ Vault هو موطن الأسرار المعتمد؛ عدم استخدام ختم له يحفظ خاصية «لا نستطيع ربط أحمد عبر جهتين» |
| D10 | **الكونسول (C13):** شاشة `/clients` لـ `tenant:admin` بشرط نطاق **`key:manage`**: قائمة (name, `key_prefix`, status, scopes, allowed schemas, `last_used_at`)، إنشاء (المفتاح يظهر مرة واحدة بزر نسخ وتحذير عربي/إنجليزي)، تدوير (عميل جديد بـ `rotated_from`، القديم → `RETIRING` مع `retire_after = now()+24h` افتراضياً قابل للتعديل 0–72h؛ worker دوري يحوّل `RETIRING` المنتهي إلى `REVOKED`)، تعليق/استئناف، إبطال (نهائي). `platform:admin` قراءة عبر المستأجرين (بلا أزرار كتابة). الأب في الهرمية يدير عملاء أبنائه عبر `OnBehalfOfExecutor#runAsChildOrg` القائم — **لا نمط موازٍ** | يحقق الافتراضي (أ)؛ الشرط `key:manage` لا `tenant:admin` وحده لأن المفتاح مادة توقيع فعلياً؛ التدوير بنافذة سماح يمنع انقطاع الـ connector |
| D11 | **Audit** (append-only كالعادة): `ISSUER_CLIENT_CREATED`/`_ROTATED`/`_SUSPENDED`/`_RESUMED`/`_REVOKED` (actor USER, entity_ref = `key_prefix`) · `ISSUER_CLIENT_AUTH_FAILED` (actor SYSTEM, detail = `key_prefix` إن وُجد + سبب؛ **مقنَّن**: صف واحد لكل prefix كل 60s) · `CREDENTIAL_ISSUED` بـ actor `API_KEY` · `ISSUANCE_REPLAYED` (detail: idempotency_key hash فقط, `claimCodeReissued: bool`) · `HOLDER_SECRET_GENERATED` (root slug فقط). **لا مفتاح ولا سر ولا `holderRef` في أي صف audit أو لوغ** | NFR-08؛ التقنين يمنع تحويل الـ audit إلى أداة DoS |
| D12 | **أكواد الأخطاء** (تُسجَّل في `docs/error-codes.md`): `KH-AUTH-0401` مفتاح غير معروف/غير صالح · `KH-AUTH-0403` نطاق غير مسموح · `KH-ICL-0409` عميل معلَّق/مُبطَل (يُميَّز داخلياً، ويُرجع للخارج نفس الجسم لمنع enumeration الحالة) · `KH-IDEM-0400` · `KH-IDEM-0409` · `KH-IDEM-0422` · `KH-ISS-0400` (claim محظور). الغلاف الموحَّد القائم (KH-1.6.3). **لا lockout تلقائي** لعميل M2M بعد فشل متكرر — تنبيه فقط (KH-2.5) | lockout على M2M يسمح لأي طرف يعرف البادئة بإيقاف جهة حكومية عن الإصدار |

## 3. العقود (إنجليزية، إضافية-فقط)

### 3.1 الإصدار عبر M2M
```
POST /api/v1/credentials
Authorization: Bearer khi_XXXXXXXXXX_<secret>
Idempotency-Key: univ-2026-grad-000123        # required for API_KEY principals
Content-Type: application/json

{ "schemaId": "...", "holderRef": "<64 hex>", "claims": {...},
  "maxUses": 1, "validFrom": "...", "validTo": "...", "sdFields": [...] }   # unchanged
```
استجابة `201` كما هي اليوم؛ **حقول إضافية:** `claimed: false`, `issuerClientId`.
replay → `200` + `Idempotent-Replayed: true` + نفس الشكل (D7).

### 3.2 إدارة العملاء (جلسات الكونسول)
```
GET    /api/v1/issuer-clients                      key:manage | platform:admin (read)
POST   /api/v1/issuer-clients                      key:manage
       { "name": {"ar":"..","en":".."}, "allowedSchemaIds":[...], "expiresAt": null }
       → 201 { "id", "keyPrefix", "apiKey": "khi_..."  (once), "holderHmacSecret": "..." (once, first client of root only) }
POST   /api/v1/issuer-clients/{id}/rotate          key:manage
       { "retireAfterHours": 24 }  → 201 { new client as above, "retiringClientId" }
POST   /api/v1/issuer-clients/{id}/suspend | /resume | /revoke   key:manage
GET    /api/v1/org/children/{slug}/issuer-clients  (KH-2.6 pattern, parent tenants)
```
`MeResponse` يُضاف إليه `scopes` إن لم يكن موجوداً (للكونسول ليخفي الشاشة بلا `key:manage`).

### 3.3 التغييرات على القاعدة
`V{next}__issuer_client.sql`: الجدولان في D1 + `issuance_idempotency` (D6) + `ALTER TABLE credential ADD COLUMN issuer_client_id uuid NULL REFERENCES issuer_client(id)` + سياسات RLS الثلاث + فهارس `(tenant_id, status)`, `(key_prefix)`. `MigrationImmutabilityTest` يبقى أخضر.

## 4. الشكل التنفيذي

- **وحدة Modulith جديدة `issuerclient/`** (top-level، بجانب `consumer/`): `domain`
  (كيانات، `ApiKeyGenerator`, `IssuerClientLifecycle`, worker `RetiringSweeper` وفق
  ADR-09)، `api` (`@NamedInterface`: `IssuerClientAuthenticator` +
  `IssuerClientPrincipal` فقط)، `web` (controllers §3.2). وحدة `credential` لا ترى
  الجدول — تستقبل الـ principal من سياق الأمان كما تستقبل مستخدم الجلسة.
- **الفلتر** يوضع في `shared/security` بجانب فلتر KH-1.4.3، ويُقرَّر في التحقيق: فلتر
  واحد يميّز بالبادئة (`khc_`/`khi_`) أم فلتران. **الافتراضي: فلتر واحد** إن كانت
  بنية KH-1.4.3 تسمح بلا إعادة كتابة.
- **الـ idempotency داخل `credential/domain`** (`IssuanceIdempotencyGuard`) لأنها
  خاصية الإصدار لا خاصية العميل؛ إعادة تعيين `claim_code` تمر عبر الواجهة المسماة
  القائمة للـ claim.
- **الحامل:** `HolderSecretProvisioner` في `issuerclient/domain` يكتب إلى Vault KV
  عبر `VaultTemplate` القائم؛ مسار KV من config
  `khatm.issuer.holder-secret.kv-path` (افتراضي `khatm/tenants`).
- config: `khatm.issuance.idempotency.retention=30d`,
  `khatm.issuer.rotation.default-retire-hours=24`,
  `khatm.issuance.forbidden-claim-names=nationalId,nid,national_id,ssn,passportNo`.
- **الكونسول:** contract re-vendor (بوابة CI للحداثة) → شاشة `/clients` → مراجعة
  RTL من مجد (بوابة دمج صلبة كالعادة) — نص «المفتاح يظهر مرة واحدة» بالعربية أولاً.

## 5. الجلسات والتقدير

| جلسة | المحتوى | تقدير |
|---|---|---|
| **KH-2.8.1-BE** | D1–D4, D8–D9, D11–D12، الفلتر، `issuerclient/`، اختبارات العزل والنطاق | 4–5 أيام |
| **KH-2.8.2-BE** | D5–D7، `issuance_idempotency`، إعادة تعيين `claim_code`، اختبار التنافس | 2–3 أيام |
| **C13** (كونسول) | D10، re-vendor، RTL | 2–3 أيام |
| docs | هذا الـ spec APPROVED + error-codes + README الوحدة + تحديث STATE | 0.5 |

المجموع ~9–11 يوماً (أعلى قليلاً من تقدير STATE 5–7 لأنه لم يشمل الكونسول وسر الحامل).
ترتيب التنفيذ: 2.8.1 ← 2.8.2 ← C13. **تجديد توكن Vault (chore، قبل 2026-09-16) جلسة مستقلة تسبق 2.8.1.**

## 6. معايير القبول (DoD)

1. إنشاء عميل من الكونسول ← مفتاح يظهر مرة واحدة ← `GET /issuer-clients` يُظهر
   `key_prefix` ولا يُظهر أي شكل من السر؛ لا صف في القاعدة يحوي المفتاح (اختبار يمسح
   dump الجداول بالبادئة `khi_`).
2. `POST /credentials` بمفتاح صالح + `Idempotency-Key` → 201، الوثيقة تحمل
   `issuer_client_id`، صف audit بـ `API_KEY`. بلا header → `KH-IDEM-0400`.
3. **اختبار العزل (NFR-07):** مفتاح مستأجر A على schema مستأجر B → 404/403 صفر
   صفوف؛ يُضاف إلى حزمة الاختبار العابر للمستأجرين القائمة تلقائياً.
4. **اختبار النطاق:** المفتاح على `/revoke`, `/consume`, `/issuer-clients`,
   `/admin/**` → `KH-AUTH-0403` جميعها (اختبار جدولي على كل المسارات المسجَّلة).
5. **اختبار التنافس:** 20 خيطاً بنفس `Idempotency-Key` ونفس الجسم → وثيقة واحدة
   بالضبط، 1×201 و19×(200 replay أو 409 ثم 200 عند الإعادة).
6. **اختبار replay بشقيه:** قبل المطالبة → كود جديد يعمل والقديم يُرفض؛ بعد المطالبة
   → `claimed:true` بلا `claimCode`. hash مختلف → `KH-IDEM-0422`.
7. تدوير: القديم يعمل حتى `retire_after` ثم يُرفض بعده (اختبار بزمن مُحقَن)؛ الجديد
   يعمل فوراً؛ `rotated_from` مضبوط؛ الـ sweeper يحوّل الحالة.
8. عميل معلَّق/مُبطَل → نفس جسم الرفض الخارجي (anti-enumeration)، audit يميّز.
9. `claims` يحوي اسماً محظوراً → `KH-ISS-0400`؛ `holderRef` غير 64-hex → 400.
10. سر الحامل: أول عميل للجذر يولّده في Vault KV ويُعرض مرة؛ عميل ثانٍ أو عميل لابن
    لا يولّد ولا يعرض؛ audit `HOLDER_SECRET_GENERATED` مرة واحدة.
11. الأب يدير عملاء الابن عبر `/org/children/{slug}/issuer-clients` وكل رفض يهبط في
    `KH-ORG-0404` الموحَّد (النمط القائم).
12. migration إضافية-فقط، `openapi.json` إضافي-فقط، الكونسول يمر بوابة الحداثة،
    RLS بالمساواة على الجداول الثلاثة، لا سر/مفتاح/`holderRef` في اللوغات (اختبار
    اعتراض لوغ)، CI أخضر، **بروفة compose حية** بمسار كامل: إنشاء عميل من الكونسول ←
    `curl` إصدار ← replay ← مطالبة من المحفظة.

## 7. نقاط الـ veto (تُعتمد دفعة واحدة؛ الافتراضي يسري إن لم تُجَب)

| # | السؤال | الافتراضي |
|---|---|---|
| V0 | ترقيم المهام: `KH-2.7` استُهلك لإصلاح A1 (`KH-2.7-BE`)، والـ spec يحمل اسم FS-2.7 كما في STATE. نعتمد **FS-2.7a/b/c** للمواصفات و**KH-2.8.x** للمهام (لا إعادة ترقيم — قاعدة WBS) | نعم |
| V1 | بادئة المفتاح `khi_` وطول السر 32 byte | نعم |
| V2 | الـ replay يعيد تعيين `claim_code` القائم بدل إنشاء صف جديد | نعم (صف واحد لكل وثيقة يبقى ثابتاً) |
| V3 | إن كان Vault KV غير مفعَّل على staging: تفعيله ضمن جلسة 2.8.1 (تغيير سياسة `khatm-transit-app.hcl` — **يُتحقَّق تجريبياً** كسابقة `transit/keys`) — أم يُؤجَّل وسر الحامل يبقى connector-only بلا تخزين في ختم؟ | تفعيل KV (مسار `khatm/` فقط، `create/update/read`) |
| V4 | نافذة السماح الافتراضية للتدوير 24h، والحد الأقصى 72h | نعم |
| V5 | `Idempotency-Key` لجلسات البشر: يُكرَّم إن أُرسل (D5) أم يُتجاهَل تماماً؟ | يُكرَّم |
| V6 | القراءة `GET /credentials/{id}` مسموحة لعميل M2M على وثائقه فقط (D4) | نعم |

## 8. الأثر وما بعده

- **FS-2.7b** (connector مرجعي + المحاكي 1 في repo المحاكيات المستقل) يبدأ من هذا
  العقد حرفياً: `Bearer khi_` + `Idempotency-Key` + `holderRef` HMAC بسر المستأجر +
  استلام `claimCode` وتسليمه للمواطن من نظام الجهة (الافتراضي 3 المعتمد).
- **FS-2.7c** (القناة B — دفعات M2M) يضيف مسار bulk بنفس الـ principal، وidempotency
  لكل صف = مفتاح الدفعة + رقم الصف؛ لا كيان جديد.
- **القناة C** (البوابة البشرية، FS-2.4) **موجودة ومغلقة** (KH-2.4-BE + C9) — لا عمل
  هنا؛ تُذكر في خطة الديمو فقط.
- OAuth2 client credentials لاحقاً = مصدر جديد لـ `IssuerClientPrincipal` نفسه؛ لا
  تغيير في `credential/`.
- بند onboarding checklist القائم (مراجعة إملاء الـ slug) يُضاف إليه: **مراجعة أن سر
  الحامل وُلِّد للجذر الإداري الصحيح** — هو الآخر غير قابل للتصحيح رجعياً بعد أول
  `holderRef`.

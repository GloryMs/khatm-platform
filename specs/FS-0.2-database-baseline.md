# FS-0.2 — Enterprise Database Baseline (Flyway V1)

> **Task:** KH-0.2.1 · **Repo:** khatm-platform · **Status:** APPROVED — 2026-07-13 · §5.7 partially deferred (error-codes.md → KH-0.6) — approved 2026-07-13 (النقاط الثلاث المؤكدة: claim_code encrypted+cleared · single active key/tenant · sequential status_idx)
> **Sources of truth:** doc 05 (data model) · SAD 20 §4.1/§5.3/§7 · SEC 21 · ADR-09 · CLAUDE.md work rules
> **اللغة:** شرح عربي، DDL ومعرّفات إنجليزية — حسب اصطلاح الوثائق.

---

## 1. الهدف والنطاق

إنشاء المخطط الكامل بمستوى enterprise في `V1__baseline.sql` (مصدر وحيد: Flyway،
`ddl-auto: validate`). المخطط يغطي كيانات doc 05 كلها **منذ اليوم الأول** حتى لو
كانت بعض الوحدات لن تُفعَّل قبل مراحل لاحقة (multi-tenancy، Merkle) — إضافة عمود
لاحقاً على بيانات حية أغلى بكثير من حمله فارغاً الآن.

**خارج النطاق:** سياسات RLS (تأتي في KH-2.1 كـ migration مستقل)، جداول الـ Merkle
التفصيلية (Phase 3)، أي DML خاص بالديمو (seeder بروفايل `local/dev` فقط).

## 2. قرارات التصميم العرضية (تنطبق على كل الجداول)

| # | القرار | التبرير |
|---|---|---|
| D1 | `id uuid PK` يولَّد في التطبيق بـ **UUIDv7** | ترتيب زمني يحافظ على locality في فهارس B-tree |
| D2 | `tenant_id uuid NOT NULL` على كل جدول أعمال + فهرس مركّب يبدأ به | جاهزية KH-2.1 (RLS) بلا migration مؤلم؛ NFR-07 |
| D3 | كل الأزمنة `timestamptz` (UTC)؛ التنسيق مسؤولية العميل | قاعدة CONVENTIONS §4 |
| D4 | الأسماء المعروضة للبشر: `name_i18n jsonb NOT NULL` بشكل `{"en":"…","ar":"…"}` + CHECK يضمن وجود المفتاحين | قاعدة العمل 2 (EN/AR في الـ DB) |
| D5 | الحالات (status/…): `text` + `CHECK` constraint، **ليس** PG enum | تطوير القيم لاحقاً بلا `ALTER TYPE` مقفِل |
| D6 | لا `ON DELETE CASCADE` على جداول الأعمال؛ الحذف الفعلي ممنوع على المسارات الموثّقة (إبطال لا حذف) | قابلية التدقيق NFR-08 |
| D7 | أعمدة إحصاء/عدّ: `int`/`bigint` فقط، لا float | CONVENTIONS |
| D8 | `created_at timestamptz NOT NULL DEFAULT now()` على كل جدول؛ `updated_at` عبر trigger مشترك حيث يلزم | اتساق |
| D9 | **P1 على مستوى المخطط:** لا عمود يخزّن claims مفكوكة أو PII. القيم الحساسة تعيش في disclosures تُسلَّم للمحفظة ولا تبقى بعد الـ claim (انظر `claim_code`) | الفلسفة الأساسية |

## 3. الجداول (13 جدولاً)

### 3.1 `tenant` — الجهة المُصدِرة
```sql
CREATE TABLE tenant (
  id           uuid PRIMARY KEY,
  slug         text NOT NULL UNIQUE,             -- معرّف آلي: 'moj', 'aleppo-univ'
  name_i18n    jsonb NOT NULL,
  type         text NOT NULL CHECK (type IN ('GOVERNMENT','EDUCATION','PRIVATE','OTHER')),
  did          text UNIQUE,                      -- did:web — يُفعَّل Phase 2
  deploy_mode  text NOT NULL DEFAULT 'SAAS' CHECK (deploy_mode IN ('SAAS','ONPREM','FEDERATED')),
  status       text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT tenant_name_i18n_langs CHECK (name_i18n ? 'en' AND name_i18n ? 'ar')
);
```
V1 تزرع **صف المستأجر الافتراضي** (uuid ثابت موثّق) — كل شيء في MVP يعمل تحته.

### 3.2 `issuer_key` — مفاتيح التوقيع (تدعم الدوران منذ الآن)
```sql
CREATE TABLE issuer_key (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenant(id),
  kid         text NOT NULL,                     -- يظهر في كل JWS header
  algo        text NOT NULL DEFAULT 'ES256' CHECK (algo IN ('ES256')),
  public_jwk  jsonb NOT NULL,                    -- المفتاح العام فقط — الخاص عند KeyProvider
  provider    text NOT NULL DEFAULT 'SOFT' CHECK (provider IN ('SOFT','KMS','PKCS11')),
  provider_ref text,                             -- مسار keystore / ARN / HSM slot
  state       text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('PENDING','ACTIVE','RETIRING','RETIRED')),
  valid_from  timestamptz NOT NULL,
  valid_to    timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, kid)
);
CREATE UNIQUE INDEX issuer_key_one_active
  ON issuer_key (tenant_id) WHERE state = 'ACTIVE';   -- مفتاح فعّال واحد لكل جهة
```
دورة الحياة `ACTIVE/RETIRING/RETIRED` من KH-2.3.2 موجودة في المخطط منذ الآن —
`RETIRING/RETIRED` تبقى قابلة للتحقق (JWKS يعرضها) ولا توقّع جديداً.

### 3.3 `credential_schema` — نوع الوثيقة
```sql
CREATE TABLE credential_schema (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenant(id),
  code        text NOT NULL,                     -- 'CriminalRecordExtract'
  version     int  NOT NULL DEFAULT 1,
  name_i18n   jsonb NOT NULL,
  claims_def  jsonb NOT NULL,                    -- تعريف الحقول: types + required + labels_i18n
  sd_fields   text[] NOT NULL DEFAULT '{}',      -- الحقول القابلة للكشف الانتقائي
  default_max_uses int NOT NULL DEFAULT 1 CHECK (default_max_uses >= 1),
  default_validity interval,
  status      text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','DEPRECATED')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, code, version),
  CONSTRAINT schema_name_i18n_langs CHECK (name_i18n ? 'en' AND name_i18n ? 'ar')
);
```
**اتفاق `claims_def` (يوثَّق في README الوحدة):** لكل claim: `type`, `required`,
`label_i18n {en,ar}` — تسمية الحقل ثنائية اللغة تأتي من هنا فتظهر صحيحة في الكونسول
والمحفظة (قاعدة العمل 2) دون تخزين أي قيمة.

### 3.4 `holder` — الحامل (اسم مستعار فقط)
```sql
CREATE TABLE holder (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL REFERENCES tenant(id),
  pseudo_ref   text NOT NULL,                    -- معرّف مستعار من نظام الجهة — ليس هوية وطنية
  wallet_jwk   jsonb,                            -- cnf key binding — nullable حتى Phase 3
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, pseudo_ref)
);
```

### 3.5 `status_list` — قائمة الإبطال المضغوطة
```sql
CREATE TABLE status_list (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL REFERENCES tenant(id),
  list_code     text NOT NULL,                   -- 'moj-2026'
  bitstring     bytea NOT NULL,                  -- مضغوطة gzip
  capacity      int  NOT NULL DEFAULT 131072,
  next_idx      int  NOT NULL DEFAULT 0,         -- تخصيص تسلسلي للبِتّات
  version       bigint NOT NULL DEFAULT 0,
  signed_artifact_ref text,                      -- مسار الـ artifact الموقّع المنشور (KH-1.3)
  published_at  timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, list_code)
);
```

### 3.6 `credential` — قلب النظام
```sql
CREATE TABLE credential (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL REFERENCES tenant(id),
  schema_id       uuid NOT NULL REFERENCES credential_schema(id),
  holder_id       uuid NOT NULL REFERENCES holder(id),
  ref             text NOT NULL UNIQUE,          -- 'CRJ-2026-0093412-c2' — المعرّف العام الوحيد
  copy_of         uuid REFERENCES credential(id),-- النسخ ترتبط بأصلها
  signed_payload  text NOT NULL,                 -- compact SD-JWT: digests فقط، لا قيم (P1/D9)
  payload_hash    bytea NOT NULL,                -- SHA-256 — ورقة Merkle مستقبلاً
  status_list_id  uuid NOT NULL REFERENCES status_list(id),
  status_idx      int  NOT NULL,
  valid_from      timestamptz NOT NULL,
  valid_to        timestamptz NOT NULL,
  max_uses        int  NOT NULL CHECK (max_uses >= 1),
  uses_remaining  int  NOT NULL CHECK (uses_remaining >= 0),
  revoked         boolean NOT NULL DEFAULT false, -- مسار سريع denormalized؛ الحقيقة في status_list
  revoked_at      timestamptz,
  issued_by       uuid,                          -- user/api-key الذي أصدر (تدقيق)
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (status_list_id, status_idx),
  CHECK (valid_to > valid_from),
  CHECK (uses_remaining <= max_uses)
);
CREATE INDEX credential_tenant_schema ON credential (tenant_id, schema_id);
CREATE INDEX credential_holder        ON credential (tenant_id, holder_id);
CREATE INDEX credential_active_window ON credential (valid_to) WHERE revoked = false;  -- SAD §7
```
**الاستعلام الذرّي** (SAD §5.3) يعمل على هذا الجدول كما هو — نجاح = صف واحد متأثر.

### 3.7 `claim_code` — تسليم الوثيقة للمحفظة (KH-1.2.1) مع احترام P1
```sql
CREATE TABLE claim_code (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL REFERENCES tenant(id),
  credential_id   uuid NOT NULL REFERENCES credential(id),
  code_hash       bytea NOT NULL UNIQUE,         -- SHA-256 للكود؛ الكود نفسه لا يُخزَّن
  disclosures_enc bytea,                         -- disclosures مشفّرة AES-GCM — تُمسح عند الـ claim
  expires_at      timestamptz NOT NULL,
  claimed_at      timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now()
);
```
هذا الجدول هو **الاستثناء الوحيد** الذي تمر عبره قيم claims — مشفّرة، بعمر قصير،
وتُصفَّر (`disclosures_enc = NULL`) لحظة الـ claim الناجح أو بانتهاء المهلة (مهمة worker
دورية). بعد ذلك المنصة لا تملك أي قيمة — proofs only.

### 3.8 `consuming_party` — الجهة المستهلِكة (KH-1.4.3)
```sql
CREATE TABLE consuming_party (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL REFERENCES tenant(id),
  name_i18n       jsonb NOT NULL,
  api_key_hash    bytea NOT NULL UNIQUE,         -- bcrypt/argon2 — المفتاح لا يُخزَّن
  status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT cp_name_i18n_langs CHECK (name_i18n ? 'en' AND name_i18n ? 'ar')
);
CREATE TABLE consuming_party_schema (              -- N—N: كل جهة تستهلك أنواعاً محددة
  consuming_party_id uuid NOT NULL REFERENCES consuming_party(id),
  schema_id          uuid NOT NULL REFERENCES credential_schema(id),
  PRIMARY KEY (consuming_party_id, schema_id)
);
```

### 3.9 `consumption_event` — سجل الاستهلاك
```sql
CREATE TABLE consumption_event (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL REFERENCES tenant(id),
  credential_id      uuid NOT NULL REFERENCES credential(id),
  consuming_party_id uuid NOT NULL REFERENCES consuming_party(id),
  idempotency_key    text NOT NULL,
  mode               text NOT NULL CHECK (mode IN ('ONLINE','OFFLINE')),
  consumed_at        timestamptz NOT NULL DEFAULT now(),
  merkle_leaf        bytea,                      -- nullable حتى Phase 3 (KH-3.2)
  receipt_sig        bytea,                      -- nullable — الاستهلاك offline (Phase 3)
  UNIQUE (idempotency_key)                       -- fallback دائم؛ Redis مسار سريع فقط (KH-1.4.1)
);
CREATE INDEX consumption_event_credential ON consumption_event (credential_id);
CREATE INDEX consumption_event_party_time ON consumption_event (tenant_id, consuming_party_id, consumed_at);
```

### 3.10 RBAC — `app_user`, `role`, `user_role` (KH-0.6 / KH-2.2)
```sql
CREATE TABLE app_user (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL REFERENCES tenant(id),
  username      text NOT NULL,
  password_hash text NOT NULL,                   -- argon2id
  display_name_i18n jsonb NOT NULL,
  preferred_lang text NOT NULL DEFAULT 'ar' CHECK (preferred_lang IN ('en','ar')),
  status        text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED','DISABLED')),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, username)
);
CREATE TABLE role (
  id         uuid PRIMARY KEY,
  tenant_id  uuid NOT NULL REFERENCES tenant(id),
  code       text NOT NULL,                      -- 'PLATFORM_ADMIN','TENANT_ADMIN','ISSUER_OPERATOR'
  name_i18n  jsonb NOT NULL,
  scopes     text[] NOT NULL,                    -- من: issue, verify, consume, revoke, admin
  UNIQUE (tenant_id, code)
);
CREATE TABLE user_role (
  user_id uuid NOT NULL REFERENCES app_user(id),
  role_id uuid NOT NULL REFERENCES role(id),
  PRIMARY KEY (user_id, role_id)
);
```
الـ scopes الخمسة من WBS KH-2.2.1 مثبتة الآن؛ V1 تزرع الأدوار الثلاثة الافتراضية
بأسماء ثنائية اللغة.

### 3.11 `audit_log` — سجل تدقيق append-only (NFR-08)
```sql
CREATE TABLE audit_log (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  actor_type  text NOT NULL CHECK (actor_type IN ('USER','API_KEY','SYSTEM')),
  actor_id    uuid,
  action      text NOT NULL,                     -- 'CREDENTIAL_ISSUED','KEY_ROTATED',…
  entity_type text NOT NULL,
  entity_ref  text,                              -- ref/kid — أبداً محتوى (SEC §9)
  detail      jsonb,                             -- بيانات وصفية غير حساسة فقط
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE OR REPLACE FUNCTION audit_log_block_mutation() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'audit_log is append-only'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER audit_log_no_update BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION audit_log_block_mutation();
```

### 3.12 بنية الأحداث (ADR-09)
Spring Modulith JDBC يتطلب جدول `event_publication` — يُنشأ في V1 بالمخطط الرسمي
للمكتبة (outbox للأحداث المُصدَّرة نحو Redis Streams). لا تصميم مخصص هنا.

## 4. ما لا يوجد في المخطط عمداً (توثيق سلبي)

- لا جدول "document" ولا عمود "content/file/scan" — **P1**. بوابة الجهات غير المؤتمتة
  (KH-2.4) ستخزّن hash فقط.
- لا عمود هوية وطنية/اسم مواطن — الحامل `pseudo_ref` حصراً.
- لا `api_key`/`claim code`/كلمة مرور بنص صريح — hashes فقط.
- لا سياسات RLS بعد — لكن كل جدول جاهز لها (`tenant_id` + فهارس تبدأ به).

## 5. معايير القبول (DoD للمهمة KH-0.2.1)

1. `MigrationCleanBootTest`: قاعدة فارغة (Testcontainers) → `flyway migrate` →
   التطبيق يقلع بـ `ddl-auto: validate` بلا أخطاء.
2. صف المستأجر الافتراضي + الأدوار الثلاثة مزروعة من V1؛ ديمو seeder يعمل في
   `local/dev` فقط ويُصدر وثيقة تجريبية كاملة (schema + holder + credential + claim_code).
3. اختبار تكامل: الاستعلام الذرّي — 50 خيطاً متوازياً على `max_uses=1` → نجاح واحد
   بالضبط (نواة `ConcurrentConsumeTest` قبل KH-1.4.2 الرسمية).
4. اختبار: إدراج `consumption_event` بمفتاح idempotency مكرر يفشل بقيد unique.
5. اختبار: UPDATE/DELETE على `audit_log` يرفضهما الـ trigger.
6. اختبار: إدراج `tenant` بـ `name_i18n` ناقص `ar` يفشل بالـ CHECK.
7. `docs/error-codes.md` و README وحدة كل جدول محدّثة (قاعدة العمل 1).

## 6. أثر على بقية المنظومة

- **الكيانات JPA** تُكتب في KH-0.1.1 مطابقة لهذا المخطط (validate يضمن التطابق).
- **الكونسول:** `name_i18n` و`label_i18n` في `claims_def` هما مصدر التسميات ثنائية
  اللغة في النماذج المولّدة (KH-1.1.2).
- **المحفظة:** لا أثر مباشر — المحفظة ترى الـ API فقط.
- **doc 05:** يبقى المرجع المفاهيمي؛ هذا الـ spec هو المرجع التنفيذي للمخطط.

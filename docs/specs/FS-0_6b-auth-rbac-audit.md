# FS-0.6b — Console Auth, API Keys, RBAC-lite & Audit Write Path

> **Tasks:** KH-0.6.1 / KH-0.6.2 / KH-0.6.3 (يُكمل Phase 0) · **Repo:** khatm-platform · **Status:** APPROVED
> **Sources of truth:** SEC 21 §7 (auth matrix) · §8 (STRIDE) · §9.4/§9.7 (audit + logging discipline) · FS-0.2 §3.8/§3.10/§3.11 · SAD 20 §4.1 (`rbac`, `shared`) · FS-0.6a (error machinery — قاعدتا العمل 2 و3 حيّتان)
> **يستبدل:** غياب أي مصادقة (كل endpoints مفتوحة اليوم) + صفوف audit المباشرة المؤقتة من KH-0.5
> **اللغة:** شرح عربي، عقود وكود إنجليزية.

---

## 1. الهدف

آخر مهمة في Phase 0: لا شيء يغادر بيئة `local` قبل أن تقف المنصة خلف مصادقة.
ثلاث قدرات: (أ) جلسات الكونسول (username/password)، (ب) مفاتيح API للمسارات
البرمجية (استهلاك اليوم، إصدار برمجي لاحقاً)، (ج) مسار كتابة `audit_log` الكامل
الذي يستبدل إدراجات KH-0.5 المباشرة. ومعها تُفعَّل أخيراً
`AuthenticationException`/`AuthorizationException` (موجودتان منذ FS-0.6a §6 بلا
مسار يرميهما).

**النطاق:** Spring Security + جلسات Redis + login/logout/me + قفل المحاولات +
`ApiKeyAuthFilter` + جدول `api_key` (V2) + `AuditService` + بوابات الـ scopes على
endpoints الموجودة + أكواد `KH-RBC-*` برسائل ثنائية اللغة.
**خارج النطاق:** TOTP 2FA (Phase 2 — SEC §7)، OAuth2 client credentials (Phase 2)،
كونسولات إدارة المستخدمين/الأدوار (KH-2.2)، `allowed_schemas` enforcement
(KH-1.4.3)، rate limiting، RLS (KH-2.1).

## 2. القرارات المسبقة D1–D10

| # | القرار | التبرير |
|---|---|---|
| D1 | جلسات الكونسول **server-side عبر Spring Session + Redis** (`spring-session-data-redis`)، كوكي `KHATM_SESSION` (HttpOnly, Secure خارج local, SameSite=Lax) | SEC §7 ينص على session auth؛ Redis موجود أصلاً؛ يصمد أمام تعدد replicas لاحقاً (KH-2.5.2) بلا تغيير. فقدان الجلسات عند إعادة تشغيل Redis = إعادة تسجيل دخول، مقبول |
| D2 | صيغة مفتاح الـ API: **`khk_<env>_<prefix>.<secret>`** — `env ∈ {live,test}`, `prefix` 8 محارف base62 يُخزَّن صريحاً (فهرس lookup), `secret` 32 محرف base62 (~190 bit entropy) | prefix-identifiable per SEC §7: الدعم الفني يميّز المفتاح من أول سطر لوغ دون كشف السر |
| D3 | جدول **`api_key` عام جديد في `V2__auth_api_keys.sql`** بعمود `owner_type ∈ {TENANT, CONSUMING_PARTY}` — ويُحذف `consuming_party.api_key_hash` في نفس الـ migration | العمود القديم (hash وحيد، بلا prefix) لا يدعم الـ lookup ولا التدوير بتداخل زمني (مفتاحان صالحان أثناء التبديل). قاعدة العمل 4: مفهوم واحد = تنفيذ واحد، فلا يبقى مخزنان للمفاتيح. V1 لا يُمَسّ ملفّه — `MigrationImmutabilityTest` يبقى راضياً |
| D4 | هاش مفاتيح الـ API = **SHA-256** للسر؛ هاش كلمات المرور = **argon2id** (كما ينص V1 نصاً) | السر عشوائي بإنتروبيا عالية → السباق ضد brute-force غير وارد وSHA-256 يسمح بتحقق بلا كلفة لكل طلب. كلمات المرور بشرية منخفضة الإنتروبيا → argon2id (`Argon2PasswordEncoder`، يتطلب BouncyCastle — إضافة معتمدة للمكدس) |
| D5 | نموذج الصلاحيات يبقى **lean كما في V1**: `role.scopes text[]` + الأدوار الثلاثة المزروعة — لا جداول Permission | KH-2.2 يملك الـ RBAC الكامل؛ V1 سبق وجسّد القرار. الـ scopes الحاكمة الآن: `issue`, `verify`, `consume`, `revoke`, `admin` |
| D6 | قفل المحاولات عبر **عدّادات Redis بـ TTL** (`khatm:auth:fail:{tenant}:{username}`؛ افتراضياً 5 محاولات / نافذة 15 دقيقة → قفل مؤقت بنفس النافذة). حالة `LOCKED` في `app_user` تبقى **قفلاً إدارياً يدوياً فقط** | لا migration لعدّاد عابر؛ فكّ القفل تلقائي بانقضاء الـ TTL؛ الفصل بين المؤقت والإداري صريح |
| D7 | فشل الدخول يرجع دائماً **رسالة واحدة عامة** (بيانات اعتماد غير صحيحة) لكل الحالات: مستخدم مجهول، كلمة سر خاطئة، مقفول مؤقتاً، `LOCKED`/`DISABLED` — والتمييز الحقيقي يذهب إلى `audit_log.detail` فقط | مضاد تعداد أسماء المستخدمين (STRIDE — Spoofing/Info disclosure)؛ الدعم يقرأ السبب من الـ audit لا من الاستجابة |
| D8 | **`AuditService` في `shared/audit`** (`@NamedInterface("audit")`): `record(action, entityType, entityRef, detail)` — الفاعل يُستنتج من `SecurityContext` (USER / API_KEY / SYSTEM)، والكتابة **داخل نفس الـ transaction** للعملية المؤدَّاة | NFR-08: الحدث وأثره التدقيقي ذرّيان معاً — لا حدث بلا أثر ولا أثر لحدث لم يقع. إدراجات KH-0.5 المباشرة تُحذف وتمرّ عبر الخدمة (قاعدة العمل 4) |
| D9 | يبقى عاماً بلا مصادقة: `POST /verify` (فلسفة P2 — التحقق لا يُحتجز خلف حساب) و`GET /.well-known/jwks.json` (مفاتيح عامة). **كل ما عداه خلف الجدار** | القرار الصريح يمنع «نسيان» endpoint مفتوحاً؛ اختبار DoD-10 يثبّته |
| D10 | **`AdminBootstrap`** عند الإقلاع (نمط `KeyBootstrap` نفسه): لا مستخدم للمستأجر الافتراضي → إنشاء admin من `KHATM_BOOTSTRAP_ADMIN_USERNAME/PASSWORD` (env). خارج `local`: غيابهما مع قاعدة فارغة = **فشل إقلاع صريح**. في `local`: قيم افتراضية موثقة | نفس فلسفة FS-0.5 §3: لا افتراضات صامتة خارج local؛ `docker compose up` يعمل بلا إعداد محلياً. Idempotent — وجود أي مستخدم يعطّله |

## 3. الشكل المعماري

```
rbac/                          ← وحدة SAD §4.1 (تُنشأ الآن)
├─ api/                        ← @NamedInterface: ما تراه الوحدات الأخرى
│  └─ CurrentActor             الفاعل الحالي (type, id, tenantId, scopes)
├─ domain/                     ← module-private
│  ├─ AppUser / Role / ApiKey  كيانات JPA (مطابقة V1 + V2)
│  ├─ AuthService              login/logout + عدّادات القفل (Redis)
│  ├─ ApiKeyService            إنشاء/إبطال/تحقق (SHA-256, prefix lookup)
│  └─ AdminBootstrap           D10
├─ security/
│  ├─ SecurityConfig           سلسلة الفلاتر، CSRF، تقسيم public/protected (D9)
│  ├─ ApiKeyAuthFilter         Authorization: Bearer khk_… → principal بالـ scopes
│  └─ ScopeGuard               @PreAuthorize("hasAuthority('SCOPE_issue')") …
└─ web/
   └─ AuthController           POST /api/auth/login · POST /api/auth/logout · GET /api/auth/me
                               + POST /api/admin/api-keys · POST /api/admin/api-keys/{id}/revoke (scope admin)

shared/audit/                  ← @NamedInterface("audit") — الموقع الذي يحدده SAD لـ shared
├─ AuditService                D8 — الواجهة الوحيدة للكتابة في audit_log
└─ AuditAction                 كتالوج الأحداث (enum — §6)
```

- بوابات الـ scopes على الموجود: `/issue` → `issue` (جلسة أو مفتاح TENANT)،
  `/revoke/**` → `revoke` (جلسة)، `/consume` → `consume` (مفتاح CONSUMING_PARTY
  حصراً — SEC §7)، endpoints الإدارة → `admin`.
- **CSRF**: `CookieCsrfTokenRepository.withHttpOnlyFalse()` — الكونسول (SPA) يقرأ
  الكوكي ويرسل `X-XSRF-TOKEN`. مسارات مفاتيح الـ API stateless → معفاة من CSRF.
- **دور الـ worker**: لا سلسلة أمن ويب فعلية هناك (الـ controllers مطفأة أصلاً) —
  `SecurityConfig` يُحمَّل لكن لا يؤثر؛ اختبار الحارس الحالي يُمدَّد ليثبت أن
  بروفايل worker يقلع سليماً بعد إضافة Spring Security.

## 4. `V2__auth_api_keys.sql` — أول migration بعد الأساس

```sql
CREATE TABLE api_key (
  id           uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL REFERENCES tenant(id),
  owner_type   text NOT NULL CHECK (owner_type IN ('TENANT','CONSUMING_PARTY')),
  owner_id     uuid,                              -- NULL عندما TENANT (المالك هو tenant_id)
  key_prefix   text NOT NULL UNIQUE,              -- lookup — يُلوَّغ بأمان
  key_hash     bytea NOT NULL,                    -- SHA-256(secret) — D4
  scopes       text[] NOT NULL,                   -- subset من كتالوج الـ scopes
  status       text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
  created_at   timestamptz NOT NULL DEFAULT now(),
  revoked_at   timestamptz,
  last_used_at timestamptz                        -- تحديث best-effort (لا يفشل الطلب لأجله)
);
CREATE INDEX api_key_owner ON api_key (tenant_id, owner_type, owner_id);

ALTER TABLE consuming_party DROP COLUMN api_key_hash;   -- D3
```

- السر يظهر **مرة واحدة** في استجابة الإنشاء ولا يُخزَّن أبداً (FS-0.2 §4 نصاً).
- التدوير = إنشاء مفتاح جديد ثم إبطال القديم — تداخل زمني طبيعي بلا آلية خاصة.
- `DemoSeeder` (local/dev): مستخدم admin تجريبي + consuming party بمفتاح يُطبع
  مرة في اللوغ عند الزرع (موثّق أنه ديمو فقط).

## 5. الأكواد والرسائل (امتداد سجل FS-0.6a — بنمط D3 هناك)

| ErrorCode | متى | الرسالة (المفتاح) |
|---|---|---|
| `KH-RBC-0401` | لا جلسة/لا مفتاح على مسار محمي، أو فشل دخول (D7 — عامة دائماً) | `error.rbc.unauthenticated` |
| `KH-RBC-1401` | مفتاح API غير صالح/مُبطَل/صيغة فاسدة | `error.rbc.api_key_invalid` |
| `KH-RBC-0403` | جلسة/مفتاح صالح لكن الـ scope ناقص | `error.rbc.forbidden` |

- الثلاثة تمرّ حصراً عبر `AuthenticationException`/`AuthorizationException` →
  `GlobalExceptionHandler` (قاعدة العمل 3 — لا `ResponseEntity` يدوي في الفلاتر:
  الفلتر يفوّض إلى `AuthenticationEntryPoint`/`AccessDeniedHandler` يكتبان نفس
  المغلّف الموحد).
- كل مفتاح رسالة في `messages_en` **و** `messages_ar` (`MessageBundleParityTest`)
  و`docs/error-codes.md` يُعاد توليده — التزامات «القاعدتان حيّتان» من STATE.md.

## 6. كتالوج الـ audit v1 (`AuditAction` — SEC §9.4)

| الحدث | الموقع | ملاحظة |
|---|---|---|
| `CREDENTIAL_ISSUED` / `CREDENTIAL_CONSUMED` / `CREDENTIAL_REVOKED` | مسارات credential الموجودة | `entity_ref` = ref فقط — لا claims (SEC §9.7) |
| `KEY_CREATED` / `KEY_ROTATED` | KH-0.5 — **تتحول من الإدراج المباشر إلى `AuditService`** | D8 |
| `CLAIM_CODES_EXPIRED` | ADR-09-worker — تتحول كذلك | actor = SYSTEM |
| `AUTH_LOGIN_SUCCESS` / `AUTH_LOGIN_FAILED` / `AUTH_LOCKOUT_TRIGGERED` | AuthService | `detail` يحمل السبب الحقيقي الذي أخفاه D7 |
| `API_KEY_AUTH_FAILED` | ApiKeyAuthFilter | `detail.prefix` فقط — لا سر |
| `API_KEY_CREATED` / `API_KEY_REVOKED` / `USER_CREATED` | ApiKeyService / AdminBootstrap | — |

## 7. الإعداد (config surface كامل)

```yaml
khatm:
  auth:
    session:
      timeout: 30m                       # Spring Session Redis
    lockout:
      max-attempts: 5
      window: 15m
    bootstrap:
      admin-username: ${KHATM_BOOTSTRAP_ADMIN_USERNAME:}   # local وحده له default موثق
      admin-password: ${KHATM_BOOTSTRAP_ADMIN_PASSWORD:}
```

Dependencies الجديدة (تعديل pom معتمد): `spring-boot-starter-security`,
`spring-session-data-redis`, `spring-security-test`, BouncyCastle (argon2 — D4).

## 8. معايير القبول (DoD)

1. `login → me → logout` دورة كاملة تعمل بكوكي الجلسة؛ `me` يرجع
   `(username, displayNameI18n, preferredLang, scopes)`.
2. كلمة سر خاطئة → 401 بمغلّف `KH-RBC-0401` بالرسالة العامة (D7) + صف
   `AUTH_LOGIN_FAILED`؛ 5 إخفاقات → المحاولة السادسة **بكلمة السر الصحيحة** ترفض
   أيضاً داخل النافذة + `AUTH_LOCKOUT_TRIGGERED`؛ بعد انقضاء TTL يعمل الدخول.
3. `/issue` بلا جلسة → 401؛ بجلسة مستخدم دون scope `issue` → 403 `KH-RBC-0403`؛
   بجلسة operator → يعمل كما قبل (اختبارات KH-0.4 القائمة تتكيف بمستخدم مزروع).
4. `/consume` بمفتاح CONSUMING_PARTY صالح → يعمل + `CREDENTIAL_CONSUMED` عبر
   `AuditService`؛ بمفتاح مُبطَل/مشوّه → 401 `KH-RBC-1401` + `API_KEY_AUTH_FAILED`؛
   **بجلسة كونسول → 403** (الاستهلاك للمفاتيح حصراً — SEC §7).
5. إنشاء مفتاح عبر `POST /api/admin/api-keys` (scope admin): السر يظهر مرة واحدة،
   الصف يحمل hash + prefix فقط، والإبطال يقطع المفتاح فوراً في الطلب التالي.
6. `V2` على قاعدة V1 قائمة: `flyway migrate` ينجح، `ddl-auto: validate` يقلع،
   `MigrationCleanBootTest` (فارغة → V1+V2) أخضر، `MigrationImmutabilityTest` أخضر.
7. لا يبقى في الشيفرة أي إدراج مباشر في `audit_log` خارج `AuditService`
   (يُثبت باختبار معماري — ArchUnit/Modulith — لا بمراجعة يدوية).
8. اختبار transactional: فشل العملية بعد `record(...)` → لا صف audit يتيماً
   (rollback مشترك — D8).
9. `POST /verify` و`GET /.well-known/jwks.json` يعملان **بلا أي credentials**
   (اختبار صريح يثبّت D9)؛ بروفايل worker يقلع سليماً (امتداد اختبار الحارس §3).
10. لا سر مفتاح ولا كلمة مرور ولا hash في أي لوغ أو استجابة أو صف audit —
    امتداد `NoDisclosureContentInLogsTest` بنمط الفحص نفسه على مسارات المصادقة.
11. المعتاد (قواعد العمل 1–4): Javadoc + README وحدة `rbac` و`shared/audit` +
    التماثل EN/AR + `error-codes.md` مولَّد + OpenAPI annotations على endpoints
    الجديدة + البناء الكامل والـ CI أخضران.

## 9. أثر على بقية المنظومة

- **الكونسول (FS-C0 لاحقاً):** عقد `login/logout/me` + نمط CSRF يثبتان الآن —
  يدخلان أول contract منشور.
- **KH-1.2.1 (claim delivery):** مصادقته **بامتلاك claim code** لا بجلسة ولا
  مفتاح — يُقرَّر في spec-ه؛ هذا الـ spec لا يغلق عليه المسار (يُسجَّل مساره
  ضمن قائمة public في `SecurityConfig` عندما يُبنى).
- **KH-1.4.3 (`allowed_schemas`):** سيبني فوق principal الـ CONSUMING_PARTY
  الذي يوفره `ApiKeyAuthFilter` — لا تغيير على الفلتر.
- **KH-2.2 (RBAC الكامل):** يستبدل D5 بجداول Permission ويضيف كونسولات الإدارة؛
  `CurrentActor` والـ scopes تبقى العقد الثابت.
- **KH-0.3.3 (staging):** بعد هذا الـ spec يصبح النشر الخارجي آمناً مبدئياً —
  الترتيب المقصود.

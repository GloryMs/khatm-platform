# SESSION-KH-2.8.1-BE — `issuer_client` + مصادقة M2M + النطاق `issue` + سر الحامل

> **Repo:** khatm-platform · **Spec:** FS-2.7a (APPROVED 2026-09-10) — القرارات D1–D4, D8–D9, D11–D12
> **المنفِّذ:** Claude Code · **المراجع/الدامج:** مجد · **PR يُفتح ولا يُدمج**
> **الجلسة التالية:** KH-2.8.2-BE (D5–D7 idempotency) — **لا تُبدأ في هذه الجلسة**
> **اللغة:** سرد عربي، كود وعقود إنجليزية.

---

## 0. بوابات ما قبل البدء (preamble) — توقف عند أول فشل وأبلغ

1. `git fetch && git status`: على `main` نظيف، `origin/main == HEAD`، صفر PRs مفتوحة على المنصة.
2. `docs/STATE.md` للمنصة يذكر لقطة 2026-08-23 (473/473، Boot 3.5.16) — إن ذكر عملاً لاحقاً غير مسجَّل هنا، **توقف وأبلغ**.
3. **بوابة الـ chore:** توكن Vault على staging جُدِّد (بند مستقل قبل 2026-09-16). التحقق: `docs/STATE.md` أو `deploy/` يحوي سطر التجديد بتاريخ ≥ 2026-09-10. إن لم يوجد → **توقف**؛ هذه الجلسة لا تنفّذ chores.
4. `./mvnw -q verify` أخضر محلياً (Testcontainers تعمل) قبل أي تعديل.
5. `docker compose up` المحلي (بروفايل local بـ Vault المقسّى) يقلع ويصدر وثيقة الديمو من `DemoSeeder`.

## 1. نقاط الـ veto — محسومة (لا تُعاد المناقشة)

| # | القرار الساري |
|---|---|
| V0 | spec = FS-2.7a، مهام = KH-2.8.x |
| V1 | `khi_<10 base32>_<32 bytes base64url>` |
| V3 | Vault **KV v2** يُفعَّل ضمن هذه الجلسة على مسار `khatm/` (سياسة `create/update/read` فقط) — **بالتحقق التجريبي أولاً** (§2.4) |
| V4 | `retire_after` افتراضي 24h، أقصى 72h |
| V6 | `GET /credentials/{id}` مسموح لعميل M2M على وثائقه فقط |

V2/V5 تخصان الجلسة التالية.

## 2. مرحلة التحقيق (قراءة فقط — اكتب `investigation.md` مؤقتاً في جذر الفرع، يُحذف قبل الـ PR)

### 2.1 مصادقة KH-1.4.3 (الجهات المستهلِكة)
- أين فلتر مفتاح الـ API القائم؟ ما الـ header (Bearer / `X-Api-Key`)؟ كيف يبني الـ principal ويضبط سياق المستأجر (`app.tenant_id`)؟
- **القرار المطلوب (افتراضي D2):** فلتر واحد يميّز بالبادئة `khc_`/`khi_` إن كان القائم قابلاً للتوسيع بلا إعادة كتابة؛ وإلا فلتران متجاوران بنفس الترتيب في `SecurityFilterChain`. إن كان header الجهات المستهلِكة **ليس** `Bearer` → المفتاح الجديد `Bearer` حصراً والقديم يبقى كما هو (لا تغيير على `consuming_party`).
- سجّل: هل مفاتيح `khc_` لها بادئة صريحة أصلاً أم البحث بالـ hash الكامل؟ (لا تُصلح — سجّل فقط.)

### 2.2 مسار الإصدار
- `POST /api/v1/credentials`: أي service/facade؟ كيف يُشتق `tenantId` وactor الـ audit اليوم من مستخدم الجلسة؟ هل يوجد `@PreAuthorize` بنطاق `issue` أم فحص يدوي؟
- **هل الـ bulk مسار API** (`POST /credentials/bulk` أو شبيه) أم منطق كونسول يستدعي المفرد؟ إن كان API → يدخل نطاق `issue` هنا (D4). إن كان كونسولاً فقط → **خارج النطاق، سجّل**.
- `GET /credentials/{id}`: أين فحص المستأجر؟ (لإضافة فحص `issuer_client_id` — V6.)

### 2.3 الأدوار والنطاقات
- هل `key:manage` موجود كقيمة scope فعلية في `role.scopes` (SEC §7 يسميه) أم أن الأدوار المزروعة تعرف `admin` فقط؟ إن غاب → **يُضاف** إلى `TENANT_ADMIN` و`PLATFORM_ADMIN` بـ migration بيانات إضافية-فقط (`UPDATE role SET scopes = array_append(...) WHERE code IN (...) AND NOT ('key:manage' = ANY(scopes))`) وتسجيله في التحقيق. حارس subset-of-grantor (PR #68) يبقى ساري المفعول تلقائياً.
- `OnBehalfOfExecutor#runAsChildOrg` و`requireDirectChild`: التوقيع الدقيق لإضافة عملية خامسة تحت `/api/v1/org/**`.

### 2.4 Vault KV (V3) — تجريبي قبل أي كود
- على compose المحلي: `vault secrets list` — هل `kv` مفعَّل؟ إن لا: `vault secrets enable -path=khatm kv-v2` ثم تعديل `khatm-transit-app.hcl` بإضافة
  `path "khatm/data/tenants/*" { capabilities = ["create","update","read"] }` (+ `khatm/metadata/tenants/*` للـ read). **جرّب الكتابة والقراءة بالتوكن التطبيقي فعلياً** وسجّل الناتج في التحقيق. إن فشل التفعيل لأي سبب بنيوي → **توقف وأبلغ** (يعود مجد للبديل connector-only).
- كيف يُحقن `VaultTemplate` اليوم في `VaultKeyProvider`؟ نعيد استخدام نفس الـ bean.

### 2.5 آخر migration
- رقم `V{n}` الأخير، وأن `MigrationImmutabilityTest` يغطي الملفات الجديدة تلقائياً.

**بوابة نهاية التحقيق:** إن كشف التحقيق أن (أ) فلتر KH-1.4.3 غير قابل للتوسيع **و**فصله يتطلب إعادة كتابة `SecurityConfig`، أو (ب) `key:manage` يتعارض مع منطق scope-gating القائم في الكونسول — **توقف وأبلغ** قبل أي كود.

## 3. التنفيذ (بالترتيب، commit لكل خطوة)

### 3.1 migration `V{next}__issuer_client.sql` (إضافية-فقط)
- `issuer_client` كما في FS-2.7a D1 حرفياً (بما فيه CHECK على `scopes <@ ARRAY['issue']` — تُوسَّع لاحقاً بـ migration). `issuer_client_schema` N–N.
- `ALTER TABLE credential ADD COLUMN issuer_client_id uuid NULL REFERENCES issuer_client(id)`.
- **لا** `issuance_idempotency` هنا (جلسة 2.8.2 لها migration خاصة).
- سياسات RLS بالمساواة الصارمة على الجدولين + فهارس `(tenant_id, status)` و`(key_prefix)` (unique).
- إن لزم: migration بيانات `key:manage` (§2.3).

### 3.2 وحدة Modulith `issuerclient/`
```
issuerclient/
├─ api/     IssuerClientAuthenticator (resolve(rawKey) → Optional<IssuerClientPrincipal>)
│           IssuerClientPrincipal(clientId, tenantId, scopes)          ← @NamedInterface
├─ domain/  IssuerClient (entity), IssuerClientRepository (module-private)
│           ApiKeyGenerator (SecureRandom; prefix+secret; argon2id hash by existing PasswordEncoder bean)
│           IssuerClientLifecycle (create / rotate / suspend / resume / revoke; one-active-per-rotation-chain)
│           HolderSecretProvisioner (D9: root-slug resolution via KH-2.6 hierarchy; Vault KV write; once-only)
│           RetiringSweeper (worker role only, ADR-09; RETIRING & retire_after < now() → REVOKED; audit)
└─ web/     IssuerClientController  (§3.2 of FS-2.7a)
            OrgIssuerClientController (/api/v1/org/children/{slug}/issuer-clients via OnBehalfOfExecutor)
```
- `ModulithBoundariesTest` يجب أن يبقى أخضر: `credential/` لا يستورد شيئاً من `issuerclient/domain`.
- التحقق من المفتاح: بحث بـ `key_prefix` → مقارنة argon2id (ثابتة الزمن بحكم المكتبة) → فحص `status`/`expires_at`/`retire_after` → تحديث `last_used_at` **بشكل غير متزامن أو بتخفيف** (لا كتابة على كل طلب: `UPDATE ... WHERE last_used_at < now() - interval '60s'`).

### 3.3 الفلتر والأمن
- `shared/security`: بحسب قرار §2.1. الـ principal يضبط `app.tenant_id` **من الكيان** حصراً.
- `SecurityConfig`: `/api/v1/issuer-clients/**` خلف `key:manage` (platform:admin قراءة فقط عبر method security)؛ لا يُضاف أي مسار للقائمة العامة (اختبار الحصر القائم يبقى على عدده).
- منطق النطاق: `IssuerClientPrincipal` يمرّ فقط على: `POST /api/v1/credentials` (+ bulk API إن وُجد) و`GET /api/v1/credentials/{id}` بشرط `credential.issuer_client_id == principal.clientId`. **كل ما عداه `403 KH-AUTH-0403`** — يُنفَّذ كقاعدة مركزية واحدة (authorization manager) لا كشروط متناثرة.
- `actor_type='API_KEY'`, `actor_id=clientId` في `AuditService` عندما يكون الـ principal من هذا النوع.

### 3.4 حرّاس الإصدار (D8)
- في `credential/`: `holderRef` يجب أن يطابق `^[0-9a-f]{64}$` وإلا `400` (كود موجود أم `KH-ISS-0400`؟ — استخدم الغلاف الموحَّد؛ إن كان التحقق الشكلي قائماً بصيغة أخرى، وسّعه لا تكرّره).
- `khatm.issuance.forbidden-claim-names` (افتراضي: `nationalId,nid,national_id,ssn,passportNo`) — مطابقة غير حساسة لحالة الأحرف على مفاتيح `claims` من المستوى الأول → `400 KH-ISS-0400`. يسري على **كل** الإصدار (بشر وM2M).
- `credential.issuer_client_id` يُملأ عند الإصدار من principal M2M؛ NULL لجلسات البشر.

### 3.5 Audit وأكواد الأخطاء (D11–D12)
- الإجراءات: `ISSUER_CLIENT_CREATED/_ROTATED/_SUSPENDED/_RESUMED/_REVOKED` (entity_ref = `key_prefix`)، `ISSUER_CLIENT_AUTH_FAILED` (مقنَّن: صف/prefix/60s عبر Redis أو خريطة داخلية — الأبسط الذي يعمل عبر replica واحدة يكفي الآن، سجّل الخيار)، `HOLDER_SECRET_GENERATED` (detail: root slug).
- `docs/error-codes.md`: `KH-AUTH-0401`, `KH-AUTH-0403`, `KH-ICL-0409`, `KH-ISS-0400`. **الجسم الخارجي لـ 0401 و0409 متطابق** (anti-enumeration)؛ التمييز في الـ audit فقط.
- **قاعدة اللوغ:** لا مفتاح خام، لا سر حامل، لا `holderRef` في أي سطر لوغ (يشمل `DEBUG`).

### 3.6 العقد
- `openapi.json` يتجدد **إضافياً-فقط**: مسارات §3.2، حقول `issuerClientId`/`claimed` في استجابة الإصدار (`claimed` يُرجع `false` دائماً في هذه الجلسة — الدلالة الكاملة في 2.8.2)، `scopes` في `MeResponse` إن غابت.
- README وحدة `issuerclient/` + تحديث README `credential/` (العمود الجديد والحارس).

## 4. الاختبارات الإلزامية (تُكتب مع كل خطوة لا في النهاية)

1. `MigrationCleanBootTest` + `MigrationImmutabilityTest` أخضران.
2. **لا سر في القاعدة:** إنشاء عميل → dump الجداول كلها → لا سطر يحوي `khi_` بأكثر من البادئة (`key_prefix` نفسها تحوي `khi_`? — **لا**: خزّن البادئة بلا `khi_` لتجعل هذا الاختبار حاسماً).
3. **العزل (NFR-07):** مفتاح مستأجر A + `schemaId` لمستأجر B → 404 صفر صفوف؛ يُضاف إلى حزمة الاختبار العابر للمستأجرين القائمة بنفس نمطها.
4. **جدول النطاق:** لكل مسار مسجَّل في التطبيق (استخراج آلي من `RequestMappingHandlerMapping`) ما عدا القائمة البيضاء (إصدار، bulk-API إن وُجد، `GET /credentials/{id}`) → `KH-AUTH-0403` بمفتاح صالح. اختبار واحد جدولي؛ أي مسار جديد مستقبلاً يفشل فيه تلقائياً حتى يُقرَّر.
5. `GET /credentials/{id}`: عميل أصدرها → 200؛ عميل آخر في نفس المستأجر → 404 (لا 403).
6. تدوير: بزمن مُحقَن (`Clock` bean) — القديم يعمل قبل `retire_after` ويُرفض بعده؛ الـ sweeper يحوّل الحالة ويكتب audit؛ `rotated_from` مضبوط؛ لا يمكن تدوير عميل `REVOKED`.
7. معلَّق/مُبطَل/منتهي (`expires_at`) → جسم رفض متطابق byte-for-byte مع مفتاح غير معروف؛ صفوف audit مختلفة.
8. `holderRef` غير مطابق للشكل → 400؛ `claims` بمفتاح `NationalId` (حالة مختلطة) → `KH-ISS-0400` — من جلسة بشر ومن M2M معاً.
9. سر الحامل: أول عميل للجذر يكتب في Vault KV (Testcontainers Vault القائم) ويعيد السر مرة؛ عميل ثانٍ للجذر أو أي عميل لابن لا يكتب ولا يعيد؛ `HOLDER_SECRET_GENERATED` مرة واحدة بالضبط.
10. الأب عبر `/org/children/{slug}/issuer-clients`: إنشاء/قائمة تعمل؛ ابن غير مباشر أو غريب → `KH-ORG-0404` الموحَّد؛ التدقيق المزدوج (علامة الأب ثم السطر تحت الابن) كما في KH-2.6.
11. اعتراض اللوغ (`ListAppender`): مسار إنشاء + مصادقة فاشلة + إصدار → لا يظهر المفتاح الخام ولا السر ولا `holderRef`.
12. تقنين `ISSUER_CLIENT_AUTH_FAILED`: 50 محاولة فاشلة بنفس البادئة خلال ثانية → صف واحد.
13. `ModulithBoundariesTest` + اختبار حصر القائمة العامة (العدد لا يتغير) أخضران.
14. `DemoSeeder` (local/dev فقط) يزرع عميلاً تجريبياً واحداً ويطبع مفتاحه في لوغ الإقلاع **بمستوى INFO وفي البروفايل `local` حصراً** (لا يُعد خرقاً لقاعدة اللوغ لأنه سر ديمو مولَّد محلياً — وثّق ذلك في README).

## 5. `[MAJD]` — الجولة الحية على compose (إلزامية، بعد اكتمال §3–4)

Claude Code يجهّز سكربت `scripts/demo-m2m.sh` (curl فقط) ويتوقف. مجد ينفّذ:

1. `docker compose up` بالبروفايل المحلي؛ التقط مفتاح الديمو من اللوغ.
2. `[MAJD]` `curl` إصدار وثيقة بـ `Bearer khi_...` على schema الديمو مع `holderRef` = `echo -n 02010012345 | openssl dgst -sha256 -hmac "<holder secret>"` (السر من استجابة الإنشاء) → 201 مع `claimCode`.
3. `[MAJD]` امسح الـ claim code بالمحفظة على الجهاز الحقيقي → الوثيقة تظهر، الجهة المُصدِرة صحيحة.
4. `[MAJD]` نفس المفتاح على `/revoke` → `KH-AUTH-0403`؛ مفتاح بحرف محرَّف → `KH-AUTH-0401`.
5. `[MAJD]` `claims` تحوي `nationalId` → `KH-ISS-0400`.
6. `[MAJD]` في Vault UI/CLI: `khatm/data/tenants/<root>/holder-hmac` موجود؛ لا سطر لوغ يحوي المفتاح (`docker compose logs api | grep khi_` يعيد البادئة فقط أو لا شيء).
7. `[MAJD]` **بروفة الكونسول لاحقة (C13)** — هذه الجلسة backend فقط؛ لا شاشة بعد.

أي انحراف → يعود إلى Claude Code كتعليق على الـ PR لا كجلسة جديدة.

## 6. DoD (checklist الـ PR)

- [ ] البوابات §0 موثَّقة في وصف الـ PR (بما فيها بوابة الـ chore).
- [ ] `investigation.md` محذوف؛ خلاصته في وصف الـ PR (قرار الفلتر، وضع الـ bulk، وضع `key:manage`, نتيجة تجربة KV).
- [ ] migration إضافية-فقط؛ الاختباران 1 أخضران.
- [ ] الاختبارات 1–14 كلها موجودة وخضراء؛ العدّ الكلي > 473 وجميعها تمر.
- [ ] `openapi.json` إضافي-فقط (diff يُظهر إضافات فقط).
- [ ] `docs/error-codes.md` + README الوحدتين + `khatm-transit-app.hcl` (KV) محدَّثة.
- [ ] `docs/STATE.md` للمنصة: قسم KH-2.8.1-BE + نتيجة تجربة KV + الدروس.
- [ ] الجولة الحية §5 مكتملة وموقَّعة من مجد في تعليق الـ PR.
- [ ] CI أخضر (بما فيه Trivy).

## 7. خارج النطاق — لا يُلمس ولو بدا «مجانياً»

- `issuance_idempotency` و`Idempotency-Key` وإعادة تعيين `claim_code` (KH-2.8.2).
- شاشة الكونسول (C13) — لكن **العقد** يُنشر هنا ليبدأ re-vendor.
- نطاق `revoke` عبر M2M، OAuth2، mTLS، IP allow-list، rate limiting لكل عميل (KH-2.5).
- أي تعديل على `consuming_party` أو بادئات مفاتيحه.
- الـ connector المرجعي والمحاكيات (repo مستقل، FS-2.7b).
- تجديد توكن Vault أو أي chore تشغيلي — بوابة §0.3 تفترض إنجازه سلفاً.
- تصحيح `moi-immegration` أو أي بيانات staging.

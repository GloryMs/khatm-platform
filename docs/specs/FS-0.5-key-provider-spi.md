# FS-0.5 — Key Provider SPI & SoftKeyProvider

> **Tasks:** KH-0.5.1 / KH-0.5.2 / KH-0.5.3 · **Repo:** khatm-platform · **Status:** APPROVED
> **Sources of truth:** SEC 21 §3 (SPI), §4 (lifecycle) · FS-0.2 §3.2 (`issuer_key`) · SAD §4.1 (key module) · ADR-09
> **يستبدل:** `SoftKeyService` المؤقت (in-memory، لا يبقى بعد إعادة التشغيل، لا يكتب في `issuer_key`)
> **اللغة:** شرح عربي، عقود وكود إنجليزية.

---

## 1. الهدف

مفتاح التوقيع هو أخطر أصل في المنظومة (SEC: "a leaked issuer key is a trust
catastrophe"). هذا الـ spec يبني الطبقة التي تجعل بقية الكود **لا يعرف شيئاً عن
المفاتيح** سوى أنه يطلب توقيعاً — بحيث يكون استبدال SOFT بـ KMS (KH-2.3) ثم HSM
(KH-3.1) تبديل بروفايل، لا إعادة كتابة. المسار الحرج في WBS صريح:
`KH-0.5 → KH-2.3 → KH-3.1`.

**النطاق:** واجهة `KeyProvider` (SPI) + `SoftKeyProvider` (keystore ملفي مشفّر) +
`kid` في كل JWS + endpoint الـ JWKS + التحقق بالـ `kid` حصراً + bootstrap المفتاح الأول.
**خارج النطاق:** rotation runbook والتدوير المجدول (KH-2.3.2)، KMS/PKCS11،
واجهة إدارة المفاتيح في الكونسول (قراءة فقط لاحقاً في KH-1.1)، الـ trust bundle (SEC §6).

## 2. الشكل المعماري — طبقتان داخل وحدة `key`

القاعدة الحاكمة من SEC §3: *"Signing is the only operation the credential module
may request; it never touches key material."* لذلك نفصل وجهين:

```
key/
├─ api/            ← ما تراه الوحدات الأخرى (النافذة الوحيدة عبر @NamedInterface)
│  ├─ KeySigner        sign(signingInput) → SignResult(kid, signature, algo)
│  └─ KeyVerifier      resolvePublicKey(kid) → Optional<PublicKeyHandle>
├─ domain/         ← module-private
│  ├─ KeyProvider      الـ SPI الكامل (sign / publicJwks / rotate / keys)
│  ├─ SoftKeyProvider  التنفيذ الملفي (هذا الـ spec)
│  ├─ KeyLifecycleService  إدارة الحالات + قيد one-active
│  └─ KeyBootstrap     توفير المفتاح الأول عند الإقلاع
└─ web/
   └─ JwksController   GET /.well-known/jwks.json
```

| القرار | التبرير |
|---|---|
| D1 — `KeySigner`/`KeyVerifier` (الموجودتان في `key::api`) تبقيان **الواجهة الوحيدة** عبر الوحدات؛ `KeyProvider` الكامل module-private | وحدة `credential` لا يجب أن ترى `rotate()` أصلاً — أقل سطح، أقل خطأ. `ModulithBoundariesTest` يفرضها |
| D2 — واجهة `KeyProvider` مطابقة لعقد SEC §3 (sign / publicJwks / rotate / keys) مع `TenantId` في كل توقيع رغم أحادية المستأجر اليوم | KMS وPKCS11 غداً per-tenant؛ إدخال المعامل الآن مجاني، لاحقاً migration |
| D3 — اختيار التنفيذ عبر Spring `@ConditionalOnProperty(khatm.keys.provider)` قيمة `SOFT` (لاحقاً `KMS`, `PKCS11`) | تبديل مزوّد = تغيير config، صفر كود |

## 3. `SoftKeyProvider` — التخزين والحماية

- **الملف:** PKCS#12 keystore واحد (`khatm-keys.p12`)، مساره من
  `khatm.keys.soft.keystore-path` (خارج شجرة الـ repo؛ في Docker: named volume
  `khatm_keys`). الـ alias لكل مفتاح = الـ `kid` نفسه.
- **كلمة السر:** `khatm.keys.soft.passphrase` من env حصراً (تكامل KH-0.3.4).
  **فشل إقلاع صريح** إن غابت في أي بروفايل غير `local` — لا قيمة افتراضية صامتة.
  في `local` فقط: قيمة افتراضية موثقة ليعمل `docker compose up` بلا إعداد.
- **العلاقة مع `issuer_key`:** الجدول يحمل **العام فقط** (`public_jwk`) + الحالة +
  `provider_ref` = alias في الـ keystore. الخاص لا يغادر الـ keystore أبداً — لا في
  الجدول، لا في اللوغات، لا في heap dumps (كائنات `Destroyable`، تصفير عند الإغلاق —
  SEC §3 rules).
- **التوليد:** ES256 (P-256) عبر JCA القياسية؛ `SecureRandom` النظامي.

## 4. `kid` — الصيغة والقواعد (KH-0.5.3)

- الصيغة: **`{tenant-slug}:key-{seq}`** (مثال: `default:key-1`) — مطابقة لنمط
  doc 05 (`moj-sy:key-3`). الـ seq عدّاد لكل مستأجر يتقدم مع كل مفتاح جديد.
- كل JWS header يحمل `kid` — بلا استثناء (يشمل توقيع Status List لاحقاً في KH-1.3).
- **التحقق يحلّ المفتاح بالـ `kid` حصراً**: `kid` مجهول أو بحالة `RETIRED` →
  `bad_signature`. **ممنوع** أي fallback إلى "المفتاح الأحدث" (SEC §3 نصاً).

## 5. دورة الحياة والـ bootstrap

آلة الحالات من SEC §4: `PENDING → ACTIVE → RETIRING → RETIRED` (DESTROYED خارج نطاق MVP).

- **قيد one-active** يفرضه الفهرس الجزئي `issuer_key_one_active` الموجود منذ V1؛
  `KeyLifecycleService.rotate()` تنفّذ الانتقال داخل transaction واحدة:
  الحالي `ACTIVE→RETIRING`، الجديد يولَّد `PENDING→ACTIVE` — بترتيب يرضي الفهرس.
- `rotate()` **تُنفَّذ في هذا الـ spec** (بسيطة، والجدول جاهز) لكن **لا endpoint لها** —
  تُستدعى من الاختبارات فقط اليوم؛ الكشف الإداري يأتي مع RBAC (KH-2.2). التدوير
  المجدول والـ runbook يبقيان KH-2.3.2.
- **`KeyBootstrap` عند الإقلاع:** إن لم يوجد مفتاح `ACTIVE` للمستأجر الافتراضي →
  توليد + تخزين + صف `issuer_key` + سطر audit (`KEY_CREATED`). idempotent — إقلاع
  ثانٍ لا يفعل شيئاً. (Phase 2 يستبدل هذا بمراسم توفير صريحة — يوثَّق في README الوحدة.)
- **migration من `SoftKeyService`:** لا توافق خلفي مطلوب — كل التوقيعات السابقة
  محلية/ديمو. `DemoSeeder` يعيد الإصدار بالمزوّد الجديد. تُحذف
  `SoftKeyService` بالكامل (لا `@Deprecated` — قاعدة العمل 4: تنفيذ واحد لكل مفهوم).

## 6. JWKS endpoint

- `GET /.well-known/jwks.json` — يخدم مفاتيح `ACTIVE` + `RETIRING` (العامة فقط)
  للمستأجر الافتراضي. `RETIRED` لا تُنشر. (per-tenant paths في KH-2.1.3.)
- Cache header بسيط (`Cache-Control: max-age=300`) — المحفظة والـ SDK يعتمدان عليه
  في KH-1.2.4، والتقادم القصير يوازن بين الأداء واستجابة التدوير الطارئ.
- بلا مصادقة — مفاتيح عامة بطبيعتها.

## 7. الإعداد (config surface كامل)

```yaml
khatm:
  keys:
    provider: SOFT                     # SOFT | KMS | PKCS11 (القيمتان الأخيرتان لاحقاً)
    soft:
      keystore-path: /var/khatm/keys/khatm-keys.p12
      passphrase: ${KHATM_KEYS_PASSPHRASE}   # env فقط؛ local وحده له default
```
تعديل docker-compose (khatm-platform وkhatm-deploy): named volume `khatm_keys`
على `/var/khatm/keys` + تمرير env — يدخل ضمن هذا الـ PR.

## 8. معايير القبول (DoD)

1. `SignResult` من `KeySigner` يحمل `kid` صحيحاً، وheader الـ JWT الصادر من مسار
   الإصدار يحمله (اختبار تكامل عبر `credential` module).
2. **الثبات:** إصدار → إعادة تشغيل التطبيق (سياق Spring جديد، نفس ملف keystore
   وقاعدة البيانات) → التحقق من التوقيع القديم ينجح بنفس الـ `kid`. (هذا بالضبط ما
   كان `SoftKeyService` يفشل فيه.)
3. `kid` مجهول → التحقق يرفض بـ `bad_signature`؛ ولا يوجد أي مسار fallback
   (يُثبت باختبار يوقّع بمفتاح خارج السجل).
4. `rotate()`: بعدها مفتاح `ACTIVE` واحد بالضبط (الفهرس يبقى راضياً)، القديم
   `RETIRING`، JWKS يعرض الاثنين، توقيع قديم ما زال يتحقق، والتوقيع الجديد يصدر
   بالـ `kid` الجديد.
5. كلمة سر خاطئة على keystore موجود → فشل إقلاع برسالة واضحة (لا إنشاء ملف جديد
   فوق القديم). غياب الـ passphrase في بروفايل غير local → فشل إقلاع.
6. لا مادة مفتاح خاص في: صف `issuer_key`، أي سطر لوغ، أي استجابة API
   (فحص يدوي موثق + assertion في اختبار الـ JWKS أن الحقول تقتصر على العامة).
7. `KeyBootstrap` idempotent (اختبار: إقلاعان متتاليان → صف واحد).
8. سطرا audit: `KEY_CREATED` و`KEY_ROTATED` يُكتبان في `audit_log`.
9. المعتاد: Javadoc + README الوحدة محدّث + حزمتا الرسائل (إن أُضيفت مفاتيح
   userfacing) + البناء الكامل أخضر + CI أخضر على الـ PR.

## 9. أثر على بقية المنظومة

- **KH-0.4 (SD-JWT، الجلسة التالية):** سيوقّع عبر `KeySigner` كما هو — لا تغيير
  على عقده. هذا سبب ترتيب 0.5 قبل 0.4.
- **KH-1.3 (Status List):** ستوقّع الـ artifacts بنفس `KeySigner`.
- **المحفظة (KH-1.2.4):** ستسحب من `/.well-known/jwks.json` — العقد يثبت الآن.
- **KH-2.3 (KMS):** يضيف `KmsProvider` implements `KeyProvider` + قيمة config —
  لا يلمس أي مستهلك.

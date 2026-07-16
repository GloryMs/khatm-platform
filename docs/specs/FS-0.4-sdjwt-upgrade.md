# FS-0.4 — SD-JWT Signing Upgrade

> **Tasks:** KH-0.4.1 / KH-0.4.2 / KH-0.4.3 (platform) — KH-0.4.4 (wallet) خارج هذا الـ spec
> **Repo:** khatm-platform · **Status:** APPROVED
> **Sources of truth:** SEC 21 §2 (SD-JWT, QR discipline) · FS-0.2 §3.3 (`sd_fields`) / §3.6 (`signed_payload`) / §3.7 (`claim_code`) / D9 (P1) · FS-0.5 §9 (يوقّع عبر `KeySigner` بلا تغيير)
> **يفكّ:** الـ blocker المفتوح `claim_code.disclosures_enc` (الشق التشفيري منه — worker التصفير يبقى KH-1.2.1)
> **اللغة:** شرح عربي، عقود وأمثلة إنجليزية.

---

## 1. الهدف

ترقية الإصدار من JWT عادي (قيم مكشوفة داخل التوقيع) إلى **SD-JWT** وفق مواصفة
IETF (selective-disclosure JWT): كل قيمة أعمال تتحول إلى **disclosure** منفصلة
(ملح عشوائي + اسم + قيمة)، ولا يدخل الـ JWT الموقّع إلا **هضمها** (`_sd` digests).
النتيجة المزدوجة: الحامل يكشف الحقول التي يختارها فقط (وعد المنتج الأساسي)،
والمنصة تخزّن `signed_payload` **بلا أي قيمة** — أي أن P1 يصبح خاصية رياضية في
بنية التوقيع نفسها لا مجرد سياسة تخزين.

**النطاق:** بناء SD-JWT عند الإصدار، مسار التحقق بإعادة حساب الهضمات، ربط
`sd_fields`، تشفير الـ disclosures في `claim_code`، تحديث `DemoSeeder` والاختبارات.
**خارج النطاق:** KB-JWT / `cnf` key binding (Phase 3 حسب SEC §2)، الـ decoy
digests (تُترك خلف علم config مطفأ — Phase 3)، واجهة الكشف الانتقائي في المحفظة
(KH-0.4.4 في repo المحفظة)، وworker تصفير `disclosures_enc` (KH-1.2.1، ينتظر
skeleton الـ ADR-09).

## 2. قرارات التصميم

| # | القرار | التبرير |
|---|---|---|
| D1 | **كل حقول `claims_def` تدخل `_sd` بلا استثناء** — لا يوجد business claim صريح في الـ payload | لو بقي حقل واحد صريحاً لدخلت قيمته `signed_payload` المخزّن → خرق P1 (FS-0.2 D9). المخفي كلياً هو الشكل الوحيد المتسق |
| D2 | **إعادة تعريف دلالة `sd_fields`** (بلا تغيير مخطط): لم تعد "ما يُخفى" (الكل مخفي بحكم D1) بل **"ما يجوز للحامل حجبه عند العرض"**. الحقول خارج `sd_fields` = إلزامية الكشف في أي عرض؛ التحقق يرفض عرضاً ينقصها | يعطي الجهة المُصدِرة سلطة "الحد الأدنى الإلزامي" (مثلاً: رقم الوثيقة والاسم إلزاميان، تاريخ الميلاد اختياري الكشف) ويحفظ عمود FS-0.2 كما هو |
| D3 | **الحقول الصريحة الوحيدة** في الـ JWT: `iss`, `iat`, `nbf`, `exp`, `vct` (= `{schema.code}:{version}`), `ref`, `status` (إشارة status_list: list URL + idx)، `_sd`, `_sd_alg` — لا `sub` ولا أي معرّف حامل | `pseudo_ref` نفسه قد يكون شبه مُعرِّف؛ الربط بالحامل يعيش في الـ DB لا في الـ token. `cnf` يُضاف Phase 3 |
| D4 | **المكتبة:** `com.authlete:sd-jwt` (Apache-2.0، بناء/تفكيك/تحقق هضمات فوق Nimbus الموجود أصلاً) — والتوقيع يبقى حصراً عبر `KeySigner` (المكتبة تبني الـ signing input، مفتاحنا يوقّعه) | بناء الـ disclosures يدوياً (base64url + ترتيب حقول + ملح) صغير لكنه حقل ألغام توافقية. شرط صارم: إن تبين في الجلسة أن المكتبة لا تقبل توقيعاً خارجياً (raw signing input)، **توقف وأبلغ** — البديل اليدوي فوق Nimbus قرار يعود لبوابة الاعتماد لا للجلسة |
| D5 | هضم `sha-256` (`_sd_alg`)، ملح 128-bit من `SecureRandom` لكل disclosure | SEC §2 (SHA-256 معياري) |
| D6 | **صيغة العرض والتخزين:** `signed_payload` = الـ compact JWT فقط (هضمات). صيغة التقديم للتحقق: `<jwt>~<disclosure_1>~...~<disclosure_n>~` (الصيغة القياسية بالـ tilde) | مطابق لتعليق FS-0.2 §3.6 حرفياً؛ الصيغة القياسية تفتح توافق SDKات الطرف الثالث لاحقاً |
| D7 | **`disclosures_enc` تُملأ الآن مشفّرة AES-256-GCM**: مفتاح من `khatm.claims.enc-key` (env، 32 bytes base64؛ فشل إقلاع صريح إن غاب خارج `local` — نفس نمط passphrase الـ keystore)؛ nonce عشوائي لكل صف يُخزَّن مع الـ ciphertext | الـ disclosures وُلدت في هذه المهمة — كتابتها plaintext ثم "تشفير لاحقاً" هو بالضبط نوع الدَين الذي منعنا الـ blocker من أجله. التصفير عند claim/انتهاء يبقى KH-1.2.1 |
| D8 | **رفض صارم في التحقق:** disclosure هضمه ليس في `_sd` → رفض؛ disclosure مكرر الاسم → رفض؛ حقل خارج `sd_fields` غائب عن العرض → رفض (D2)؛ `_sd_alg` غير sha-256 → رفض | سطح التحقق هو خط الدفاع؛ التساهل هنا يلغي قيمة التوقيع |

## 3. تدفق الإصدار (بعد الترقية)

```
issue(schemaCode, holderRef, claims{name→value})
 1. validate claims against schema.claims_def (types/required)   ← موجود
 2. for each claim: salt = 128-bit random
    disclosure_i = base64url(JSON [salt, name, value])
    digest_i     = base64url(sha256(disclosure_i))
 3. payload = {iss, iat, nbf, exp, vct, ref, status{list,idx},
               _sd:[digest_1..n] (shuffled), _sd_alg:"sha-256"}
 4. jws = KeySigner.sign(payload)          ← FS-0.5، بلا أي تغيير على عقده
 5. persist credential(signed_payload = jws, payload_hash = sha256(jws))
 6. claim_code: disclosures_enc = AES-GCM(join(disclosures,"~"))   ← D7
 7. response: { ref, sdJwt: "<jws>~d1~..~dn~" }   ← التسليم المباشر (عابر، لا يُخزَّن)
```

## 4. تدفق التحقق

```
verify(presentation "<jwt>~<disc..>~")
 1. parse jwt → kid → KeyVerifier.resolvePublicKey(kid)   ← FS-0.5، لا fallback
 2. verify signature; check nbf/exp; check status_list bit + revoked fast path ← موجود
 3. _sd_alg == sha-256 else reject
 4. for each presented disclosure: digest ∈ payload._sd else reject;
    no duplicate claim names else reject
 5. mandatory-disclosure check (D2): every claims_def field NOT in schema.sd_fields
    must be present among disclosures, else reject
 6. result: verified claims = disclosed {name→value} only + الحقول البنيوية
```

ملاحظة معمارية صغيرة: البند 5 يتطلب أن يصل مسار التحقق إلى تعريف الـ schema —
موجود أصلاً عبر `SchemaCatalog#findById` (`schema :: api`) بلا حدود جديدة.

## 5. أثر على واجهات الـ API الحالية

- **`/issue` (بأي مسار حالي):** الاستجابة تعيد `sdJwt` كاملاً بالصيغة القياسية
  بدل الـ JWT العاري. حقل الاستجابة القديم يُستبدل لا يُضاف بجانبه (قاعدة العمل 4).
- **`/verify`:** يقبل صيغة الـ tilde. تمرير JWT عارٍ بلا disclosures = عرض صفري
  الكشف — يمر بالبنود 1–4 ثم يسقط غالباً في البند 5 (إلا إذا كانت `sd_fields`
  تغطي كل الحقول). هذا سلوك صحيح لا bug — يوثَّق في OpenAPI.
- كسر التوافق مع POC المحفظة **معلوم ومقبول** — مسجل في `khatm-wallet/docs/STATE.md`
  ويُعالج في KH-1.2.

## 6. معايير القبول (DoD)

1. **اختبار P1 البنيوي:** بعد إصدار وثيقة بحقول ديمو، JSON الـ payload المفكوك من
   `signed_payload` **لا يحتوي أي مفتاح من مفاتيح `claims_def` ولا أي قيمة من قيمها**
   (assertion نصي مباشر) — فقط الحقول البنيوية (D3) و`_sd`/`_sd_alg`.
2. **round-trip كامل:** إصدار → تقديم كل الـ disclosures → تحقق ناجح والـ claims
   المستخرجة تطابق المُدخلة.
3. **كشف انتقائي:** تقديم subset (الإلزامية + بعض الاختيارية) → نجاح، والحقول
   المحجوبة غائبة عن ناتج التحقق.
4. **العبث:** تعديل قيمة داخل disclosure → رفض؛ disclosure مزوّر (هضمه ليس في
   `_sd`) → رفض؛ disclosure مكرر → رفض؛ حجب حقل إلزامي (خارج `sd_fields`) → رفض.
5. **`disclosures_enc`:** مُشفَّر فعلاً (الاختبار يفك بالـ AES-GCM ويطابق، ويفشل
   بمفتاح خاطئ)؛ غياب `khatm.claims.enc-key` خارج `local` → فشل إقلاع؛
   سطر الـ blocker في STATE يُحدَّث (يبقى منه worker التصفير فقط).
6. **regression مفاتيح:** اختبارات FS-0.5 كلها خضراء بلا تعديل على عقودها
   (`KeySigner`/`KeyVerifier` لم يتغيرا) — خصوصاً اختبار إعادة التشغيل.
7. لا قيمة disclosure ولا ملح في أي سطر لوغ (فحص + قاعدة SEC §9 القائمة).
8. المعتاد: Javadoc + README وحدة `credential` محدّث (شرح D1/D2 تحديداً) +
   OpenAPI محدّث لصيغ الطلب/الاستجابة الجديدة + البناء وCI أخضران.

## 7. أثر على بقية المنظومة

- **KH-1.2.1 (claim flow):** المحفظة ستستلم `disclosures_enc` مفكوكاً عند الـ claim —
  البنية جاهزة الآن؛ يتبقى للـ KH-1.2.1 مسار التسليم وworker التصفير.
- **KH-1.2.3 (منتقي الكشف):** يقرأ `sd_fields` بدلالتها الجديدة (D2) — الحقول
  القابلة للتبديل في الواجهة.
- **KH-1.3 (Status List):** حقل `status` الصريح (D3) يثبّت شكل الإشارة الآن.
- **KH-1.6 (OpenAPI المنشور):** صيغ SD-JWT تدخل العقد المنشور — الكونسول
  والمحفظة يبنيان عليها.
- **Phase 3:** `cnf`/KB-JWT يُضافان كحقل صريح جديد + توقيع حامل — بلا كسر لهذا التصميم.

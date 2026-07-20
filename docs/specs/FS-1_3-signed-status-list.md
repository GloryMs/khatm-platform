# FS-1.3 — Signed Status List (قائمة الإبطال الموقّعة)

> **Task:** KH-1.3 · **Repo:** khatm-platform · **Status:** APPROVED (تفويض مسبق — قابلة للنقض عند مراجعة الـ PR)
> **Sources of truth:** SAD 20 §5.4 (تدفق الإبطال) · §6 (`GET /sl/{tenantSlug}/{listCode}`) · ADR-003 (bitstring لا OCSP) · NFR-06 (≤ 60s من الإبطال إلى النشر) · FS-0.2 §3.5 (`status_list`) · ADR-09 (بنية worker/events) · FS-1.2.1 D4 (`statusListUri`)
> **اللغة:** شرح عربي، عقود إنجليزية.

## 1. الهدف والنطاق

الإبطال اليوم يقلب `credential.revoked` فقط (المسار السريع). هذا الـ spec يجعل
«الحقيقة» — الـ bitstring — حيّة وموقّعة ومنشورة: كل إبطال يقلب البِت في
transaction الإبطال نفسها، وworker يعيد التوقيع والنشر خلال ≤ 60 ثانية، والمتحقق
غير المتصل يستطيع سحب artifact موقّع والتحقق منه بمفاتيح JWKS.

**خارج النطاق:** object storage/CDN (Phase 2 — SAD §8)، سياسة staleness لدى
المتحقق (config المستهلك)، un-revoke (لا وجود له تصميماً).

## 2. القرارات D1–D7

| # | القرار | التبرير |
|---|---|---|
| D1 | صيغة الـ artifact: **compact JWS (ES256، مفتاح الجهة الفعّال، `kid` في الرأس)** بحمولة `{ "list": "<list_code>", "ver": <version>, "cap": <capacity>, "bits": "<base64url(gzip(bitstring))>", "iat": <epoch> }` — v1 خاصة بسيطة، ومواءمة IETF Token Status List تُراجع في Phase 3 (يقيَّد في الـ spec كملاحظة ADR-003) | نفس بنية مفاتيح/تحقق SD-JWT القائمة حرفياً — المتحقق يملك JWKS أصلاً؛ المعيار الـ IETF ما زال draft والالتزام به الآن كلفة بلا عائد MVP |
| D2 | النشر: **`GET /sl/{tenantSlug}/{listCode}`** (خارج `/api/v1` كـ well-known، وفق جدول SAD §6 نصاً) — عام، `Content-Type: application/jose`، مع `ETag` = version و`Cache-Control: max-age=60` | التحقق لا يُحتجز خلف حساب (P2)؛ الـ ETag يجعل سحب المتحققين الدوري رخيصاً |
| D3 | **قلب البِت متزامن، النشر لا-متزامن**: transaction الإبطال تقفل صف `status_list` (`FOR UPDATE`)، تقلب البِت، ترفع `version`، وتبقي `revoked` السريع كما هو — ثم حدث `StatusListChanged` (`@Externalized`، بنية ADR-09 نفسها) يدفع consumer في الـ worker يعيد gzip+توقيع ويخزّن الـ JWS | الحقيقة تتقدم ذرّياً مع الإبطال (لا نافذة «مُبطَل لكن البِت صفر» في القاعدة)؛ إعادة التوقيع عمل مؤجَّل مثالي للـ worker؛ NFR-06 يقاس على النشر لا القلب |
| D4 | تخزين الـ artifact: **`V3__status_list_artifact.sql`** يضيف `signed_artifact text` (الـ JWS نفسه) و`artifact_version bigint` إلى `status_list`؛ `signed_artifact_ref` القائم يبقى مؤشّر التخزين الخارجي لـ Phase 2 (nullable، غير مستخدم الآن) | migration إضافية-فقط، V1 لا يُمَسّ؛ التقديم من القاعدة يكفي on-prem MVP (SAD §8: «status lists served locally») |
| D5 | consumer الـ worker **متجمّع (debounced)**: يعيد النشر إذا `artifact_version < version` — عاصفة إبطالات = إعادة توقيع واحدة للنسخة الأخيرة، idempotent بالبنية القائمة (خطأ → retry → DLQ) | إعادة التوقيع لكل حدث فردي هدر؛ الشرط يجعل المستهلك idempotent وذاتي-اللحاق حتى بعد DLQ |
| D6 | `/verify` (متصل) يظل على المسار السريع `revoked` ويضيف حقلين إضافيين-فقط للاستجابة: `statusListVersion` (نسخة القائمة وقت التحقق) و`statusListUri` — وSAD §6's `statusListChecked` يُطبَّق كـ `true` عندما يقارن الخادم البِت فعلاً (يفعل — رخيص محلياً) | المتصل عنده القاعدة فلا يحتاج الـ artifact؛ الحقلان يمهّدان للمتحقق غير المتصل ويحترمان العقد الإضافي-فقط |
| D7 | `statusListUri` في استجابة FS-1.2.1 D4 يتحول من placeholder إلى `{base}/sl/{tenantSlug}/{listCode}` الحقيقي — تغيير قيمة لا شكل. Audit: `STATUS_LIST_PUBLISHED` (actor SYSTEM، detail: list_code + version) عند كل نشر | كما وعد FS-1.2.1 §5 حرفياً |

## 3. الشكل التنفيذي

- **وحدة `status/` الجديدة** (top-level — يقرّها SAD §4.1/ADR-001 التي تسمّيها مرشح
  الاستخراج الأول): domain (BitstringCodec gzip/b64url + قلب البِت + النشر)،
  worker (consumer بشرط D5، مقيَّد بدور worker)، web (`StatusListController` للمسار
  العام D2). وحدة `credential` تستدعي قلب البِت عبر الواجهة المسماة — لا وصول
  مباشراً لجدولها (حدود Modulith كالمعتاد).
- `SecurityConfig`: `/sl/**` يُضاف للقائمة العامة (**تصبح أربعة** — تحديث اختبار
  الحصر القائم).
- config: `khatm.status.publish.debounce=2s` (نافذة تجميع D5)، والاختبارات تضبطها
  قصيرة.

## 4. معايير القبول (DoD)

1. إبطال → في نفس الـ transaction: البِت `status_idx` = 1 في `bitstring`
   و`version` مرتفعة و`revoked` مضبوط؛ فشل بعد القلب = rollback كامل (ذرّية D3).
2. خلال النافذة (config قصيرة في الاختبار): الـ worker نشر JWS جديداً —
   `signed_artifact` محدَّث، `artifact_version == version`، صف
   `STATUS_LIST_PUBLISHED`. **اختبار NFR-06 صريح** بقياس زمني ≤ الحد المضبوط.
3. `GET /sl/{slug}/{code}`: عام بلا credentials، يرجع JWS يُتحقق منه ضد JWKS
   بالـ `kid` الصحيح، فكّ `bits` (b64url→gunzip) يُظهر البِت المقلوب؛ `ETag`/304
   يعملان.
4. عاصفة: 25 إبطالاً متسارعاً لنفس القائمة ← نشر واحد أو قليل (لا 25)،
   والنسخة النهائية المنشورة تعكس كل البتات (اختبار D5).
5. تنافس: إبطالان متوازيان لوثيقتين على نفس القائمة ← لا تحديث ضائع
   (`FOR UPDATE` على صف القائمة؛ كلا البِتّين مقلوبان والنسخة +2).
6. `/verify` لوثيقة مُبطلة يرجع النتيجة المعهودة + `statusListChecked:true` +
   `statusListVersion` + `statusListUri`؛ redeem (FS-1.2.1) يرجع الـ URI الحقيقي.
7. migration V3 إضافية-فقط: `MigrationImmutabilityTest` وclean-boot أخضران.
8. العقد `openapi.json` يتجدد إضافياً-فقط (المسار العام الجديد + الحقول الجديدة).
9. لا bitstring خام ولا محتوى artifact في اللوغات (سطر النشر: list_code + version
   فقط)؛ المعتاد كاملاً (قواعد 1–4، الحزم، error-codes إن وُجدت مفاتيح، CI أخضر).

## 5. الأثر

- المتحقق غير المتصل (SDK/محفظة، Phase لاحقة) صار له مصدر إبطال قابل للتحقق.
- KH-1.4.3 غير متأثر؛ KH-2.5 (object storage/CDN) يملأ `signed_artifact_ref`
  لاحقاً دون تغيير شكل.
- بعد هذا الـ spec: platform v1 يكتمل بـ KH-1.4.3 ثم لا يتبقى قبل المحفظة شيء
  خادمي.

# SESSION-CHORE-VAULT-HOUSEKEEPING — إغلاق الذيل التشغيلي لنشر Vault على staging

> **النوع:** chore session — documentation/config/scripts فقط، **لا تعديل على `src/**` إطلاقاً**.
> **المستودعات:** `khatm-platform` (تحقق فقط) + `khatm-deploy` (إضافة ملفات).
> **المرجع:** `docs/STATE.md` (خصوصاً سجل 2026-08-15/16: نشر Vault على bunny MC والهجرة SOFT→VAULT)
> و`STATE-central-2026-08-16.md`.
> **قاعدة حاكمة:** unseal keys وroot token تعيش **فقط** في password manager الخاص بمجد —
> لا تظهر في أي repo أو ملف أو هذا الحوار. أي محتوى يشبه سراً (token، unseal key، كلمة مرور)
> يظهر أثناء العمل ← **SELF-STOP فوراً** وإبلاغ مجد.

---

## Preamble (بوابة قبل أي عمل)

1. `git fetch && git status` على `khatm-platform`: تأكد أن `origin/main` يحوي تصحيحات جلسة
   `SESSION-CHORE-VAULT-STAGING-RECORD` (2026-08-16/17):
   - `docker/vault-policy/khatm-transit-app.hcl` يمنح `["create", "update", "read"]` على
     `transit/keys/*` (لا `create+read` فقط) مع تعليق يوثّق الاستنتاج التجريبي وتاريخه.
   - `docs/deploy-staging.md` يحوي قسم Vault hardening المحدَّث (نشر MC، الصورة المخصّصة
     `khatm-vault:1.17-mc`، التخزين المشترك، init/unseal، تصحيح الـ policy، runbook إعادة الختم).
2. **إن كانت هذه التغييرات ما تزال working tree غير ملتزَم** ← التزمها عبر chore PR قياسي
   (`chore/vault-staging-record`) قبل المتابعة، ولا تخلطها بأعمال هذه الجلسة.
3. **إن كانت مدموجة على `main`** ← سجّل ذلك في تقرير الجلسة وتابع.
4. تحقق من `Dockerfile.postgres` غير المتعقَّب في working tree المنصة (بند معلّق من لقطة
   2026-08-16): إن كان موجوداً ← **SELF-STOP** واسأل مجد (يُلتزم بتوثيق أم يُحذف؟).
   إن كان غائباً من الشجرة كلياً ← سجّل أنه حُسم سابقاً وتابع.

---

## Scope — ثلاثة أجزاء

### الجزء A — `khatm-deploy`: ضم artifacts الـ Vault للمستودع

الهدف: جعل صورة `ghcr.io/gloryms/khatm-vault:1.17-mc` وإجراءات تشغيلها قابلة لإعادة الإنتاج
من المستودع. الملفات الثلاثة تعيش حالياً على جهاز مجد خارج git:

| الملف | الوجهة المقترحة في `khatm-deploy` |
|---|---|
| `Dockerfile` الخاص بالصورة المخصّصة | `vault/Dockerfile` |
| `vault-config.json` | `vault/vault-config.json` |
| `unseal-staging-vault.sh` | `vault/unseal-staging-vault.sh` |

**[MAJD]** يزوّد محتوى الملفات الثلاثة (لصقاً في الجلسة أو وضعها في المجلد). **SELF-STOP** إن لم
تكن متوفرة عند بلوغ هذه الخطوة.

**بوابة تعقيم إلزامية قبل الالتزام (لكل ملف):**
- `unseal-staging-vault.sh`: يجب ألا يحوي أي unseal key أو token حرفياً — المفاتيح تُقرأ من
  prompt تفاعلي أو متغيرات بيئة وقت التشغيل فقط. إن وُجد سر مضمَّن ← **SELF-STOP** (لا تلتزم
  نسخة «منظّفة» من تلقاء نفسك؛ التعقيم قرار مجد).
- `vault-config.json`: تأكد أنه إعداد بنيوي فقط (storage path، listener، `disable_mlock: true`)
  بلا أسرار.
- أضف `vault/README.md` قصيراً يوثّق: سبب الصورة المخصّصة (علم `no-new-privileges` في MC يكسر
  entrypoint الصورة الرسمية)، الانحرافات الثلاثة المقبولة على staging (الصورة المخصّصة،
  `disable_mlock: true`، توكن تطبيق بلا انتهاء مقابل AppRole في الإنتاج)، وإجراء الـ unseal
  اليدوي بعد كل إعادة نشر للـ pod (يشير إلى قسم Vault hardening في
  `khatm-platform/docs/deploy-staging.md` كمرجع أساسي بدل التكرار).
- PR قياسي على `khatm-deploy` (`chore/vault-artifacts`)، مجد يدمج بعد المراجعة.

### الجزء B — تحضير أوامر التشغيل الحية (تحضير فقط — التنفيذ [MAJD] حصراً)

Claude Code **لا ينفّذ** أي نداء ضد staging Vault. المطلوب: ملف
`vault/runbooks/staging-housekeeping.md` في `khatm-deploy` (ضمن نفس الـ PR أعلاه) يحوي الأوامر
الجاهزة بمتغيرات placeholder، تُنفَّذ من **Git Bash** (لا PowerShell — مشكلة اقتباس JSON
الموثّقة على Windows ضد bunny MC):

1. **تعطيل audit device** (كان أداة تشخيص للـ ACL، انتهى دوره):
   ```bash
   export VAULT_ADDR="https://<temporary-allowlisted-cdn-endpoint>"
   export VAULT_TOKEN="<root-token-from-password-manager>"   # لا يُحفظ في أي ملف
   curl -sS -X DELETE -H "X-Vault-Token: $VAULT_TOKEN" "$VAULT_ADDR/v1/sys/audit/stdout"
   # تحقق: يجب أن تعود القائمة فارغة من stdout
   curl -sS -H "X-Vault-Token: $VAULT_TOKEN" "$VAULT_ADDR/v1/sys/audit"
   ```
2. **تحقق اختياري من التوكن**: `GET /v1/auth/token/lookup-self` بتوكن التطبيق للتأكد الفعلي أن
   `ttl: 0` (بلا انتهاء) كما هو موثَّق — يحسم الالتباس مع أي ملاحظة سابقة عن انتهاء في
   2026-09-16.
3. **جرد المفاتيح**: أمر واحد يعرض حالة كل مفاتيح التوقيع من جهة المنصة
   (`GET /api/v1/admin/signing-keys` عبر جلسة الكونسول المحلي) لتأكيد قائمة RETIRING الفعلية
   (المتوقَّع: key-2 حتى key-8 تقريباً، ومنها الدورتان التشخيصيتان key-5/key-6) — **لا تفترض
   القائمة؛ الجرد الفعلي هو المرجع** ("the code is the reference").

### الجزء C — تنظيف مفاتيح RETIRING (توثيق الإجراء؛ التنفيذ [MAJD])

في نفس ملف الـ runbook:
- الانتقال RETIRING→RETIRED يتم **من جهة المنصة فقط** عبر
  `POST /api/v1/admin/signing-keys/{kid}/retire` (بوابة `key:manage` + TOTP، من الكونسول المحلي
  أو fetch من DevTools). حارس العمر الأدنى `KH-KEY-0422` سيرفض المفاتيح الأحدث من
  min-retiring-age — استخدام `force: true` قرار لكل مفتاح على حدة، ويوثَّق في الـ runbook أنه
  متعمَّد للمفاتيح التشخيصية.
- **قاعدة صريحة في الـ runbook: لا حذف لأي transit key من جهة Vault** (`DELETE transit/keys/*`
  غير ممنوح لتوكن التطبيق أصلاً، ولا يُنفَّذ بالـ root token أيضاً). RETIRED تبقى مادتها قائمة؛
  التحقق من الاعتمادات القديمة يقرأ `issuer_key.public_jwk` من Postgres، لكن الحذف الوقائي
  لمادة مفاتيح وقّعت اعتمادات حية ليس ضمن هذه الجلسة بأي حال. (veto point V1 أدناه.)

---

## Veto points

- **V1 — حذف مادة transit keys للمفاتيح المتقاعدة من Vault:** الافتراضي **لا** (يبقى الحذف خارج
  النطاق نهائياً). لا يُنفَّذ إلا بقرار صريح لاحق من مجد + المعماري.
- **V2 — موضع ملفات الـ Vault في `khatm-deploy`:** الافتراضي المجلد `vault/` كما في الجدول
  أعلاه. إن كان لبنية المستودع الحالية عرف مغاير واضح، اتبعه وسجّل ذلك.

## DoD

- [ ] بوابة الـ preamble مجتازة (تصحيحات chore السابقة مؤكَّدة على `main`).
- [ ] PR على `khatm-deploy` يضم `vault/Dockerfile` + `vault-config.json` + سكربت unseal معقَّم
      + `vault/README.md` + `vault/runbooks/staging-housekeeping.md` — مفتوح بانتظار دمج مجد.
- [ ] لا سر من أي نوع في أي diff (فحص يدوي أخير قبل فتح الـ PR).
- [ ] **[MAJD]** نفّذ الجزأين B وC على staging وأبلغ بالنتيجة (تُسجَّل في STATE بالإفادة).
- [ ] تحديث `docs/STATE.md` في `khatm-deploy` (وسطر إشارة في STATE المنصة إن لزم) عند إغلاق
      الجلسة.

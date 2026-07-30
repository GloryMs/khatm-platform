# FS-2.3 — KMS Provider & Key Rotation (KH-2.3.1 → KH-2.3.3)

> **Repos:** khatm-platform (+ كونسول C8 صغيرة، + تحقق wallet بلا جلسة متوقعة) · **Status:** Approved
> **Sources of truth:** FS-0.5 (KeyProvider SPI) · FS-0.2 §3.2 (`issuer_key`: دورة PENDING/ACTIVE/RETIRING/RETIRED،
> فهرس one-active، provider SOFT/KMS/PKCS11، `provider_ref`) · SEC §5/§8 (مفاتيح غير قابلة للتصدير؛ التطبيق لا
> يلمس المادة الخاصة) · WBS KH-2.3 · **مستوى النموذج:** Sonnet حصراً (وحدة key).
> **دليل خروج Phase 2 — النصف الثاني**: «دوران مفتاح ناجح منفَّذ وموثَّق» (KH-2.3.3) يولد من هذا الـ spec.

## 1. القرارات

| # | القرار |
|---|---|
| D1 | **الدوران يُبنى أولاً provider-agnostic** (يعمل على SOFT فوراً) ثم يأتي مزوّد KMS — عكس ترتيب WBS الاسمي، لأن دليل الخروج يتحقق بالدوران نفسه أياً كان المزوّد، ولأن فصل «منطق الدورة» عن «بنية Vault» يعزل المخاطر (نفس منطق فصل KH-2.1a/b). |
| D2 | **دلالة الدوران** (سطح `key:manage`): `POST /api/v1/admin/signing-keys/rotate` — ذرّياً: توليد مفتاح جديد عبر الـ SPI ← القديم `ACTIVE→RETIRING` ← الجديد `ACTIVE` (فهرس one-active هو الحَكم النهائي ضد السباقات — اختبار `ConcurrentRotationTest`: دورانان متزامنان ← واحد فقط ينجح). `RETIRING/RETIRED` يبقيان في JWKS ولا يوقّعان جديداً (FS-0.2 نصاً) — وثائق الأمس تتحقق غداً بلا أي عمل إضافي. |
| D3 | **قوائم الحالة بعد الدوران**: version bump إجباري لكل قوائم المستأجر ضمن نفس عملية الدوران (سابقة V9 كآلية، لكن runtime لا migration) ← الـ sweep القائم يعيد توقيعها بالمفتاح الجديد خلال دورته. لا قائمة تبقى موقَّعة بمفتاح RETIRING أطول من دورة sweep واحدة. |
| D4 | **التقاعد**: `POST /api/v1/admin/signing-keys/{kid}/retire` — يقبل `RETIRING→RETIRED` فقط (409 لغيره) + حارس عمر أدنى قابل للضبط (`khatm.keys.min-retiring-age`, افتراضي 30 يوماً؛ تجاوزه يتطلب `force=true` مدقَّقاً) — حماية من تقاعد متسرّع يقتل تحقق وثائق طويلة العمر متداولة offline. RETIRED يبقى في JWKS (قرار FS-0.2؛ إخراجه النهائي أرض trust-bundle في Phase 3). |
| D5 | **مزوّد KMS الأول = Vault Transit** (`VaultTransitProvider implements KeyProvider`): self-hosted/on-prem أولاً بتصميم للسوق المستهدف؛ ECDSA P-256 sign/verify عبر transit؛ المادة الخاصة لا تغادر Vault إطلاقاً (`exportable=false`)؛ `provider_ref` = اسم مفتاح transit؛ compose يكتسب خدمة vault (dev mode محلياً + توثيق production hardening في deploy-staging). محوّلات AWS/GCP لاحقاً على نفس الـ SPI — خارج النطاق. |
| D6 | **هجرة المزوّد = دوران عادي**: الانتقال SOFT→KMS ليس عملية خاصة — دوران يولّد الجديد على KMS بينما القديم SOFT يتقاعد طبيعياً. إعداد لكل مستأجر (`tenant.key_provider` عمود جديد أم config عام؟ ← فيتو V3). لا نقل مادة مفاتيح بين مزوّدين أبداً. |
| D7 | **الـ wallet**: صفر تغيير متوقع — لكن **يُتحقق لا يُفترض**: جلسة 2.3a تُصدر وثيقة بمفتاح قديم، تدوّر، تتحقق أن wallet يختار المفتاح بالـ `kid` من JWKS متعدد المفاتيح (جولة جهاز قصيرة). إن ثبت أنه يقرأ أول مفتاح فقط ← جلسة W5 micro تُفتح (لا يُصلح ارتجالاً). |
| D8 | **التدقيق/الأخطاء**: `AuditAction.KEY_{ROTATED,RETIRED}` (+`FORCED` في details عند force)؛ `KH-KEY-0404/0409/0422`؛ runbook `docs/runbooks/key-rotation.md` يُكتب في 2.3a ويُنفَّذ حرفياً في اليوم المشهود. |

## 2. الجلسات

**KH-2.3a-BE — الدوران (provider-agnostic):** D1+D2+D3+D4+D7+D8 على SOFT.
**KH-2.3b-BE — Vault Transit:** D5+D6 + إعادة كل اختبارات 2.3a على المزوّد الجديد (نفس الجناح، parametrized إن أمكن).
**C8 — كونسول (micro):** لوحة مفاتيح التوقيع (موجودة من KH-1.1.5) تكتسب: عرض الدورة الكاملة بشاراتها، زر Rotate بتأكيد مشدَّد، زر Retire بحارس العمر ورسالة 0422 واضحة، عرض المزوّد لكل مفتاح. `key:manage`. بعد دمج 2.3a (لا تنتظر 2.3b).
**KH-2.3.3 — Game-day (أنت + أنا، ليس Claude Code):** تنفيذ الـ runbook حرفياً على compose (وعلى staging إن جهز): دوران مستأجر حي بوثائق متداولة ← وثائق الأمس تتحقق، قوائم الحالة أعيد توقيعها، wallet يتحقق بالمفتاحين، ثم دوران SOFT→Vault لمستأجر ثانٍ. النتيجة توثَّق كـ **دليل خروج Phase 2 — النصف الثاني** في STATE.

### Brief — KH-2.3a-BE (يُلصق بعد حسم الفيتو)
```
Session: feat/KH-2.3a-BE-key-rotation — spec FS-2.3 (approved, §3 resolutions attached), D1-D4+D7+D8.
Branch off latest origin/main. Sonnet only (key module).
VERIFY FIRST: KeyProvider SPI surface (FS-0.5 vs live code — code wins); how JWKS builds today
(all states or ACTIVE-only? FS-0.2 says RETIRING/RETIRED stay published — verify); how the
status-list sweep picks work (artifact_version vs version — the V9 precedent); signing call sites
(must resolve the ACTIVE key at sign time, never cache a kid across requests — grep for caching).
ALSO: codify the "context-switch-before-transaction" pattern (3rd occurrence: ApiKeyService,
TenantAdmin, AuthService#login) into docs/CONVENTIONS.md as a named rule with the rotation
orchestration as its 4th example if applicable.
BUILD: D2 rotate endpoint (atomic, one-active index as final arbiter, ConcurrentRotationTest);
D3 forced version-bump of the tenant's status lists inside rotation (runtime V9-style), regression
test: post-rotation sweep re-signs with the NEW kid; D4 retire endpoint (RETIRING->RETIRED only,
min-age guard khatm.keys.min-retiring-age default P30D, force=true audited); D8 audit actions +
KH-KEY-* codes + docs/runbooks/key-rotation.md (step-by-step, verification checkpoints, rollback
stance: rotation is roll-FORWARD-only — document why); D7 wallet verification walkthrough (issue
under old kid -> rotate -> old credential verifies on device from multi-key JWKS; if wallet fails
kid-selection -> STOP wallet-side, record W5 ask, platform work continues).
Message keys EN/AR same commit if any (Arabic gate). Contract additive-only.
DoD: mvn verify green (report N/N); live compose e2e: issue -> rotate -> old verifies + new issues
under new kid -> lists re-signed (new kid in artifact) -> retire blocked by min-age -> force
retire -> old STILL verifies (RETIRED in JWKS). PR opened NOT merged; STATE updated.
```

### Brief — KH-2.3b-BE (بعد دمج 2.3a)
```
Session: feat/KH-2.3b-BE-vault-transit — spec FS-2.3 D5+D6. Branch off latest origin/main.
Sonnet only. Compose gains a vault service (dev mode locally; production hardening notes ->
docs/deploy-staging.md: real storage backend, unseal, audit device, app token policy scoped to
transit sign/verify/create only — least privilege, no admin token in the app).
BUILD: VaultTransitProvider implements the KeyProvider SPI: create (transit key type ecdsa-p256,
exportable=false), sign (transit/sign, marshaling to JOSE ES256 signature format — verify the
raw-vs-DER encoding difference explicitly with a test vector), public JWK export from transit
read. provider_ref = transit key name (include tenant slug for operability). Provider selection
per veto V3 resolution. D6: SOFT->Vault migration IS a normal rotation — e2e proves it.
Re-run the ENTIRE 2.3a test suite against the Vault provider (parametrize the harness if feasible;
duplication is acceptable, silent gaps are not). Failure mode: Vault unreachable at sign time =>
fail-closed with a distinct error + alarm-friendly log (no silent SOFT fallback — a fallback
would be a key-security downgrade, forbidden).
DoD: mvn verify green incl. Vault testcontainer; live compose e2e: tenant on SOFT -> rotate onto
Vault -> issue/verify/consume all work -> old SOFT credential still verifies -> kill vault
container -> issuance fails closed with the distinct error, verify (JWKS/status) keeps working
[public artifacts don't need Vault at read time — verify this claim in code]. PR opened NOT
merged; STATE updated.
```

### Brief — C8 (كونسول micro، بعد دمج 2.3a)
```
Session: chore/C8-key-rotation-ui. Preamble: contract:update; self-stop if rotate/retire
endpoints or key lifecycle fields absent.
1. Signing-keys panel (dashboard, key:manage): full lifecycle badges (PENDING/ACTIVE/RETIRING/
   RETIRED) + provider column; Rotate button with a hardened confirm (types the tenant slug to
   confirm — irreversible-action pattern; verify if one exists, else this becomes the precedent);
   Retire per key with min-age guard surfaced (0422 explained inline, force path visually severe
   + second confirm). 2. EN/AR + RTL; tests. DoD: npm run check green; live walkthrough: rotate ->
   list shows new ACTIVE + old RETIRING -> retire early blocked with clear copy. PR opened NOT
   merged; Majd walkthrough EN/AR = gate; STATE updated.
```

## 3. نقاط الفيتو

| # | السؤال | الافتراضي المقترح |
|---|---|---|
| V1 | Vault Transit كمزوّد KMS أول (بدل سحابة أجنبية) | نعم — on-prem أولاً لسوقكم؛ السحابات لاحقاً على نفس الـ SPI |
| V2 | ترتيب D1 (الدوران قبل المزوّد، عكس WBS الاسمي) | نعم — دليل الخروج أسرع والمخاطر معزولة |
| V3 | اختيار المزوّد: عمود `tenant.key_provider` (لكل مستأجر) أم config عام للمنصة؟ | **عمود لكل مستأجر** — deploy_mode مختلط بطبيعته (SaaS + on-prem)، والعمود يمهّد لـ HSM لكل جهة في Phase 3 بلا هجرة مفهوم |
| V4 | حارس العمر الأدنى للتقاعد: 30 يوماً افتراضاً؟ | نعم P30D + force مدقَّق — يوازن وثائق offline طويلة التداول مع مرونة الطوارئ |

# FS-1.6 — Consumption Lifecycle Visibility (spec + three session briefs)

> **Status:** APPROVED — 2026-07-27 (نقاط الفيتو الثلاث حُسمت بتوصياتها، انظر §2) · **Sessions:** KH-1.6-BE (platform) → C6 (console) ∥ W4 (wallet)
> **المبدأ الحاكم:** مصدر حقيقة واحد في المنصة؛ الكونسول والـ wallet يعرضان، لا يستنتجان.

## 1. القرارات

| # | القرار |
|---|---|
| D1 | حالة دورة حياة صريحة للوثيقة تظهر في كل الأسطح: `ACTIVE / EXHAUSTED / REVOKED / SUSPENDED / EXPIRED`. `EXHAUSTED` تُثبَّت **ذرّياً في نفس مسار الاستهلاك race-safe القائم** لحظة استهلاك آخر استخدام — تُقلَب مرة واحدة بالضبط (يمدَّد `ConcurrentConsumeTest`: N+1 استهلاكاً متزامناً على maxUses=N ← بالضبط N تنجح والحالة تنقلب مرة). ما إذا كانت عموداً جديداً أم مشتقة من الأعمدة القائمة = قرار المنفّذ بعد قراءة الكود (الكود مرجع). |
| D2 | **قائمة الحالة تبقى 1-bit**: الاستنفاد يقلب نفس بِت الإبطال (invalid). أي مُتحقق — بما فيه wallet على جهاز ثانٍ يمسح QR مشارَكاً — يرى «غير صالح» فوراً بلا أي تغيير في decoder الـ W2 المنشور. التمييز بين consumed/revoked للحامل يأتي من D3 لا من البِت. (multi-bit مرفوض: يكسر عقد الـ wallet المنشور.) |
| D3 | **نقطة حالة الحامل بإثبات حيازة** — عكس واعٍ لقرار PR #33: `POST /api/v1/credentials/holder-status` عامة (مثل verify)، جسم الطلب = الـ JWT العاري؛ الخادم يتحقق من التوقيع ثم يرد `{status, maxUses, usesRemaining, lastConsumedAt?}`. توقيع غير صالح أو jti مجهول ← 404 موحَّد (anti-enumeration كما هو). لا rate-limit جديد الآن (يلحق بحدود KH-2.5). |
| D4 | `/verify` لوثيقة مستنفدة ← `200 valid:false` مع `VerifyReason` جديد `EXHAUSTED` (إضافي). التحقق يبقى حياً دائماً — «لا أخضر كاذب» تعمل في الاتجاهين. |
| D5 | استجابات بحث/تفاصيل الوثائق (سطح الكونسول القائم من KH-1.1) تكتسب `status` و`usesConsumed` و`maxUses` — إضافي فقط. |
| D6 | الأسماء: `EXHAUSTED` للحالة النهائية (استخدامات صفر)؛ «consume» يبقى اسم الحدث الفردي. مفاتيح رسائل جديدة EN/AR في نفس الـ commit — بوابة عربية. |

## 2. نقاط الفيتو — **محسومة (موافقة مجد 2026-07-27)، لا تُعاد مناقشتها في الجلسات**

- **V1 — نعم**: D3 معتمدة كعكس رسمي وواعٍ لقرار PR #33، بصيغة إثبات الحيازة (الـ JWT العاري في جسم الطلب). **واجب على جلسة KH-1.6-BE**: تسجيل العكس نصاً في STATE المنصة مع الإشارة إلى قرار PR #33 الأصلي، كي لا يقرأه أحد لاحقاً كتناقض غير مقصود.
- **V2 — يستدعيها**: wallet-المُتحقق (مسح جهاز ثانٍ) يستدعي `holder-status` بعد حكم البِت ليعرض السبب الدقيق (EXHAUSTED vs REVOKED)؛ عند فشل الاستدعاء أو الـ offline يبقى حكم «غير صالح» العام كما هو اليوم — النقطة تحسين للسبب، لا شرط للحكم.
- **V3 — كلاهما**: `EXPIRED` تُحسب محلياً من `expiresAt` للعمل offline، والقيمة الخادمية من `holder-status` هي الحَكم عند توفر الاتصال.

---

## Brief — KH-1.6-BE (platform, Sonnet — يمسّ مسار consume)

```
Session: feat/KH-1.6-BE-consumption-lifecycle — spec FS-1.6 (approved, veto resolutions attached).
Branch off latest origin/main. mvn verify green at end; PR opened NOT merged; STATE updated.

VERIFY-AGAINST-CODE FIRST (report findings before writing):
- How consume currently tracks uses (AtomicConsumptionRecorder + credential columns), what an
  exhausted consume attempt returns today, and whether any status-like column already exists.
- Whether the consume response already tells the consuming party remaining uses; if not, add it (additive).
- Where revocation flips the status-list bit today — exhaustion MUST reuse that exact path
  (same sweep/publish mechanics, per-tenant signing per KH-2.1 bug-7 fix).

BUILD (per FS-1.6 D1–D6):
1. D1 exactly-once EXHAUSTED transition inside the existing race-safe consume path; extend
   ConcurrentConsumeTest (N+1 concurrent on maxUses=N: exactly N succeed, bit-flip + status flip once).
2. D2 status-list bit flip on exhaustion via the existing revocation path; regression test:
   exhausted credential's list republishes and the bit reads invalid (MSB-first decode, live-code authority).
3. D3 POST /api/v1/credentials/holder-status (public path list test updated; scope: none/public):
   body = bare JWT; verify signature via the issuing tenant's key (multi-tenant aware — resolve
   tenant from the credential, use SystemAccessExecutor pattern like verify does); respond
   {status, maxUses, usesRemaining, lastConsumedAt?}; invalid sig or unknown jti -> unified 404.
4. D4 new VerifyReason EXHAUSTED on /verify (200 valid:false).
5. D5 credential search/detail responses gain status, usesConsumed, maxUses (additive).
6. Error/message keys per CONVENTIONS §7.1, EN+AR same commit; regenerate contract + error-codes
   docs via their tests. Live compose e2e (DoD): issue maxUses=2 -> consume x2 (2nd returns
   remaining=0) -> 3rd consume rejected -> holder-status shows EXHAUSTED 0/2 -> /verify says
   valid:false EXHAUSTED -> status-list bit flipped -> search row shows EXHAUSTED 2/2.
Hard constraints: contract additive-only; V1–V9 migrations untouched (new migration only if D1
needs a column); no changes to redeem-time snapshot semantics from PR #33 (it stays the offline seed).
```

## Brief — C6 (console — بعد دمج KH-1.6-BE)

```
Session: feat/C6-credential-lifecycle — small session. Preamble: npm run contract:update; self-stop
if status/usesConsumed/maxUses absent from credential search schema or holder-status absent from contract.
1. Credentials search rows: status badge (reuse StatusBadge tokens) + "uses" column rendered as
   usesConsumed/maxUses (e.g. 2/2); detail (if a detail surface exists — verify; else rows only).
2. Filter bar: add status dropdown (server-side param only if the contract exposes one — verify;
   if not, client-side column display only, record the platform ask in STATE instead of improvising).
3. Consume simulator: after a consume, surface the returned remaining-uses in the result panel.
4. EN/AR keys same commit; RTL pass; Majd browser walkthrough EN+AR = merge gate. PR opened not merged.
```

## Brief — W4 (wallet, Flutter — بعد دمج KH-1.6-BE؛ مستقلة عن C6)

```
Session: feat/W4-credential-lifecycle — spec FS-1.6 (D2/D3/V2/V3 resolutions attached).
1. Data: new holder-status client (POST bare JWT from stored credential; reuse apiBase config).
   Extend the stored verify snapshot (JSON-map pattern from W2 — keep on-disk shape additive)
   with {status, maxUses, usesRemaining, lastCheckedAt}.
2. Refresh flow (existing manual refresh button): crypto verify + status-list bit as today, PLUS
   holder-status call; merge results — bit says invalid + endpoint says EXHAUSTED => show
   "Consumed/Exhausted" chip (new status color: neutral/warning, not the danger red of revoked);
   REVOKED keeps danger. Detail screen shows "uses: X of Y remaining" from the live response,
   replacing the static PR #33 snapshot WHEN a live value exists; offline => last cached value
   + existing staleness indicator (never show the static max as if it were remaining).
3. Verifier mode (scanning another device's shared QR): per V2 resolution — bit invalid => verdict
   as today; additionally call holder-status with the presentation's JWT to refine the verdict
   reason (EXHAUSTED vs REVOKED); offline/failure => keep today's generic invalid verdict.
4. EXPIRED per V3 resolution: local computation from expiresAt stays for offline; server value wins online.
5. ARB keys EN+AR (new statuses + uses line) same commit; goldens updated; flutter analyze/test green.
   DoD live walkthrough on physical Android against compose stack: issue 2-use credential ->
   consume once (console sim) -> wallet refresh shows 1 remaining -> consume again -> refresh shows
   Exhausted chip + 0 of 2 -> share QR to second device/profile -> scan shows invalid with
   exhausted reason. Majd device sign-off EN/AR/RTL = merge gate. PR opened not merged.
```

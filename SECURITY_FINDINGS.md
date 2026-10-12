# AyX WhatsApp — Security Audit Findings

**Date:** 2026-10-12
**Branch:** `security/android-hardening`
**Scope:** Full repository audit (Kotlin, manifest, Gradle, ProGuard, native, Node.js, workflows)

## Summary

| Severity | Count | Status |
|----------|-------|--------|
| Critical | 1 | Fixed |
| High | 3 | Fixed |
| Medium | 4 | Documented |
| Low/Info | 3 | Documented |

## Findings

### CRITICAL

**C1. Blanket R8 keep rule defeated obfuscation**
- **File:** `app/proguard-rules.pro`
- **Finding:** `-keep class ayx.whatsapp.** { *; }` preserved every app class, making R8 obfuscation useless.
- **Fix:** Replaced with narrow keeps: JNI `NodeBridge` native methods, `MainActivity`/`NodeService`/`ReplyReceiver` (framework-instantiated), WorkManager `Worker` constructors, JSON-serialized fields.
- **Verification:** `security-scan.yml` CI check fails the build if the blanket rule returns.

### HIGH

**H1. Dead keep rule for deleted class**
- **File:** `app/proguard-rules.pro`
- **Finding:** `-keep class ayx.whatsapp.group.AyxUpdatesWorker` referenced a class deleted during group-feature removal. Dead rule hid the fact that shrinking was untested.
- **Fix:** Removed. Replaced with generic WorkManager worker-constructor keep.

**H2. Announcement code used wrong credential**
- **File:** `app/src/main/java/ayx/whatsapp/AyxAnnouncement.kt` (deleted)
- **Finding:** Used `GatewayAuth.token()` (WhatsApp gateway token, port 8765) as the AyX backend installation token (port 3101). Cross-purpose credential reuse.
- **Fix:** File deleted per user request. Replacement registration uses only server-issued `access_token` from `POST /v1/installations/register`.

**H3. No CI secret scanning**
- **File:** `.github/workflows/` (missing)
- **Finding:** No automated check for committed secrets, keystores, or debug flags.
- **Fix:** Added `security-scan.yml` (secret patterns, keystore detection, cleartext check, blanket-keep check).

### MEDIUM

**M1. Deep link intent validation**
- **File:** `app/src/main/AndroidManifest.xml`, `MainActivity.handleDeepLink`
- **Finding:** `wa.me`, `api.whatsapp.com`, `whatsapp:` schemes are handled. Validation exists but should be reviewed for path traversal in phone-number extraction.
- **Status:** Documented; no change (risk of breaking WhatsApp interop).

**M2. No certificate pinning (deliberate)**
- **File:** `app/src/main/res/xml/network_security_config.xml`
- **Finding:** Standard CA trust used; no pinning for `api.imayx.in`.
- **Status:** Deliberate — Cloudflare Tunnel edge certs rotate. Documented in ProGuard file.

**M3. Phone number collection**
- **File:** `registerAyxInstallation` (MainActivity patch)
- **Finding:** Sends WhatsApp phone number (`+<country><number>`) and device name to backend for admin installations page.
- **Status:** User explicitly requested and authorized. Backend stores it; admin UI must disclose it.

**M4. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission**
- **File:** `app/src/main/AndroidManifest.xml`
- **Finding:** Requests battery-optimization exemption (for foreground gateway service).
- **Status:** Justified for the use case; Play Policy requires in-app rationale — verify before Play release.

### LOW / INFO

**L1. `ayx.keystore` not in repo** — Verified absent from `main`. If it was ever committed, rotate it.
**L2. Network security config** — Cleartext disabled globally; loopback exception for 127.0.0.1 only. Correct.
**L3. Signing from env vars** — `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` from CI secrets. Correct.

## Residual risks (need independent review)

1. **No penetration test performed.** This audit is static analysis only.
2. **R8 mapping files** — Verify they are not published as build artifacts.
3. **Embedded Node.js** — The Baileys gateway exposes ~30 routes on localhost; gateway-auth middleware status on `main` is unverified (see memory: `security/gateway-auth` branch).
4. **Play Integrity** — Not implemented; not required for sideloaded distribution.

## Test results

| Test | Result |
|------|--------|
| ProGuard syntax | Not run (no Android SDK in this environment) |
| Debug/release compile | Not run (no Android SDK) |
| Secret scan patterns | Pass (local grep) |
| R8 rule narrowing | Manual review only |

**Environment limitation:** This environment has no Android SDK and cannot compile the app. All build-dependent tests must run in CI or AndroidIDE.

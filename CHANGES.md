# CHANGES — "✦ AyX updates" system channel

Implemented against the copy at `~/workspace/ayxwa-work/impl/AyXwhatsapp`
(original at `~/workspace/ayxwa-work/extracted/AyXwhatsapp` untouched).
Follows `docs/ANDROID_CONTRACT.md` **except FCM** (this app has no Firebase;
delivery is polling + local notifications — see "Deliberate deviations").

## New files

- `app/src/main/java/ayx/whatsapp/group/AyxModels.kt`
  `Announcement`, `AyxStatus`, `SyncCursor` (+ `NO_CURSOR`), ISO-8601 parsing
  via `SimpleDateFormat` (works on minSdk 24, no desugaring), `relativeTime()`
  ("5m ago", "Yesterday", …), JSON parse/serialize helpers using the app's
  existing `org.json` style.
- `app/src/main/java/ayx/whatsapp/group/AyxGroupApi.kt`
  Raw-`HttpURLConnection` client (same style as `GatewayClient`), base URL
  `https://api.imayx.in` (public hostname — **not a secret**).
  `register()` → `{installation_id, access_token}`;
  `bootstrap()` → announcements + statuses + cursor;
  `sync(cursor)` → upserts/deletes + new cursor;
  `rotateToken()`. 15s timeouts; exponential backoff 2s/4s/8s (max 4 tries) on
  network errors and 5xx only; 429 honors `Retry-After` (capped 120s);
  401 `{"code":"installation_revoked"}` throws `InstallationRevokedException`
  (never retried). **The token is never logged or put in error messages.**
- `app/src/main/java/ayx/whatsapp/group/AyxGroupStore.kt`
  `EncryptedSharedPreferences` (`androidx.security:security-crypto`,
  AES-256-GCM, `MasterKey` AES256_GCM) holding `installation_id`,
  `access_token`, sync `cursor`, `last_sync_at`. Cursor is persisted **only
  after** content writes succeed (enforced by callers). `wipe()` clears creds.
- `app/src/main/java/ayx/whatsapp/group/AyxGroupSync.kt`
  Sync engine, serialized with a coroutine `Mutex`:
  first-run `register → bootstrap → persist`; later `sync(cursor)` applying
  upserts/deletes idempotently by id, persisting cursor only after the JSON
  files land. Content cached in app-private `files/ayx_group/`
  (`announcements.json`, `statuses.json`) for offline reading.
  401-revoked → wipe + re-register + bootstrap (once). Status images:
  Bearer download, `https://api.imayx.in/`-only URLs, 25MB per-file cap,
  25MB total cache cap with oldest-first eviction, atomic temp-file writes.
  `foregroundSync()` — debounced 5-minute foreground trigger.
  `latestSnippet()` — latest announcement title for the pinned chat-list row.
- `app/src/main/java/ayx/whatsapp/group/AyxUpdatesWorker.kt`
  WorkManager `CoroutineWorker`, periodic every 15 min,
  `NetworkType.CONNECTED`. Runs `syncNow()`; notifies once per newly arrived
  announcement via `NotificationHelper.notifyUpdate()`. Idempotent: sync only
  reports announcements not previously known, so re-runs never re-notify.
  Retries transient failures up to 3 attempts, then waits for the next period.
- `app/src/main/java/ayx/whatsapp/group/AyxUpdatesScreen.kt`
  Compose Material3 screen: app bar "✦ AyX updates" (back + sync-now),
  announcement cards (title, body, relative time), statuses section
  (text + cached images), "Last synced …" footer. Requests
  `POST_NOTIFICATIONS` on first open (Android 13+). Syncs on open; works
  offline from cache.
- `app/src/main/res/drawable/ayx_updates_avatar.png`
  Real channel avatar (purple ✦ sparkle). Replaced the earlier vector
  placeholder — nothing to do.

## Modified files

- `app/src/main/java/ayx/whatsapp/MainActivity.kt` (surgical, existing
  behavior untouched):
  - Imports: `PushPin` icon, `painterResource`, `ayx.whatsapp.group.*`.
  - `handleDeepLink()`: honors `open_ayx_updates=true` intent extra →
    `AppNav.pendingOpenAyxUpdates`.
  - `onCreate()`: `AyxUpdatesWorker.enqueue(applicationContext)` (KEEP policy).
  - `onResume()`: `AyxGroupSync.foregroundSync()` (debounced 5 min).
  - `GatewayApp`: new `showAyxUpdates` nav state; `LaunchedEffect` opens it on
    notification tap; `BackHandler` + app-bar back button close it first;
    content `when` gains a `showAyxUpdates` branch (before link/settings).
  - `ChatsWithStatus()`: threads `ayxSnippet` / `onOpenAyxUpdates` through.
  - `ChatList()`: ONE pinned row at the very top — avatar drawable,
    "✦ AyX updates" (bold), snippet = latest announcement title or
    "Official updates from AyX", pin icon. Tap → updates screen.
    **Plain `clickable`, no `combinedClickable` — no long-press menu, no
    mute/block/hide/delete, no ChatFlags interaction.** Empty-state text
    preserved (now renders below the pinned row).
  - `StatusScreen()`: prepends a locked "✦ AyX" ring (avatar drawable, green
    ring, lock icon, tap → updates screen). **No `StatusFlags` checks, no
    long-press menu** — mute/hide/lock can never apply to it.
  - Nothing else changed: row rendering, `ChatFlags`, `StatusFlags`,
    conversation screen, bubbles, navigation all byte-identical in behavior.
- `app/src/main/java/ayx/whatsapp/NotificationHelper.kt`:
  - `AppNav.pendingOpenAyxUpdates` state.
  - New channel `ayx_updates` ("AyX updates", `IMPORTANCE_HIGH`).
  - `notifyUpdate(announcementId, title, body)`: BigTextStyle, tap →
    MainActivity with `open_ayx_updates=true`, stable id per announcement
    (re-delivery updates, never stacks). **Deliberately skips
    `ChatFlags.isMuted`** — this is a system channel, not a chat JID.
- `app/build.gradle.kts`:
  - New deps: `androidx.security:security-crypto:1.0.0`,
    `androidx.work:work-runtime-ktx:2.9.0`.
  - Release: `isMinifyEnabled = true` + `proguard-rules.pro` (see below).
- `app/proguard-rules.pro`: conservative keeps — `-keep class ayx.whatsapp.**
  { *; }`, explicit JNI keep for `NodeBridge.native*`, keep for
  `AyxUpdatesWorker`'s constructor, keeps for Compose / coroutines / zxing /
  emoji2 / security-crypto / work-runtime. Documents why there is **no
  certificate pinning** (Cloudflare Tunnel edge certs rotate).

## Deliberate deviations from ANDROID_CONTRACT.md

1. **No FCM.** The app has no Firebase and none was added (per task). Push
   delivery = WorkManager polling (15 min) + local notifications. Sections 6–7
   of the contract (FCM token, data-message payloads) do not apply.
2. **No Room.** The contract suggests Room tables; this app has no Room and
   adding it for two small lists was disproportionate — content is cached as
   JSON files in the app-private files dir with the same cursor-after-write
   guarantee. (User private messages / replies UI from contract §5 is out of
   scope for this task and was not built.)
3. **POST_NOTIFICATIONS at launch kept.** The app already requests it in
   `onCreate`; removing that would regress message notifications. It is now
   *also* requested on first AyX-updates open (no-op if already granted).
4. **No certificate pinning.** `api.imayx.in` is served through a Cloudflare
   Tunnel; edge certificates rotate, so pinning would break the app. Standard
   Android CA trust is used (same as the app's other api.imayx.in calls).

## USER ACTION REQUIRED

1. **Gradle sync.** Two new dependencies were added (`security-crypto`,
   `work-runtime-ktx`). Open the project and let Gradle sync / download them.
2. **Avatar PNG — DONE.** The real `ayx_updates_avatar.png` is in place
   (`res/drawable/`); the temporary vector placeholder was removed.
3. **No Firebase needed.** Nothing to configure — there is deliberately no
   `google-services.json`, no FCM code, no new manifest entries. `INTERNET`,
   `ACCESS_NETWORK_STATE`, and `POST_NOTIFICATIONS` were already declared.
4. **Build + test checklist** (no SDK was available here — code was
   hand-reviewed only):
   - [ ] `./gradlew :app:assembleDebug` compiles (new deps resolve).
   - [ ] Fresh install → open "✦ AyX updates" → registration happens silently;
     announcements/statuses appear; `POST_NOTIFICATIONS` requested once.
   - [ ] Chat list shows the pinned "✦ AyX updates" row on top; long-press on
     it does nothing (no menu); tap opens the screen; back returns.
   - [ ] Status tab shows the locked "✦ AyX" ring first; long-press does
     nothing; Status mute/hide cannot affect it.
   - [ ] Publish an announcement in the admin panel → within ~15 min a
     notification arrives; tap opens AyX updates; no duplicate notifications
     on subsequent syncs.
   - [ ] Revoke the installation in the admin panel → next sync wipes and
     re-registers silently (check logcat for `InstallationRevokedException`
     handling, no crash).
   - [ ] Airplane mode → screen still shows cached content with "Last synced …".
   - [ ] `./gradlew :app:assembleRelease` with R8 — if the release APK
     misbehaves, set `isMinifyEnabled` back to `false` in
     `app/build.gradle.kts` and report it.
   - [ ] Confirm no token/secrets in logcat during sync (grep `access_token`).
5. **Security posture.** Zero secrets in code — only the public
   `https://api.imayx.in` base URL. The per-install token lives in
   EncryptedSharedPreferences, is never logged, and all admin-side
   enforcement (revocation, blocking) stays server-side. The channel cannot
   be muted/blocked/hidden from inside the app by design (product decision
   recorded per the owner's request).

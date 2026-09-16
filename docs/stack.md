# Technology Stack and Version Decisions

All version numbers were taken from actually inspecting `maven-metadata.xml` on
`repo1.maven.org` / `dl.google.com/dl/android/maven2` on 2026-07-28 — not from search results.
The pinned values live in `gradle/libs.versions.toml`.

> Side note: `search.maven.org`'s index was badly stale at the time (it reported CMP as 1.8.2
> and Ktor as 3.2.0). Check core versions by reading maven-metadata.xml directly.

## Version compatibility

| Component | We pin | Latest at the time | Why |
|---|---|---|---|
| AGP | 9.3.1 | 9.3.1 | See "Forced to upgrade AGP" below |
| Gradle | 9.5.0 | 9.6.1 | Kotlin 2.4.x's tested upper bound; also satisfies AGP 9.3's ≥9.5.0 requirement |
| compileSdk | 37 | — | Hard floor imposed by the androidx ecosystem, see below |
| DataStore | 1.2.1 | 1.3.0-alpha09 | 1.3.0 has gone through nine consecutive alphas without reaching beta |

### Forced to upgrade AGP: a plan that didn't pan out

**The original plan** was to pin AGP 9.1.0, staying within Kotlin 2.4.x's officially tested range
(AGP ≤9.1.0 + Gradle ≤9.5.0). **That turned out not to work**, forced out by actual build errors:

1. `androidx.lifecycle 2.11.0` requires compileSdk ≥ 37.
2. After downgrading to lifecycle 2.10.0, `androidx.core 1.19.0` also required compileSdk ≥ 37.
3. AGP 9.1.0's compileSdk ceiling is 36.

In other words, the androidx ecosystem's floor has moved to 37 across the board. Staying pinned
at 36 would mean walking a whole set of androidx libraries back down version by version, and the
problem would keep recurring with every new dependency.

**Conclusion: upgrade to AGP 9.3.1 + compileSdk 37**, at the cost of exceeding Kotlin's officially
tested AGP ceiling. Gradle stays at 9.5.0 (satisfying both Kotlin's tested ceiling and AGP 9.3's
minimum requirement). `targetSdk` stays at 36 — compileSdk uses the latest, targetSdk uses what's
actually been tested; this is standard practice.

Locally requires `platforms;android-37.0` (already installed).

### CMP's material3 follows its own independent version line

The version of `org.jetbrains.compose.material3:material3` **does not match the Compose plugin
version** — with plugin 1.11.1, it's **1.9.0**. Pulling it in with 1.11.1 gives
`Could not find ...material3:1.11.1`. (The old `compose.material3` shorthand used to align
versions automatically, but that shorthand was deprecated as of CMP 1.11; once you switch to the
explicit coordinate you have to manage this version yourself. Tested and confirmed: material3
1.9.0 + CMP 1.11.1 resolves and compiles on both platforms.)

On the lifecycle side: CMP 1.11.1 ships with **2.11.0-beta01** by default, while the stable 2.11.0
line is only picked up starting from 1.12.0-beta. We manually bump it to stable 2.11.0 (this is
also one of the sources of the compileSdk 37 requirement above).

## AGP 9's module structure requirements (hard requirements)

Starting with AGP 9:

- `com.android.library` and KMP can no longer coexist → KMP modules must use
  **`com.android.kotlin.multiplatform.library`**.
- `com.android.application` and KMP can no longer coexist either → **the Android entry point must
  be a standalone subproject**.

So the old single-`composeApp`-module layout from older tutorials is obsolete — don't copy it.
Other knock-on changes:

- The top-level `android { }` block is gone; configuration moves into `kotlin { android { ... } }`.
- The source directory `src/main` becomes `src/androidMain`.
- The new plugin's limitations: **single variant only, no build types / product flavors**, no
  BuildConfig, no view binding, no NDK. Java compilation, host tests, device tests, and Android
  resources all need to be explicitly opted into
  (`withJava()`, `withHostTestBuilder {}`, `withDeviceTestBuilder {}`,
  `androidResources { enable = true }`).
- The escape hatch `android.enableLegacyVariantApi=true` will stop working in AGP 10 — don't rely
  on it.

### Four issues actually hit while scaffolding (that research didn't cover)

1. **You can't add the `org.jetbrains.kotlin.android` plugin.** AGP 9.0 has built-in Kotlin
   support, and applying that plugin in androidApp **fails the build outright**:
   `The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0`.
   androidApp only needs `com.android.application` + `org.jetbrains.kotlin.plugin.compose`.
2. **`androidLibrary {}` is deprecated; use `kotlin { android {} }`.** The former still works but
   emits a deprecation warning. Note this `android {}` is *inside* `kotlin {}`, and is not the same
   thing as the top-level one (which no longer exists for KMP modules).
3. **Test targets must be explicitly enabled.** Without `withHostTestBuilder {}`, tests in
   `commonTest` **have nowhere to run on the JVM, and there's no error whatsoever** — you'd think
   "tests passed" when in fact none ran. The task name to run is `testAndroidHostTest` (not
   `androidHostTest`).
4. **The `compose.runtime` shorthand is deprecated too**; once you switch to explicit coordinates
   you have to handle material3's independent version line yourself (see above).

JetBrains' wizard (kmp.jetbrains.com) has been producing the new structure since May 2026, and
requires IntelliJ 2026.1.2+ / Android Studio Otter 3 Feature Drop or later.

## A few contested choices

### Database: SQLDelight (rejected Room)

Both genuinely support iOS — this isn't a stable-vs-alpha question. Reasons for choosing
SQLDelight:

1. **No KSP required.** Room needs to be registered separately for every target
   (`kspAndroid` / `kspIosArm64` / `kspIosSimulatorArm64` / `kspIosX64`), which is the single most
   common friction point in KMP builds. And at the time, **KSP was stuck at 2.3.10 while Kotlin
   was already at 2.4.10** — that version gap is the most likely day-one blocker. SQLDelight uses
   its own Gradle plugin and sidesteps the whole issue.
2. **SQL-first fits this app.** Net-worth computation is fundamentally time-series aggregation
   (group by date, roll up by category, convert currencies) — this is more natural to write in SQL
   than via DAO annotations, and SQLDelight does compile-time validation against the real schema.
3. **No asterisks on iOS support.** Room has a list of exclusions on non-Android platforms
   (prepackaged databases, `setQueryCallback`, multi-instance invalidation notifications are all
   unavailable), and **DAO functions on non-Android platforms must all be `suspend`**. SQLDelight
   is KMP-native by design and has none of these exceptions.
4. Room's last release was 2025-11 — eight months of silence; SQLDelight 2.3.2 shipped in 2026-03.

**When it would make sense to switch back to Room:** if the team has deep Room experience and
wants annotation-style entities rather than `.sq` files, or wants first-party Google support and
Paging integration. Room 2.8.4 + `androidx.sqlite:sqlite-bundled:2.7.0` (use `BundledSQLiteDriver`,
not the platform SQLite, otherwise the SQLite versions on the two platforms will drift apart).

### Navigation: navigation-compose 2.9.2 (not moving to Nav3 yet)

Nav3 **is usable** on CMP, but there's a catch: Google's `navigation3-runtime` is genuinely KMP,
but Google's `navigation3-ui` only ships `-android` artifacts and stubs — `NavDisplay` is
Android-only. To use Nav3 on CMP, the UI layer has to be swapped for JetBrains'
`org.jetbrains.androidx.navigation3:navigation3-ui`. Reading only Google's Nav3 docs will mislead
you.

Reasons for not adopting it yet: on iOS, Nav3 route serialization has to be hand-written via
`SerializersModule` (reflection-based routes are JVM-only), `adaptive-navigation3` is still in
alpha, and navigation-compose 2.9.2 has been stable since 2025-09 with solid documentation. Pick
the stable option while getting started.

**Voyager rejected:** no stable 1.1.0, no feature updates since 2024-10, latest build still stuck
on CMP 1.10.3. **Decompose 3.5.0** is only worth considering when some screens specifically need
native SwiftUI.

### DI: Koin 4.2.2

At our current scale, Koin's runtime overhead is negligible, and the upside is real: no codegen,
fastest iteration, no per-target KSP configuration. `koin-compose-viewmodel` plugs directly into
the multiplatform ViewModel. The old problem of runtime errors can be caught with `verify()`
tests.

**Metro 1.3.2** is a genuine alternative — 1.0 shipped 2026-04, compile-time validated, KMP-native,
and it's a **compiler plugin rather than KSP** (so no per-target configuration issues); JetBrains'
own KotlinConf App has already migrated from Koin to Metro. If the module count grows later, or
runtime DI failures become intolerable, switch to it. The cost is that a compiler plugin ties you
to a Kotlin version, which could block Kotlin upgrades.

**Don't pick kotlin-inject**: five years in and it's still at 0.9.0; Metro is essentially a
superset of it.

**Neither** Google nor JetBrains has an official KMP DI recommendation — this is a
community-chosen area.

### Charts: Vico 3.2.3, coordinate is `:compose-m3`

**The module naming here is extremely easy to get wrong — read carefully.** Vico 3.0.0 (2026-02)
did a reorganization: it **deleted** the old Android-only `compose` module, then **renamed** the
cross-platform `multiplatform` module to `compose`. So now:

- ✅ `com.patrykandpatrick.vico:compose-m3:3.2.3` — cross-platform, Material 3, current stable.
- ⚠️ `com.patrykandpatrick.vico:multiplatform:2.5.2` — the legacy 2.x line, still patched but not
  for new projects.

Vico's official release notes literally say the Compose Multiplatform module "is now stable."
Confirmed that 3.2.3 ships `compose-iosarm64` / `compose-iossimulatorarm64` klibs, and builds
against CMP 1.11.1 + Kotlin 2.4.10; the repo gets weekly updates and is sponsored by Software
Mansion. Line/trend charts (`LineCartesianLayer`) are exactly its home turf.

Note: `compose-iosx64` is stuck at 3.1.0 — 3.2.0 dropped the Intel simulator, which doesn't affect
us (we're arm64-only anyway). Another cost: every 3.x minor has some deprecation or small breaking
change, so upgrades require reading the release notes.

**Findings on other options** (verified against actually published artifacts, not README claims):
- KoalaPlot 0.12.0 supports iOS but is pre-1.0, and 0.12.0 genuinely removed a batch of APIs. Risky
  to depend on for a product.
- ComposeCharts 1.0.0 (2026-07) and HDCharts 2.3.0 are both usable and active, but lack Vico's
  track record.
- **aay-chart cannot be used for iOS** — the README claims support, but the actually published
  artifact only has android/desktop/js/wasm; the iOS variant stopped in 2023.
- **Kandy (JetBrains' own) is JVM-only** — it ships a plain jar, not a klib. Not a CMP solution.
- Charty is stalled: the latest release literally has a `-test` tag, and the repo hasn't moved
  since 2025-12.

If in the end you're only drawing mini sparklines in list rows, using `Canvas` / `drawPath`
directly is perfectly reasonable too — CMP runs on Skia on iOS, so `DrawScope`/`Path`/`TextMeasurer`
are shared code with consistent behavior. What's actually laborious is axis-tick rounding, label
collision avoidance, date-axis formatting, zoom inertia, hit-testing — that's where a chart library
earns its keep. **A hybrid approach is worth considering: Vico for real charts, hand-rolled Canvas
for sparklines in list rows.**

### Time: use the standard library's `kotlin.time`, not kotlinx-datetime's

`kotlin.time.Instant` and `kotlin.time.Clock` have been stable since Kotlin 2.3.0, while
kotlinx-datetime 0.7.0 **removed** its own `Instant`/`Clock`. kotlinx-datetime 0.8.0 is only used
for `LocalDate`, `TimeZone`, and formatting.

Note that some third-party libraries still use the old types; there's a `-0.6.x-compat` artifact
for transition, but JetBrains stopped publishing it after 0.8.x.

kotlinx-datetime itself is still 0.x and self-describes as experimental — this is a known risk.

### Networking: Ktor 3.5.1, skipping 3.5.0

3.5.1 fixes several iOS Darwin bugs, one of which crashes the process outright when a WebSocket
receives a PONG after close. Uses `ktor-client-darwin`; `DarwinLegacy` was deprecated in 3.4.0.

## Secure storage: the weakest link in the ecosystem — set expectations accordingly

- `androidx.security:security-crypto` did reach 1.1.0, but **`EncryptedSharedPreferences` is
  deprecated** (Keystore reliability and performance issues). Don't use it in new projects.
- Google's replacement, `androidx.datastore:datastore-tink`, **only ships `-android` and `-jvm`, it
  is not multiplatform**, so it can't serve as a shared-layer solution.
- **multiplatform-settings cannot be used to store secrets.** Two common misconceptions, both
  disproven by testing: the `multiplatform-settings-keychain` artifact **does not exist**
  (`KeychainSettings` lives in the core artifact's `appleMain` and is marked experimental); and the
  library **has no encrypted Android backend at all** — the Android side only has plaintext
  `SharedPreferencesSettings` / `DataStoreSettings`. Fine for non-sensitive preferences, not fine
  for anything money-related.
- **DataStore is cross-platform, but it only gives you a file container, not encryption.** On iOS
  you have to write your own `Serializer`; keys go in the Keychain.

### App lock (implemented, verified on a real Android device)

Following the conclusions above, we hand-wrote both platforms ourselves rather than pulling in a
third-party KMP biometrics library. About 200 lines total.

- **Android**: stable `androidx.biometric:1.1.0` + `BiometricPrompt`, allowing
  `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (so users without a fingerprint can still use their
  screen-lock PIN). ⚠️ **A pitfall found during real testing**: `canAuthenticate(combined value)`
  returns `NONE_ENROLLED` when no biometric is enrolled, even if the screen-lock PIN is available.
  You must **check both capabilities separately** and take "either available," otherwise a device
  with only a PIN set will be wrongly judged unable to use app lock.
- **iOS**: `LAContext` + `LAPolicyDeviceOwnerAuthentication` (**not**
  `...WithBiometrics` — only the former automatically falls back to the device passcode).
  **No cinterop needed.** The `NSError` out-parameter of `canEvaluatePolicy` needs
  `memScoped { alloc<ObjCObjectVar<NSError?>>() }`.
- **Activity bridging**: `BiometricPrompt` strictly requires a `FragmentActivity`, and the shared
  layer can't hold a reference to one, so we use `CurrentActivityHolder` (**a weak reference** — a
  strong reference would leak the whole Activity and its View tree), registered/unregistered by
  `MainActivity` in onCreate/onDestroy.

Two product decisions: **authentication must succeed before app lock can be enabled** (otherwise
whoever is holding the phone could lock the owner out, or a user could enable it on a device that
can't authenticate and then be unable to get back in); **unlocked state is never persisted**
(going to the background or restarting always requires re-authentication — that's the whole point
of an app lock).

**Conclusion: both storage and app lock are hand-written expect/actual.** Storage totals roughly
300 lines and authentication about 120 lines across both platforms — not worth treating a 9-star,
or a 14-month-old, repo as a security boundary.

- **`androidMain` storage**: a 256-bit DEK is wrapped with an AES/GCM key from AndroidKeyStore; the
  wrapped DEK plus ciphertext go into DataStore Preferences. **Do not use
  EncryptedSharedPreferences.** Also remember to **exclude this secure storage from Android Auto
  Backup** — the ciphertext can't be decrypted after restoring to a new device.
- **`iosMain` storage**: `platform.Security.SecItemAdd` + `kSecClassGenericPassword` +
  `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`. Worth considering:
  `SecAccessControlCreateWithFlags(..., kSecAccessControlBiometryCurrentSet)`, so the entry
  auto-invalidates when the enrolled biometric changes — quite valuable for a financial app.
- **No mature KMP biometrics library exists**, but the good news is **iOS doesn't need cinterop at
  all**: `LocalAuthentication` is a first-class Kotlin/Native platform library, so `iosMain` can
  just `import platform.LocalAuthentication.LAContext` directly — zero Gradle config, zero `.def`
  file, zero Obj-C shim. Android side uses the stable `androidx.biometric:1.1.0` + `BiometricPrompt`;
  **do not** reach for the 1.4.0-alpha just to get the Compose-native API on the authentication path
  of a financial app. (Side note: KMPAuth is OAuth/social login, not biometrics — don't confuse
  the two; moko-biometry has been unmaintained for three years.)
- **Database encryption: no full-database encryption by default**, unless compliance requires it.
  iOS Data Protection and Android FBE already encrypt app-private storage at the system layer. Only
  put the actual secrets (tokens, app-lock PIN hashes) in Keychain/Keystore. If you really do need
  SQLCipher on iOS, **there's a pitfall that fails silently**: any transitive dependency that
  links the system `-lsqlite3` (the Firebase iOS SDK does this) will win symbol resolution, and
  your database **becomes plaintext with no error** — reordering the link order doesn't fix it. If
  you must go this route, always assert `PRAGMA cipher_version` at runtime and check the file
  header.

## Testing

The combination: `kotlin-test` (runner + basic assertions) + Kotest **assertions only** + Turbine +
Compose `ui-test`, all runnable under `iosSimulatorArm64Test`. Run with:
`./gradlew :shared:iosSimulatorArm64Test` (requires macOS + Xcode). On Kotlin/Native this
**doesn't go through JUnit** — a test binary is compiled and dropped into the simulator to run.

Ordered by "most likely to trip you up":

1. **`runComposeUiTest` is deprecated.** CMP 1.11.0 deprecated `runComposeUiTest` /
   `runSkikoComposeUiTest` / `runDesktopComposeUiTest` in favor of
   **`androidx.compose.ui.test.v2.runComposeUiTest`** (still `@ExperimentalTestApi`). Behavior
   changed too: v2 defaults to `StandardTestDispatcher` rather than `UnconfinedTestDispatcher` on
   non-Android platforms, so tests written against v1 assumptions will hang.
2. **`ui-test-junit4` has no iOS variant** (only `-android` and `-desktop`). Use `ui-test` in
   `commonTest`.
3. **Don't bring in Mokkery.** It's a compiler plugin whose compatibility table only lists up to
   Kotlin **2.4.0**, and we're on 2.4.10. MockK is JVM-only. **Default to hand-written fakes** —
   this app's domain layer is just a handful of narrow repository interfaces, and hand-written
   fakes paired with `MutableStateFlow` + Turbine feel natural, with zero coupling to a compiler
   plugin/KSP/Kotlin version. If you genuinely need mock verification, reach for
   **Mockative 3.3.2** (KSP-based, doesn't pin a Kotlin version).
4. **`IdlingResource` is unavailable on iOS** (removed from commonMain); use `waitUntil {}`.
5. **Kotest: assertions only, not its test framework.** `kotest-assertions-core` has an iOS klib,
   so `shouldBe` works directly in `commonTest`, **no `io.kotest` Gradle plugin needed, no KSP
   needed**. Its framework engine does technically support native, but annotation-based
   configuration (`@EnabledIf`, `@Tags`) **silently fails** on non-JVM platforms — because Kotlin
   doesn't expose annotations at runtime there. Very hard to diagnose once you hit it.

⚠️ **Turbine 1.2.1's iOS klib was built against Kotlin stdlib 2.1.21 / coroutines 1.10.2**, three
minors behind our 2.4.10. Klib compatibility within the 2.x line is usually fine, but **write a
smoke test early** to confirm it — don't wait until mid-project to find out. (Turbine itself isn't
dead, it just has only had dependency bumps since 2025-06, no feature updates.)

**Don't use assertk**: still stuck at 0.28.1 (2024-04), and its iOS klib declares stdlib 1.9.21 —
the 1.9→2.4 native klib gap tends to surface as cryptic errors.

## Known unresolved risks

1. **KSP 2.3.10 lags behind Kotlin 2.4.10.** Choosing SQLDelight already sidesteps the main impact
   surface, but if any KSP-based library gets introduced later (Room, koin-annotations, certain
   Mokkery configurations), verify this combination can actually build first.
2. **KMP + Gradle configuration cache** has had long-standing compatibility issues with
   Kotlin/Native tasks (KT-44900); neither side has declared full support. If you hit weird build
   failures, try disabling it first.
3. Metro's contribution-hint generation on native/Wasm reportedly needs Kotlin 2.3.20-Beta1+ per
   its docs — this hasn't been directly confirmed as the current state; re-check if we ever migrate
   to Metro.

## Environment requirements

- **Apple Silicon Mac** (iosX64 has been removed; Intel machines can't run the iOS simulator).
- JDK 17+ — this machine has 21, confirmed working.
- Android SDK `platforms;android-37.0` — already installed.
- **Full Xcode** — **this machine currently only has the Command Line Tools installed, not full
  Xcode.** Scope of impact (tested): `linkDebugFrameworkIosSimulatorArm64` and running the
  simulator are unavailable; `compileKotlinIosSimulatorArm64` **is unaffected and runs fine**.
- Android Studio + KMP plugin (for in-IDE run configurations; not needed for command-line builds).

## What's still unverified

Honest record of what's "actually tested" versus still just "should be fine per the docs":

**Actually tested and passing**:
- Android builds an APK; iOS klib compiles (including the SQLDelight native driver).
- 35 unit tests all green (domain calculations + schema/constraint validation on real SQLite).
- **The full SQLDelight pipeline**: code generation, bidirectional enum adapters, CHECK constraints
  genuinely block bad writes, idempotent seeding, carry-forward queries, per-day upsert — all run
  against real SQLite (JDBC driver).
- Compose / lifecycle / navigation / Koin / coroutines / serialization / datetime / SQLDelight
  dependency resolution on `iosSimulatorArm64`.

**Vico / Koin / navigation-compose actually run and verified** (Android emulator): charts with
axes render correctly, Koin wiring succeeds, bottom navigation switching works, the
SQLDelight-write-to-UI-refresh pipeline works end to end.

Two things confirmed by testing about Vico 3.x:
- Coordinate confirmed as `com.patrykandpatrick.vico:compose-m3:3.2.3`; the iOS klib exists
  (resolution verified).
- **`lineSeries` is deprecated, use `lineModel`.** The API was looked up from the sources jar —
  this library's coordinates and API have changed before; don't write it from memory.

**Ktor actually run and verified** (real Android network requests): 3.5.1 + OkHttp (Android) /
Darwin (iOS) engines, compiles on both platforms, actually fetched exchange rates on Android.
Tests use `ktor-client-mock`'s MockEngine, **no real network calls**.

**FX rate data source: Frankfurter (api.frankfurter.dev)**, official ECB data, no API key needed,
**supports historical dates** — this last point is the decisive one, since the domain model
requires "convert historical net worth using the exchange rate at that time." Two things confirmed
by testing:
- **Weekends/holidays return the actual business day.** Requesting 2026-07-26 (a Sunday) returns
  `"date":"2026-07-24"` in the response. So **you must store the date from the response, not the
  requested date**, otherwise the rate gets filed under a day the ECB never published one for.
- **Only 30 currencies are supported; TWD is not among them.** Unsupported currencies show "cannot
  be valued" — **it will never silently fall back to a 1:1 conversion** (there's a test locking
  this in).

**DataStore was not introduced** — see "Deliberate deviations" below.

**Quote source: Tencent Finance `qt.gtimg.cn` (unofficial)**

Chosen after comparative testing (2026-07-29); the other three options were all unusable:

| Source | No key needed | Test result |
|---|---|---|
| **Tencent `qt.gtimg.cn`** | ✅ | **200, works**, covers A-shares/HK/US stocks, supports batch requests |
| Sina `hq.sinajs.cn` | ✅ | 403 (still rejected even with a Referer) |
| Tiantian Fund | ✅ | Returns HTML instead of data |
| Yahoo unofficial | ✅ | 429 rate-limited |
| Alpha Vantage / Twelve Data / Finnhub | ❌ | Require registration; free tiers mainly cover US stocks |

⚠️ **Unofficial API, risk explicitly accepted** (product is positioned for personal/small-scale
use): no documentation, no ToS guarantee, could change at any time. When it fails, assets show
"cannot be valued" instead of silently computing something wrong — this is guaranteed by the
`QuoteSource` contract.

Two implementation details:
- **The payload is GBK-encoded.** Kotlin/Native has no built-in GBK decoder. The approach is to
  read it byte-by-byte as **Latin-1**; the ASCII price/code fields come through with zero loss,
  only the Chinese name field gets garbled (and we don't use that field anyway). **Do not switch to
  UTF-8 decoding** — GBK bytes are not valid UTF-8, and replacement characters could swallow an
  adjacent `~` separator, shifting fields out of alignment. There's a test locked in using real GBK
  bytes.
- **Currency is determined by code prefix** (`hk`→HKD, `us`→USD, otherwise→CNY), and is **forced to
  match the asset's currency** when a QUOTED asset is created. Mismatches would silently corrupt
  results: the quote price is denominated in the market's currency, while valuation converts using
  `asset.currency`.

**iOS actually tested (Xcode 26.6 / iOS Simulator 26.5 SDK, 2026-07-29)**, clearing three
long-standing risks:

1. **Framework linking succeeds** — it previously failed at `xcrun xcodebuild -version`; installing
   Xcode fixed it. Kotlin/Native's own declared minimum is `minimalXcodeVersion=12.5` (read from
   `~/.konan`'s konan.properties); those `xcode_26.4` references are the toolchain it downloads
   itself, not a requirement on the Xcode you have installed.
2. **SQLDelight's NativeSqliteDriver works on the real simulator** — `iosTest/NativeDatabaseTest`
   verified schema creation, bidirectional enum adapters, **CHECK constraints**, transactions, and
   per-day upsert. CHECK is worth verifying separately: Android and iOS use different SQLite
   builds, and whether constraints actually hold is a runtime behavior.
3. **Turbine's klib version gap turned out not to be a problem** — its iOS klib was built against
   stdlib 2.1.21 while we're on 2.4.10, and now `PortfolioFlowTest` actually exercises it on iOS.
   ⚠️ This risk had actually never been verified before: Turbine was a dependency that was
   **declared but never imported by any test**, and the linker strips unreferenced symbols — so
   even "it compiles" proved nothing.
   **After adding a cross-platform library, confirm a test actually uses it — otherwise a
   successful compile is a false sense of security.**

**The iOS App actually runs in the simulator (2026-07-30)**, hitting three snags along the way, all
recorded in the comments of `iosApp/project.yml`:

1. **Linking fails at `_sqlite3_step`** — the Xcode target needs an explicit `-lsqlite3`. **The
   easiest thing to misjudge: the iOS unit tests all pass.** When Kotlin/Native links its own test
   executable it inherits SQLiter's cinterop linker opts, but once the static framework is handed
   to Xcode, those opts don't carry over into the App's link command line. So "iOS tests all green"
   does not imply "the App can link."
2. **Missing `CADisableMinimumFrameDurationOnPhone` causes a crash on launch** — CMP's
   `PlistSanityCheck` proactively throws `IllegalStateException` (meaning: without this key,
   ProMotion devices would get capped at 60Hz). **This crash produces no crash report and nothing
   in the system log**, because the exception happens on a dispatch queue; `simctl launch` just
   returns a PID and the App quietly disappears. The only way to see the Kotlin exception and stack
   trace is `xcrun simctl launch --console`.
3. Initially suspected it was the Xcode 16+ debug dylib / Previews mechanism, and added
   `ENABLE_DEBUG_DYLIB: NO`. **Later testing proved that wasn't the cause** (it launches fine either
   way), so it was removed — leaving a wrong explanation in place is worse than having none.

The project uses **XcodeGen**: `project.yml` is committed, `.xcodeproj` and `Info.plist` are both
generated artifacts. This directly eliminates the root cause behind the "pbxproj conflicts are
nearly impossible to resolve" constraint in AGENTS.md — what needs review becomes a twenty-odd-line
YAML file.

### iOS UI tests (XCUITest) — three facts uncovered by probing

CMP draws the entire UI onto a Skia canvas, so the first thing to confirm was whether XCUITest can
even locate elements. **It can** — CMP's semantics map onto UIAccessibility. But there are three
pitfalls, all found by actually testing:

1. **Compose's `OutlinedTextField` shows up in the accessibility tree as a `TextView`, not a
   `TextField`.** `app.textFields` finds none of them.
2. **You can't rely on `OutlinedTextField`'s `label` for locating it** — it only maps to an
   accessibility label in some states, and disappears once focused (screen-reader users would also
   hear nothing). So key input fields got an explicit `Modifier.semantics { contentDescription = ... }`
   added — which is also a genuine accessibility improvement in its own right.
3. **Compose concatenates `contentDescription` with the visible label** into something like
   `'field-asset-name, name, e.g. "CMB checking"'`, so XCUITest has to do **prefix matching**, not
   exact matching.

**Tapping after scrolling has two pitfalls, both actually hit** (both manifest as "tapped and
nothing happened," which is harder to debug than "element not found"):

1. **`isHittable` being true doesn't mean it's actually tappable.** An element stuck at the edge of
   the screen still reports `isHittable` as true, but `tap()` hits its **center point**, which may
   be outside the visible area. The check needs to become "the element lies entirely within the
   window," with extra margin reserved at the top for the TopAppBar's height — otherwise an element
   scrolled to the top gets covered by the app bar, and the tap lands on the app bar instead.
2. **Inertial scrolling that hasn't stopped yet means the element is still moving** — tapping
   immediately after a swipe can miss. Wait 0.5s after each swipe.

One more structural point: **a single test should only scroll in one direction.** Scrolling to the
bottom to find something at the end of the list, then scrolling back to the top to assert on the
first screen, collides with point 1 above (the element back at the top gets blocked by the app bar,
the check rejects it, and then it scrolls down again and drifts away). Moving the "assertion about
the end of the list" into a test that was already going to scroll there anyway is more reliable
than adding more scroll logic.

Also, **the accessibility tree only reports nodes within the visible area** — an off-screen
element's `exists` is simply false. Same for Android's uiautomator (tested: the liabilities group
in the instrument-type list only shows up in the dump after scrolling a screen down). So the first
suspicion for an "element doesn't exist" error should be "haven't scrolled there yet," not "wasn't
rendered."

Two more operational notes: after typing into a field, **dismiss the keyboard** (trigger
ImeAction.Done via a newline), otherwise the next field might be under the keyboard and `tap()`
hits the keyboard instead, producing an error like "Neither element nor any descendant has
keyboard focus" — which looks like an element-not-found problem but is really a mis-tap. And the
`-uitest-reset` launch argument gives every test a clean database to start from (implemented on the
Swift side, doesn't pollute shared production code).

**Still unverified**:
- End-to-end HK/US stock flow (only A-shares sh600519 verified; parsing and currency mapping are
  covered by unit tests).
- **The interaction flow on iOS** (add/update/archive an asset) — no iOS UI automation exists yet;
  the Android side has been manually tapped through on a real device.
- iOS real device (not simulator) — needs signing configuration.
- **Turbine's klib version gap** (its iOS klib was built against Kotlin stdlib 2.1.21, we're on
  2.4.10) — it's already in commonTest and compiles on the JVM, but **the iOS test hasn't run yet**,
  so the risk is still open. Needs Xcode.
- **SQLDelight actually running on real iOS** (NativeSqliteDriver) — only compilation has been
  verified, it hasn't actually run. Tests use the JVM's JDBC driver; the SQL and constraints are the
  same, but the driver isn't.
- Compose UI testing (the `runComposeUiTest` v2 API) hasn't been touched at all.
- iOS linking and running on a real device/simulator — no Xcode available.

### Why unit price doesn't use Money

`quote.price` is `UnitPrice` (scale 8), not `Money` (scale 2). Unit prices need more precision than
amounts: low-priced HK stocks quote to 3 decimal places (Tencent returns `462.400`), and crypto
tokens can be `0.00001234`. Storing at scale 2 would turn the latter into `0.00`, silently zeroing
out an entire position.

The cost is that `shares(8) × unit price(8) → amount(2)` has to be computed in two steps — a naive
implementation would necessarily overflow a `Long` (1e14 × 1e11 = 1e25). See `UnitPrice.valueAt`.

### Deliberate deviation #2: preferences stored in SQLDelight, not DataStore

The base currency is stored in the `settings` table (SQLDelight) rather than DataStore. Reason: we
currently only need to store a single string, while DataStore would require pulling in a new
dependency + okio Path + platform-specific path implementations on both platforms, and its
**non-Android targets are still officially marked experimental by Google**. Paying that price for
one string isn't worth it, and it adds one more iOS risk.

Once there are actually batches of preferences (theme, reminders, app-lock config), move to
DataStore, and the `settings` table can be migrated over at that point. The datastore version stays
in the catalog for now.

### A deliberate deviation: not using `expect class`

`DatabaseDriverFactory` uses **an interface plus per-platform implementation classes**, not
`expect class`. Two reasons: `expect class` is still Beta in Kotlin 2.4 (KT-61573, emits a
warning); and the Android implementation needs a `Context` while iOS doesn't, so an interface fits
the differing-constructor-parameters scenario more naturally. When adding more platform
implementations later (Keychain/Keystore, biometrics), follow this same pattern.


## App icon and brand color

Brand color: **amber-brown `#8A5A18`** — evokes the abundance implied by "旺" (prosperity), while
avoiding stock-market red.
There was no brand color before this — the whole app ran on a bare `MaterialTheme {}`, using
Material 3's default purple (that's where the pale-purple FAB and purple nav indicator came from).

We first tried ink blue `#1F4E85`, which felt flat on a real device (cool tone + near-gray-white
background gave it no presence on a screen full of colorful icons), so we switched to amber-brown
and warmed up the icon's background color too. **Changing the brand color only requires editing two
constants**: `BrandAmber` in `Theme.kt` and `BRAND` in `generate.py` (plus the Android adaptive
background layer's `colors.xml`).

⚠️ The tertiary color is deliberately **cool gray-blue**, not the green M3 would auto-derive from a
warm seed color — green reads as "loss" in a Chinese finance-app context, so avoid it even as a mere
accent.

The icon is "a four-segment allocation ring plus a center 旺 character," with **all sizes generated
by `tools/appicon/generate.py`**. Both the script and the generated artifacts are committed: the
script is the source of truth, and the PNGs are also checked in so the build doesn't depend on
Pillow.

### Why a script is required instead of manually cropping images

The geometric requirements on the two platforms are fundamentally different, and manual alignment
is bound to go wrong eventually:

| | iOS | Android adaptive |
|---|---|---|
| Canvas | Full 1024×1024 | 108dp layer |
| Visible area | All of it (system applies a squircle mask) | Only the middle 72dp, and **only a 66dp-diameter circle is guaranteed uncropped** |
| Alpha | **Not allowed** (alpha gets the app rejected from the App Store) | Foreground must be transparent |
| Corner rounding | **Can't draw it yourself** — the system applies a mask | Determined by the launcher's mask (varies by OEM) |

Taking the full-bleed iOS image and using it directly as the Android foreground would crop off part
of the ring.

### Three pitfalls found through testing

1. **Pixel Launcher rescales adaptive icons an additional time.** After insetting to 66/108, the
   locally composited version against a 72dp viewport put the ring almost flush against the edge
   (0.938), **but on a real device it was noticeably smaller.** This extra scaling is Launcher3's
   icon normalization, which is outside the `AdaptiveIconDrawable` spec, so local compositing can't
   reveal it. Pushing right up against the safe area gets clipped under other OEMs' masks —
   compensated instead by **thickening the strokes** to restore visual weight.
2. **Geometry parameters must be given relative to the ring's outer radius, not the canvas.** The
   inner radius and glyph size were originally expressed relative to the canvas; adjusting the
   full-bleed version's margin from 0.80 to 0.72 **incidentally thinned the strokes by 20%** as a
   side effect, and it was hard to notice. It's now `RING_OUTER` (controls margin only) +
   `INNER_RATIO` (controls thickness) + `GLYPH_RATIO`.
3. **After changing the icon, SpringBoard shows a placeholder icon** — could be a dark gray
   background (looking like a broken auto-derived dark variant), or could be a frosted blank
   square. Both are just an unrefreshed icon cache — **rule this out first** before debugging an iOS
   icon problem. The reliable verification order is:
   - First read the bitmap **actually produced** in Xcode's compiled resource directory:
     `<app>/AppIcon60x60@2x.png` (120×120, the one shown on the iPhone home screen). This step is
     unaffected by caching.
   - Then look at a home-screen screenshot. `launch` followed by `terminate` leaves SpringBoard on
     the page where that app lives; **`simctl` has no way to flip home-screen pages**, and
     `launchctl stop com.apple.SpringBoard` jumps back to the first page after restarting — so don't
     try to find the icon by restarting SpringBoard.

The font is **Hiragino Sans GB W6** (a Gothic/sans face) rather than a Song/serif face: a serif's
thin horizontal strokes turn to mush at 40px, confirmed by actually comparing the two. PingFang is
system-protected and Pillow can't read it.

### Configuring a ColorScheme requires writing out every single role

Parameters not passed to `lightColorScheme()` fall back to **baseline defaults**, and the baseline's
surface family is **gray with a purple cast** (the `#F3EDF7`-ish kind). Changing only `primary`
leaves Card and BottomBar backgrounds still tinted purple — it looks like the theme is only
half-changed. `Theme.kt` spells out every single role explicitly, including the whole
`surfaceContainer*` group.

**Brand color and gain/loss semantic colors are two separate things.** Chinese stock markets use
red-for-up/green-for-down; when coloring gains/losses later, add a separate set of constants —
don't reuse `primary`/`error`.


## Why the surface went from warm off-white to neutral white-gray

The first version paired amber-brown with a warm off-white surface, and the feedback was "doesn't
feel premium." **The root cause isn't the hue, it's the background.** A warm off-white background
(`#FFFBF5` / `#F7EEE1`) inherently reads as "beige/retro," and it also raises the overall brightness,
which degrades every accent color's contrast — that's exactly how the first version ended up with
four category colors below 3:1.

Referencing **Youzhiyouxing** (有知有行)'s design tokens (read directly from their site's computed
styles and CSS variables, not from memory):

| Role | Value |
|---|---|
| Page / card / section | `#FFFFFF` / `#FFFFFF` / `#FAFAFA`, `#F5F5F5` |
| Text gradient | `#262626` → `#5C5C5C` → `#808080` → `#BFBFBF` (**pure gray, no color cast**) |
| Divider | `#E0E0E0` |
| Accents | primary `#21A3FF`; blue `#4287CE`, cyan `#66B5CC`, green `#2EB88A`, gold `#E0B870`, orange `#E5881E`, purple `#595E99`, pink `#F19D79`, red `#E5605C` |

Its "premium feel" comes from **restraint**: neutral surfaces plus a very small amount of
low-saturation accent color, rather than more colors. We adopted the same idea and switched the
surface and text to neutral, **keeping the brand amber-brown as the sole warm accent** — it stands
out more against a neutral background, and the icon didn't need to be redone.

## Chart colors: why they weren't picked by hand

Category colors and deviation colors were derived by running **dataviz's six checks plus a
validator**, not chosen by feel. The values are pinned in `ChartColors.kt`; `ChartColorsTest` will
fail if anyone changes them, with the rerun command documented in that test's comments.

### Two responsibilities, two rule sets

| | Responsibility | Rule |
|---|---|---|
| Five asset classes | **Categorical color** (encodes identity) | A fixed-order set of five hues, **order must not be rearranged** |
| Deviation | **Divergent color** (encodes sign) | Two opposing hues + a **neutral** midpoint |

**The order itself is the colorblind-safety mechanism**, not an aesthetic choice — candidate orders
were each individually validated, and only ones that passed were picked from. So don't reorder
`assetClassColors`, and don't "casually generate" a color for a hypothetical sixth category: a
generated color isn't protected by validation.

### Must validate against the actual rendered background color

The progress bar is drawn inside a Card, so its background is `surfaceContainer` (light `#FAFAFA` /
dark `#1C1C1C`), **not** the page background. Contrast computed against the wrong background is
meaningless. Light mode is validated against the **worst case `#FFFFFF`** (the actual card is
slightly darker, which can only help).

### Youzhiyouxing's hues need to be snapped before use

Directly using their color values to fill the five categories fails: gold `#E0B870` (L 0.803) and
pink `#F19D79` (L 0.772) **fall outside the lightness range**, cyan `#66B5CC` (C 0.084) and purple
`#595E99` (C 0.094) **fall below the 0.10 chroma floor** (they'd read as gray and lose their
identity-coding power). The reason is straightforward: **their colors are meant for small-area
accents and text**, which don't need five color blocks to be mutually distinguishable.

We applied snap-to-passing — **the hue angle is fixed**, only lightness and chroma are moved. Out
of 2520 permutations from choosing 5 of 7, **588 passing combinations** were found; we took the one
closest to the originals: total deviation across all five colors of only **ΔE 5.8**, with green
unchanged by even a single pixel. Gold and pink were automatically excluded (the snap cost was
highest for them, ΔE 8.7 / 5.7 respectively).

Test results (OKLab ΔE ×100, protan/deuteran simulation, thresholds 8 / 15):

| | Worst adjacent pair, CVD | Worst pair, normal vision | Color-block contrast |
|---|---|---|---|
| Light | 11.5 | 20.4 | 2 below 3:1 → WARN |
| Dark | 10.8 | 16.6 | All ≥ 3:1 |

The light-mode WARN **cannot be waived**: it must have a compensating channel. Our compensation is
**structural** — every row always shows both the category name and the percentage simultaneously,
and the bar's own length is readable on its own without depending on color. **Don't remove those
labels when redesigning the layout**, or this compensation genuinely breaks.

### Dark mode is not an automatic flip of light mode

The same five hues were **re-derived for lightness and separately validated** against the dark
background. An automatic flip would fall outside the lightness range.

### Three choices for the deviation colors

1. **Overweight uses red** — a product requirement.
2. **Underweight uses blue, not green** — green reads as "down" in a Chinese finance context, so
   using it for "underweight" would be read as a loss.
3. **On-target uses neutral gray**: the midpoint of a divergent scheme can't itself be a hue, or
   "no deviation" would also look like a state.

Also, deviation colors **do not reuse `error`**: being overweight isn't an error, it's a difference
from the plan; painting it as an error color would dilute the weight of an actual validation
failure. The underweight blue is **one shade darker** than category-slot 1's blue, so it stays
distinguishable from the "liquid funds" category color block.

These three are **text colors**, validated against the WCAG body-text standard (≥ 4.5:1; measured
4.89 / 5.34 / 6.69 in light mode). "Overweight" red is drawn from Youzhiyouxing's `#E5605C`, but on a
white background it's only 3.41:1 — **fails for body text** — so light mode darkens it to
`#C5453F`; the dark background is dark enough that the original value can be reused.

### The net-worth chart uses the brand color

There's only one series, so it uses the brand amber-brown, **not one of the category colors** —
giving it a category color would suggest it's tied to a specific asset class. A single series
doesn't need a legend; the title already explains what it is.

⚠️ Vico's class paths are very easy to get wrong: `LineCartesianLayer`, `rememberLine`, `Fill` are
all under `com.patrykandpatrick.vico.compose.*`, with **no** `multiplatform` segment. If unsure,
unpack the klib and check the actual symbols — don't write the import from memory.

### Not yet done

**Gains/losses have no color coding yet.** Chinese stock markets use red-for-up/green-for-down, and
coloring P&L is a reasonable next step, but first we need to decide what red means exclusively in
this app — right now red means "overweight," and having it also mean "gained" at the same time would
conflict.


## Vico API lookups must be done against the pinned tag

The first time we looked up the parameters for `HorizontalAxis.ItemPlacer.aligned()`, we used
WebFetch to pull source from GitHub's `master` branch, and got a signature that included a
`shiftExtremeLabels` parameter — the build then failed with "no such parameter," because the
project's pinned 3.2.3 version doesn't have this parameter at all (it was added later, in a newer
version).

The correct approach:

```bash
# 1. Find the git tag matching the project's pinned version, and get the commit sha it points to
gh api repos/patrykandpatrick/vico/git/refs/tags | python3 -c "
import json,sys
for t in json.load(sys.stdin):
    if 'v3.2.3' in t['ref']: print(t['ref'], t['object']['sha'])"

# 2. If unsure of the file path, search for it — don't guess from experience; this library's
#    directory structure is several levels deeper than its package name suggests
gh api search/code -X GET -f q='filename:PieChartModel.kt repo:patrykandpatrick/vico'

# 3. Use the sha from step 1 as ?ref= to look up the source — this is the actual API the project links against
gh api "repos/patrykandpatrick/vico/contents/<path>?ref=<sha>" --jq '.content' | base64 -d
```

`gh api` is more reliable than WebFetch: what WebFetch fetches is a summarized page (possibly
paraphrased by a small model, and possibly fetching the default branch instead of the version you
actually want), while `gh api` gives you the raw content directly and lets you pin `?ref=` to an
exact commit. **Vico's pitfalls aren't limited to package paths — parameters change between
versions too** — both lessons boil down to the same thing: don't trust "looks about right," go
check the actual source that's actually linked.

## Vico's PieChart

`com.patrykandpatrick.vico.compose.pie` has ready-made donut/pie chart components
(`PieChart` / `PieChartHost` / `PieChartModelProducer` / `pieSeries {}`) — no need to hand-draw with
Canvas. The usage pattern mirrors the line chart we already use (`CartesianChartModelProducer` /
`lineModel {}`):

```kotlin
val modelProducer = remember { PieChartModelProducer() }
LaunchedEffect(values) {
    modelProducer.runTransaction { pieSeries { series(values) } }
}
val chart = rememberPieChart(
    sliceProvider = PieChart.SliceProvider.series(
        colors.map { PieChart.Slice(fill = Fill(it)) }
    ),
    innerSize = PieSize.Inner.fixed(64.dp),  // > 0 makes it a donut, 0 is a solid pie
)
PieChartHost(chart = chart, modelProducer = modelProducer, ...)
```

`PieChartModel.Entry` requires non-negative values (`>= 0f`) — a negative value throws at
construction time. Same reasoning as [ClassRow](../shared/src/commonMain/kotlin/com/boomsset/ui/allocation/AllocationScreen.kt)'s
progress bar: a negative net exposure can't be drawn as a slice, so `coerceAtLeast(0)` it before
passing it in.

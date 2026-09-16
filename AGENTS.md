# AGENTS.md — Boomsset (旺资)

## Project Status

**The core loop is closed end-to-end (verified on a real Android device).** Three screens: Net Worth Chart / Asset Allocation / Asset List.
Add asset → periodically update valuation → archive — the whole flow works. **All 247 unit tests pass.**

Verified by actually running the app (including checking SQLite directly): an update **appends a new snapshot** rather than overwriting (cost basis carries forward correctly); archiving appends a zero-value snapshot without touching a single character of history; allocation percentages sum to 100%.

**Both market quotes and multi-currency support are wired up (verified on a real device):**
- FX rates: Ktor + Frankfurter (ECB, no API key needed, **supports historical dates**). Tested: $1000 → ¥6,771.30
- Quotes: Ktor + Tencent Finance `qt.gtimg.cn` (**an unofficial API**, no key needed, covers A-shares/HK stocks/US stocks). Tested: 100 shares of sh600519 @1334.05 → ¥133,405.00, gain +11.17%
- The base currency is switchable and persisted; switching only changes the display basis — not a single snapshot gets rewritten
- **Historical FX rates are backfilled by range** (verified on the emulator: `fx_rate` went from 1 row to 20 rows, covering 2026-08-07…09-03, i.e. the entire holding period). It queries Frankfurter's `/v1/{start}..{end}`, fetching an entire span in one call. The logic lives in `RateRefresher.requiredRanges` — see Lesson 15 below.

⚠️ **Known issue: Quote backfill hasn't been done yet, but FX-rate backfill has.** `refreshQuotes` only fetches today's price, while valuation looks up "the most recent entry at or before that point in time" — so for a stock held for three months, historical points can't find a price, get marked "cannot be valued," and the net worth at those points is computed as 0 (**this happens even when the base currency is CNY** — it has nothing to do with FX). The fix is structurally the same (Tencent's daily-K-line has a separate endpoint), but since Tencent's API is unofficial, its response format and semantics would need to be re-explored from scratch — so it wasn't done alongside the FX-rate fix. **Check this issue first before touching net-worth-chart accuracy.**

⚠️ **The Tencent API is unofficial**: no documentation or ToS guarantee, and it could stop working at any time. The product is positioned for personal/small-scale use, and this risk is explicitly accepted. When it fails, affected assets show "cannot be valued" (**it will not silently compute a wrong number**). The response is GBK-encoded; it's read byte-by-byte as Latin-1 to preserve the ASCII price fields — don't change this to UTF-8 decoding.

**Target allocations are editable (verified on a real device):** multiple presets can be switched between and compared, percentages can be edited, and built-in presets can be "restored to default" but not deleted. **Saving enforces that the percentages sum to 100%** — an allocation that doesn't add up would make every deviation calculation silently wrong.

**Asset metadata is editable (verified on a real device):** name / asset class / instrument type / whether it counts toward allocation can be changed at any time; **currency and liability flag can only be changed while the asset has exactly one snapshot** — changing them retroactively reinterprets every historical snapshot (the amount numbers stay the same but their meaning changes); the logic lives in `AssetEditPolicy`. Instrument types can be freely added by the user, and archiving can be undone.

**iOS has been verified (Xcode 26.6 + iOS Simulator 26.5 SDK):** the framework links successfully, **all 158 iOS simulator tests pass**, including `NativeDatabaseTest` which specifically verifies `NativeSqliteDriver` (schema creation, enum adapters, CHECK constraints, transactions, per-day upserts) and `PortfolioFlowTest` which uses Turbine.

**The iOS app has actually been run in the simulator (verified):** the shared Compose UI renders correctly, Koin starts up successfully, and SQLDelight's native driver creates and seeds the database inside the real app. `iosApp/` uses **XcodeGen** to generate the Xcode project — what's committed is `project.yml`; the `.xcodeproj` and `Info.plist` are generated artifacts and are gitignored. See iosApp/README.md.

**There's a manual fallback for quotes (verified on a real device):** every QUOTED asset shows the date of its quote, flagged as stale after 3 days; the user can manually enter a price to override it. **This is the only remedy when the Tencent API fails** — without it, an asset whose price can't be fetched would show "cannot be valued" forever. A manually entered price is written into the same `quote` table, so a successful automatic refresh on the same day will overwrite it (this is intentional: a real market price is naturally more accurate than a manual entry).

**App lock has been implemented (verified on a real Android device):** either biometrics or the screen-lock passcode, whichever the user chooses; authentication must succeed once before enabling it, and the unlocked state **is not persisted** (re-authentication is required after backgrounding or restarting). While locked, protected content is **not composed at all** rather than covered with an overlay — the latter would show up in the task-switcher screenshot and could also flash visible for an instant.

**Brand color and app icon are done. ⚠️ The current value is deep rosewood purple `#5D3270`** — defined in
[Theme.kt](shared/src/commonMain/kotlin/com/boomsset/ui/theme/Theme.kt), and follows the system light/dark mode.
The paragraphs below record, in chronological order, the multiple revisions from `#8A5A18` → `#BD4D03` → `#918163` → `#986E00` → `#955E00` → `#D3BC7D` → `#5D3270` and the reasons each one was rejected.
**When reading a paragraph that mentions an old hex code, remember that it's history, not the current state.**
The icon shows "bursting out of a ring" (an allocation ring + three rising bars, with the tallest bar breaking out past the ring) — **the ring uses the actual colors of the app's five asset classes**, and all the sizes are generated by
[tools/appicon/generate.py](tools/appicon/generate.py) —
**that is the single source of truth; the PNGs under res/ and Assets.xcassets are build artifacts, don't hand-edit them.**
Any change to its constants must be followed by running [tools/appicon/validate.py](tools/appicon/validate.py).

Before all this, the entire app was wrapped in a single bare `MaterialTheme {}`, so the UI ran on Material 3's own **default purple** —
that wasn't a design decision, it's just that nobody had configured a color scheme yet. When configuring a `ColorScheme`, **every role must be filled in explicitly**: any parameter you don't pass falls back to the baseline default,
and the baseline `surface` family is a purple-tinted gray — changing only `primary` would leave Cards and the BottomBar still looking purple.

**Verified on a real Android device (Xiaomi 15 Pro / Android 16 / HyperOS 3):** launches without crashing,
theme and empty states are correct, status-bar icon contrast is 10.20:1; app lock's
`capability()` returns AVAILABLE **on a device with real biometrics enrolled** (the emulator only has a PIN, so that path hasn't been verified there).
⚠️ **Don't store the `adb` device argument in a shell variable** (`D="-s xxx"` then using `$D`) —
zsh doesn't word-split an unquoted variable, so it gets passed as a single argument and you get `-s requires an argument`.

**Empty states are usable now (verified on both platforms, real device):** the net-worth page gives a three-step onboarding guide and explains "we record snapshots, not transactions"
(without that explanation, users would use it the way they'd use a bookkeeping app). **The allocation page works fine even with zero assets** —
the target allocation percentages themselves are shown, presets can be switched, and percentages can be edited.

**The net-worth chart no longer shows periods with "no data," and the allocation page gained a pie chart (verified on both platforms, real device):**
when viewed by quarter/year, an account that's only a few months old used to have a fixed window of 12 periods, with a large leading stretch of zero-value points where "the asset didn't exist yet." Now there's a `trimBeforeFirstSnapshot` toggle (off by default, so it doesn't change the carry-forward semantics locked in by existing tests); when turned on in the UI, it keeps only the sample points at or after the first snapshot. The criterion is **whether the sample point's end time is before the earliest snapshot time**, not "whether net worth is 0" — a genuine zero after the account has been cleared out (e.g. every asset archived)
shouldn't be erased by this rule as "no data."

At the same time, Vico's `HorizontalAxis.ItemPlacer.aligned(spacing, offset)` is used to thin out x-axis labels
based on point count, instead of "one label per point" cramming into overlap and truncation (observed in testing: "Oct/Nov/Dec"
got truncated to "Oct…/Nov…/Dec…"); `offset` is deliberately computed so that "the last index is aligned," guaranteeing the newest point
always has a label on the far right, so it's never missed just because the point count isn't a multiple of spacing.

The allocation page gained a donut chart (Vico's `PieChart`/`PieChartHost`, from the same package already used for the line chart),
sharing the five fixed-order asset-class colors from [chartColors](shared/src/commonMain/kotlin/com/boomsset/ui/theme/ChartColors.kt)
with the progress bars in [ClassRow](shared/src/commonMain/kotlin/com/boomsset/ui/allocation/AllocationScreen.kt) — the donut doesn't get its own separate legend text, since the names and percentages are already written on the cards below it,
and writing them again would be redundant (the first version of the allocation page got exactly this kind of feedback for repeating "compared to which target," and that lesson was carried straight over).

⚠️ **When looking up the Vico API, always check against the pinned tag, never trust GitHub's default branch.**
The first time we looked up the parameters of `HorizontalAxis.ItemPlacer.aligned()`, WebFetch pulled from the
`master` branch and returned a "parameter that only exists in a newer version" (`shiftExtremeLabels`), which failed to compile with
"parameter not found." We used `gh api repos/.../git/refs/tags` to find the commit SHA matching the version the project actually pins,
then `gh api repos/.../contents/<path>?ref=<sha>` to look at the source — only that gave us the API this
project actually links against. The file paths for Vico's pie-chart code were likewise found using
`gh api search/code` for the real paths, not guessed from experience (guessing would likely have been wrong —
this library's directory structure goes several levels deeper than its package name suggests).

**The brand color was brightened again (verified on both platforms):** `#8A5A18` → `#BD4D03`, feedback being that the original color wasn't "upbeat/positive" enough.
Simply raising lightness/chroma along the old hue wasn't feasible — the candidate would get clipped in sRGB, and clipping itself silently shifts the hue angle,
which ended up colliding with the "Equity" chart orange `#E58A26` (the allocation page's FAB and the Equity bar would sit right next to each other).
Instead, the hue was shifted about 25° toward red, and the dataviz validator confirmed a separation of ΔE 16.2 (threshold 15).
Details and the solving process are in the comments of [Theme.kt](shared/src/commonMain/kotlin/com/boomsset/ui/theme/Theme.kt) . The icon was updated to the new color at the same time; the ring's four-step gradient was recalculated as an "offset relative to BRAND," not hand-tuned.

**Surfaces are neutral white-gray, not warm off-white (verified on a real device):** the main cause of it feeling "not premium enough" wasn't the hue, it was the background —
a warm off-white background reads as "yellowish/retro" on its own, and it also degrades the contrast of every accent color.
Now the surface and text lightness ladder is taken from **Youzhiyouxing (有知有行)**'s design tokens, and the brand amber-brown is the **only** warm accent.
The icon keeps its warm color unchanged (the icon has nothing to do with the app's surfaces, so it didn't need redoing).

**⚠️ The two paragraphs above have been superseded by the round below** (neither pure neutral white-gray nor `#BD4D03` is current anymore),
but the conclusion that "a warm off-white background reads as yellowish/retro" still holds — the current warm background's chroma is only 0.013, far lower than that version.

**Changed to a warm Morandi tone: `#BD4D03` → olive gold `#918163` (verified on the Android emulator, API 34).**
Feedback was "not attractive enough." The problem wasn't the hue, **it was role assignment**: `#BD4D03`'s OKLCH chroma was **0.160**,
and it sat on `primary` — the FAB, nav indicator, switches, and net-worth bars were all colored by it. A high-chroma orange used as a large fill
reads, in a Chinese-app context, like the e-commerce tier of design. Now the chroma is down to **0.047**, and color is reserved entirely for data.

Two dead ends explored along the way each left behind a reusable finding:

* **"Dark-ink anchoring" (primary L 0.33 + a dark hero card) was rejected as too dark.** But it exposed a real dependency:
  `primaryContainer` in this project **has exactly one consumer — the net-worth page's hero card**
  (the Card in `NetWorthScreen`), so changing it is equivalent to changing just that one card, no UI code changes needed.
* **Cool colors were rejected, but for a computable reason**: four of the five asset-class colors are cool colors, and they already fill up the cool-color region
  of the lightness axis (at the time: Protection purple L.48, Liquid Funds blue L.60, Fixed Income green and Alternative/Physical Assets cyan both L.70).
  A cool `primary`'s available window is limited to **L ≤ 0.42**, necessarily a full step darker than a warm one; a warm color can reach **L 0.61**.
  **The only way to get a brighter cool color is to first move "Protection purple"** — it's the one pinning the ceiling at L 0.48.
  (⚠️ It really was moved later — see the "brand color changed to deep rosewood purple" section below: Protection has since moved from purple to golden-yellow `#977E00`.)

The three hard criteria now in place (re-verify if you change any color value; method is the same as in the ChartColorsTest comments):
1. **Minimum ΔE ≥ 15 against all five asset-class chart colors** (measured 15.2). The old `#BD4D03` was only 16.2 against the Equity orange,
   sitting right at the threshold — changing color wasn't just about looking good, it was pulling a barely-passing value further apart.
2. **`primary` against the page background ≥ 3:1** (measured 3.70); below this, the FAB blurs into the background.
3. **`onPrimary` against `primary` ≥ 4.5:1**. ⚠️ Once primary reaches L 0.61, **white text is only 3.86:1, which fails**,
   so `onPrimary` is dark brown, not white — this is generally true across Morandi palettes: **a color bright enough to feel sunny can't support white text**. Don't just assume `Color.White` will work.

Neutral surfaces are no longer pure gray either: the lightness steps are kept, but a slight chroma at H=82 is added
(near-white surface 0.008, mid-gray 0.013, dark text 0.020); body-text contrast barely changes (13.1:1).

**Gain/loss colors were changed along with it, and went from "one fixed set of values" to "two sets, light and dark"** (see
[GainLossColors.kt](shared/src/commonMain/kotlin/com/boomsset/ui/GainLossColors.kt)).
The criterion is **≥4.5:1 against every background it actually sits on**. It's now provided by `BoomssetTheme` through
`LocalGainLossColors`, the same approach as `LocalChartColors`. **Red for gains, green for losses — that hasn't changed at all.**

⚠️ **"The worst-case background" isn't the same one in light mode and dark mode — this was a real trap.**
In light mode, the hero card (`primaryContainer`) is **darker** than the page background, so it's the worst case
(the old value `#C5453F` only reached 3.34:1 against it); **in dark mode, the hero card is actually lighter than the page background**,
and it's again the worst case — but the first dark-mode value was only verified against `surfaceContainer`,
and it turned out to be **only 2.50:1** against the dark hero card (`#5E4200`),
**only discovered by actually switching to dark mode on a real device**. General rule: when changing these two color values, **check all three backgrounds one by one**
(page background / regular card / hero card) — don't assume which one is the worst case.

**The Morandi version was rejected again as "too dull" → switched to warm gold `#986E00` (verified on the Android emulator, API 34).**
The restraint that came with low chroma cost something: **every place that relies on color to indicate state got weaker** (the bottom nav's selected state ended up
fainter than the unselected state — see below). Now the chroma is back up to **0.120**, but the lightness is pulled down to **L 0.565** —
the key insight being that **going darker actually lets you carry more chroma**: "Equity" orange sits at L 0.715, so moving further away from it naturally opens up ΔE
(16.0, wider than the Morandi version's 15.2). **White text finally passes too** (4.60:1) —
the Morandi version at L 0.61 only got white text to 3.86:1, forcing dark brown.
⚠️ Going even more gold means going darker still: `#AC8137` (L 0.63) is only ΔE 10.3 against Equity — outright fails.

**The icon ring switched to using the app's actual five asset-class colors (the light-mode values from `ChartColors`, not a single pixel changed).**
The ring on the icon is exactly the five color chips on the allocation page — "blue = Liquid Funds" holds true in both places;
`validate.py` checks every color one by one and FAILs on any mismatch.
**The cost was giving up "monotonically increasing lightness"** — the three brightest of the original colors (Fixed Income 0.699 /
Alternative 0.715 / Equity 0.715) are nearly tied, leaving no usable gradient along the lightness axis. The fallback is colorblind separation
(minimum adjacent ΔE 10.2, threshold 8). ⚠️ **The background color was forced by this set of colors**: to get all five original colors to
≥1.8:1 against the background, the background had to be either **L ≤ 0.30 or L ≥ 0.95**, so it moved from wheat gold `#D7B984` to a
near-white warm cream `#FBF2E3`. The bars are three steps of brand gold, and **the middle one is exactly `BrandGold`**.

**The splash screen (Android 12+'s system splash) had never been configured before.** The host theme is
`android:Theme.Material.Light.NoActionBar`, and without configuring `windowSplashScreenBackground`,
it falls back to the platform's light gray `windowBackground` — which is neither our warm background **nor does it follow dark mode**
(in dark mode it would flash light gray first). Now all three of `values/`, `values-v31/`, and `values-night/` are set consistently,
with the color value equal to `Theme.kt`'s `surface`, so there's no color jump between the splash screen and the first frame.

**The `alpha = 0.5f` on the net-worth chart's bars was removed.** That was leftover from the version where bars and a line were drawn overlapping
(the transparency let the line show through); once the line was deleted, all it did was "make the bars fainter" — measured contrast of the bars
against the page background was only **1.95:1**, even though the bars are the primary data marker on this page. Solid color measures 4.41:1.
⚠️ This alpha had been low the whole time (2.08:1 with `#BD4D03`, 1.77:1 with `#918163` — even worse) —
**it just had never been measured.** It's worth measuring all "brand colors with alpha applied" whenever the brand color changes.

**The app icon was redone as "bursting out of a ring," and switched to a multi-hue design (verified on the Android emulator, build artifact).**
The feedback was "it should represent not just allocation, but also management, and money growing over time," plus "it's all one color family, too boring."
Now it's: five segments of an allocation ring (allocation) + the ring gathers them into a base for the bars (management) + three rising bars, with the tallest one
**breaking out past the ring** (money growing). The background is warm terracotta `#CBAD76`, chroma 0.080 —
**higher than the UI's**, because the icon isn't subject to any collision constraint (it's never on the same screen as the charts), so most of the sunny feel comes from here.

Three pitfalls hit during this icon round are all documented in `generate.py`'s comments — only the general lessons are recorded here:

1. **The hand-computed bounding radius was off by 27%**, so the adaptive foreground exceeded the 66/108 safe circle — the farthest point turns out to be **the top-right corner
   of the rightmost bar**, not the top of a bar. So `validate.py` now **reads the generated output pixel by pixel** to check this, rather than trusting the constants.
2. **The gaps around the bars in the transparent foreground must actually be cut to transparent**, not filled with the background color — Android 13+ themed icons
   only take the alpha channel as the silhouette, and filling with the background color would fuse the bars and the ring back together. So the adaptive background layer
   must be **a solid color matching FIELD**, not the off-white gradient left over from the old design.
3. **PIL's `rounded_rectangle` requires height ≥ 2r + 2**; satisfying only `2r` still throws "y1 must be greater than or equal to y0."
   Only the shortest bar, **only at the mdpi (48px) size tier**, hits this — every other size is fine — so the corner radius now goes uniformly through `_radius()`, which clamps it.

**The FAB needs `primary` given explicitly, not the M3 default.** The default is `primaryContainer`, which under the Morandi palette
is a pale sand color, and against the equally warm off-white page background it's **only 1.3:1 — the plus sign is nearly invisible**
(confirmed on the emulator). `primary` gives 3.70:1. See [App.kt](shared/src/commonMain/kotlin/com/boomsset/ui/App.kt).
General rule: **whenever you change the lightness of `primaryContainer`, go through every component that defaults to that role** —
in this project it has two consumers: the net-worth hero card (explicit) and the FAB (M3 default, easy to miss).

**The bottom nav's selected state can't be colored with `primary`** (real-device feedback: "the selected effect is too faint").
`primary` is an L 0.61 olive gold, and against the nav-bar background it's only **3.41:1**, while the unselected
`onSurfaceVariant` is **5.26:1** — **the selected state ends up fainter than the unselected one.**
The old high-chroma orange `#BD4D03` relied on chroma to carry the "has color = selected" reading, and that reading collapses once the color is low-chroma.
Now the selected state goes through **three channels**: the text `onPrimaryContainer` in dark brown (12.72:1) + `SemiBold`,
plus the indicator bar above the text switched to `primary` (3.41:1, past the 3:1 "visible" threshold for UI elements).
The indicator's default color `secondaryContainer` was only **1.05:1** against the nav-bar background — effectively invisible —
which was half the reason for the "too faint" feedback.
⚠️ This tab bar has `icon = {}` — no icon — so the indicator bar is a **solid block of color**, and its presence or absence *is* the selected state;
**if an icon is added in the future, `selectedIconColor` must be changed to `onPrimary`**, or the icon will blend into the color block.

⚠️ **This is a general rule, not just about the nav bar: once you switch a high-chroma `primary` to a low-chroma one,
every place relying on color to indicate state needs its contrast re-measured** — a high-chroma color can stand out even without a lightness advantage,
thanks to chroma; a low-chroma one can't. The way to check is to **compute the contrast ratio of both the selected and unselected states and compare them**,
not just look at "does the selected state have the brand color on it."

✅ **Verified on a real Xiaomi 15 Pro device (Android 16 / SDK 36 / HyperOS 3):** net-worth page
(hero card and solid gold bars with real data), allocation page, asset page, bottom nav, splash background,
**both light and dark modes**, and the icon in the real device's launcher — it wasn't pulled into HyperOS's "unified icon" treatment
on top of the generated background; the graphic's proportion matches its neighbors (**the "shrunk again" effect seen on the Pixel emulator
did not reproduce on this device** — scaling policy differs between OEMs, don't extrapolate from one device).
SDK 36 also incidentally confirmed that status-bar icon lightness/darkness is correct under forced edge-to-edge (Lesson 9).
⚠️ **Still not verified:**
**iOS hasn't been verified at all** (this machine only has Command Line Tools, `xcodebuild` doesn't run, and
`compileKotlinIosSimulatorArm64` passing doesn't prove linking or rendering).

**⚠️ "Protection" has changed from purple to golden-yellow `#977E00` (light) / `#9B8100` (dark).** The reason is that the brand color
needs to use purple, and the brand color must be ΔE ≥ 15 from all five asset-class colors — Protection was occupying purple, so brand purple had no room to stand.
The direction to move it was **calculated, not guessed**: magenta (H340) was tried first and was wrong — magenta instead blocks the H300~330 purple range and
forces its chroma up to 0.19 (too vivid). It had to move to **the side farthest from purple**, at which point purple's minimum required chroma dropped from
0.135 to 0.060. Both palettes were re-run through the dataviz validator, passing all six checks.
⚠️ This change **rebuilds user expectations** — "purple = Protection" had already been running on real devices for a while.

⚠️ **During the search I added my own extra rule, "any two colors ΔE ≥ 10" — that is NOT this project's actual criterion.**
The official validator only checks **adjacent pairs**; the current palette's global minimum is only 8.3, and it still passes. Searching with a stricter
criterion than what's actually in effect can produce a false conclusion of "no solution exists" — **verify against the current state before adding a constraint.**

**The chart color system is fully worked out (verified on both platforms, real device, light and dark):** each of the five asset classes has its own color,
and **the order is fixed and must not be reordered** — the order itself is the colorblind-safety mechanism. Deviation uses a divergent scale: red for overweight,
blue for underweight, neutral gray for on-target. All three pages (net worth / allocation / assets) share the same asset-class colors —
"blue = Liquid Funds" holds on every page.
⚠️ **The color values weren't hand-picked**: the hues come from Youzhiyouxing (有知有行), but **they had to go through a snap-to-passing step**
(hue angle untouched, lightness and chroma nudged into compliance) — their original values are meant for small-area accents, and when used to fill five categories,
gold/pink's lightness went out of range and cyan/purple's chroma was insufficient. Searching across 2,520 combinations found 588 passing sets, and the one
closest to the original colors was chosen (total deviation only ΔE 5.8). Changing a color value requires re-running the validator;
the method and commands are documented in the comments of [ChartColorsTest](shared/src/commonTest/kotlin/com/boomsset/ui/theme/ChartColorsTest.kt).
In light mode, a few of the asset-class color chips fall below 3:1 contrast, **and this must be compensated for by always keeping a name + percentage
label next to the color chip** — don't remove those labels when redesigning the layout.

**Adding an asset is a standalone page (verified on both platforms, real device):** not a dialog — a dialog can't fit this form; once the keyboard
pops up there's only room for two or three lines. **Step one is choosing the instrument type, not the asset class**: the user doesn't know which class
Alipay ("支付宝") belongs to, so it's done in reverse — pick "支付宝" (Alipay), and the asset class and default valuation mode are carried in from the built-in
instrument-type table, with the asset class only shown as a resulting field. Liability instrument types (mortgage / car loan / credit card / consumer loan)
form their own group, and selecting one automatically turns on the liability toggle. Currency is a **dropdown**, not a row of chips (it's almost never changed,
so it shouldn't occupy the form's most prominent spot).

**The iOS interaction flow has been verified (XCUITest, 10 tests all passing):** empty state, tab switching, correct net worth and gain/loss after
adding an asset, the update dialog's prefilled value being parseable, allocation percentages, app-lock capability prompt, plus **the target-allocation entry
point being reachable with zero assets**, empty-state guidance, choosing by instrument type in the add flow, and liability instrument types presetting the
liability flag.
How to run it: `cd iosApp && xcodebuild test -scheme iosApp -destination "id=<UDID>"`.

⚠️ There are also **2 net-worth-page chart-control tests that haven't been run yet** (`testChartPeriodIsADropdown`,
`testChartModeAndStyleSwitchesStayUsable`) — the machine that wrote them only has Command Line Tools, so `xcodebuild` doesn't run.
They cover the period dropdown (including the "switching to coarser granularity can crash" direction from Lesson 14) and the four combinations of the
two switches (including Lesson 10's "a single point can't draw a trend line, and that must be stated explicitly"). Two spots in them have
**element types that are guesses** and need a real run to confirm: what type Compose's `Switch` and `DropdownMenuItem` end up as in the iOS accessibility tree
(hence the two helpers `toggle()` / `menuItem()` trying each type). **The first time this runs on a machine with Xcode, be prepared to adjust these two selectors.**

**A second round of UI polish based on real-device usage feedback (verified on both platforms, real device/emulator):**
- The net-worth chart changed to a **bar chart + line chart** combo, no longer a pure line chart — see Lesson 10 below;
  only 1 point used to make the bar stretch to fill the entire x-axis, narrowed to a normal width using a ghost series — see Lesson 13
- **The add button (add asset) moved from the net-worth page to the asset page**: the net-worth page is a read-only trend overview, and adding an asset
  is something the asset page does — putting the entry point on the wrong page makes users look for it in the wrong place. The empty-state copy was
  updated to match (no longer says "go to the net-worth page and tap the plus")
- The bottom nav's selected state now also needs the **text** to turn brand color, not just rely on a light gray indicator bar — that indicator was too
  subtle, you couldn't tell which tab was selected
- The net-worth page got a bit of warm color: the overview card switched to a `primaryContainer` light background, and gain/loss numbers got colored
  (see [GainLossColors.kt](shared/src/commonMain/kotlin/com/boomsset/ui/GainLossColors.kt), **red for gains, green for losses** in the Chinese
  stock-market convention). This set of colors, at the time, **reused already-verified M3 role colors instead of introducing a new set of hex values**,
  because the validator script hadn't been committed to the repo and there wasn't time in that round to rebuild it.
  ⚠️ **This is no longer true**: the Morandi round solved for gain/loss colors independently (as light/dark sets, criterion ≥4.5:1 against the hero card),
  and the validator was added to the repo too ([tools/appicon/validate.py](tools/appicon/validate.py) is the icon validator suite, sharing the same color math).
  Current values are in `GainLossColors.kt`
- Allocation page: net-exposure amounts got an explicit **+/−** sign (previously only negative numbers showed a sign); the long explanatory text about
  "which target is being compared" and about the built-in presets was tucked into an **(i) icon tooltip** that only shows on tap; "edit percentages /
  restore default / delete" changed from always-visible buttons to only expanding on a **long press on the current target**
  ([AllocationScreen.kt](shared/src/commonMain/kotlin/com/boomsset/ui/allocation/AllocationScreen.kt)'s `InfoTooltip`/`AllocationPicker`)
- The allocation page's donut chart **segments are now tappable** — tapping shows that class's name and amount, tapping again collapses it.
  Vico's `PieChart` has no click callback, so hit detection is hand-written angle/radius math; see
  [AllocationDonut.kt](shared/src/commonMain/kotlin/com/boomsset/ui/allocation/AllocationDonut.kt)
- The asset page removed the always-visible "tap to update valuation and record a snapshot" hint at the top; each row's previously always-visible
  "update valuation / rename & reclassify / archive" three buttons now only reveal on **swipe left**, using material3's built-in `SwipeToDismissBox`
  (it's not actually a dismiss — swiping doesn't remove the data, it just reveals the buttons behind it). **The card itself is still fully tappable to
  open the update dialog directly** — this is deliberately kept, see Lesson 11 below
- The second step of the add-asset form (the "shares held"/"total cost basis" fields, for share-based quoted instruments) had its fields covered by
  the popping-up keyboard; added `Modifier.imePadding()` — see Lesson 12 below

**The net-worth page can now hide amounts (eye icon, verified on the Android emulator API 34, both light and dark modes):** an eye icon in the
top-right corner of the overview card; tapping it swaps **this page's absolute amounts** for a fixed-length `••••••`
([AmountVisibility.kt](shared/src/commonMain/kotlin/com/boomsset/ui/AmountVisibility.kt)), **percentages and ratios still display as normal** —
growth rate, return rate, and liability ratio on their own can't reveal net worth, and they're exactly the value proposition of this page; hiding
everything would be equivalent to turning off the net-worth page. Date, number of assets covered, and the shape of the bars are also kept (shape is a
relative quantity).

Three decisions worth recording:

* **The state is persisted** (in the settings table, key `amounts_hidden`), not just a UI `remember`. This toggle's use case is "someone else is
  nearby" — during which the user is likely to switch to the allocation page and come back; if it reset on restart, the user would have to race to
  tap it again before someone else sees the app every time they open it, which defeats the whole feature.
* **The chart's y-axis labels must be hidden along with it.** Hiding just the card would leave the y-axis still saying something like "2.4M" —
  the placeholder at the top would become pointless. **A privacy toggle that only covers half of it is worse than no toggle at all** — the user
  would think it's fully hidden when it isn't.
* **The placeholder is a fixed length**, not generated based on digit count. `"•".repeat(digits)` would let a seven-digit number and a four-digit
  number be told apart at a glance, leaking exactly the "roughly how much money" information that's supposed to be hidden. There's a test locking
  this down ([AmountVisibilityTest](shared/src/commonTest/kotlin/com/boomsset/ui/AmountVisibilityTest.kt)).

⚠️ **Only the net-worth page got this.** Amounts in the asset list and the update history still display normally — the requirement was specifically
about the home page.

⚠️ **Hiding the axis = `label = null`, but the `itemPlacer` must be swapped along with it** — see
[NetWorthChart.kt](shared/src/commonMain/kotlin/com/boomsset/ui/networth/NetWorthChart.kt)'s `rememberAmountAxis`. Both placers take a
**special-case branch** when there are no labels: the default `step()` skips overlap-avoidance and just uses `10^(floor(log10(maxY))-1)` as the
step size, which at a net worth of 2.38 million gives a step of 100,000 — **23 horizontal gridlines, smearing the bars into stripes** (only visible
from an emulator screenshot; compiling and unit tests were all green). Switching to `count()` fixes it: with zero label height, it returns only the
two ends, so the gridlines disappear entirely — which is exactly what's wanted. **General rule: before setting some component to null, first find
out who else is reading its size to compute something else.**

The icon is a **hand-drawn Canvas** shape, no icon library was imported (elsewhere in the project "+"/"ⓘ"/"▾" are all glyphs, but there's no usable
glyph for an eye: 👁 is a colored emoji and can't pick up the theme color, and there's no code point at all for a "crossed-out eye"). Three things hit
while doing this are all documented in that file:
1. **The slash was drawn wrong in an earlier version, only visible once installed on a device and zoomed in** (real-device feedback: "the icon looks
   wrong"): the short line's two ends landed exactly on the eye-outline curve, and its middle merged into a blob with the pupil, so it didn't read as
   "crossed out." It needs to **go corner-to-corner** + carve a gap with `BlendMode.Clear` (paired with `CompositingStrategy.Offscreen`, otherwise
   Clear would erase the card's background color), **and the pupil must not be drawn in the hidden state** — the slash runs straight through the
   center, and drawing both would leave the pupil erased into two disconnected fragments left and right.
2. **`IconButton`'s accessibility semantics need to be declared entirely by hand** (`clearAndSetSemantics` + `role` + `onClick`). It puts `clickable`
   on an inner element, so the semantics node hung on the outside is an **ancestor** of the clickable one: attaching just `contentDescription` produces
   two nodes (the 28dp one has a name but isn't clickable, the 48dp clickable one has no name); `mergeDescendants = true` can merge them into one, but
   `clickable` is still false. Each option was checked against `uiautomator dump` before settling on this one — there was no way to tell without
   checking. (`LabeledSwitch` is unaffected: `Switch`'s `clickable` is on its own layer.)
3. Shapes drawn with Canvas **don't produce accessibility nodes**, and have no automated coverage (same as the vertical line in `AllocationBar`) —
   any change to the drawing must be checked on a real device.

**The net-worth page's "view currency" changed to a dropdown (verified on the Android emulator API 34):** it used to be 9 `FilterChip`s laid out in
a row, plus a label and a line of explanation, taking up three or four lines on a phone (feedback: "takes up too much space"). Now it's collapsed
into a single line: label + `OutlinedButton`("CNY ▾") + `DropdownMenu`, with the explanation tucked into an (i) tooltip. The default is still CNY
(`DEFAULT_BASE_CURRENCY`, unchanged).
**`ExposedDropdownMenuBox` was not used** — that component is meant for text-input fields and brings in a 56dp-tall `OutlinedTextField`, which is
the opposite of "saving space"; the add-asset page's `CurrencyDropdown` is right to use it (that's already a form). The expanded menu **covers the
button itself**, so the current currency is marked in the menu with a `trailingIcon` checkmark (not just a color change — color alone carrying the
state doesn't work for colorblind users). The allocation page's `InfoTooltip`, mentioned in
[InfoTooltip.kt](shared/src/commonMain/kotlin/com/boomsset/ui/InfoTooltip.kt), is shared by both pages and was switched to `isPersistent = true` at
the same time — see Lesson 15 below.
Tested: switching to USD converts correctly (¥100,000 → $14,883, Frankfurter live rate); switching back to CNY leaves the original value unchanged.

**Net-worth page top card redone (verified on the Android emulator API 34, both 360dp and 411dp widths checked):** feedback was "the total-asset
info takes up too much space," with a reference screenshot of a household-budgeting app's top card attached. It used to be four or five full
sentences ("Net worth growth +2.10% (including new contributions)"…), where you had to read the entire sentence each time just to know what the
number meant, and it was **missing the three things that mattered most**: what day the data was recorded, what the growth is being compared against,
and total assets / total liabilities / liability ratio.
Now it's split into three sections: net worth + recorded date → total assets / total liabilities in a grid → net-worth growth / unrealized
gain-loss as a full row.

The new derived values all live in **pure functions in the domain layer** (`NetWorthPoint.liabilityRatioBp`,
`NetWorthSeries.growthAbsolute/baselineDate/hasBaseline`, `PortfolioSeriesCalculator.lastRecordedDate`); the UI is only responsible for formatting —
the definitions and trade-offs are documented in [docs/domain.md](docs/domain.md) (why the liability-ratio denominator is total assets, why
freshness excludes archived snapshots).

**The two sections use different layouts, and that wasn't arbitrary**: short labels with short values (total assets/total liabilities) suit a
grid; a label like "net worth growth (including new contributions)," which runs to a dozen-plus Chinese characters, only leaves 120dp per cell when
placed side by side, and **the label and the value end up wrapping together** (measured at 360dp: the label wraps to two lines, and the amount wraps
right after " · " leaving a dangling separator dot, and since the two labels wrap to different numbers of lines, the values below end up at
different heights). In the full-row version, `weight(1f)` is given to **the label**: Row measures the non-weighted children at their full width
first, so **the value is always shown in full**, and only the label wraps if there isn't enough room. The liability ratio doesn't get its own cell —
it appears as a footnote under total liabilities (since it's derived from those two numbers anyway).

**Two Markdown asterisks in strings were fixed in passing**: `Text` doesn't parse `**bold**`, and the asterisks in the tooltip bubble and the
"cannot be valued" hint had been displaying literally to users the whole time (confirmed by screenshot).

**Allocation page: the target position is marked on the bar, and the deviation is converted into a money amount (verified on the Android emulator
API 34, both light and dark):** two pieces of feedback — "the progress bar only draws the current share, you can't tell where the target is," and
"being 31% overweight doesn't by itself tell you how much money to move." `LinearProgressIndicator` can't draw a second marker point (it only takes
a single `progress` argument), so it was replaced with a hand-written
[AllocationBar](shared/src/commonMain/kotlin/com/boomsset/ui/allocation/AllocationBar.kt): a rounded track + fill (current) + a vertical line
(target). **The horizontal axis is a constant 0–100% and does not adapt per row** — the whole point of this page is comparing the five rows against
each other; the target line has a surface-colored outline around it, otherwise it's invisible sitting on top of a fill of the same color (the
Equity class at 76% with a 40% target line is exactly the case where the line falls inside the fill).

The amount is `AllocationView.rebalanceAmount()`, using an **internal-rebalance, total-net-worth-unchanged** basis (sell the overweight classes, buy
the underweight ones); from this there's an assertable invariant: **the amounts across all classes sum to 0**. The other possible basis, "only
invest new money," and why it wasn't chosen, is documented in that function's KDoc.
**Don't back-derive it from `deviationBp`** — that's computed by subtracting from a `shareBp` that's already been truncated to whole basis points,
and 1 basis point times total net worth is real money (a random spot check found a discrepancy as large as ¥99,876); `target amount − net exposure`
only truncates once. The overflow guard for the basis-point math was unified into `fitsBpMath()`, shared by
`shareBp`/`rebalanceAmount`/`liabilityRatioBp`.

⚠️ **That vertical target line has no automated coverage**: shapes drawn with Canvas don't produce accessibility nodes, and the project still has
no Compose UI tests at all (`compose-ui-test` is declared in libs.versions.toml but was never actually pulled in). Changing `AllocationBar` requires
manually looking at it on a real device/emulator — the same kind of gap as the swipe-left gesture.

**Net-worth page chart: the line was removed, two toggles were added (Android emulator API 34, all four combinations checked one by one):** the
line and the bars were drawing the same data, so the line was redundant and got deleted. The chart's form is now determined by two orthogonal
toggles: **Total Assets / By Asset Class** (by class = each class's **net exposure**, same basis as the allocation page, which can be negative) ×
**Bar chart / Trend line**, giving four combinations; "Month/Quarter/Year" was also collapsed from a row of `FilterChip`s into a dropdown (same
approach as "view currency" above). Every bar chart now has a **growth-rate band** above it: relative to the previous bar, an integer percentage,
red for gains and green for losses. At 12 bars, each bar is only ~22dp wide, so the percentage keeps no decimal places, and the y-axis switches to
units of 万 (10k)/亿 (100M). The legend when viewing by class is **a colored checkbox + name** (not a plain color chip — in light mode several
asset-class colors are below 3:1, and the name compensates, per the chart-color section above).

**The growth rate did not use Vico's `dataLabel`**, even though `rememberColumnCartesianLayer` has this parameter: its formatter **only receives the
y value, not the x** — and this project's carry-forward semantics often make the net worth of adjacent periods identical (if no new snapshot was
recorded that month, the previous one carries over) — bars with identical values can't be told apart, and a mislabeled one wouldn't error, it would
just silently be wrong. Instead, a `HorizontalAxis.rememberTop` was added at the top of the chart (with `line`/`tick`/`guideline` all set to null);
the label table and the data points are written into `ExtraStore` in the **same transaction**, and the formatter reads by x-index — this is the same
general rule as Lesson 14. `CartesianValueFormatter.format` returns a `CharSequence`, and `TextMeasurer.measure` has a dedicated overload for
`AnnotatedString`, so the red/green coloring happens **inside a single label**, without stacking a second axis.

**The four combinations must be isolated from each other with `key(mode, style)`.** `CartesianChartModelProducer.collectAsState` has
`check(previousHashCode == null || hashCode == previousHashCode)` — swapping the producer under the same chart host throws directly; this is a hard
constraint, not a stylistic choice.

**Vico 3.2.3 has no native stacked area chart.** The trend line's stacking is simulated with "cumulative boundaries + opaque `AreaFill` + later
draws covering earlier ones," which **requires every segment to be non-negative**. Net exposure can be negative (one class's liabilities can exceed
its assets), so `AllocationSeries.hasNegativeExposure()` checks for this first, and if it's true, falls back to **drawing each series as its own
independent line** with a note below the chart explaining why — without this check, the stacking would silently draw wrong (a negative segment
would drag the layers above it down, reading as if that class had inexplicably shrunk). The trend line still can't draw anything with only 1 sample
point (Lesson 10); here it no longer falls back on the bar chart, it just states directly: "need two or more sample points to draw a line — see the
bar chart instead."

**The two toggles need an explicit `contentDescription`.** The text label is a **sibling node** next to the `Switch`, not merged into the switch's
own accessibility node — without this, a screen-reader user just hears "switch, on," and this page has three switches (by class / trend line / app
lock), with no way to tell which is which. It uses the same `field-*` approach as the input fields. `uiautomator dump` confirmed the
`content-desc` actually lands on the switch node (it was empty before the change). XCUITest also relies on exactly this to locate the two switches.

**Re-ran verification (Android emulator API 34 / 411dp):** all four combinations checked one by one with 12 sample points — the bar chart has no
leftover line, the growth-rate band and the month labels at the bottom **align to the same set of indices** (at 12 points, both are 1/3/5/7/9/11,
and the newest period always has a label), red for gains and green for losses, y-axis in units of 万/亿. By year (2 sample points), the first bar
shows "—" instead of making up a percentage, and the second shows +824%, with the bar centered and aligned to its axis label. **Deselecting a legend
item recalculates the growth rate accordingly**: unchecking Equity turns the same bar from +824% into +118% (growth rate is computed over **the sum
of the visible classes only**, otherwise the bar would be shorter but the percentage above it would still reflect the full total, which wouldn't
match). Month → Year (the crash direction from Lesson 14) doesn't crash. **The two paths for a single sample point (ghost-series bar width, the
trend line's "not enough points" message) weren't reached in this round on a real device** — the emulator's seed data has 12 months, not enough to
produce a single point; the two new XCUITests added above cover exactly this scenario (but haven't been run yet, see above).

**A "history" section was added to the bottom of the asset page (verified on the Android emulator API 34, both 411dp and 360dp widths):** the
requirement, as stated, included "don't keep anything older than six months," which **was not implemented, and cannot be implemented** — snapshots
are the **only** data source for the net-worth chart, and the carry-forward rule looks up "the most recent one at or before this point in time";
deleting records older than six months would mean an asset that hasn't been updated in over six months would fail to find a snapshot even for
**today**, and it would vanish entirely from net worth, allocation, and the asset list (not a precision loss — the asset disappears out of thin air,
with no error). There's also no storage benefit: one snapshot row is roughly 100 bytes, so 20 assets updated monthly for ten years is under 250 KB.
So **not a single record is deleted; pagination is done purely on the UI side**: 20 by default + "load more." Paging is by count, not by a time
window — someone who records quarterly would only have two entries in "the last six months" (expanding it would feel just as broken), while someone
who records daily would have hundreds in six months. The trade-off is documented in [docs/domain.md](docs/domain.md) and in the KDoc of
[UpdateHistory.kt](shared/src/commonMain/kotlin/com/boomsset/domain/UpdateHistory.kt).

**No new table was added**: the `snapshot` chain is already immutable and append-only, which is itself the transaction log.
`UpdateHistory.build(data, zone)` flattens it in reverse-chronological order, pairing each entry with "the one immediately before it in the chain";
the three event types — add / update / archive — are all derived from the snapshots themselves (the derivation criteria are in the domain.md table).
**QUOTED rows only show shares and cost, not market value** — market value would depend on the quote at that point in time, and since quotes haven't
been backfilled historically, computing it would either be "cannot be valued" or would misleadingly explain a three-month-old record using today's
price. Likewise, **amounts use the asset's own currency, not a converted one**. Changes in value **aren't color-coded**: this column mixes assets
and liabilities together, and a mortgage dropping from ¥1,000,000 to ¥950,000 colored as a "decrease" would read like bad news, when the before →
after values already make it clear.

This section **sits at the end of the `LazyColumn`, not inside any `if (isEmpty)` branch** — verified on a real device by archiving all 4 assets one
by one: the empty-state copy "no assets currently held" appears at the same time as the four "Archived ¥X → ¥0.00" records are still there — this is
exactly the same class of bug as items 1/5/8 below. The pagination path was actually run too (temporarily changing `HISTORY_PAGE_SIZE` to 5: 14
records → "9 more" → "4 more" → "showing all 14," then changed back to 20 and rebuilt). Records spanning a year automatically get the year appended
(`2025年10月5日` vs `9月4日`), and at 360dp, `更新 ¥140,000.00 → ¥100,000.00` (Updated ¥140,000.00 → ¥100,000.00) still fits on one line.

**Still not done:** real authentication for app lock on iOS (the simulator has no enrolled biometrics, only the capability prompt has been
verified); iOS 18+ dark/tinted icon variants (currently only one light-mode icon is provided, and the system will derive the rest automatically);
**this whole round of chart changes and the "history" section have only been run on Android** — this machine only has Command Line Tools installed,
no full Xcode, so `iosSimulatorArm64Test` and XCUITest don't run; the shared code passes `compileKotlinIosSimulatorArm64` but linking and
real-device rendering haven't been verified (the dropdown menu, `Switch`, and `FlowRow` layout on iOS in particular haven't been looked at; the
history row is plain `Text`/`Row`/`Column`, lower risk than the chart, but there's no XCUITest covering it on iOS either). **The eye icon has
likewise only been checked on Android** — it uses `BlendMode.Clear` + an offscreen layer; Android uses Skia and so does iOS, so in theory it should
be consistent, but a pixel-level thing like "carving out a gap" **isn't considered verified without seeing it on a real device** (Lessons 7, 13, and
20 are all this same shape of pitfall). There's no XCUITest covering it on iOS yet either (the selector could be `app.buttons["隐藏金额"]` ("Hide
Amount"); on Android its accessibility node has been confirmed to be a single named, clickable Button).

**Lessons learned (all twenty of these were only discovered by actually running the app — compile and unit tests were all green):**
1. The empty-state condition used `series.latest == null`, but with zero assets the series still has a run of zero-value points → the empty state
   never showed
2. Prefill used the thousands-separated `formatAmount()`, while the parser rejects commas → **assets ≥¥1000 could not be updated**
3. FX-rate refresh only ran once, in the ViewModel's `init`, at which point there were no assets yet and the set of needed currencies was empty →
   **any foreign-currency asset added afterward would never get an FX rate**. Fixed by triggering it off changes to "the set of needed currencies,"
   with an "already attempted" set to prevent infinite retries on failure (writing to fx_rate re-emits the data flow)
4. Accidentally entering 100 million shares of Moutai → `FixedPoint`'s overflow guard throws → the exception escapes all the way from the valuation
   layer up through the ViewModel's `combine` → **the app crashes**. Throwing the exception itself is correct (an amount must never silently wrap
   around), but **the exception must never reach the UI**. Now the valuation layer downgrades overflow to "cannot be valued": nothing is computed
   wrong, and nothing crashes.

5. After archiving the **last remaining** asset, the asset page's empty-state branch returned early, while the "view archived" expand button was
   only rendered inside the `LazyColumn` → **that asset became completely unreachable in the UI**, with no way to un-archive it ever again. The data
   layer was correct the whole time; this was purely a missing UI path. Now the empty state and the list go through the same rendering path.

**From this comes a general rule: any computation in `PortfolioCalculator` that can throw must be downgraded to null within that layer — the
exception must never pass through the ViewModel.** That layer is pure functions, but pure functions can still throw.

6. `AddAssetDialog`'s content had no `verticalScroll` → on iOS, when the keyboard popped up, **the market-value and cost fields got clipped off and
   the user couldn't reach them**. The Android emulator's screen is tall enough that everything fit, so this had never been exposed. **Dialog
   content should be scrollable by default** — the keyboard can eat up half the screen.

7. The icon's adaptive foreground was inset to the 66/108 safe zone per spec, and looked nearly edge-to-edge when composited locally at a 72dp
   viewport, **but the ring was noticeably smaller on the real device** — the Pixel Launcher applies **an additional shrink** to adaptive icons
   (Launcher3's icon normalization, which isn't part of the `AdaptiveIconDrawable` spec). Enlarging right up against the safe zone would get clipped
   under other OEMs' masks, so it was compensated for instead with **bolder strokes** to add back visual weight. **The icon must be installed on a
   device to be seen — local compositing can't prove what it looks like in a launcher.**

8. With zero assets, the allocation page took the `state.isEmpty` branch and only rendered a single line, "no assets yet, go add one on the
   net-worth page," while `AllocationPicker` (the **only** entry point for switching / editing / creating a target allocation) was in the `else`
   branch → **new users had no way to set a target allocation at all**. And that's exactly the thing they'd want to do *before* recording their
   first asset. **This general rule was already written down in this file, and it still happened again** — because the rule's literal wording only
   mentioned an early `return`, and this time it was a `when` branch — different shape, same consequence.

9. In light mode, **the status bar had white icons over a cream-white background**, making the time and signal icons nearly invisible (measured WCAG
   contrast **1.36:1**; 10.20:1 after the fix). Cause: **starting with Android 15 (SDK 35), targetSdk ≥ 35 forces edge-to-edge**, content is drawn
   under the status bar, and the system doesn't know whether your background is light or dark, so it defaults to white icons. The fix is
   `isAppearanceLightStatusBars = !darkTheme` (the name is easy to misread: "Light" refers to the **background** being light, so the icons are
   drawn dark) — see [SystemBars.android.kt](shared/src/androidMain/kotlin/com/boomsset/ui/theme/SystemBars.android.kt).

   **The API 34 emulator on hand didn't catch this** — before forced edge-to-edge, the system draws an opaque status bar with the right color on
   its own. The API 36 emulator should have caught it, but its `screencap` returned all-black, so I switched to API 34 for screenshots — **which
   happened to switch away the exact API level that exposes this bug.** Lesson: **when switching devices to work around a tooling problem, first
   confirm the new device hasn't also switched away the condition being tested.** Testing anything related to edge-to-edge / system bars must use a
   device with **SDK ≥ 35**.

10. After adding `trimBeforeFirstSnapshot` to the net-worth chart, a user who had just recorded their first snapshot would have only one sample
    point, and Vico's `LineCartesianLayer` can't draw a line segment (a line needs 2+ points) — **the chart area had only axes, no visible shape at
    all** (the real-device feedback, verbatim: "there's just a dashed line" — actually there wasn't even a dashed line, what was seen was an empty
    grid). Unit tests test `NetWorthSeries`'s data, and had never asserted "can Vico actually draw something at this point count," so compiling and
    unit tests being all green couldn't catch this. Changed to a bar+line combo (`ColumnCartesianLayer` + `LineCartesianLayer` stacked in the same
    `rememberCartesianChart`), so even a single point draws a bar. **This general rule can be generalized once more: a chart component's
    correctness can't be tested at the data layer alone — "is anything visible when the point count is very small (1 or 0)" needs to be
    specifically confirmed on a real device** — correct data doesn't guarantee it can actually be drawn.

11. **Verifying a custom gesture (long-press to expand, swipe-left to reveal actions) can't use the testing framework's "most convenient" API — and
    the idea of "switch to an API that mimics a real drag more closely" ultimately never fixed it on iOS either; this is left here as a genuine gap.**
    Compose's `SwipeToDismissBox` uses `anchoredDraggable` to recognize dragging, while Android's `adb shell input swipe` and iOS XCUITest's
    `XCUIElement.swipeLeft()` are both "confined to the element's bounds, fixed and extremely short duration" synthetic gestures, generating too
    few/too fast intermediate move events for either side to recognize — **a real finger swipe works fine on the device, but automated verification
    looks like nothing happened**, which is easy to misjudge as "this feature wasn't implemented correctly." On Android, switching to
    `adb shell input draganddrop` (closer to a real continuous drag) **confirmed the gesture itself works fine** — swipe-left reveals the buttons,
    tapping "Update" opens the dialog, all verified working end to end. On iOS, following the same idea, switched to the coordinate-level
    `XCUICoordinate.press(forDuration:thenDragTo:)`; the first attempt seemed to have fixed it (and this lesson was written with that conclusion),
    but after rerunning the full test suite it turned out **it still wasn't actually triggering** — the earlier "looks like it passed" was a
    conclusion written down without re-verifying. One lesson from this: **after changing an automated assertion, you must actually rerun it and
    record the real result — you can't conclude "it should be fine now" without checking.** Later, the overload with an explicit velocity was also
    tried — `press(forDuration:thenDragTo:withVelocity:thenHoldForDuration:)` (given a speed far below the default) — still no effect. None of the
    three XCUITest gesture APIs got `anchoredDraggable` on this simulator to recognize it as a drag. **The conclusion was to drop this small piece
    of automated assertion**, and change the relevant tests to go through the already-verified stable path of "tap the card directly" (see
    `testUpdateValuePrefillIsParseable`/`testUpdatingValueIsAVisibleAction`), with a code comment stating clearly that "this specific swipe-left
    interaction has no automated coverage — changes to this area need a manual swipe on a real device/simulator" — honestly acknowledging a gap in
    the tooling is more responsible than forcing an assertion that looks like it passes but doesn't actually test anything. The architectural
    argument backing "the feature itself is fine" is: `SwipeToDismissBox`/`anchoredDraggable` is pure shared Kotlin code, and iOS and Android gesture
    recognition logic are exactly the same — the only platform difference is in the layer that feeds touch events in — and that layer has already
    been verified on Android using something close to a real continuous touch. Also, **`coordinate(withNormalizedOffset:)`, when built on an
    element that hasn't appeared yet, has internal retries that hang until XCTest's default timeout** (measured: it took 600–950 seconds to fail,
    instead of failing fast) — a custom-gesture test helper function must `waitForExistence` before taking a coordinate, or a minor "element not
    present yet" issue gets dragged out into what looks like a hang, an order of magnitude more expensive to debug.

12. **`verticalScroll` does not mean "can scroll to the focused field when the keyboard pops up."** `AssetDetailForm` already had `verticalScroll`
    (Lesson 6 fixed the case of no scrolling at all), but the "shares held"/"total cost basis" fields for share-based quoted instruments were still
    covered by the keyboard (real-device feedback). The reason is that **`verticalScroll` on its own doesn't know how much height the keyboard is
    taking up** — it still computes the scrollable range as if "the whole screen is visible," so the focused field's "scroll into view" logic judges
    it as "already in view" and doesn't scroll further. Adding `Modifier.imePadding()` shrinks the content area with the keyboard height, so the
    scroll container's visible height becomes accurate, and it correctly scrolls out the part eaten by the keyboard. **These are two different
    pitfalls: `verticalScroll` solves "content doesn't fit," `imePadding` solves "knowing how tall the keyboard is." Any keyboard-involving form needs
    both — checking only for `verticalScroll` isn't enough.**

13. **A bar chart with only 1 point stretches to fill the entire x-axis, and `LineComponent`'s `thickness` has no effect on this at all.** Lesson 10
    switched the net-worth chart to a bar+line combo, solving "1 point can't draw a line"; but with exactly 1 point, that bar stretches into one
    giant solid rectangle (real-device feedback: "too wide"). **A wrong verification was tried first**: assuming `thickness` controlled bar width,
    it was reduced to 1dp with no visible change at all — this showed that Vico draws bars based on "how much available width this x position gets,"
    and with only 1 x position, the available width is the entire plot area; `thickness` doesn't participate in that computation at all.
    **Verification method**: switching the bar color to a solid color clearly distinct from the trend-line's area fill (opaque blue) confirmed at a
    glance that the "overly wide solid rectangle" really was drawn by the bar-chart layer, not the line's area fill or something else — when
    tracking down a visual issue, isolating the layer with an exaggeratedly unmistakable color is faster than repeatedly reading the source trying
    to guess which layer it is.

    The first attempt was to use `CartesianLayerRangeProvider.fixed()` to artificially widen the x-axis range's **lower bound** to the left (e.g.,
    pretend there are 6 positions when there's only 1 point), so "available width per position" matches the case with more points. The idea was in
    the right direction, but it **caused a crash directly**: while measuring axis-label width, `HorizontalAxis` also calls `valueFormatter` once for
    the "virtual" x positions that were padded in and have no corresponding real date, and the formatter for those positions can only return an
    empty string — which Vico doesn't allow (`IllegalStateException`, with a message explicitly saying "use ItemPlacer instead, don't use an empty
    string"). Changing the empty string to placeholder text avoided the crash, but `ItemPlacer`'s spacing/offset algorithm is designed around "the
    real point count," and doesn't know which positions to skip as padded-in virtual ones, so the virtual positions ended up genuinely being
    selected for display too, resulting in a few extra fake labels on the left of the chart pointing to nonexistent dates. Backing out of this path
    cost far more than expected, so it was abandoned in time in favor of a different approach.

    The approach that actually worked doesn't touch the x-axis range at all: use `ColumnCartesianLayer.MergeMode.Grouped` and add a few
    **all-zero-value "ghost series,"** so the available width at that same x position gets split into several shares — real data occupies just one
    of them, and the remaining shares are 0, drawn at zero height, invisible. This approach only affects "how width is split within a single x
    position," and doesn't touch the x-axis range or axis labels at all, so it doesn't repeat the crash above. **Ghost series are only added when
    there's exactly 1 point** — with 2 or more points, multiple real points naturally spread across the full width, and there's no "one giant block"
    effect that looks like a rendering bug, so there's no need to handle it. This could have been thought of sooner: solving "how width is split
    within one x position" should use the mechanism at "how one position's width is split" (multi-series grouping), not jump straight to the
    outer-level mechanism of "x-axis range" — **the closer the fix is to where the problem actually happens, the fewer the side effects.**

    This fix **had a follow-up bug in its first version**, also only spotted once installed on a real device: all the ghost series were appended
    **after** the real series (`series(values)` first, then 5 `series(listOf(0.0))`), and `Grouped` lays out sub-bars left to right in the order
    `series()` was called, so the real bar ended up at the **leftmost** position for that x; but the axis label ("Aug") is drawn at **the center of
    the entire position** — the result was the bar and its own month label sitting offset from each other, looking at a glance like "the bar is
    matched to the wrong date" (real-device feedback). The fix was to split the 5 ghost series into two groups, 2 before the real series and 2
    after, sandwiching the real series exactly in the **middle** (index 2 out of 5 series), so the bar's horizontal center aligns with the label's
    horizontal center. **Lesson: the order sub-bars are laid out under `MergeMode.Grouped` is entirely determined by the order `series()` is
    called — "where to put the placeholder series" isn't an insignificant detail, it directly determines whether the real bar sits left-of-center
    or centered within that position.**

14. **Switching the net-worth page from "by month" to "by quarter/year" crashed outright** (real-device feedback; the original crash was reproduced
    on the emulator: `IllegalStateException: CartesianValueFormatter.format returned a blank string`, with `HorizontalAxis.getMaxLabelWidth` at the
    top of the stack). The cause: **the chart model and the UI state are inevitably a frame apart** — `CartesianChartModelProducer` is created with
    `remember {}` and stays alive across period switches, while the model update happens inside a **suspend transaction** in a `LaunchedEffect` (with
    a transition animation too). On the frame of the switch, the composition already has the new `series` (3 points by quarter), but Vico is still
    holding the old model (7 points by month) — the original `valueFormatter` directly closed over `series.dates`, and when asked about x=3..6,
    `getOrNull` returns null and the formatter returns `""`, while **Vico calls `check(isNotBlank())` on every axis label**. Going the other
    direction (quarter → month) increases the point count and never hits null, so **it only crashes when switching to coarser granularity** —
    exactly the reported symptom, and that directionality itself was a clue to locating it. `ItemPlacer`'s spacing/offset is the other half of the
    same pit: `getFirstLabelValue()` uses `minX + offset * xStep` to query the formatter, and **this x is not range-clipped**, so with the old model
    having fewer points, and the newly computed offset being too large, it likewise queries an out-of-range x. The fix was to put the label table
    into `ExtraStore`, landing it in the **same transaction** as the data points; the formatter reads from `context.model.extraStore`, and the
    spacing/offset are also computed from the `model.extraStore` that Vico passes in (the parameter to those two lambdas). **General rule: anything
    a formatter / ItemPlacer needs must travel with the model, never captured from the composition** — "UI state" and "chart model" are two
    independent timelines, and any implicit dependency crossing between them will blow up on the frame of the switch. Unit tests can't test what
    Vico actually draws, but they can lock down "whatever is fed in is always valid": see `NetWorthChartAxisTest` (labels are never blank, spacing >
    0, offset >= 0, the last point is always labeled).

15. **M3's tooltip bubble disappears on its own after 1.5 seconds by default — you need to know this before tucking explanatory text into an (i)
    icon.** After the net-worth page's currency explanation was tucked into `InfoTooltip`, installing it on the emulator and **only discovering via
    burst screenshots** that the bubble only lived for an instant: `rememberTooltipState()` defaults to `isPersistent = false`, auto-dismissing on a
    timer (`TooltipDuration` 1500ms). What's tucked in there is three or four lines of Chinese text that take several seconds to read — effectively
    hiding the text somewhere there's no time to read it. Changed to `rememberTooltipState(isPersistent = true)` (dismissing only on tapping
    elsewhere). The tooltips on the allocation page had the same problem the whole time and were fixed together once the component was shared.
    ⚠️ **The verification method itself was a pitfall too**: doing `adb shell input tap` and then going back to the host to `sleep` and then
    `exec-out screencap` — a single round trip alone takes 1.5 seconds, so what got captured was always the frame after the bubble disappeared —
    which led to **first misjudging this as "the tooltip doesn't show up at all."** The correct approach is to put the tap and the burst of
    screenshots into **the same on-device command**: `adb shell 'input tap X Y; for i in 1 2 3 4; do screencap -p /sdcard/tt_$i.png; done'`, and the
    first frame (around 0.3s) already caught it. **General rule: verifying "briefly appearing" UI can't use a host-side tap→sleep→screencap — the
    round-trip latency is longer than the phenomenon being tested; either burst-capture on the device, or make it stop auto-dismissing first.** Also
    hit an environment issue along the way: on an API 35 `google_apis_playstore` emulator, `install` reported Success, and `dumpsys package`'s
    resolver table clearly had MainActivity, but `am start` kept reporting `Activity class does not exist` (reinstalling and restarting the emulator
    didn't help); switching to a `google_apis` (no Play Store) AVD fixed it. Per Lesson 9: **before switching devices to work around a tooling
    problem, confirm the new device hasn't also switched away the condition being tested** — this time the thing under test was layout, unrelated to
    SDK level, so switching to API 34 was fine; but if what's being tested is system bars/edge-to-edge, it must stay on SDK ≥ 35.

16. **Adding a "change amount" display to the top card turned a pre-existing data defect into an outright false statement.** The net-worth page used
    to only show the growth **percentage**; when the starting net worth was 0, it returned null and nothing showed on screen at all, so the defect
    of "historical points missing an FX rate get computed as 0" stayed hidden. After adding the amount, switching the view currency to USD showed
    **"Net worth growth +$13,097.04 · vs. August 2026"** — that August date had no historical FX rate, so the asset at that point couldn't be valued
    at all, and net worth was computed as 0, making it look like "went from nothing to a full fortune." The number itself wasn't computed wrong (0 →
    13,097 really is +13,097), **the mistake was using a known-incomplete number as the baseline.** The fix is at the domain layer: the change
    amount now requires **matching valuation coverage** at both ends (`unpricedAssetIds` must be equal), and the growth rate requires **neither** end
    to have any unpriced assets (the percentage's denominator is net worth itself, and an understated denominator inflates the growth rate); when it
    can't be computed, the UI states clearly whether it's "only recorded once" or "the two ends aren't comparable" — the two cases call for different
    user actions. **General rule: before adding a new derived display value, first ask under what conditions its inputs are "known to be
    incomplete"** — the old value happened to return null under exactly the same conditions, so the defect stayed invisible; expressing it a
    different way made it visible.
    ⚠️ This can only be discovered by actually running the app, and **specifically requires actually switching currency**: everything looks fine
    under the CNY view, and the fixtures in unit tests are all valuable at both ends too. When changing overview-style UI, treat "switch the base
    currency" as a mandatory test case.
    ⚠️ **What got fixed here was "don't use an incomplete number as a baseline," not the missing FX rate itself** — the root cause is covered in
    Lesson 18, and has been fixed separately. After that fix, these two coverage checks no longer trigger under normal conditions, **but they should
    stay**: they cover every case of "a value couldn't be computed at some point in time" (a failed price fetch, an overflow downgrade, or quotes
    not having been backfilled historically yet).

17. **Layouts need to be checked on the narrowest screen, not just whatever device is at hand.** The emulator is 411dp wide, and the new card fit on
    it just fine (only 4dp of margin left to the right of the amount); using `adb shell wm density 640` to turn the same device into 360dp made the
    label wrap to two lines, the amount wrap right after " · " leaving a dangling separator dot, and the two columns' values end up at different
    heights — looking, at a glance, like something rendered broken. `wm density 640` / `wm density reset` switches this in one command, much faster
    than swapping AVDs. **Chinese-language UI especially needs this check**: CJK character width is roughly equal to the font size (labelSmall 11sp
    ≈ 11dp per character), so a label of a dozen-plus Chinese characters will necessarily wrap inside a 120dp cell, and estimating from experience
    with Latin text would underestimate this by two or three times.

18. **"Supports historical dates" only means the API can be queried for them, not that we've actually stored the history.** (The root cause of
    Lesson 16's false statement.) After switching the net-worth page to USD, the August bar was **$0**: in the old card this showed up as the
    growth-rate line vanishing entirely (starting net worth 0 → can't divide into a percentage → returns null); with the new card, the same defect
    became "Net worth growth +$13,097.04 · vs. August 2026." The exact same data in CNY reads ¥100,000 → -12.00%, completely normal — **only a
    non-base currency triggers it, which makes it especially easy to miss.** Root cause: `RateRefresher` only ever `fetch(..., on = today)`s **a
    single day, today**, while valuation looks up "the most recent FX rate at or before this point in time" — the sample point on August 31 has no
    FX rate before it at all (the database only had the one from September 3), so the asset is judged "cannot be valued," and net worth at that
    point is computed as 0. **The shape of this bug is especially worth remembering: it doesn't error, doesn't crash, doesn't show "cannot be
    valued" — instead it makes the curve start from 0, reading as if "the user was penniless in August and made their entire fortune in one month"**
    — exactly the "silently computing something wrong" this document keeps emphasizing. And the rule in `docs/domain.md`, "convert historical net
    worth using the rate at that time," **had already been written down**, and the query layer's code did in fact follow it — what was missing was
    that **the refresh layer never actually fetched those historical rates back.** General rule: **a rule of "use the X from back then" constrains
    both the query layer and the fetch layer. Getting the query layer right doesn't mean the data is actually there.**

    The fix was to change `FxRateSource.fetch(on:)` entirely into `fetchRange(start, end)` (a single day is just `start == end`, no need to keep two
    separate interfaces), with the refresh layer backfilling based on "the span during which that currency was held." **The API's behavior was
    worked out with `curl` before touching any code**, and three tested-in-practice behaviors are documented in the KDoc and tests: a range
    response's `rates` is **two levels deep** (date → currency → rate, unlike the single-level shape for a single day); `start == end` is valid;
    **when the entire span falls on a weekend, the server shifts the range back to the previous business day** and returns that instead of an empty
    result — so "continue backfilling from here" needs to start from the **last existing day itself**, not the day after it, or it would come up
    empty on a weekend. A ten-year range is roughly 2,561 business days / 74KB, entirely acceptable to fetch in one shot — no pagination needed.
    Writing to the database uses a single `db.transaction` for a batch upsert: doing one `upsertFxRate` per day would make the portfolio flow emit
    hundreds of times, recalculating the entire curve every time. Convergence still relies on an "already attempted" set, but **the key must
    include the date range** — otherwise, after a backfill goes further back (because a snapshot further back was added, lengthening the range),
    that refresh would be skipped as "already tried." Verification was done by **querying SQLite once before and once after the fix**: `fx_rate`
    went from 1 row to 20 rows, covering the entire holding period; and CNY reported -12.00% while USD reported -11.99%, **with that 0.01%
    difference being exactly the FX drift between the two endpoints** — if both ends used today's rate, the two percentages would be identical.
    **This kind of check — where two bases should show a small, expected difference — proves that the historical rate was actually used far better
    than just checking that the number changed at all.**

19. **`uiautomator dump` doesn't include popup windows** — floating layers like tooltip bubbles and `DropdownMenu` **simply don't show up** in the
    UI tree. After tapping the (i) icon, the bubble text couldn't be found in the dump, which was momentarily judged as "the bubble never popped
    up," and per Lesson 15 there was even a suspicion it had been eaten by the auto-dismiss again; but a `screencap` immediately showed it — text,
    line wrapping, and position were all fine. **Use screenshots, not dumps, to verify floating layers.** A more general pitfall came up alongside
    this: `adb install -r`, in the tens of seconds right after an emulator **restores from a snapshot**, reports Success but doesn't actually take
    effect (the restore overwrites the filesystem state back). After installing, you must pull the result with `pm path` and check for a string
    that only exists in the new version — otherwise you'll spend rounds debugging code you just wrote against a stale APK. This actually cost three
    rounds of confusion.

20. **The ghost series from Lesson 13 is specific to `MergeMode.Grouped` and completely stops working under `Stacked`; and "how wide the bar is" is
    something you can only see once it's on a device.** The stacked bar for viewing by asset class runs into the same "only 1 sample point" case
    (viewing by year, with an account only a few months old), but under `Stacked`, every series stacks into the same single bar, and adding any
    number of zero-value ghost series doesn't change that bar's width at all — that trick solves "how width is split within one x position," and
    `Stacked` doesn't split at all. What worked instead was `Zoom.min(Zoom.Content, Zoom.x(n))` (`Zoom.x(n)` = guarantee n x-units are visible), **and
    `rememberVicoZoomState`'s `minZoom` had to be given the same value** — it defaults to `Zoom.Content`, and changing only `initialZoom` would get
    pulled straight back to "content fills the viewport." The first version picked `n = 6.0` — compiled, unit-tested, and reasoned correctly, but
    looked broken once installed on the emulator: the bar had thinned into a line, and it was **stuck against the left edge** with a large empty
    space on the right (`Zoom.x` only determines the zoom ratio, not where the content sits in the viewport; `Scroll.Absolute` was checked too, but
    when content is narrower than the viewport there's simply no scroll range at all, so centering wasn't an option that way). The relationship
    worked out on a real device was **bar width ≈ viewport width ÷ (2n)**; taking `n = 2.0` gives about 1/4 of the viewport width, reading like "the
    first of two bars," which looked right. **This number can only be tuned on a real device: unit tests can lock down "whatever is fed in is
    valid," but not "does it look right"** — same category as Lessons 10 and 13: half of a chart's correctness lives in the pixels, not the data.

**Another general rule (items 1, 5, and 8 are all this same rule): an empty state must never take a rendering path that omits an entry point.**
**Don't just watch for an early `return`** — a `when`/`if` branch, an early-exiting `LazyColumn` item, any pattern where "the empty state and the
with-data state take different paths" can fall into this trap. An actionable check: **list out every entry point on the page (archived items,
settings, help, target allocation, app lock…) and confirm each one is still there in the empty state.** Ideally the entry points should sit outside
any branching at all, with only the "main content" branching.

And **this class of bug can only be caught by UI tests**: the data layer was correct the whole time (the preset comes from `observeAllocations()`,
unrelated to holdings), and state-layer tests can only prove the data exists. So every fix like this needs a matching XCUITest, and **you must first
revert the fix and confirm the test actually fails.**

The lesson from item 2 is: formatting and parsing each had their own tests, **but nothing tested the seam between them**. Now there's an
`InputRoundTripTest` that locks down "a prefilled string must be readable back to its original value by its own parser." **Always use
`formatForInput()` to prefill a numeric input field — never `formatAmount()`.**

**All domain-calculation rules live in
[PortfolioCalculator](shared/src/commonMain/kotlin/com/boomsset/domain/PortfolioCalculator.kt)**, pure functions with no IO — read docs/domain.md
before changing it.

## What This Is

Boomsset (旺资) is a **multi-asset net-worth tracking + asset-allocation monitoring** app, for both Android and iOS.

The fundamental difference from a traditional budgeting app: **it records snapshots, not transactions.** The user doesn't log income and expenses
line by line — instead, they periodically update the current market value of each asset.

Two core views:

1. **Net worth trend** — "how much am I worth right now, and is it up or down from last quarter" (viewable by month/quarter/year)
2. **Asset allocation** — "how far is my allocation from my target" (five asset classes' share vs. target allocation, showing deviation)

Use this test for any feature decision: **does it serve the "overall asset picture" or "transaction-level detail"?** The latter is out of scope.

## Tech Stack

Versions are always governed by `gradle/libs.versions.toml` (that's the single source of truth; this document doesn't repeat version numbers).
Rationale for each choice, options that were rejected, and upgrade risk are in **[docs/stack.md](docs/stack.md)** — read it before changing any
dependency.

| Layer | Choice |
|---|---|
| Language / UI | Kotlin Multiplatform + Compose Multiplatform (UI is shared too, not just logic) |
| Architecture | MVVM + unidirectional data flow; `ViewModel` uses `org.jetbrains.androidx.lifecycle` |
| Navigation | `org.jetbrains.androidx.navigation:navigation-compose` |
| DI | Koin |
| Local storage | SQLDelight (local-first, no backend, no accounts) |
| Preferences | DataStore Preferences |
| Networking | Ktor (used only to fetch FX rates/quotes; never syncs user data) |
| Charts | Vico (the coordinate is `:compose-m3`, **not** `:multiplatform` — see stack.md, extremely easy to get wrong here) |
| Testing | kotlin-test + Kotest assertions + Turbine + Compose ui-test; mocks default to hand-written fakes |
| Color scheme | Brand **deep rosewood purple** `#5D3270` (OKLCH H=315°, L 0.40, chroma 0.110). ⚠️ **Neutral surfaces do not share the brand's hue** — surfaces are still warm greige (H 70); this version has **two independent hue inputs** |
| Chart colors | Asset class = categorical color (fixed order), deviation = divergent color; see [ChartColors.kt](shared/src/commonMain/kotlin/com/boomsset/ui/theme/ChartColors.kt). **The color values have been validated — re-run the validator if you change them** |

## Project Structure

```
shared/          KMP library, where most of the code lives
  src/commonMain/   domain models, data layer, ViewModel, Compose UI — write here by default
  src/androidMain/  Android-only implementations (SQLDelight driver, Keystore…)
  src/iosMain/      iOS-only implementations (native driver, Keychain…)
  src/commonTest/   shared tests
androidApp/      Android app entry point (com.android.application)
iosApp/          Xcode project
tools/appicon/   app icon generator (the PNGs are build artifacts — change the design here)
docs/            detailed documentation, consult as needed
```

**Why `androidApp` is a separate module:** AGP 9 no longer allows applying the application plugin inside a KMP module. `shared` uses
`com.android.kotlin.multiplatform.library`, **not** `com.android.library`. This isn't a style choice, it's a hard requirement. Details in
docs/stack.md.

**Write in commonMain by default.** Only drop down to androidMain/iosMain when you genuinely need to call a platform API, exposed via
`expect`/`actual`.

## Build & Verify

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64   # iOS compile — run this first after changing shared code (no Xcode needed)
./gradlew :shared:testAndroidHostTest              # shared-code unit tests (run on the JVM, 247 of them)
./gradlew :shared:iosSimulatorArm64Test            # iOS simulator tests (158, requires Xcode)
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64  # iOS link (requires Xcode)
./gradlew :androidApp:assembleDebug                # Android build
./gradlew :shared:allTests                         # both platforms together
```

After changing shared code, **at least run `compileKotlinIosSimulatorArm64`.** Running only the Android build would miss failures specific to
Kotlin/Native (reflection, dependencies missing an iOS variant).

**Which commands need a full Xcode install — tested conclusions:**

| Command | Needs Xcode? | What it catches |
|---|---|---|
| `compileKotlinIosSimulatorArm64` | **No** | Kotlin/Native compile errors. Kotlin/Native ships its own platform libraries, so compiling to a klib doesn't touch the iOS SDK |
| `linkDebugFrameworkIosSimulatorArm64` | **Yes** | Link-time errors. Without Xcode it fails at `xcrun xcodebuild -version` |
| Running the simulator | **Yes** | Runtime issues |

So in a Command-Line-Tools-only environment, the first verification step still runs, but **passing it doesn't mean iOS has no problems** — link
errors can only be discovered with Xcode.

**The JVM and iOS test counts differ (247 vs. 158), and that's expected:**
- Database tests (`DatabaseSchemaTest` / `AllocationEditingTest` / `AssetEditingTest`) run under `androidHostTest`, using the JVM's JDBC driver
- `iosTest/NativeDatabaseTest` separately verifies iOS's `NativeSqliteDriver` (**a different SQLite build**, and whether the CHECK constraint holds
  is a runtime behavior)

Note that the `androidHostTest` target is **explicitly enabled** in `shared/build.gradle.kts` via `withHostTestBuilder {}` — the new KMP Android
plugin doesn't create a test target by default, and without enabling it, commonTest has nowhere to run, with no warning at all.

**After adding a cross-platform library, don't stop at `compileKotlinIosSimulatorArm64` passing.** The linker discards symbols that are never
referenced, so a dependency that's "declared but never imported by any test" can't be proven to work on iOS even by compiling. Turbine was a
dependency like this for a long time — now `PortfolioFlowTest` actually uses it.

See **[iosApp/README.md](iosApp/README.md)** for the state of the iOS project (the `.xcodeproj` hasn't been generated yet; that file explains how
to regenerate it).

## Hard Constraints (violating these wastes a lot of time)

1. **iOS is arm64 only.** `iosX64` has been removed — an Intel Mac can't even run the simulator, so the team must use Apple Silicon. iOS minimum is
   15.0.
2. **Kotlin/Native has no reflection.** You can't call bare `viewModel()`; every one needs an initializer: `viewModel { PortfolioViewModel(...) }`.
   Likewise, navigation-route serialization on iOS must be hand-written with `SerializersModule` — it can't rely on reflection.
3. **iOS has no built-in `ViewModelStoreOwner`.** Lifecycle has to be wired to SwiftUI by hand.
4. **Amounts are never `Double`.** Use `Long` to store the smallest unit (cents) or a fixed-point decimal. Floating-point error gets amplified when
   accumulating net worth.
5. **Confirm a new dependency has an iOS artifact before adding it.** Many popular Android libraries don't — **including some whose README
   explicitly claims KMP support** (one library's iOS variant, in practice, stopped shipping three years ago while the README still claims
   support). Check maven-metadata.xml for an `-iosarm64` classifier — don't trust the README.
6. **`MainActivity` must extend `FragmentActivity`, not `ComponentActivity`.** The CMP template defaults to `ComponentActivity`, but
   `BiometricPrompt`'s constructor requires `FragmentActivity`. `FragmentActivity` itself extends `ComponentActivity`, so `setContent {}` still
   works fine — a one-line change, but discovering it only once building app lock means redoing work.
7. **iOS's `Info.plist` must have `NSFaceIDUsageDescription`**, or the app **crashes outright** the first time Face ID is invoked (Touch ID doesn't
   need this, Face ID does).
8. **iOS's `Info.plist` must also have `CADisableMinimumFrameDurationOnPhone`**, or **the app crashes on launch** — CMP's `PlistSanityCheck` throws
   an exception proactively. This crash **produces no crash report and shows nothing in the system log** (the exception happens on a dispatch
   queue) — only `xcrun simctl launch --console` shows it. **Start any iOS launch investigation with `--console`.**
9. **`BiometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` can't be checked only as a combined value.** With no biometrics
   enrolled, it returns `NONE_ENROLLED`, **even when the device has a screen-lock passcode set and authentication would actually succeed** —
   resulting in a device that could actually use app lock being told it can't. Both must be checked separately and then combined as "either one
   available."
10. **The Xcode target must explicitly link `-lsqlite3`**, or linking fails with `_sqlite3_step` undefined.
   ⚠️ **All iOS unit tests passing doesn't prove the app can link** — Kotlin/Native inherits cinterop's linker options when linking the test
   executable, but those options don't carry over once a static framework is handed off to Xcode.

## Domain Model

The full definition is in **[docs/domain.md](docs/domain.md)** (product decisions are settled; the table structure can be implemented as
documented). Key points:

- `Asset` + `Snapshot` + `Quote` + `FxRate` → aggregate into a `NetWorth` time series (derived, not a table)
- **`Quote` (market price) and `Snapshot` (user holdings) must be kept separate.** Refreshing quotes only writes to Quote. Mixing them would blow
  up the snapshot table, and adding to a position would retroactively rewrite historical net worth — see domain.md for why.
- Valuation splits into `QUOTED` (market value is read-only, = shares × unit price; **shares and cost** can be edited) and `MANUAL` (both market
  value and cost can be edited, never refreshed). **Cost is an independent field, mode-agnostic — both modes let you fill it in, and both show a
  return rate** — don't mistake "market value is read-only" for "cost is read-only" too. What's stored is **total cost**, with average cost as a
  derived display value (storing average cost would silently compute wrong when adding to a position — see domain.md). **The mode is recorded on
  `Snapshot`, and valuation always looks at `Snapshot.mode`, never `Asset`** — the one on `Asset` is only the default for a new snapshot. Getting
  this backwards wouldn't blow up until an asset gets delisted and converted, and it fails silently.
- Snapshots are **immutable, append-only** — changing history means adding a new record, not editing in place; **each record is a complete state,
  not a delta.**
- The base currency defaults to CNY and is switchable, passed in as a query parameter — **it is never stored on Asset/Snapshot.**
- Historical net worth is converted using **the rate at that point in time**, not today's — this rule **simultaneously requires** the refresh
  layer to backfill historical FX rates by range; storing only today's rate would make every historical point unable to be valued (see Lesson 15).
- Classification is **two-tiered**: five asset classes (the four SAA classes + Protection, serving allocation percentages) + instrument type
  (freely customizable, serving record-keeping).
- The allocation percentage's numerator is **net exposure** (that class's assets − liabilities attributed to that class), with the denominator
  being total net worth. **Every liability must have an `assetClass`** — missing one means the percentages don't add up, silently, with no error.
- **Net-worth growth rate ≠ investment return rate** — the former includes new contributions. Both must be shown, with labels that make the
  distinction clear.

## Boundaries

- **Not a transaction ledger.** See the litmus test at the top.
  **Exception: cost basis and unrealized gain/loss are in scope** (an explicit product decision). It's recorded in `Snapshot.costBasisMinor`, with
  the user directly entering total invested cost — **it does not derive an average price from individual buy transactions**, which would cross
  the line. Don't delete this as if it were out-of-scope code — see docs/domain.md for details.
- **User asset data is never uploaded.** The network layer only ever fetches, never sends user data — it only pulls public market data, never
  sends holdings. Any change that would send asset data to a server needs to be discussed first.
- **Try not to hand-edit the Xcode project files (.pbxproj) in `iosApp/`** — conflicts there are very hard to resolve.

## Working Agreement for Agents

- Changing a dependency version → read docs/stack.md first; it records which versions are deliberately not the latest.
- Not sure whether a library works on iOS → look up the actual variant published to Maven, don't answer from memory.
- After finishing a change, run the corresponding verification command, and **report the result honestly** — if a test doesn't pass, say so.

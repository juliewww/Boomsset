# iosApp

## The project file is generated, not committed

Only **`project.yml`** (the XcodeGen definition) lives in the repo. Both **`.xcodeproj`
and `Info.plist`** are generated artifacts and are already in .gitignore — XcodeGen
rewrites `Info.plist` in place based on the `properties` in project.yml, so that YAML
file is the single source of truth.

Why: AGENTS.md says `.pbxproj` should be "avoided as a manual edit as much as possible —
conflicts are extremely hard to resolve." Turning it into a generated artifact means
what actually needs review and merging is a twenty-some-line YAML file, which removes
the root cause of that constraint.

## How to run it

```bash
brew install xcodegen        # only needed once
cd iosApp && xcodegen generate
open iosApp.xcodeproj        # or use the command line below
```

Build and run on the simulator from the command line:

```bash
UDID=$(xcrun simctl list devices available | grep -m1 'iPhone 16 (' | grep -oE '[0-9A-F-]{36}')
cd iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp -destination "id=$UDID" -configuration Debug build
APP=$(find ~/Library/Developer/Xcode/DerivedData/iosApp-*/Build/Products/Debug-iphonesimulator -maxdepth 1 -name 'iosApp.app' | head -1)
xcrun simctl install "$UDID" "$APP"
xcrun simctl launch --console "$UDID" com.boomsset     # --console matters, see below
```

The Run Script in `project.yml` automatically invokes
`:shared:embedAndSignAppleFrameworkForXcode`, so there's no need to build the framework
manually beforehand.

## Three real pitfalls we hit (all noted in project.yml's comments)

1. **`-lsqlite3` must be given explicitly**, otherwise linking fails with
   `_sqlite3_step` undefined. SQLDelight's native driver goes through SQLiter, and its
   cinterop doesn't link that in for consumers automatically.
   **Note: passing iOS unit tests does not prove the app can link** — when Kotlin/Native
   links a test executable it inherits cinterop's linker opts, but once the static
   framework is handed off to Xcode those opts don't carry over.

2. **`Info.plist` must contain `CADisableMinimumFrameDurationOnPhone`**, otherwise the
   app crashes immediately on launch: Compose Multiplatform's `PlistSanityCheck` throws
   an `IllegalStateException`.

3. **`NSFaceIDUsageDescription`** must be present, otherwise the first Face ID call
   crashes outright (app lock isn't implemented yet, but this is set up in advance).

## Debugging iOS launch crashes: always use `--console`

The crash in item 2 above **produces no crash report and doesn't show up in the system
log** (the exception happens on a dispatch queue) — `simctl launch` just returns a PID
and the app quietly disappears. The only way to see the Kotlin exception and stack
trace is:

```bash
xcrun simctl launch --console "$UDID" com.boomsset
```

**Start debugging iOS launch issues with this command, not with crash reports.**

## Verified / not yet verified

**Verified (Xcode 26.6 / iOS Simulator 26.5)**: the app builds, installs, and launches;
the shared Compose UI renders correctly (three tabs, empty states, FAB); Koin starts up
successfully; SQLDelight's NativeSqliteDriver creates and seeds the database in the
real app (8 tables, 25 built-in asset types, 3 target allocation presets, with
"Balanced" active).

**Not yet verified**: interactive flows on iOS (adding/updating/archiving assets) —
there's no UI automation on iOS yet; those flows have only been manually tested on
Android. Running on a physical device (not the simulator) also hasn't been tried, since
that requires signing configuration.

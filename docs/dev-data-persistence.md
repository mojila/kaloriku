# Development data persistence

Uninstalling an APK deletes `/data/data/id.kaloriku`, and that is where the app's
entire state lives:

| State | Path |
| --- | --- |
| Food log (Room v4) | `databases/kaloriku.db` (+ `-wal`, `-shm`) |
| Settings (DataStore) | `files/datastore/kaloriku_settings.preferences_pb` |

No manifest flag can survive a real uninstall. The rule is therefore simple:
**install over the APK, do not uninstall it.**

```powershell
./gradlew --no-daemon :phone:installDebug   # adb install -r -> /data/data is kept
```

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` from `install -r` almost always means the
signing certificate changed. That is the failure mode the committed dev key below
exists to remove -- when it happens, the tempting fix is to uninstall, which is
exactly what destroys the data.

## Committed dev signing key

`keystore/kaloriku-dev.jks` (alias/passwords in `gradle.properties` under
`kaloriku.dev.*`) signs the `debug` build type for both `:phone` and `:wear` via
`gradle/dev-signing.gradle.kts`.

Two things this guarantees:

- **Stable certificate across machines and clean checkouts**, so `install -r`
  replaces the APK in place instead of forcing an uninstall.
- **The same certificate for phone and wear**, which the Wear OS DataLayer
  requires to route messages between the two apps.

This is a throwaway dev key, committed on purpose. Never use it to sign a Play
release -- wire a real key through CI secrets for that.

## Clean-install tests

When you actually want a clean state:

```powershell
adb shell pm clear id.kaloriku        # wipes data, keeps the APK installed
```

This is preferred over uninstall/reinstall: it is faster and keeps the signing
situation unchanged.

## Saving and restoring data

`tools/dev-data.ps1` copies the state out of the app sandbox (via `run-as`, so a
debuggable build is required) and back in.

```powershell
# before a clean install
./tools/dev-data.ps1 save

# after reinstalling
./tools/dev-data.ps1 restore
```

Pass `-Serial <device>` when a phone and a watch are both attached. The backup
lands in `.data-backup/` (gitignored). The script preserves the `-wal`/`-shm`
files -- omitting them silently drops anything still in the write-ahead log.

If a true uninstall is unavoidable, keep the data dir with `-k` and reinstall
over it:

```powershell
adb shell pm uninstall -k id.kaloriku
./gradlew --no-daemon :phone:installDebug
```

## Backup rules and the uninstall dialog

Both manifests declare:

- `android:hasFragileUserData="true"` -- adds a **"Keep app data"** checkbox to
  the system uninstall dialog (API 29+). It does not help `adb uninstall`, but it
  protects real users.
- `android:dataExtractionRules` (API 31+) and `android:fullBackupContent`
  (API 30-), both including only the `database` domain and `datastore/`. Backup
  is opt-in per domain rather than "everything by default", so what gets restored
  is deliberate. This also makes `adb shell bmgr backupnow id.kaloriku` usable.

## Schema changes

`KaloriKuDatabase` falls back to destructive migration **only when
`BuildConfig.DEBUG` is true**. In a release build a missing migration now throws
instead of silently dropping `food_entries`. When you bump the schema version,
always add a real `Migration` -- otherwise a debug build will quietly wipe the
log and it will look exactly like the uninstall problem this guide is about.

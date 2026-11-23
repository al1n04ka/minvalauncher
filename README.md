# minvalauncher

A minimal Mako-style Android launcher: one screen, nothing extra.

- app list with search (`/` button at the top left)
- groups: create, rename, collapse
- `BASE` / `CUSTOM` themes and wallpapers with opacity control
- config import/export, including Mako's `prefs_backup.json`
- gesture navigation support

Settings: `≡` button or long press on the time/date.
App/group menu: long press on a row.

Build: `./gradlew assembleRelease` (Android SDK 37, JDK 17+).
Signing needs `release.keystore.file`, `release.store.password`, `release.key.alias`, and `release.key.password` in `local.properties`.

Ready-to-install APK: https://github.com/al1n04ka/minvalauncher/releases

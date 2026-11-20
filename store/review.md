# pLauncher publication review

Reviewed 2026-11-16. Source commit: unavailable (no readable Git metadata).

- Target SDK 35: below the current phone-app submission minimum of API 36, effective August 31, 2026 (extension ended November 1, 2026). See the official target API reference. Do not change a deliberate runtime target without redesign.
- Accessibility API declaration, accurate disclosure, consent flow and demonstration video where required. Do not claim accessibility-tool status without meeting its definition.
- Owner must confirm developer identity/contact, production support and privacy URLs, countries, pricing, ads, target age and content rating.
- Audit runtime traffic, dependencies and final merged release manifest before signing off Data safety. Source manifest alone is not a complete data audit.
- Create a signed release AAB, test the release on a device and complete the Play Console testing/review requirements applicable to this account. No release binary was built by this documentation task.

## Source evidence

Manifest: `app/src/main/AndroidManifest.xml`. Build: `app/build.gradle.kts`.

- network: `app/src/main/java/x/vladgba/launcher/pui/PAbout.kt`
- storage: `app/src/main/java/x/vladgba/launcher/LauncherActivity.kt`, `app/src/main/java/x/vladgba/launcher/model/IconCache.kt`
- sharing: `app/src/main/java/x/vladgba/launcher/LauncherActivity.kt`
- learning_clipboard: No matches in scanned module sources; absence is not proof.
- speech_translation: No matches in scanned module sources; absence is not proof.

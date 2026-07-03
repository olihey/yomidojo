# Dev setup — Windows + IntelliJ IDEA Community → Android device

Android-only for now (no Mac — PLAN.md §12). This is the IDE-light path: IntelliJ IDEA
Community as the editor, the Android SDK installed separately via command-line tools.

## 1. JDK 17
AGP 8.7 / Kotlin 2.1 require **JDK 17** (not 21, not 11).
```
winget install EclipseAdoptium.Temurin.17.JDK
```
Set `JAVA_HOME` to the install dir; add `%JAVA_HOME%\bin` to `PATH`.

## 2. IntelliJ IDEA Community + plugins
```
winget install JetBrains.IntelliJIDEA.Community
```
Settings → Plugins → Marketplace → install **Android** and **Kotlin Multiplatform** → restart.

## 3. Android SDK (Community does not bundle it)
Download "Command line tools only" from https://developer.android.com/studio and unzip so the
path is exactly:
```
C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat
```
(the `latest` nesting is mandatory). Then:
```
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
sdkmanager --licenses
```
Environment:
- `ANDROID_HOME = C:\Android\Sdk`  (AGP reads this; no local.properties needed)
- add `%ANDROID_HOME%\platform-tools` to `PATH`  (for `adb`)

## 4. Open the project
IntelliJ → **Open** → select the repo root. It regenerates the missing
`gradle/wrapper/gradle-wrapper.jar` and runs the first Gradle sync (no system Gradle needed).

- If it complains about the JDK: Settings → Build, Execution, Deployment → Build Tools →
  Gradle → **Gradle JDK = 17**.
- The first sync reality-checks the pinned version matrix (PLAN.md §13). Most likely snag is
  `cmp-navigation` (alpha) vs the Compose Multiplatform version — change one version at a time.

Verify the logic spikes pass:
```
./gradlew :core:domain:testDebugUnitTest :core:scanner:testDebugUnitTest
```

## 5. Connect the Galaxy Tab
- Install the Samsung USB driver (or Samsung Smart Switch, which bundles it).
- On the tab: Settings → About tablet → Software information → tap **Build number** 7× →
  Developer options → enable **USB debugging**.
- Plug in via USB, tap **Allow** on the debugging prompt.
- Confirm: `adb devices` lists the tab as `device`.

## 6. Build & deploy
Run button: with the Android plugin a `composeApp` run config appears — pick the tab, hit Run.

Or via Gradle / terminal:
```
./gradlew :composeApp:installDebug
adb shell am start -n com.oliver.heyme.mangazuki/.MainActivity
```
APK-only: `./gradlew :composeApp:assembleDebug` → `composeApp/build/outputs/apk/debug/`.

## Notes
- `minSdk` is 26 (Android 8.0) — fine for any modern Galaxy Tab.
- Wrapper jar is not committed; IntelliJ generates it on first open. IDE-less bootstrap:
  `winget install Gradle.Gradle` then `gradle wrapper --gradle-version 8.11.1`.
- iOS build is deferred until a Mac exists (PLAN.md §12, §16).

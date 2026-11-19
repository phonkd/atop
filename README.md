# atop

A clean root system monitor for Android, built with Kotlin and Jetpack Compose.

## Build

With Nix installed, enter the development shell and build the app:

```sh
nix develop
./gradlew assembleDebug
```

To build the release APK locally:

```sh
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

## Releases

The [Release workflow](.github/workflows/release.yml) runs for every push to `main` and can also be started manually from the Actions tab. It builds the release APK, uploads it as a workflow artifact, and publishes it as a GitHub Release tagged `main-<run-number>`.

> **Note:** The release build currently uses Android's debug signing configuration. These APKs are suitable for testing, not production distribution. CI runners generate their own debug keys, so an APK from one CI release may not install as an update over another without uninstalling the old app first.

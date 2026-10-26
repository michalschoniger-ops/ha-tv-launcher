# Nástroje pro sestavení

`gradlew`, `gradlew.bat` a `gradle/wrapper/gradle-wrapper.jar` jsou oficiální soubory Gradle 8.13.0 z [gradle/gradle](https://github.com/gradle/gradle/tree/v8.13.0), Apache License 2.0; plný text je v `LICENSE-Gradle.txt`. Wrapper JAR byl při přípravě porovnán s [kontrolním součtem SHA-256 wrapperu](https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256). Distribuce Gradle má samostatný SHA256 přímo v `gradle-wrapper.properties`.

# Běhová knihovna WebRTC

Nativní kamerový přehrávač používá `io.github.webrtc-sdk:android:150.7871.04` z [oficiálního vydání vydavatele](https://github.com/webrtc-sdk/android/releases/tag/v150.7871.04). Úplné původní licence, patenty a přiložené licence dalších komponent jsou distribuovány přímo v APK v `assets/licenses/`: `WEBRTC-THIRD-PARTY.md`, `WEBRTC-SDK-MIT.txt`, `WEBRTC-BSD.txt` a `WEBRTC-PATENTS.txt`. Soubory jsou převzaté beze změn; přesné zdrojové adresy a SHA-256 jsou v `assets/licenses/README.txt`.

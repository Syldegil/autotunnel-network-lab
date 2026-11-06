# Build and reproduce

JDK 17, Android SDK platform 36 and build-tools compatible with AGP 8.13.2, Gradle 8.13, Python 3 for wrapper bootstrap. Set ANDROID_HOME to your SDK. No local.properties from a developer machine is included.

```sh
python3 bootstrap-wrapper.py
sh gradlew --no-daemon --max-workers=2 testDebugUnitTest assembleDebug
```

The wrapper and distribution checksums are pinned from services.gradle.org; bootstrap downloads the wrapper from Gradle's official GitHub repository. Dependencies resolve from Google Maven, Maven Central and Gradle Plugin Portal. WireGuard tunnel 1.0.20260102; JUnit 4.13.2. Only sources are released: build outputs and debug signatures remain local. Public application ID io.github.syldegil.autotunnel does not automatically update the existing private app.

JVM tests use simulated packets, routes and backend fixtures; they do not exercise an Android hotspot. Instrumentation sources are included but not run by the host command. No root provisioning, device install, Linux namespace or kernel module operation is launched by publication. Historical Python/namespace harnesses are excluded from this first source export as an explicit scope boundary; the Java engine and JVM fixtures are included. A DNS firewall template now requires a single explicit IPv4 address in the editor; no home DNS is preset.

Instrumented Android sources are HISTORICAL_NOT_RUN: several assert old version codes/UI labels or require preexisting profiles. They are retained for provenance, not a current connectedAndroidTest recipe. Some capture screenshots or contact diagnostic services; review before separately authorized use. No device or CI workflow executes them here.

# AutoDonut

Fabric mod for Minecraft **26.3** (Fabric Loader **0.19.5**, Java **25**).

## IntelliJ IDEA setup
1. Install a **JDK 25** (File → Project Structure → SDKs).
2. *File → Open* → select this folder → open as Gradle project.
3. Settings → Build, Execution, Deployment → Build Tools → Gradle → set **Gradle JVM** to JDK 25.
4. Let Gradle sync; Loom generates the `Minecraft Client` / `Minecraft Server` run configurations.
   If they don't appear, run `./gradlew genSources` then reload the Gradle project.

## Build
```
./gradlew build
```
Output jar: `build/libs/`.

Versions are in `gradle.properties` — verify `loom_version` and `fabric_api_version` against https://fabricmc.net/develop.

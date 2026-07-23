# lumella-glasses

Gradle multi-module skeleton for the lumella glasses client. Scaffolded in P2
around the already-moved luma engine at `TUTOR/lumella/luma/` (read-only
baseline for wrapper/version-catalog).

## Module map

- `app/` — Android application module (`com.woolab.lumella`). The installable
  glasses client. Depends only on `:tutor-contract`.
- `tutor-contract/` — pure Kotlin JVM library. Interfaces/data types the
  TutorBrain contract exposes to the app. No Android, no engine dependency.
- `luma-adapter/` — pure Kotlin JVM library implementing `:tutor-contract`
  against the luma engine. Depends on `:tutor-contract`.
- `contract-tests/` — pure Kotlin JVM library with compatibility tests that
  exercise `:tutor-contract` implementations (e.g. `:luma-adapter`) against
  the same contract surface.
- `token-service/` — owned by a separate slice (auth/token issuance service).
- `docs/` — owned by a separate slice (dev-loop, COMPAT notes).

## Dependency rule

`:app` depends on `:tutor-contract` only. `:app` does NOT depend on
`:luma-adapter` directly — the concrete adapter implementation is wired in
at runtime via dependency injection (lands with the P3 port), keeping the
app module decoupled from the luma engine implementation.

```
:app --------------> :tutor-contract
:luma-adapter ------> :tutor-contract
:contract-tests ----> (tests :tutor-contract implementations)
```

## Build commands

```sh
# from TUTOR/lumella/glasses/
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"

./gradlew tasks -q
./gradlew projects -q

# verify :app only pulls in :tutor-contract, not :luma-adapter
./gradlew :app:dependencies --configuration debugCompileClasspath -q

# run contract-tests
./gradlew :contract-tests:test
```

Requires JDK 17 and Android SDK platform 34 (compileSdk) / minSdk 30,
matching the baseline in `TUTOR/lumella/luma/ELLA-main/`.

# Repository Guidelines

## Project Structure & Module Organization

`deepseek-helper` is a Kotlin Multiplatform (KMP) library with a JVM target that wraps the DeepSeek chat APIs and provides a tool-calling pipeline. Source sets follow the KMP convention:

- `src/commonMain/kotlin/io/github/hatoyuze/deepseek/` — shared code: `protocol/` (DeepSeek API models, requests, networking), `toolcall/` (tool-calling DSL, executors, pipeline plugins, registry, serializers)
- `src/jvmMain/kotlin/` — JVM-specific code (Ktor CIO engine, logging)
- `src/commonTest/kotlin/` and `src/jvmTest/kotlin/` — unit tests
- `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/` — build configuration and version catalog

## Build, Test, and Development Commands

Requires JDK 17. Always use the Gradle wrapper:

- `./gradlew build` — compiles all targets, runs tests, and assembles JARs; this is exactly what CI runs
- `./gradlew test` — runs tests only
- `./gradlew jvmJar` — builds the JVM artifact under `build/libs/`

## Coding Style & Naming Conventions

- Use 4-space indentation and keep lines under 120 columns (see `.editorconfig`); IntelliJ defaults are fine
- Follow the official Kotlin code style (`kotlin.code.style=official` in `gradle.properties`)
- Package names follow `io.github.hatoyuze.deepseek.*` (for example, `protocol.api`, `toolcall.executor`)
- Mark experimental or unstable public APIs with `@ExperimentalDeepseekApi`
- No formatter or linter is configured; match the surrounding code and keep diffs small

## Testing Guidelines

- Framework: `kotlin.test` with `kotlinx-coroutines-test` for suspending code
- Place tests in `<Thing>Test.kt` under `src/commonTest/kotlin/`; use `src/jvmTest/kotlin/` for JVM-only behavior
- Name test methods with descriptive backtick sentences, e.g. `` `ContentDelta with null reasoning` ``
- Use `assertEquals`, `assertIs`, `assertNull`, and similar kotlin.test assertions
- Run tests with `./gradlew test`; CI requires them to pass on JDK 17

## Commit & Pull Request Guidelines

- History is minimal, so adopt Conventional Commits: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:` followed by a short imperative summary
- PRs target `main`; CI (`.github/workflows/gradle.yml`) runs `./gradlew build` and uploads the JVM artifact
- Describe what changed and why, link related issues, and call out breaking API changes

## Agent-Specific Instructions

- Read `build.gradle.kts` and `gradle/libs.versions.toml` before touching dependencies
- Keep common code platform-agnostic; put JVM-only code in `jvmMain`
- Do not commit IDE files (`.idea/`) or build output — `.gitignore` covers build artifacts

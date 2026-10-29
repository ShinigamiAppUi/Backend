# Shinigami Backend

Standalone Shinigami API and catalog-first stream runtime.

## Production architecture

`AniList metadata -> Shinigami API -> catalog -> DIRECT / RESOLVER / EXTRACTOR -> Media3`

The production backend does not load an AnimeX/Hatsune provider pool, provider auto-update runtime, Sanka fallback, or provider-selection router.

## Included
- Shinigami API and authentication/admin runtime
- Catalog repository and multi-source source health
- Catalog DIRECT / RESOLVER / EXTRACTOR pipeline
- Native extractor registry
- JVM backend and deployment E2E

## Test

`./gradlew test --stacktrace`

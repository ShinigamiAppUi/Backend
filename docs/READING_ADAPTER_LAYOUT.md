# Reading Adapter Layout

**Scope:** Backend reading adapters only  
**Working branch:** `feature/manga-manhwa-novel-foundation`  
**Purpose:** Keep site-specific adapter work discoverable by content type and language as the catalog expands.

## Naming contract

Use this filename shape for language-scoped adapter files:

`<ContentType><Language>[Purpose]ReadingRuntimeProvider(s).kt`

Examples:
- `MangaIndonesianReadingRuntimeProviders.kt`
- `MangaEnglishReadingRuntimeProviders.kt`
- `ManhwaIndonesianApiReadingRuntimeProviders.kt`
- `ManhwaEnglishAdditionalReadingRuntimeProviders.kt`
- `ManhuaIndonesianReadingRuntimeProviders.kt`
- `ManhuaEnglishReadingRuntimeProviders.kt`
- `NovelIndonesianReadingRuntimeProviders.kt`
- `NovelEnglishReadingRuntimeProviders.kt`

Keep the content type first and language second. Add a purpose suffix such as `Api`, `Theme`, `Additional`, or a provider/site name only when a category needs more than one file. Keep each site parser in its own provider class; a shared file is only a source-file organization choice, not a shared parser contract.

## Domain/language work matrix

| Domain | Language | Canonical file prefix | Work status |
|---|---|---|---|
| Manga | Indonesian (`id`) | `MangaIndonesian` | `IN_PROGRESS` — current source audit batch |
| Manga | English (`en`) | `MangaEnglish` | `NOT_STARTED` — file naming standardized; implementation audit still required |
| Manhwa | Indonesian (`id`) | `ManhwaIndonesian` | `NOT_STARTED` — file naming standardized; implementation audit still required |
| Manhwa | English (`en`) | `ManhwaEnglish` | `NOT_STARTED` — file naming standardized; implementation audit still required |
| Manhua | Indonesian (`id`) | `ManhuaIndonesian` | `NOT_STARTED` |
| Manhua | English (`en`) | `ManhuaEnglish` | `NOT_STARTED` |
| Novel | Indonesian (`id`) | `NovelIndonesian` | `NOT_STARTED` — file naming standardized; implementation audit still required |
| Novel | English (`en`) | `NovelEnglish` | `NOT_STARTED` |

File existence, provider registration, or a class name is not proof that an adapter is complete or live-verified. The implementation ledger remains the source of truth for provider-level status and evidence.

## Current organization

Language-specific files were renamed without changing Kotlin package names, class names, provider IDs, parsing logic, or runtime registration:
- `MangaIndonesianCoreReadingRuntimeProviders.kt`
- `MangaIndonesianAdditionalReadingRuntimeProviders.kt`
- `MangaIndonesianApiReadingRuntimeProviders.kt`
- `MangaIndonesianThemeReadingRuntimeProviders.kt`
- `MangaIndonesianSpecializedApiReadingRuntimeProviders.kt`
- `MangaIndonesianKomikNesiaReadingRuntimeProvider.kt`
- `MangaEnglishReadingRuntimeProviders.kt`
- `MangaEnglishAdditionalReadingRuntimeProviders.kt`
- `ManhwaIndonesianReadingRuntimeProviders.kt`
- `ManhwaIndonesianAdditionalReadingRuntimeProviders.kt`
- `ManhwaIndonesianApiReadingRuntimeProviders.kt`
- `ManhwaEnglishReadingRuntimeProviders.kt`
- `ManhwaEnglishAdditionalReadingRuntimeProviders.kt`
- `NovelIndonesianReadingRuntimeProviders.kt`

Older shared files containing providers whose content type/language needs source-by-source confirmation remain separate for now. Do not move a site into a language bucket based only on its name. During the relevant batch audit, verify actual content/language, then split or relocate it without changing provider IDs or registration behavior.

## Shared code boundaries

Keep domain-neutral contracts and transport helpers in shared files, including:
- `ReadingRuntimeProvider.kt` — adapter contract, output validation and page normalization
- `ReadingHttpTransport.kt` / `ReadingHttpResponse.kt` — network boundary
- `ReadingNovelText.kt` — novel text normalization
- `ReadingCatalogModels.kt`, `ReadingCatalogRepository.kt`, and `ReadingCatalogService.kt` — catalog/domain contracts

Catalog remains source-URL configuration, not a duplicate metadata catalog. AniList metadata remains separate. Each adapter must keep source-specific host/path checks, safe URL validation, cancellation propagation, deterministic page order, and bounded outputs.

## Adding a language

1. Add/confirm the language and source inventory in `READING_ADAPTER_IMPLEMENTATION_PROGRESS.md`.
2. Create a file with the canonical content-type-first/language-second prefix.
3. Keep provider IDs stable and unique; do not copy a provider implementation into another language bucket unless that source genuinely serves that language and the behavior is audited.
4. Register the provider through the existing runtime registration path.
5. Record implementation, fixture, live-validation and CI evidence separately. Do not mark a batch `LOCKED` without proof.

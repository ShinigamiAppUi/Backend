# Reading Adapter Layout

**Scope:** Backend reading adapters only  
**Working branch:** `feature/manga-manhwa-novel-foundation`  
**Purpose:** Keep site-specific adapter work discoverable by content type and language as the catalog expands.

## Directory and filename contract

Source files live under a content-type directory, then a language directory:

```text
streamix/readingcatalog/reading/
├── manga/
│   ├── id/
│   └── en/
├── manhwa/
│   ├── id/
│   └── en/
├── manhua/
│   ├── id/
│   └── en/
└── novel/
    ├── id/
    └── en/
```

Target naming is one adapter per file, using the site's adapter name, for example `reading/novel/id/BacaLightNovelAdapter.kt` and `reading/novel/en/RoyalRoadAdapter.kt`. Prefer `<SiteName>Adapter.kt`; do not bundle unrelated sites into one source file. Shared transport, parsing utilities and domain-neutral contracts remain outside these site folders.

Examples:
- `MangaIndonesianReadingRuntimeProviders.kt`
- `MangaEnglishReadingRuntimeProviders.kt`
- `ManhwaIndonesianApiReadingRuntimeProviders.kt`
- `ManhwaEnglishAdditionalReadingRuntimeProviders.kt`
- `ManhuaIndonesianReadingRuntimeProviders.kt`
- `ManhuaEnglishReadingRuntimeProviders.kt`
- `NovelIndonesianReadingRuntimeProviders.kt`
- `NovelEnglishReadingRuntimeProviders.kt`

Directory path, not a class-name prefix, is the source of truth for content type and language. Keep each site's parser in its own adapter file. A file that genuinely serves multiple content types or languages must be source-audited and split where practical; do not infer classification from the provider name alone.

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

## Migration status

The first directory move places the already language-classified groups into the matching content/language folders. Kotlin package declarations, class names, provider IDs, parsing logic, and runtime registration remain unchanged, so existing imports remain compatible. These files are still grouped by category and are an intermediate migration step; split them into one site adapter per file after source-level classification:
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

New paths under `src/main/kotlin/streamix/readingcatalog/reading/`:
- `manga/id/` — six Manga Indonesian source groups
- `manga/en/` — two Manga English source groups
- `manhwa/id/` — three Manhwa Indonesian source groups
- `manhwa/en/` — two Manhwa English source groups
- `novel/id/` — one Novel Indonesian source group

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


## Current per-adapter layout progress

- Static tree check: 102 `*Adapter.kt` files are present under `reading/`, and all 102 reading-provider imports in `BackendMain.kt` map to an expected per-site adapter filename. This checks layout/import-name alignment only; it does not prove parser correctness, source availability, runtime registration behavior, or test/build success.

- The Indonesian/English Manga and Indonesian/English Manhwa language directories have been further split from grouped `*Providers.kt` files into site-specific `*Adapter.kt` files for the providers audited in this refactor. This is a file-organization change only; provider class names, IDs, and the existing `streamix.readingcatalog` package remain unchanged.
- A `reading/shared/` exception now holds providers whose current contracts span multiple non-novel content kinds or whose language/content-kind bucket is not yet proven. These must be source-audited before narrowing to a language/content folder; shared placement is not completion evidence.
- The directory structure is not yet complete: additional root-level mixed-domain files still need source-by-source classification and relocation, and the Manhua buckets need their own audited inventory/implementations.

## Per-adapter file rule

Use one provider class per site-specific adapter file, following the requested shape: `reading/<content-type>/<language>/<SiteName>Adapter.kt` (for example `reading/novel/id/BacaLightNovelAdapter.kt` and `reading/novel/en/RoyalRoadAdapter.kt`). Do not group unrelated sites into `*Providers.kt` files inside the language directories. Shared parsing/transport helpers stay in shared files and are not counted as adapters. Existing provider class names and IDs remain stable while the source files are split.

# Reading Adapter Implementation Progress

> Source of truth for resumable, language-batched implementation of site-specific adapters for manga, manhwa, manhua, and novels in **ShinigamiAppUi/Backend**.
>
> Keep this ledger updated on every implementation commit and before handoff. Inventory, a class, or a registry entry is not proof that an adapter is complete or healthy.

## Current work state

- **Repository:** `ShinigamiAppUi/Backend`
- **Working branch:** `feature/manga-manhwa-novel-foundation`
- **Protected rule:** implementation commits stay on this branch; do not use `main` for implementation.
- **Current batch:** Batch 1 — Manga / Indonesian (`MANGA` / `id`)
- **Batch status:** `IN_PROGRESS`
- **Latest confirmed code commit:** `093ae83e1ecb2eba63f9f8dc57701b8b5e8d974e` — resolve Bacami relative chapter image URLs
- **Next concrete action:** continue source-by-source audit and implementation for the 25 Indonesian manga providers below. Do not start a new language batch until this batch's feasible implementations have been completed and its inventory reconciled.
- **Testing policy:** no full tests, CI, or build run for this implementation pass. Defer the planned full test/build pass until all feasible adapters in all planned language batches have an implementation pass. Never describe unrun tests as passed.
- **Important status distinction:** the changes so far tighten content-kind dispatch/chapter boundaries only. They do **not** mean the source-specific parser work is complete or that these adapters are fully implemented.

## Batch plan

| Order | Content type | Language | Status | Notes |
|---:|---|---|---|---|
| 1 | Manga | Indonesian (`id`) | `IN_PROGRESS` | Content-kind boundary pass started; 25 inventory rows below; source-specific implementation audit remains. |
| 2 | Manga | English (`en`) | `NOT_STARTED` | Resume from existing comic audit matrix and source references. |
| 3 | Manhwa | Indonesian (`id`) | `NOT_STARTED` | Existing site-specific provider classes received a chapter-boundary pass; this is not the full implementation batch. |
| 4 | Manhwa | English (`en`) | `NOT_STARTED` | Resume from existing comic audit matrix and source references. |
| 5 | Manhua | Indonesian (`id`) | `NOT_STARTED` | Confirm actual source/content type; do not force manga-only sites into this group. |
| 6 | Manhua | English (`en`) | `NOT_STARTED` | Confirm actual source/content type; do not force manga-only sites into this group. |
| 7 | Novel | Indonesian (`id`) | `NOT_STARTED` | Reconcile the Indonesian novel inventory in `READING_MANGA_ARCHITECTURE.md`. |
| 8 | Novel | English (`en`) | `NOT_STARTED` | Reconcile the English novel inventory in `READING_MANGA_ARCHITECTURE.md`. |

## Status vocabulary

- Inventory/audit: `INVENTORIED`, `AUDIT_PENDING`, `AUDITED`, `PARTIAL`
- Implementation: `NOT_STARTED`, `IN_PROGRESS`, `IMPLEMENTED_UNTESTED`
- Verification: `FIXTURE_TESTED`, `LIVE_VERIFIED`, `TEST_FAILED`
- Exceptions: `BLOCKED`, `UNAVAILABLE`, `LANGUAGE_UNVERIFIED`
- Final: `LOCKED` only after required implementation, tests/builds, re-audit, and evidence are complete.

Track implementation and verification independently. A commit confirms a change was recorded, not that it works.

## Per-source adapter tracker — Batch 1: Manga / Indonesian

The class names and paths below were checked in this repository. Canonical URLs and live health are deliberately not guessed: validate each against maintained source references and record the evidence in its row.

| Source ID / adapter class | Canonical URL / reference | Content type | Language | Audit status | Implementation status | Fixture test status | Live verification | Source file | Blocker / next action | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| `bacakomik` / `BacaKomikReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianAdditionalReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `bacami` / `BacamiReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianMangaReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `kiryuu` / `KiryuuReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianThemeReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `klikmanga` / `KlikMangaReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianMangaReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `apkomik` / `ApkomikReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianThemeReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `komikindoid` / `KomikIndoIdReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianMangaReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `komiknesia` / `KomikNesiaReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/KomikNesiaReadingRuntimeProvider.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `komik-station` / `KomikStationReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianThemeReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `komiku` / `KomikuReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianMangaReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `komiku-asia` / `KomikuAsiaReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianApiReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `kuro-manga` / `KuroMangaReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/KuroMangaReadingRuntimeProvider.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `manga-can` / `MangaCanReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianAdditionalReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `mangalay` / `MangalayReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianAdditionalReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `lepoytl` / `LepoyTlReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianThemeReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `mg-komik` / `MGKomikReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/MGKomikReadingRuntimeProvider.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `mikoroku` / `MikoRokuReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianSpecializedApiReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `narasininja` / `NarasiNinjaReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianApiReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `ngamenkomik` / `NgamenKomikReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianAdditionalReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `ngomik` / `NgomikReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianAdditionalReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `okyykomik` / `OkyyKomikReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianAdditionalReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `riztranslation` / `RiztranslationReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianSpecializedApiReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `shiyurasub` / `ShiyuraSubReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianAdditionalReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `shinigami` / `ShinigamiReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/ShinigamiReadingRuntimeProvider.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `siikomik` / `SiikomikReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianApiReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |
| `west-manga` / `WestMangaReadingRuntimeProvider` | Needs source-specific canonical URL/reference review; code allowlist is not live proof | Manga | Indonesian (inventory label; verify chapter content) | `PARTIAL` | `IN_PROGRESS` | Not run — deferred | Not run — deferred | `src/main/kotlin/streamix/readingcatalog/IndonesianSpecializedApiReadingRuntimeProviders.kt` | Complete per-site parser/source-reference audit; record evidence and remaining work | Content-kind boundary pass only; not counted as fully implemented. |

## Changes made on this branch so far

- Tightened broad “not a novel” adapter support predicates to exact `MANGA` or `MANHWA` dispatch for the relevant source-specific groups.
- Added chapter-boundary kind checks so direct resolver/extractor calls cannot use the wrong adapter for another content type.
- Fixed the existing KomikNesia and Shinigami chapter guards to enforce `MANGA` exactly.
- Tightened the UlasComic provider in the Indonesian manhwa API group to `MANHWA`, consistent with its group and neighboring provider contracts.
- Hardened WestManga and MikoRoku chapter URL parsing by constraining API path identifiers to bounded alphanumeric/underscore/hyphen segments before interpolating them into fixed API paths.
- Updated Bacami chapter image parsing to resolve relative `imageUrls` values against the chapter URL before HTTPS image validation, while still rejecting invalid/non-HTTPS results.
- Added explicit chapter guards to the Indonesian Manhwa provider families.
- **Scope limitation:** this is a boundary-safety pass, not a claim that all 25 manga adapters or the manhwa batch are fully implemented. Parser details, source references, API changes, image extraction, pagination, error states, and licensing/access constraints still need per-site review.
- **Tests/build/CI:** not run, intentionally deferred by the agreed implementation-first policy.

## Batch completion checklist

For each batch, check these in order:

- [ ] Resume from existing audit/inventory evidence; avoid unnecessary full re-audit.
- [ ] Confirm canonical source URL/reference, content type, and actual chapter language.
- [ ] Record site-specific search/detail/chapter/content behavior and source references.
- [ ] Implement each feasible site using a dedicated adapter; shared code is limited to infrastructure.
- [ ] Commit coherent, reviewable increments during the implementation pass.
- [ ] Update every affected source row and record commit SHA(s).
- [ ] Perform a batch completeness audit and list unfinished adapters explicitly.
- [ ] After all language batches are implemented, run the planned full fixture tests and build.
- [ ] Run live smoke tests only where permitted and practical; record limitations.
- [ ] Record failures, fix them, and re-test.
- [ ] Re-audit and mark only evidence-backed adapters `LOCKED`.
- [ ] Update the current batch, counts, blockers, and exact next action before handoff.

## Per-batch handoff log

### Batch 1 — Manga / Indonesian

- **Completed so far:** initial kind-boundary pass in the main Indonesian manga groups and chapter-boundary pass in dedicated Indonesian manhwa groups.
- **Still remaining:** per-site source-reference verification and implementation review for every row above; do not count a boundary-only patch as a finished adapter.
- **Testing/build:** deferred; not run.
- **Next step:** continue Manga / Indonesian; inspect and implement site-specific parsing and URL/source rules in coherent commits, updating this ledger each time.

## Change log

| Date (UTC) | Batch | Change | Commit | Evidence / follow-up |
|---|---|---|---|---|
| 2026-10-29 | Manga / Indonesian | Started exact content-kind dispatch and chapter-boundary hardening; added bounded path-identifier validation for WestManga and MikoRoku API routes; resolved relative Bacami image URLs; source-specific implementation remains partial | `093ae83e1ecb2eba63f9f8dc57701b8b5e8d974e` | No tests/build run; continue site-by-site implementation before moving to the next language batch |

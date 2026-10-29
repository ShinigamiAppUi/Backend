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
- **Latest confirmed code commit:** `9499c9f414488c9ea3ab80c6bd83f9d445619aab` — harden MGKomik fetch boundary
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

## Latest implementation note — 2026-10-29

- Updated Komiku.asia page-object parsing to validate each candidate field (`url`, `src`, `image`, `image_url`) as it is tried. A non-empty but unsafe first field no longer blocks a later safe field. Code commit: `7d86ee71325d3b2ddf4e7566ed4856b45691e0a1` (`fix(reading): try later safe Komiku Asia page fields`).
- Updated the four Blogger-style Indonesian adapters in `IndonesianAdditionalReadingRuntimeProviders.kt` (Mangalay, NgamenKomik, OkyyKomik, ShiyuraSub) to validate each resolved image candidate before selection, so invalid/unsafe lazy attributes do not block later valid image attributes. Code commit: `65756b71ea4ee206c5cee4de7f139c7f19ab2605` (`fix(reading): validate every Blogger manga image candidate`).
- Updated Siikomik and NarasiNinja HTML image extraction to validate each resolved candidate as it is tried instead of rejecting only the first non-empty attribute. Code commit: `065f015fb239edbe38abb45e9e03fe39b71d03bc` (`fix(reading): fall through unsafe API adapter image candidates`). Tests/build/CI remain deferred.


- Updated the four Blogger-style Indonesian adapters in `IndonesianAdditionalReadingRuntimeProviders.kt` (Mangalay, NgamenKomik, OkyyKomik, ShiyuraSub) to validate each resolved image candidate before selection, so invalid/unsafe lazy attributes do not block later valid image attributes. Code commit: `65756b71ea4ee206c5cee4de7f139c7f19ab2605` (`fix(reading): validate every Blogger manga image candidate`).
- Updated Siikomik and NarasiNinja HTML image extraction to validate each resolved candidate as it is tried instead of rejecting only the first non-empty attribute. Code commit: `065f015fb239edbe38abb45e9e03fe39b71d03bc` (`fix(reading): fall through unsafe API adapter image candidates`). Tests/build/CI remain deferred.


- Hardened the native Shinigami adapter's chapter URL parsing: HTTPS catalog URLs must use the Shinigami domain or its subdomains, contain no user-info credentials, use only the default/443 port, pass the shared safe-URL guard, and yield a bounded opaque chapter identifier. The adapter still constructs API requests against its fixed API origin. Code commit: `3265a58605b865e630f70bbb397a8f57f759b412` (`fix(reading): validate Shinigami chapter URL authority`).
- Updated MGKomik reader extraction to resolve and validate each lazy/source image candidate before selection; malformed or unsafe earlier attributes no longer prevent fallback to a later valid image. Code commit: `d5c55a9cc8c8e4bf019765b9ad0e5cb43f1b687d` (`fix(reading): fall through invalid MGKomik image candidates`).
- Updated Kuro Manga reader extraction to validate each resolved image candidate as it is tried, rather than selecting the first non-empty attribute and rejecting it afterward. Code commit: `1cded7553c85eaab3f98023536990b8cd358607f` (`fix(reading): fall through invalid Kuro Manga image candidates`). These are targeted parser/URL validation fixes; tests/build/CI remain deferred.


- Updated MGKomik reader extraction to resolve and validate each lazy/source image candidate before selection; malformed or unsafe earlier attributes no longer prevent fallback to a later valid image. Code commit: `d5c55a9cc8c8e4bf019765b9ad0e5cb43f1b687d` (`fix(reading): fall through invalid MGKomik image candidates`).
- Updated Kuro Manga reader extraction to validate each resolved image candidate as it is tried, rather than selecting the first non-empty attribute and rejecting it afterward. Code commit: `1cded7553c85eaab3f98023536990b8cd358607f` (`fix(reading): fall through invalid Kuro Manga image candidates`). Both are targeted parser fallback fixes; tests/build/CI remain deferred.


- Updated KomikStation and Apkomik image extraction to validate each candidate before selection; an invalid first lazy/source attribute no longer blocks trying later safe attributes. Code commit: `3714158b625e550210fda7c89c7a822751aa133f` (`fix(reading): fall through invalid theme image candidates`).
- Updated KlikManga image extraction with the same per-candidate validation and fallback behavior. Code commit: `60f2a2f53d20eb02ae3b471d6b76197ae0d51c34` (`fix(reading): fall through invalid KlikManga image candidates`).
- Updated Kiryuu and LepoyTL image extraction to resolve and validate each lazy/source attribute before selecting it. A malformed or unsafe first attribute now falls through to later candidates. Code commit: `4f0154a10a5bc96db9f288081394c1e8c74859dd` (`fix(reading): validate each Indonesian theme image candidate`). This improves parser fallback behavior only; source-specific completeness and live health remain unverified.
- Hardened the MangaCan and Ngomik reader parsers to validate each lazy/source image candidate before selection, rather than choosing the first non-empty attribute and rejecting it afterward. This preserves fallback to later safe attributes. Code commit: `429327d1ee9d1695adcbeae90445f0e134665ed3` (`fix(reading): validate each Blogger reader image candidate`).
- Fixed BacaKomik image candidate selection: each fallback/lazy/source attribute is now resolved and validated before selection, so an unsafe or malformed earlier candidate does not block a later safe image URL. Code commit: `6048f1fd11954e6055354caa890dfa638e826137` (`fix(reading): try later safe BacaKomik image candidates`). This is a parser robustness fix, not full adapter verification.

- Hardened MikoRoku chapter URL authority checks to reject embedded credentials and nonstandard ports. Commit: `0e090d2e4c8845be273799d678ccffa3baed25c8` (`fix(reading): harden MikoRoku chapter URL authority`).
- Hardened KomikIndoID chapter URL authority checks and changed image selection to validate each candidate as it is tried, including parsing either quote style in the `onError` fallback. Commit: `d296b902f76027a39b0ad2ec92761e61b97de32c` (`fix(reading): validate KomikIndoID image fallbacks`).

- Updated `KomikuAsiaReadingRuntimeProvider` so both chapter-source URL parsing and the API transport reject embedded credentials and nonstandard ports; accepted explicit ports are limited to 80/443, alongside the existing safe-URL and exact-host checks.
- Code commit: `01b7d2a4e81e5644f7b5e4df74eb258f6ee59787` (`fix(reading): reject nonstandard Komiku Asia URL ports`).
- This is a targeted source hardening fix, not proof the provider is fully implemented. Batch 1 remains `IN_PROGRESS`; tests/build/CI remain deferred per the all-batches-first instruction.

- Updated Siikomik and NarasiNinja image extraction to validate each lazy-image candidate before selecting it. An unsafe first candidate no longer prevents the parser from trying a later safe attribute.
- Code commit: `c7d7eca05204b2485f4558b8daa993e069d5bf05` (`fix(reading): skip unsafe lazy images in Indonesian API adapters`).

- Hardened KlikManga chapter URL validation to reject embedded credentials and explicit ports other than 80/443, consistent with the other Indonesian manga HTML adapters. This is source-URL hardening only; parser coverage remains under audit.
- Code commit: `a95c9524ca6fdd521f26c82d2deafbe80a0aa39f` (`fix(reading): harden KlikManga chapter URL authority`).

- Hardened KlikManga chapter URL validation to reject embedded credentials and explicit ports other than 80/443. Code commit: `a95c9524ca6fdd521f26c82d2deafbe80a0aa39f` (`fix(reading): harden KlikManga chapter URL authority`).
- Updated BacaKomik `onerror` fallback extraction to recognize either single-quoted or double-quoted `src` values, matching the site's HTML fallback variants without relaxing image URL validation. Code commit: `92ec83cbdf2d3e8f8efc22883cb14363158eccd2` (`fix(reading): parse both BacaKomik fallback quote styles`).

- Hardened Siikomik and NarasiNinja chapter URL validation to reject embedded credentials and nonstandard explicit ports (only default, 80, or 443 are accepted), aligning these API-backed HTML adapters with the existing Indonesian manga URL-authority policy. Code commit: `f090f552ffc2b043717922680b241710236ce077` (`fix(reading): restrict API adapter chapter URL ports`).

- Hardened Bacami chapter URL authority validation: reject embedded credentials and explicit ports other than 80/443 while preserving the existing host and chapter-path checks. Code commit: `4379f6fe76f3429b41b15c06da27f2eb27a0018a` (`fix(reading): harden Bacami chapter URL authority`).
- Hardened Riztranslation chapter URL parsing: reject embedded credentials and nonstandard ports, and bound numeric chapter IDs to 1–160 digits before constructing the API request. Code commit: `4aa83fad5502cb8f23bca6faa5e8f06af077bd54` (`fix(reading): harden Riztranslation chapter URL validation`).

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
- Updated Komiku.asia response parsing to skip empty top-level `pages` arrays and continue checking nested response locations for actual page data.
- Hardened Komiku.asia by rejecting credentialed source URLs and bounding comic/chapter identifiers to 1–160 safe characters; hardened Komiku, BacaKomik, MangaCan, Mangalay, NgamenKomik, Ngomik, OkyyKomik, ShiyuraSub, Kiryuu, KomikStation, Apkomik, LepoyTl, and WestManga chapter URL authority checks to reject embedded credentials and nonstandard ports.
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

- **Completed so far:** initial kind-boundary pass in the main Indonesian manga groups and chapter-boundary pass in dedicated Indonesian manhwa groups; targeted hardening for WestManga/MikoRoku identifiers and WestManga URL authority, Bacami relative image URLs, Komiku.asia response parsing/source URLs/IDs, and credential/port validation for Indonesian manga chapter sources.
- **Still remaining:** per-site source-reference verification and implementation review for every row above; do not count a boundary-only patch as a finished adapter.
- **Testing/build:** deferred; not run.
- **Next step:** continue Manga / Indonesian; inspect and implement site-specific parsing and URL/source rules in coherent commits, updating this ledger each time.

## Change log

| Date (UTC) | Batch | Change | Commit | Evidence / follow-up |
|---|---|---|---|---|
| 2026-10-29 | Manga / Indonesian | Komiku.asia now validates page object fields one-by-one and falls through to later safe URL fields when an earlier field is invalid. | `7d86ee71325d3b2ddf4e7566ed4856b45691e0a1` | Targeted parser fallback fix; tests/build/CI deferred. |
| 2026-10-29 | Manga / Indonesian | Siikomik and NarasiNinja now validate each resolved image candidate before choosing it, preserving fallback after unsafe/malformed lazy attributes. | `065f015fb239edbe38abb45e9e03fe39b71d03bc` | Targeted parser fallback fix; tests/build/CI deferred. |
| 2026-10-29 | Manga / Indonesian | Mangalay, NgamenKomik, OkyyKomik and ShiyuraSub now validate each resolved image candidate before choosing it, preserving fallback after unsafe/malformed lazy attributes. | `65756b71ea4ee206c5cee4de7f139c7f19ab2605` | Targeted parser fallback fix; tests/build/CI deferred. |
| 2026-10-29 | Manga / Indonesian | KomikStation and Apkomik now validate each reader image candidate before selection, allowing safe fallback after malformed/unsafe earlier attributes. | `3714158b625e550210fda7c89c7a822751aa133f` | Parser fallback improvement only; tests/build/CI deferred. |
| 2026-10-29 | Manga / Indonesian | KlikManga now validates each lazy/source image candidate before selection and falls through to later safe attributes. | `60f2a2f53d20eb02ae3b471d6b76197ae0d51c34` | Parser fallback improvement only; tests/build/CI deferred. |
| 2026-10-29 | Manga / Indonesian | Kiryuu and LepoyTL now resolve and validate each image attribute before choosing a candidate, preserving safe fallback to later attributes. | `4f0154a10a5bc96db9f288081394c1e8c74859dd` | Targeted parser robustness fix; no tests/build/CI run. |
| 2026-10-29 | Manga / Indonesian | MangaCan and Ngomik now validate each image candidate before selection so later safe attributes remain usable when earlier values are malformed/unsafe. | `429327d1ee9d1695adcbeae90445f0e134665ed3` | Parser robustness update; tests/build/CI remain deferred. |
| 2026-10-29 | Manga / Indonesian | BacaKomik now validates each fallback/lazy/source image candidate before selecting it, allowing later safe candidates after an unsafe/malformed earlier value. | `6048f1fd11954e6055354caa890dfa638e826137` | Targeted source-specific parser robustness fix; no tests/build/CI run. |
| 2026-10-29 | Manga / Indonesian | Exact content-kind dispatch and chapter-boundary hardening; bounded WestManga/MikoRoku API identifiers; resolved relative Bacami image URLs; improved Komiku.asia page-array selection and URL/identifier validation; hardened chapter URL authority checks across Indonesian manga providers; changed Komiku image extraction to validate each candidate before choosing it, allowing later lazy/source attributes when an earlier candidate is invalid. Source-specific implementation remains partial. | `1f3fcc55bf403750f2e962d94ecebb5497518931` (latest); earlier code commits: `274c64eacc7c8519fd4ab49dcc628ad4e5ce21d8`, `d5a100104863768c5e6e507ccc326b235b7ccb4d`, `0ddd266cb54037f0e1867e52e428203870f0f0b6`, `a46ed2f2aa4e48016259d608e08d7bd61c9f0d08`, `30743cc37adbdb7f7f1286677685ed35eafa9268`, `97fae3b1318434c711dd86b9c0e7ec848d30ffea`, `035ee82c85ea3d33cdd4ddeec6218fa3c0015d1f`, `093ae83e1ecb2eba63f9f8dc57701b8b5e8d974e`, `3981e0c12398b3fb06480984bf16d0ac2ba70b67`, `57699761a3dde90b11de32221c694d0d24d7aca5` | No tests/build run; continue site-by-site implementation before moving to the next language batch |

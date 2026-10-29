# Manga, Manhwa, Manhua, and Novel Architecture

**Status:** Architecture proposal — not implementation evidence  
**Scope:** Backend only  
**Reference architecture:** Anime metadata, Catalog-first source selection, provider runtime fallback, extractor/resolver boundary  
**Rule:** This document defines the target contract. It does not assert that manga/novel playback or reading flows are already implemented.

## 1. Goals

Build the reading domain using the same high-level architecture as Anime while preserving the differences between video playback and page-based reading.

- AniList is the primary metadata source for manga, manhwa, manhua, and supported novel entries.
- Reading Catalog is a manually curated source registry. It stores source URLs and source configuration, **not media metadata**.
- Provider runtime is a fallback when Catalog has no usable source or all Catalog sources fail.
- Provider selection is automatic. Users do not choose scraping providers.
- Resolver and extractor output is untrusted and must pass validation before being returned to the client.
- Reading flows must remain separate from Anime video playback and Media3 contracts.
- This document is a design target; implementation must be audited and tested before any checkpoint is marked locked.

## 2. High-level architecture

```text
Search / Detail request
        |
        v
AniList Metadata Gateway
  - MediaType.MANGA
  - manga / manhwa / manhua / supported novel formats
        |
        v
Reading Metadata DTO
  - AniList ID
  - title variants
  - cover/banner
  - description
  - format, status, genres, staff where available
        |
        | metadata remains separate from source URLs
        v
Chapter List request (AniList ID)
        |
        v
Reading Catalog (manual, source URLs only)
        |
        +--> usable Catalog chapter/source
        |        |
        |        v
        |   Direct source / Resolver / Extractor
        |        |
        |        v
        |   Validate chapter and page/image URLs
        |        |
        |        v
        |   ReadingChapterRef + ordered ReadingPageRef[]
        |
        +--> no usable Catalog result / source failure
                 |
                 v
          Reading Provider Runtime
          - parallel provider race
          - first valid chapter/page result wins
          - cancel losing jobs
                 |
                 v
          Site-specific provider adapter
                 |
                 v
          Host resolver / image extractor
                 |
                 v
          Validate output and normalize page order
                 |
                 v
          ReadingChapterRef + ordered ReadingPageRef[]
                 |
                 v
             Android Reader
```

The Catalog-first policy mirrors Anime's source-selection priority. The output contract differs: Anime produces playable stream references; Reading produces a chapter with ordered page/image references.

## 3. Metadata boundary

### 3.1 AniList responsibilities

Use AniList as the primary metadata source for reading titles. Query manga-domain media using AniList's manga media type and map available formats into Shinigami's reading domain, including manga, manhwa, manhua, and novel/light-novel entries when AniList provides them.

Metadata fields may include:

- AniList media ID (canonical metadata identity)
- Romaji, English, and native titles when available
- Cover and banner images
- Description, genres, tags, status, season/year where applicable
- Format and country of origin where available

AniList metadata is not a guarantee that chapters exist at any particular provider.

### 3.2 Metadata fallback

If AniList does not contain enough metadata, metadata adapters may be added for sources such as MAL/Jikan, Kitsu, MangaUpdates, MangaBaka, or Open Library where appropriate. These are **metadata fallbacks only** and must not silently become reading-source Catalog entries.

Metadata-source fallback must be explicit and deterministic. Preserve AniList ID when one exists; use a separate external-ID mapping for other services. Do not merge titles solely because their names look similar.

## 4. Reading Catalog contract

Reading Catalog stores source configuration for a title/chapter. It must not duplicate AniList title metadata.

A source record should conceptually contain:

- Stable source ID
- AniList media ID
- Chapter identifier/number and optional chapter title
- Content kind: manga, manhwa, manhua, or novel
- Source type: `DIRECT`, `RESOLVER`, or `EXTRACTOR`
- Source URL
- Optional language, scanlation/group label, quality/format hints where meaningful
- Enabled flag and source health
- Safe, scoped headers/cookies/referer only when required and permitted
- Audit metadata for source changes and health checks

The exact persistent schema must be checked against existing backend models before implementation. Do not add a second metadata catalog.

### Source types

| Type | Meaning | Expected result |
|---|---|---|
| `DIRECT` | A direct chapter payload or a known page-list endpoint | A normalized chapter/page list |
| `RESOLVER` | A source page that a registered resolver understands | A normalized chapter/page list |
| `EXTRACTOR` | A source page processed by a site/host extractor | A normalized chapter/page list |

A direct source is not automatically safe or valid because its URL ends in an image extension. Validate each returned page URL and reject unsupported schemes, local/private targets, unsafe redirects, and invalid payloads.

## 5. Reading domain contracts

Do not reuse Anime's `StreamRef` as the reader payload. Define reading-specific contracts equivalent to:

- `ReadingTitleRef`: AniList identity plus metadata reference
- `ReadingChapterRef`: stable chapter identity, display number/title, language, source identity, and ordered pages
- `ReadingPageRef`: page index, image URL, optional headers/referer and image dimensions when known
- `ReadingSourceOutcome`: source/provider ID, outcome, elapsed time, and sanitized failure category

Names are proposed contracts, not a claim that these exact classes already exist.

### Ordering and normalization

- Page order must be deterministic and preserved exactly after extraction.
- Never sort page URLs alphabetically or by URL text.
- Normalize page numbering only when the source's numbering is unambiguous.
- Reject duplicate/empty page URLs and malformed image entries.
- Keep chapter display numbering separate from the stable source/chapter identity.
- Preserve original page URLs only in trusted backend state where necessary; do not leak cookies, credentials, or internal diagnostics to clients.

## 6. Provider Runtime and fallback policy

Use the same orchestration principles as Anime, adapted for chapter discovery and page extraction.

1. Query Reading Catalog first.
2. Try enabled Catalog sources in deterministic source-tier order.
3. If a Catalog source fails or returns no valid pages, continue to the next source.
4. If Catalog produces no usable chapter/page result, start the Reading Provider Runtime.
5. Run eligible reading providers concurrently.
6. The first **validated, non-empty, correctly ordered** chapter result wins.
7. Cancel remaining provider jobs after a winner is accepted.
8. If all providers fail or the overall deadline expires, return a safe empty/unavailable result with sanitized diagnostics.

Do not treat a search result, chapter landing page, or unvalidated list of URLs as a successful winner. A winner must satisfy the Reading output contract.

Provider IDs and failed-source exclusions must be scoped to the reading domain. Anime provider IDs and failure state must not accidentally suppress manga/novel providers, and vice versa.

Provider adapters should be isolated behind a common contract. Adding a new provider must not require changes to reader UI contracts or Catalog persistence semantics.

## 7. Resolver and extractor boundary

Use site-specific adapters to discover chapter pages, then registered host resolvers/extractors to obtain page URLs. Avoid embedding site-specific parsing in API route handlers.

- Resolve relative URLs against the correct source page URI.
- Restrict schemes to HTTP/HTTPS.
- Validate every redirect hop and every final page URL.
- Apply DNS/IP protections at the actual connection boundary to mitigate SSRF and DNS rebinding.
- Strip authorization, cookies, and sensitive headers on cross-origin redirects.
- Apply request timeouts, response-size limits, bounded page counts, and overall runtime deadlines.
- Propagate coroutine cancellation; do not swallow cancellation as an ordinary provider failure.
- Sanitize exceptions and diagnostics; do not return credentials, cookies, full sensitive URLs, or upstream HTML to clients.
- Validate extracted image URLs as rigorously as Catalog source URLs.
- Keep runtime HTTP hardening consistent with Catalog transport hardening; do not assume URL pre-validation alone is sufficient.

The backend should avoid implementing any bypass of access controls or authentication on source websites. Only integrate sources that the project is permitted to access.

## 8. Source health and recovery

Track health per Reading Catalog source, not only per title. A source should become unhealthy based on explicit, observable failures, not merely because a provider returned no matching chapter.

Suggested outcomes:

- `HEALTHY`: valid chapter/page payload
- `EMPTY`: request succeeded but no matching chapter/page exists
- `EXPIRED`: source URL or signed link is no longer usable
- `FAILED`: request, parsing, validation, or extraction failure
- `DISABLED`: manually disabled by an administrator

Requirements:

- Respect `enabled=false` during selection.
- Define how `EXPIRED` and `FAILED` sources are retried and recovered.
- Keep health-check traffic bounded and separate from reader requests where possible.
- Log source mutation intent and completion with an operation ID.
- Do not automatically rewrite manual Catalog data without an auditable, approved repair flow.
- Provider runtime fallback must not silently convert discovered URLs into permanent Catalog entries.

## 9. API and client boundary

The backend should expose separate reading endpoints/contracts for:

- Searching reading titles by AniList metadata
- Reading-title detail
- Chapter list for an AniList media ID
- Resolving a selected chapter into ordered page references
- Optional source-health/admin operations under authorization

Exact endpoint paths must be reconciled with existing routes before implementation. Do not make the Android client scrape provider websites directly. Do not return a video `StreamRef` where the reader expects page references.

The client should receive a stable, provider-neutral reading contract. Provider names and internal extraction details should remain internal unless a specific admin/debug response explicitly requires them.

## 10. Non-goals

- Replacing AniList metadata with scraped provider metadata as the default.
- Storing title descriptions, covers, genres, or other metadata in Reading Catalog.
- Adding a user-facing provider selector.
- Reusing Anime video playback or Media3 classes for image/page reading.
- Treating a list of 100 adapter descriptors as 100 implemented adapters.
- Marking providers verified without live evidence.
- Automatically adding every discovered provider URL to the manual Catalog.
- Reintroducing legacy provider routing or mappings.

## 11. Required tests before implementation can be locked

### Metadata
- AniList manga search/detail success, valid empty results, upstream errors, GraphQL errors, malformed payloads, and timeouts.
- Correct mapping for manga/manhwa/manhua and supported novel formats.
- External metadata fallback does not overwrite or duplicate canonical identity.

### Catalog
- Manual source create/update/disable and persistence round-trip.
- `DIRECT`, `RESOLVER`, and `EXTRACTOR` source behavior.
- Disabled source is never selected.
- Expired/failed source is skipped or retried according to policy.
- Unsafe source, redirect, and extracted image URLs are rejected.
- Multiple sources fall through when the earlier source fails or returns no valid pages.

### Provider Runtime
- Providers race concurrently and the first valid result wins.
- Empty or malformed result does not win.
- Failure of one provider does not block other providers.
- Losing provider jobs are cancelled.
- Overall timeout cancels provider work.
- Failed-source exclusions are domain-scoped.
- Provider diagnostics are sanitized.

### Reader payload
- Page ordering is preserved.
- Relative image URLs resolve against the correct base URL.
- Empty/duplicate/malformed page lists are rejected.
- Large chapter page counts and payload sizes are bounded.
- Image request headers/referer are handled safely and do not leak cross-origin.

### API and security
- Route contracts are separate from Anime stream contracts.
- Authentication/authorization applies to admin Catalog mutations.
- SSRF, DNS rebinding, redirect chains, cross-origin header stripping, and timeout behavior are tested at the transport boundary.

## 12. Audit and rollout gates

Follow the project workflow:

**AUDIT → IMPLEMENT/FIX → TEST → RE-AUDIT → LOCK**

1. Audit existing Reading Catalog, metadata, API, runtime, and reader contracts on the target backend branch.
2. Map each requirement in this document to existing code; label each item `implemented`, `partial`, `missing`, or `not verified`.
3. Implement only confirmed gaps in small, reviewable commits.
4. Run focused tests and the required backend checks only when the user authorizes build/test work.
5. Re-audit the final commit and record evidence, test results, and remaining limitations.
6. Do not mark this architecture or any adapter as `LOCKED` without evidence tied to an exact commit.

## 13. Current status

This document records the **target architecture only**. No claim is made here that Reading Catalog, AniList reading metadata, 100+ providers, chapter extraction, or reader contracts have all been implemented or verified. The next step is a GitHub-only audit of the existing backend code against this contract.


## 14. Keiyoushi source inventory and adapter progress tracker

**Purpose:** Keep the candidate website list beside the Reading architecture so implementation progress can be checked off over time.

**Evidence and status rules**
- These are candidate sources gathered from the Keiyoushi extension index and an initial category pass. This is an inventory, **not proof that every site is live, legal to access, currently supported, or offers every listed content type/language**.
- Verify the actual catalogue and language of each site before implementation. Names in broad aggregator lists may cover manga, manhwa, and manhua together.
- `[ ]` = not yet verified/finished; change to `[x]` only when the corresponding site-language/content task has been audited and completed with evidence.
- Keep the status specific to each category/language. A site may appear in multiple lists, and completing its Indonesian manga adapter does not automatically complete its English manhwa support.
- Suggested completion evidence: adapter path + exact commit, search/title result, chapter list, resolved ordered pages, and passing focused tests. Do not count a descriptor-only entry as an implemented adapter.
- Suggested statuses for implementation notes: `INVENTORY`, `AUDIT_PENDING`, `IMPLEMENTING`, `IMPLEMENTED`, `TESTED`, `FAILED`, `UNAVAILABLE`, `DUPLICATE`. Update the checkbox only when the chosen completion gate is satisfied.

### 14.1 Manga — Indonesian

- [ ] Kiryuu
- [ ] KlikManga
- [ ] BacaKomik
- [ ] Bacami
- [ ] Komikindo
- [ ] KomikIndoID
- [ ] KomikNesia
- [ ] Komik Station
- [ ] Komiktap
- [ ] Komiku
- [ ] Komiku.com
- [ ] Komikmu
- [ ] Komikzoid
- [ ] Kuro Manga
- [ ] Mangalay
- [ ] Mangasusu
- [ ] Natsu
- [ ] NgamenKomik
- [ ] TheManga
- [ ] West Manga
- [ ] Shinigami
- [ ] LepoyTL
- [ ] Ryukomik
- [ ] Noromax

### 14.2 Manga — English

- [ ] MangaDex
- [ ] MangaFire
- [ ] MangaPill
- [ ] Mangakakalot
- [ ] Mangabat
- [ ] MangaKatana
- [ ] Mangahere
- [ ] MangaHub
- [ ] Manganato
- [ ] MangaGG
- [ ] MangaRead.org
- [ ] MangaReader.site
- [ ] Mangatown
- [ ] MangaNow
- [ ] MangaGeko
- [ ] MangaFox
- [ ] Mangasushi
- [ ] Manga.uno
- [ ] MangaBolt
- [ ] Manga Trend
- [ ] Weeb Central
- [ ] ComicK Fanmade
- [ ] Manga Demon
- [ ] Manga District
- [ ] MangaManiacs

### 14.3 Manhwa — Indonesian

- [ ] Manhwa Indo
- [ ] ManhwaLand.mom
- [ ] Manhwa List
- [ ] ManhwaDesu
- [ ] Komikhwa
- [ ] MG Komik
- [ ] LianScans
- [ ] Soul Scans
- [ ] Ainz Scans ID
- [ ] IsekaiKomik
- [ ] Ota Scans
- [ ] West Manga
- [ ] Komiku
- [ ] Komikindo
- [ ] KomikNesia
- [ ] Komik Station
- [ ] Komikmu
- [ ] Shinigami

### 14.4 Manhwa — English

- [ ] Asura Scans
- [ ] Aunt Manhwa
- [ ] ManhwaBuddy
- [ ] ManhwaGalaxy
- [ ] ManhwaHub.to
- [ ] ManhwaManhua
- [ ] ManhwaNex
- [ ] ManhwaOnline
- [ ] ManhwaRead
- [ ] Manhwa Reads
- [ ] Manhwa Toon
- [ ] ManhwaTop
- [ ] ManhwaUS
- [ ] ManhwaZ
- [ ] ManhwaZone
- [ ] New Manhwa
- [ ] MurimScan
- [ ] Hiperdex
- [ ] Manhwa68
- [ ] Manhwaddict
- [ ] ManhwaDen
- [ ] Manhwa18
- [ ] Manhwa18.today
- [ ] Manhwa18TX
- [ ] Manhwa Comics

### 14.5 Manhua — Indonesian

These are **verification candidates**, mostly general Indonesian comic aggregators; confirm that each actually has Indonesian-language manhua before treating it as a manhua source.

- [ ] Komiku
- [ ] Komiku.com
- [ ] Komikindo
- [ ] KomikIndoID
- [ ] KomikNesia
- [ ] Komik Station
- [ ] Komiktap
- [ ] Komikmu
- [ ] APKOMIK
- [ ] West Manga
- [ ] MG Komik
- [ ] NgamenKomik
- [ ] Shinigami
- [ ] Kiryuu
- [ ] KlikManga

### 14.6 Manhua — English

- [ ] ManhuaHot
- [ ] Manhuanext
- [ ] Manhua Plus
- [ ] ManhuaTop
- [ ] ManhuaUS
- [ ] Manhua Zonghe
- [ ] Top Manhua
- [ ] TopManhua.fan
- [ ] TopManhua.net
- [ ] ManhwaManhua
- [ ] CManhua
- [ ] Zinmanga
- [ ] MangaDex
- [ ] MangaFire
- [ ] MangaPill

### 14.7 Initial audit priority

This is a proposed audit order, not a verified availability ranking.

1. **Foundation / multilingual:** MangaDex.
2. **English manga:** MangaFire, MangaPill, Mangakakalot, MangaKatana.
3. **English manhwa:** Asura Scans, ManhwaBuddy, ManhwaGalaxy, ManhwaHub.to.
4. **Indonesian sources:** Kiryuu, KlikManga, Komiku, Komikindo, KomikNesia.
5. **English manhua:** ManhuaHot, Manhuanext, Manhua Plus, ManhuaTop.
6. **Cross-category candidates:** West Manga, MG Komik, Komik Station, Manhwa List.

### 14.8 Per-source work log template

Copy this template into an entry or linked audit note when work begins:

```text
Source:
Content category:
Language:
Keiyoushi extension/reference:
Shinigami adapter path:
Status: INVENTORY | AUDIT_PENDING | IMPLEMENTING | IMPLEMENTED | TESTED | FAILED | UNAVAILABLE | DUPLICATE
Last audited commit:
Evidence:
- Search/title lookup:
- Chapter listing:
- Ordered page extraction:
- Focused tests:
Known limitations:
```

Do not mark a source `TESTED` based on compilation alone. Confirm that the source can return the expected reading contract, or record the precise reason it is unavailable/unsupported. Re-audit source availability before implementation because third-party sites and extension definitions can change.


## 15. Novel source inventory — Indonesian and English

This inventory was researched against public GitHub source-code repositories, primarily [NovelSourcery/extensions-source](https://github.com/NovelSourcery/extensions-source) and [HnDK0/external-sources](https://github.com/HnDK0/external-sources). The repository directories contain source adapters for the named sites; that is evidence of a code/reference candidate, **not proof the live website currently works or that Shinigami has implemented an adapter**. Every item starts unchecked and must be audited individually.

### 15.1 Novel — Indonesian

Candidates found under NovelSourcery's `src/id` plus matching Indonesian novel plugins in NoveLA's external source repository:

- [ ] BacaLightNovel
- [ ] MeioNovel
- [ ] NovelBookID
- [ ] Sektenovel
- [ ] VaNovel
- [ ] WBNovel
- [ ] IndoWebNovel
- [ ] MeioNovels

References:
- [NovelSourcery Indonesian source directory](https://github.com/NovelSourcery/extensions-source/tree/main/src/id)
- [NoveLA Indonesian external sources](https://github.com/HnDK0/external-sources/tree/main/id)

### 15.2 Novel — English

Candidates found under NovelSourcery's `src/en`. The list is a source-code inventory, not a guarantee of current site availability. Some entries may be translation groups, community-fiction platforms, metadata/discovery tools, mirrors, or source duplicates; verify that a candidate actually provides chapter content before implementing it.

- [ ] AllNovelFull
- [ ] AsianNovel
- [ ] Atsumaru Novels
- [ ] Baka-Tsuki
- [ ] BotiTranslation
- [ ] BoxNovel
- [ ] Bright Novels
- [ ] ByteTranslations
- [ ] Chikari
- [ ] Citrus Aurora
- [ ] Crimson Scrolls
- [ ] Cyrisia
- [ ] Dao Translate
- [ ] Divine Dao Library
- [ ] Dragonholic
- [ ] DragonTea
- [ ] Dusk Blossoms
- [ ] EtuDe Translations
- [ ] FansMTL
- [ ] Fenrir Realm
- [ ] FictionZone
- [ ] Flame Comics
- [ ] Foxaholic
- [ ] Foxaholic18
- [ ] FreeGoodNovel
- [ ] FreeWebNovel
- [ ] FuckNovelpia
- [ ] Genesis Studio
- [ ] Greenz
- [ ] Helios Scans
- [ ] Hireath Translation
- [ ] Honeyfeed
- [ ] Indra Translations
- [ ] Inkitt
- [ ] Jade Scrolls
- [ ] Kat Reading Cafe
- [ ] KDT Novels
- [ ] Knoxt
- [ ] Konkon
- [ ] KuuPress
- [ ] Lazy Girl Translations
- [ ] Leaf Studio
- [ ] Libread
- [ ] Light Novel Heaven
- [ ] LightNovelTo
- [ ] Light Novel Translation
- [ ] LNCrawler
- [ ] LNMTL
- [ ] LNori
- [ ] LulloBox
- [ ] Lunox Scans
- [ ] MTLBooks
- [ ] MTLWuxia
- [ ] MVLEmpyr
- [ ] MZNovels
- [ ] NoBadNovel
- [ ] Noice Translations
- [ ] Novel Archive
- [ ] NovelBin.com
- [ ] NovelBin.net
- [ ] NovelBuddy
- [ ] NovelCool
- [ ] NovelDex
- [ ] NovelFire
- [ ] NovelFull
- [ ] NovelHall
- [ ] NovelHi
- [ ] NovelLib
- [ ] NovelLight
- [ ] NovelLib
- [ ] NovelLive
- [ ] NovelLunar
- [ ] NovelNinja
- [ ] NovelPhoenix.net
- [ ] NovelPing
- [ ] NovelsHaven
- [ ] NovelsOnline
- [ ] Novels.pl
- [ ] NovelUpdates
- [ ] Nyx Scans
- [ ] OpenQuill
- [ ] ORV Reader
- [ ] Pastel Tales
- [ ] PawRead
- [ ] Penguin Squad
- [ ] R18Novel
- [ ] Ranobes
- [ ] Ranovel
- [ ] ReadFrom.net
- [ ] ReadHive
- [ ] ReadNovelFull
- [ ] ReadNovelMTL
- [ ] Rechapters
- [ ] Reigokai Translations
- [ ] Requiem Translations
- [ ] Royal Road
- [ ] Sai no Kantei
- [ ] Scribble Hub
- [ ] Shanghai Fantasy
- [ ] Sleepy Translations
- [ ] SonicMTL
- [ ] Story Seedling
- [ ] Sufficient Velocity
- [ ] TomatoMTL
- [ ] Translatino
- [ ] TransWeaver
- [ ] Universal Novel
- [ ] Wattpad
- [ ] WebNovel
- [ ] WebNovel Translation
- [ ] We Tried TLs
- [ ] WoopRead
- [ ] WordExcerpt
- [ ] WuxiaBox
- [ ] WuxiaClick
- [ ] WuxiaDreams
- [ ] WuxiaSpace
- [ ] WuxiaWorld.eu
- [ ] WuxiaWorld.site
- [ ] XNovel
- [ ] Zetro Translation

Additional English-source candidates present in NoveLA's external source repository:
- [ ] AllNovel
- [ ] Asura Scans — verify that the adapter is for prose novels rather than comics before classifying it as a novel provider
- [ ] Chikari
- [ ] Empire Novel
- [ ] FAQWiki
- [ ] FictionZone
- [ ] FreeWebNovel
- [ ] LightNovelPub
- [ ] NovelBin (compare with NovelSourcery's NovelBin.com / NovelBin.net entries; deduplicate by canonical domain)

Reference: [NoveLA English external sources](https://github.com/HnDK0/external-sources/tree/main/en).

### 15.3 How to audit and count novel adapters

For each candidate, record:
- canonical domain and any alternate domains;
- content type: original novel, translated novel, light novel, community fiction, or metadata/discovery only;
- language of chapter text (not merely UI language);
- search/title lookup, chapter list, and ordered text extraction evidence;
- legal/access constraints, robots/rate limits, anti-bot behavior, redirects, and SSRF-safe fetch behavior;
- duplicate relationship with another adapter;
- Shinigami adapter path, last audited commit, and test evidence.

Status: `INVENTORY` → `AUDIT_PENDING` → `IMPLEMENTING` → `IMPLEMENTED` → `TESTED`; use `FAILED`, `UNAVAILABLE`, or `DUPLICATE` with evidence when appropriate. Do not count a checkbox, extension descriptor, repository name, or copied implementation as a completed adapter. A novel source counts as implemented only after Shinigami's own adapter supports the Reading Catalog/provider contract; it counts as tested only after focused evidence for search, chapter ordering, and text extraction is recorded.


## 16. Novel source freshness review and implementation priority

Reviewed GitHub source history on **2026-10-29**. Prioritize sources with recent source-adapter fixes/additions, not merely large inventories. Git commit activity is a useful maintenance signal, but it does **not** prove that a site publishes new chapters frequently; chapter-release cadence must be checked separately on the live site.

### 16.1 Indonesian — highest audit priority

1. **Grensia** — recently added to LNReader's Indonesian plugins on 2026-10-17. Audit live search, latest chapter timestamps, and chapter text before implementation.
2. **IndoWebNovel** — chapter-list/text recovery was committed 2026-09-24 and chapter HTML sanitization/legacy markup handling on 2026-10-19. Strong maintenance signal; verify live content cadence and current domain.
3. **BacaLightNovel, MeioNovel, NovelBookID, Sektenovel, VaNovel, WBNovel** — present in NovelSourcery's Indonesian source directory, but the recent directory history is dominated by shared/bulk merges rather than clear source-specific fixes. Keep as second-wave candidates until source-specific activity and live update cadence are verified.
4. **SakuraNovel** — LNReader source exists, but its latest source-specific history is older (2025-10-20). Lower priority unless live chapter cadence or current community evidence justifies it.
5. **NovelRingan** — its LNReader filename is explicitly marked `broken`; do not prioritize unless repaired and re-verified.

References:
- [LNReader Indonesian plugins](https://github.com/LNReader/lnreader-plugins/tree/master/plugins/indonesian)
- [Grensia source history](https://github.com/LNReader/lnreader-plugins/commits/master/plugins/indonesian/grensia.ts)
- [IndoWebNovel source history](https://github.com/LNReader/lnreader-plugins/commits/master/plugins/indonesian/indowebnovel.ts)
- [NovelSourcery Indonesian sources](https://github.com/NovelSourcery/extensions-source/tree/main/src/id)

### 16.2 English — highest audit priority

1. **Royal Road** — active maintenance through 2026-10-28, including Anubis bot-check and chapter-list handling fixes. Strong candidate for ongoing maintenance, but bot protection must be handled within access rules; do not attempt to bypass access controls.
2. **Novel Bin / NovelBin.xyz** — source updated 2026-10-28 to use a standalone JSON API plugin. Audit the canonical domain and API contract before implementation; avoid duplicate NovelBin adapters for alternate domains.
3. **WebNovel** — plugin fixes on 2026-10-26 for chapter dates/statistics and ongoing status; also had search fixes in September 2026.
4. **NovelBuddy** — search results/query-length fix on 2026-10-19 and domain update in July 2026.
5. **NovelSourcery English source pool** — the repository has multiple source additions/fixes on 2026-10-27 and 2026-10-28, including FreeWebNovel, ByteTranslations, JadeScrolls, IndraTranslations, FenrirRealm, and NoiceTranslations. Audit these as a second source pool, checking whether each contains actual novel chapter text and whether the content language matches the target.
6. **NovelFire / NovelPhoenix** — source changes were seen in August–October 2026, but confirm current branding/domain and chapter cadence before selecting.
7. **Scribble Hub and other community-fiction platforms** — useful for original/community fiction but classify separately from translated light novels; verify update cadence per title.

References:
- [LNReader English plugins](https://github.com/LNReader/lnreader-plugins/tree/master/plugins/english)
- [Royal Road source history](https://github.com/LNReader/lnreader-plugins/commits/master/plugins/english/royalroad.ts)
- [Novel Bin source history](https://github.com/LNReader/lnreader-plugins/commits/master/plugins/english/novelbin.ts)
- [WebNovel source history](https://github.com/LNReader/lnreader-plugins/commits/master/plugins/english/webnovel.ts)
- [NovelBuddy source history](https://github.com/LNReader/lnreader-plugins/commits/master/plugins/english/novelbuddy.ts)
- [NovelSourcery English sources](https://github.com/NovelSourcery/extensions-source/tree/main/src/en)

### 16.3 Evidence required before a source is called “frequently updated”

For each shortlisted site, record separate measurements:
- **Adapter maintenance:** date and substance of latest source-code fix/addition.
- **Live publication cadence:** inspect a sample of the latest 10–20 chapter entries; record their publication dates and count updates across at least 7 days where feasible.
- **Content correctness:** language of actual chapter text, not just site UI; novel vs comic classification; chapter order and duplicate chapters.
- **Reliability/access:** successful search, chapter list and text fetch; redirects, rate limits, bot challenges, login/paywall requirements.
- **Decision:** `PRIORITY_A`, `PRIORITY_B`, `LOW_ACTIVITY`, `BROKEN`, `DUPLICATE`, or `UNAVAILABLE`, with dated evidence.

Do not equate a repository's frequent commits with frequent chapter releases. Use source-code freshness to decide what to audit first, then use live publication evidence to decide which providers deserve implementation priority.

### 16.4 Additional source pool — WOX Tadami Extensions (2026-10-29)

Add **[WizardOfXerox/WOX-Tadami-Extensions](https://github.com/WizardOfXerox/WOX-Tadami-Extensions/tree/main/apks/novel)** as another discovery pool, alongside NovelSourcery, LNReader plugins, NoveLA, NovelScraper, IReader and Shosetsu. Its novel manifest is [index.json](https://github.com/WizardOfXerox/WOX-Tadami-Extensions/blob/main/apks/novel/index.json) and contains **192 entries total** across languages. The manifest lists **8 Indonesian** and **99 English** entries. These are *source descriptors*, not proof of site activity, unique canonical domains, or Shinigami-ready adapters.

Repository maintenance notes mention fixes for Novel Fire, NovelBin, Riwyat, Belle Repository, Genesis, SakuraNovel and Foxaholic. That is a useful adapter-maintenance signal, but not live chapter-cadence evidence. Before implementation, check canonical domain, chapter text language, actual novel content (not comics/index-only sites), duplicate domains/aliases, access restrictions, search, chapter order and recent chapter timestamps. Keep separate labels for translated light novels, original/community fiction, MTL and discovery/index sites.

#### Indonesian candidates from Tadami manifest (8)

- [ ] **Baca Light Novel** — https://bacalightnovel.co/ — ID: bacalightnovel
- [ ] **IndoWebNovel** — https://indowebnovel.id/ — ID: IDWN.id
- [ ] **MeioNovel** — https://meionovels.com/ — ID: meionovel
- [ ] **MTL Novel (ID)** — https://mtlnovel.me — ID: mtlnovel-id
- [ ] **SakuraNovel** — https://sakuranovel.id/ — ID: sakura.id
- [ ] **Sekte Novel** — https://sektenovel.web.id/ — ID: sektenovel
- [ ] **Vanovel** — https://vanovel.com/ — ID: morenovel
- [ ] **WBNovel** — https://wbnovel.com/ — ID: wbnovel

#### English candidates from Tadami manifest (99)

- [ ] **Akknovel** — https://www.akknovel.com/ — ID: akknovel
- [ ] **AllNovel** — https://allnovel.org/ — ID: allnovel
- [ ] **AllNovelFull** — https://novgo.net/ — ID: anf.net
- [ ] **Arcane Translations** — https://arcanetranslations.com/ — ID: arcane
- [ ] **Archive Of Our Own** — https://archiveofourown.org/ — ID: archiveofourown
- [ ] **BlumeVerse** — https://blume-verse.com/ — ID: blumeverse
- [ ] **BoxNovel** — https://novelnice.com/ — ID: boxnovel
- [ ] **Chrysanthemum Garden** — https://chrysanthemumgarden.com — ID: chrysanthemumgarden
- [ ] **Citrus Aurora** — https://citrusaurora.com/ — ID: citrusaurora
- [ ] **CPUnovel** — https://cpunovel.com/ — ID: cpunovel
- [ ] **Crimson Scrolls** — https://crimsonscrolls.net — ID: crimsonscrolls
- [ ] **Daoist Quest** — https://daoist.quest — ID: daoistquest
- [ ] **DaoTranslate** — https://daotranslate.com/ — ID: daotranslate
- [ ] **Divine Dao Library** — https://www.divinedaolibrary.com/ — ID: DDL.com
- [ ] **Dragon Tea** — https://dragontea.ink/ — ID: dragontea
- [ ] **Dragonholic** — https://dragonholic.com/ — ID: dragonholic
- [ ] **Dusk Blossoms** — https://duskblossoms.com/ — ID: duskblossoms
- [ ] **Eternalune** — https://eternalune.com/ — ID: eternalune
- [ ] **Etude Translations** — https://etudetranslations.com/ — ID: etudetranslations
- [ ] **FanNovel** — https://www.fanmtl.com — ID: fannovel
- [ ] **Fans MTL** — https://www.fanmtl.com — ID: wuxiamtl
- [ ] **Fenrir Realm** — https://fenrirealm.com — ID: fenrir
- [ ] **Fiction Zone** — https://fictionzone.net — ID: fictionzone
- [ ] **Foxaholic** — https://www.foxaholic.com/ — ID: foxaholic
- [ ] **Foxteller** — https://www.foxteller.com — ID: foxteller
- [ ] **Free Web Novel** — https://freewebnovel.com/ — ID: FWN.com
- [ ] **FuckNovelpia** — https://fucknovelpia.com/ — ID: FuckNovelpia
- [ ] **Galaxy Translations** — https://darkstartranslations.com/ — ID: galaxytranslations
- [ ] **Genesis** — https://genesistudio.com — ID: genesistudio
- [ ] **Guavaread** — https://guavaread.com/ — ID: guavaread
- [ ] **Hiraeth Translation** — https://hiraethtranslation.com/ — ID: hiraethtranslation
- [ ] **Hyacinth in Bloom** — https://hyacinthbloom.com/ — ID: hyacinthbloom
- [ ] **Indra Translations** — https://indratranslations.com — ID: indratranslations
- [ ] **Inkitt** — https://www.inkitt.com — ID: inkitt
- [ ] **iNovelTranslation** — https://inoveltranslation.com — ID: inoveltranslation
- [ ] **Ippotranslations** — https://ippotranslations.com/ — ID: ippotranslations
- [ ] **KDT Novels** — https://kdtnovels.net/ — ID: kdtnovels
- [ ] **Keopi Translations** — https://www.keopitranslations.com — ID: keopi
- [ ] **KnoxT** — https://knoxt.space/ — ID: knoxt
- [ ] **Lazy Girl Translations** — https://lazygirltranslations.com — ID: lazygirltranslations
- [ ] **Lib Read** — https://libread.com/ — ID: libread
- [ ] **Light Novel Translations** — https://lightnovelstranslations.com/ — ID: lightnoveltranslations
- [ ] **Light Novel Updates** — https://lightnovelupdates.com — ID: LightNovelUpdates
- [ ] **LightNovelCave** — https://www.lightnovelcave.com/ — ID: lightnovelcave
- [ ] **LightNovelHeaven** — https://lightnovelheaven.com/ — ID: lnheaven
- [ ] **LnMTL** — https://lnmtl.com/ — ID: lnmtl
- [ ] **LNORI** — https://lnori.com/ — ID: lnori
- [ ] **LulloBox** — https://lullobox.com/ — ID: lullobox
- [ ] **Mistmint Haven** — https://www.mistminthaven.com/ — ID: mistminthaven
- [ ] **MTL Novel** — https://mtlnovel.me — ID: mtlnovel
- [ ] **MVLEMPYR** — https://www.mvlempyr.io/ — ID: mvlempyr.com
- [ ] **NobleMTL** — https://noblemtl.com/ — ID: noblemtl
- [ ] **Noice Translations** — https://noicetranslations.com/ — ID: noicetranslations
- [ ] **Novel Arrow** — https://novelarrow.com/ — ID: novelarrow
- [ ] **Novel Fire** — https://novelfire.net/ — ID: novelfire
- [ ] **Novel Hall** — https://novelhall.com/ — ID: novelhall
- [ ] **Novel Ninja** — https://novelninja.xyz/ — ID: novelninja
- [ ] **Novel Updates** — https://www.novelupdates.com/ — ID: novelupdates
- [ ] **NovelBuddy** — https://novelbuddy.me/ — ID: novelbuddy
- [ ] **NovelCool** — https://www.novelcool.com — ID: novelcool
- [ ] **NovelFull** — https://novelfull.com/ — ID: novelfull
- [ ] **NovelHi** — https://novelhi.com/ — ID: novelhi
- [ ] **Novelight** — https://novelight.net/ — ID: novelight
- [ ] **NovelLib** — https://novelib.com — ID: novelib
- [ ] **NovelRest** — https://novelrest.vercel.app — ID: novelrest
- [ ] **Ocean Tales** — https://oceantales.com/ — ID: oceantales
- [ ] **Pastel Tales** — https://pasteltales.com/ — ID: pasteltales
- [ ] **PawRead** — https://m.pawread.com/ — ID: pawread
- [ ] **Penguin Squad** — https://penguin-squad.com — ID: penguinsquad
- [ ] **Ranobes** — https://ranobes.top — ID: ranobes
- [ ] **Ranovel** — https://ranovel.com/ — ID: ranovel
- [ ] **Re:Library** — https://re-library.com — ID: ReLib
- [ ] **Read From Net** — https://readfrom.net/ — ID: readfrom
- [ ] **ReadNovelFull** — https://readnovelfull.com/ — ID: readnovelfull
- [ ] **Requiem Translations** — https://requiemtls.com/ — ID: requiemtls
- [ ] **Royal Road** — https://www.royalroad.com/ — ID: royalroad
- [ ] **Scribble Hub** — https://www.scribblehub.com/ — ID: scribblehub
- [ ] **SleepyTranslations** — https://sleepytranslations.com/ — ID: sleeptTLS
- [ ] **SonicMTL** — https://www.sonicmtl.com/ — ID: sonicmtl
- [ ] **Srank Manga** — https://srankmanga.com/ — ID: asuralightnovel
- [ ] **StorySeedling** — https://storyseedling.com/ — ID: storyseedling
- [ ] **Sweet Escape** — https://sweetescapetranslations.com/ — ID: sweetescape
- [ ] **TranslatinOtaku** — https://translatinotaku.net/ — ID: translatinotaku
- [ ] **Translation Weaver** — https://transweaver.com/ — ID: transweaver
- [ ] **Universal Novel** — https://universalnovel.com/ — ID: universalnovel
- [ ] **Vandy Translate** — https://vandytranslate.com — ID: vandytranslate
- [ ] **Web Novel Translation** — https://wntl.net — ID: WebNovelTraslation
- [ ] **Webnovel** — https://www.webnovel.com — ID: webnovel
- [ ] **Webnovel (Unlocked)** — https://www.webnovel.com — ID: webnovelunlocked
- [ ] **White Moonlight Novels** — https://whitemoonlightnovels.com/ — ID: whitemoonlightnovels
- [ ] **Witch Cult Translations** — https://witchculttranslation.com — ID: witchculttranslations
- [ ] **Wook's Teahouse** — https://wooksteahouse.com/ — ID: wooksteahouse
- [ ] **WTR-LAB** — https://wtr-lab.com/ — ID: WTRLAB
- [ ] **Wuxia Space** — https://www.wuxiaspot.com — ID: wuxiaspace
- [ ] **Wuxia World** — https://www.wuxiaworld.com/ — ID: wuxiaworld
- [ ] **Wuxiabox** — https://www.wuxiabox.com — ID: wuxiap
- [ ] **Wuxiafox** — https://www.wuxiafox.com — ID: wuxiacity
- [ ] **WuxiaWorld.Site** — https://wuxiaworld.site/ — ID: wuxiaworld.site
- [ ] **Zetro Translation** — https://zetrotranslation.com/ — ID: zetroTL

#### Merge/deduplication rules

- Treat these as **discovery candidates** until live validation; do not mark implemented or frequently updated just because the manifest contains them.
- Deduplicate by canonical site/domain and actual backend implementation, not plugin name. For example, multiple aliases may point to the same site, and one domain may appear in more than one language.
- Archive Of Our Own, Inkitt, Royal Road, Scribble Hub, and similar community-fiction sites should be classified separately from translated light novels.
- Novel Updates is primarily a discovery/index site, and Srank Manga needs media-type verification; neither should be assumed to provide normal novel chapter text.
- Prioritize sources with recent source-specific maintenance **and** recent live chapter updates. Track those signals independently per section 16.3.

## 17. Additional live-site candidates — Indonesian and English (2026-10-29)

This section adds candidates found by checking live public site pages alongside the extension/source inventories above. Live homepage visibility and recent chapter timestamps are preliminary evidence only: they do not establish permission to scrape, stable chapter extraction, uniqueness of the source, or Shinigami implementation status. All entries remain `INVENTORY` / `AUDIT_PENDING` until the per-source audit in §15.3 and §16.3 is recorded.

### 17.1 Indonesian-language novel sites — additional candidates

| Site | Canonical URL | Preliminary evidence observed | Initial classification / follow-up |
|---|---|---|---|
| BacaLN | https://bacaln.com/ | Homepage displayed several Indonesian chapter entries dated 28 Oct 2026, including *Supreme Sword God* Ch. 621 and *The Quest for Immortality* Ch. 1243. | `AUDIT_PENDING`. Compare canonical domains and catalogue overlap against Baca Light Novel at `bacalightnovel.co`; do not assume they are the same source or independent coverage until checked. |
| Idnovelland / Idnoveland | https://idnoveland.my.id/ | Homepage displayed chapter updates with relative ages of about 4, 10, 20 and 23 hours, and entries dated 24–27 Oct 2026. | `AUDIT_PENDING`. Verify canonical spelling/domain, redirects, prior `idnovelland.com` links, chapter text language, and duplicates. |
| LexiNovel | https://lexinovel.web.id/ | Homepage identifies itself as an Indonesian light-novel/web-novel translation catalogue and exposes a latest-updates section. | `AUDIT_PENDING`. Inspect title search, chapter pagination, actual chapter text, domain stability, and release cadence over multiple days. |
| Ranahnovel | https://ranahnovel.com/ | Homepage advertises translated web/light novels across Chinese cultivation, Korean murim, and Western fantasy; latest-chapter entries were visible. Its About page describes AI-assisted translation with per-novel glossaries and a DMCA/takedown contact process. | `AUDIT_PENDING`. Review site terms and rights/access constraints; check actual chapter extraction and distinguish translated text from source metadata. |
| BUBOC | https://buboc.com/ | Homepage reported 763 novels and 250.1K chapters, with a latest-release section. | `AUDIT_PENDING`. Catalogue totals are site-reported, not independently validated. Check chapter text quality, unique coverage, canonical links, and permitted access before prioritizing. |
| KimiNovel | https://www.kiminovel.com/ | Site About information describes Indonesian fan translations of Japanese, Mandarin, and English light novels and identifies the project as dating back to 2014. | `AUDIT_PENDING` / activity not yet established. Verify current chapter dates and recent release cadence before assigning implementation priority. |

These sites are additions to the candidate inventory, not replacements for the existing Indonesian extension list in §15.1. In particular, a website discovered through live browsing is not automatically represented by an LNReader, NovelSourcery, Tadami, or NoveLA extension.

### 17.2 English-language novel sites — priority candidates and classification

The sites below already appear in the broader English inventory in §15.2 and/or the Tadami list in §16.4. They are highlighted here because source-code maintenance was reported in the reviewed inventories; recheck both repository history and live-site behavior before implementation.

| Site | Canonical URL | Preliminary signal | Initial classification / follow-up |
|---|---|---|---|
| Royal Road | https://www.royalroad.com/ | English original/community fiction platform; source history in the LNReader plugin repository was reported active through 28 Oct 2026. | `AUDIT_PENDING`. Classify separately from translated light novels. Bot-check/access controls must not be bypassed. |
| NovelBin | https://novelbin.com/ | NovelBin source maintenance was reported on 28 Oct 2026, including a move toward a standalone JSON API plugin; aliases and mirrors appear in source inventories. | `AUDIT_PENDING`. Confirm canonical domain and API terms, and deduplicate aliases such as NovelBin.com / NovelBin.net / NovelBin.xyz before counting adapters. |
| WebNovel | https://www.webnovel.com/ | Source fixes were reported on 26 Oct 2026 for chapter date/statistics and ongoing-status handling, with earlier search fixes in Sep 2026. | `AUDIT_PENDING`. Check access restrictions, login/paywall behavior, permitted access, and whether each target chapter is readable without bypassing controls. |
| NovelBuddy | https://novelbuddy.me/ | Source search/query-length fix was reported on 19 Oct 2026 and a domain change in Jul 2026. | `AUDIT_PENDING`. Verify canonical domain, live search/chapter retrieval, and duplicate coverage. |
| FreeWebNovel | https://freewebnovel.com/ | Listed in the NovelSourcery and Tadami source inventories; source-pool changes were reported in late Oct 2026. | `AUDIT_PENDING`. Verify source-specific changes, actual chapter content, domain redirects, and release cadence. |
| NovelFire | https://novelfire.net/ | Source changes were reported in Aug–Oct 2026 in the reviewed inventories. | `AUDIT_PENDING`. Confirm current canonical domain, chapter extraction, and whether another alias maps to the same site. |
| NovelPhoenix | https://novelphoenix.com/ | A source entry/change was reported in the broader extension inventory during 2026; current canonical availability is not yet established by this review. | `AUDIT_PENDING`. Resolve the current canonical domain before implementation; do not infer availability from a descriptor alone. |
| Scribble Hub | https://www.scribblehub.com/ | Community-fiction platform included in the English source inventory. | `AUDIT_PENDING`. Keep in a separate original/community-fiction category; check terms, API/robots guidance, and chapter order. |

### 17.3 Research references

**Live Indonesian sites**
- BacaLN homepage and latest chapter entries: https://bacaln.com/
- Idnovelland / Idnoveland homepage and latest updates: https://idnoveland.my.id/
- LexiNovel homepage and latest-updates section: https://lexinovel.web.id/
- Ranahnovel homepage: https://ranahnovel.com/
- Ranahnovel About, translation approach and takedown information: https://ranahnovel.com/tentang
- BUBOC homepage and reported catalogue totals: https://buboc.com/
- KimiNovel: https://www.kiminovel.com/

**Extension inventories and source-code history**
- LNReader official plugin directory (reports the overall plugin/language inventory): https://www.lnreader.app/plugins
- LNReader Indonesian plugins: https://github.com/LNReader/lnreader-plugins/tree/master/plugins/indonesian
- LNReader English plugins: https://github.com/LNReader/lnreader-plugins/tree/master/plugins/english
- NovelSourcery source code — Indonesian sources: https://github.com/NovelSourcery/extensions-source/tree/main/src/id
- NovelSourcery source code — English sources: https://github.com/NovelSourcery/extensions-source/tree/main/src/en
- NovelSourcery source repository and license/disclaimer: https://github.com/NovelSourcery/extensions-source
- NoveLA external sources — Indonesian: https://github.com/HnDK0/external-sources/tree/main/id
- NoveLA external sources — English: https://github.com/HnDK0/external-sources/tree/main/en
- WOX Tadami novel extensions: https://github.com/WizardOfXerox/WOX-Tadami-Extensions/tree/main/apks/novel
- WOX Tadami novel manifest: https://github.com/WizardOfXerox/WOX-Tadami-Extensions/blob/main/apks/novel/index.json
- LNReader Grensia source history: https://github.com/LNReader/lnreader-plugins/commits/master/plugins/indonesian/grensia.ts
- LNReader IndoWebNovel source history: https://github.com/LNReader/lnreader-plugins/commits/master/plugins/indonesian/indowebnovel.ts
- LNReader Royal Road source history: https://github.com/LNReader/lnreader-plugins/commits/master/plugins/english/royalroad.ts
- LNReader NovelBin source history: https://github.com/LNReader/lnreader-plugins/commits/master/plugins/english/novelbin.ts
- LNReader WebNovel source history: https://github.com/LNReader/lnreader-plugins/commits/master/plugins/english/webnovel.ts
- LNReader NovelBuddy source history: https://github.com/LNReader/lnreader-plugins/commits/master/plugins/english/novelbuddy.ts

**Research caveat:** Website content, redirects, extension code, licensing and anti-bot behavior can change. The timestamps above are observations from the research pass dated 2026-10-29, not a continuing availability guarantee. Recheck them when an adapter is selected. Do not bypass authentication, paywalls, bot challenges, or other access controls; record a source as `UNAVAILABLE` when it cannot be accessed within permitted boundaries.

## 18. Consolidated novel-source inventory — user-provided full list (2026-10-29)

This section preserves the complete source-name inventory supplied for Shinigami research. It complements Section 17's live-site candidates and the repository-derived inventories above; it does not replace them. A source name in this list is an inventory record only, **not** proof of current availability, permission to ingest, a unique domain, or a working Shinigami adapter.

### 18.1 Indonesian novel sources — core list (8)

1. Baca Light Novel
2. IndoWebNovel
3. MeioNovel
4. MTL Novel (ID)
5. SakuraNovel
6. Sekte Novel
7. Vanovel
8. WBNovel

### 18.2 Indonesian plugin inventory — repository cross-reference (10)

The earlier manifest review reported 10 plugins explicitly labelled Indonesian in LNReader. Keep these as plugin/source records and deduplicate by canonical website only after inspecting each plugin's URL and redirects.

| No. | Plugin/source name | Reported repository presence |
|---:|---|---|
| 1 | Baca Light Novel | LNReader, NovelSourcery, WOX Tadami, NoveLA |
| 2 | Grensia | LNReader |
| 3 | IndoWebNovel | LNReader, WOX Tadami, NoveLA |
| 4 | MeioNovel | LNReader, NovelSourcery, WOX Tadami, NoveLA |
| 5 | MTL Novel (ID) | LNReader, WOX Tadami |
| 6 | NovelBookID | LNReader, NovelSourcery |
| 7 | SakuraNovel | LNReader, WOX Tadami |
| 8 | Sekte Novel | LNReader, NovelSourcery, WOX Tadami |
| 9 | Vanovel | LNReader, NovelSourcery, WOX Tadami |
| 10 | WBNovel | LNReader, NovelSourcery, WOX Tadami |

Additional Indonesian research candidates (not counted as verified active sources): **NovelRingan** (previously reported broken plugin; repair candidate only), **WTR-LAB** (test whether its Indonesian path genuinely serves Indonesian-language novel content), and **Legado** (ecosystem/directory for finding source definitions; no new specific Indonesian source confirmed by this inventory alone). Section 17 also records the additional live-site candidates **BacaLN, Idnovelland/Idnoveland, LexiNovel, Ranahnovel, BUBOC, and KimiNovel**. Do not merge similarly named sites without verifying canonical URLs.

### 18.3 English novel sources — full user-provided list (99)

The numbering below preserves the supplied list, including distinct product variants and similarly named sites. Treat each as a candidate record pending URL resolution, activity and access-policy review.

1. Akknovel
2. AllNovel
3. AllNovelFull
4. Arcane Translations
5. Archive Of Our Own (AO3)
6. BlumeVerse
7. BoxNovel
8. CPUnovel
9. Chrysanthemum Garden
10. Citrus Aurora
11. Crimson Scrolls
12. Daoist Quest
13. DaoTranslate
14. Divine Dao Library
15. Dragon Tea
16. Dragonholic
17. Dusk Blossoms
18. Eternalune
19. Etude Translations
20. FanNovel
21. Fans MTL
22. Fenrir Realm
23. Fiction Zone
24. Foxaholic
25. Foxteller
26. Free Web Novel
27. FuckNovelpia
28. Galaxy Translations
29. Genesis
30. Guavaread
31. Hiraeth Translation
32. Hyacinth in Bloom
33. Indra Translations
34. Inkitt
35. iNovelTranslation
36. Ippotranslations
37. KDT Novels
38. Keopi Translations
39. KnoxT
40. Lazy Girl Translations
41. Lib Read
42. Light Novel Translations
43. Light Novel Updates
44. LightNovelCave
45. LightNovelHeaven
46. LnMTL
47. LNORI
48. LulloBox
49. Mistmint Haven
50. MTL Novel
51. MVLEMPYR
52. NobleMTL
53. Noice Translations
54. Novel Arrow
55. Novel Fire
56. Novel Hall
57. Novel Ninja
58. Novel Updates
59. NovelBuddy
60. NovelCool
61. NovelFull
62. NovelHi
63. Novelight
64. NovelLib
65. NovelRest
66. Ocean Tales
67. Pastel Tales
68. PawRead
69. Penguin Squad
70. Ranobes
71. Ranovel
72. Re:Library
73. Read From Net
74. ReadNovelFull
75. Requiem Translations
76. Royal Road
77. Scribble Hub
78. SleepyTranslations
79. SonicMTL
80. Srank Manga
81. StorySeedling
82. Sweet Escape
83. TranslatinOtaku
84. Translation Weaver
85. Universal Novel
86. Vandy Translate
87. Web Novel Translation
88. Webnovel
89. Webnovel (Unlocked)
90. White Moonlight Novels
91. Witch Cult Translations
92. Wook's Teahouse
93. WTR-LAB
94. Wuxia Space
95. Wuxia World
96. Wuxiabox
97. Wuxiafox
98. WuxiaWorld.Site
99. Zetro Translation

### 18.4 Inventory counts and normalization rules

- User-provided baseline: **8 Indonesian sources** and **99 English sources**.
- Repository-derived Indonesian plugin list above: **10 plugin records**. These are not additive to the baseline count without checking overlap; several are the same sources represented in different extension repositories.
- Section 17's six additional Indonesian live-site candidates and the English candidates highlighted there remain supplementary candidates, not silently folded into the 8/99 baseline.
- Preserve the supplied English labels as written until canonical URL/domain checks are completed. Similar names (for example, Novel Fire / NovelFire, Free Web Novel / FreeWebNovel, Webnovel / Webnovel (Unlocked), Wuxia World / WuxiaWorld.Site) may be aliases, variants, or distinct services; do not deduplicate by name alone.
- Classify platforms and indexes separately from chapter-hosting sources where appropriate (for example, Light Novel Updates, AO3, Royal Road, Scribble Hub, Inkitt). Their inclusion in the inventory does not imply that they should all use the same adapter type.
- Before implementation, audit canonical domain and redirects, language, search and pagination, title/detail/chapter navigation, chapter ordering and content, recent chapter cadence, duplicate coverage, applicable terms/licensing, and permitted access. Never bypass login, paywalls, bot challenges, or other access controls.
- Status for every item in this section: `INVENTORY` / `AUDIT_PENDING` unless a separate evidence-backed audit record explicitly changes it. A plugin entry or repository presence is not proof of a live, maintained website or a completed Shinigami adapter.

## 19. Mandatory adapter implementation rules — site-specific and language-separated

These are binding implementation rules for all Reading Catalog novel-source work. They apply to every source in Sections 15–18 and any source added later.

### 19.1 One dedicated adapter per website

- **Every website must have its own site-specific adapter.** Do not implement one generic novel adapter that attempts to support multiple unrelated sites through shared selectors, domain switches, a large conditional tree, or configuration-only source definitions.
- The adapter must encode that website's own behavior: canonical base URL, search request/response format, pagination, title/detail parsing, chapter-list navigation and ordering, chapter-content extraction, URL normalization, request headers that are legitimately required, and site-specific error/empty-state handling.
- Shared infrastructure is allowed only for genuinely cross-cutting concerns such as HTTP transport, rate limiting, retry/backoff, caching, common domain models, telemetry, and test fixtures. Shared infrastructure must not replace each website's dedicated parsing/navigation implementation.
- If multiple hostnames are confirmed to be aliases for the same service, document the evidence and canonicalization before treating them as one adapter. If sites merely have similar names, themes, or CMS software, keep separate adapters.
- A website-specific adapter must fail clearly when its expected page structure or response contract changes. Do not silently fall back to generic selectors that may return incorrect titles or chapter text.
- Each adapter must have its own unit/fixture tests and an explicit live smoke-test record where permitted. A descriptor, manifest entry, or shared generic parser is not an implemented adapter.

### 19.2 Strict language separation: Indonesian vs English

- Keep Indonesian and English novel sources in separate implementation groups, packages/directories, registries, progress tables, and audit batches. Do not mix them into a single undifferentiated source list or completion counter.
- Recommended grouping:
  - `reading/novel/id/` — Indonesian-language source adapters.
  - `reading/novel/en/` — English-language source adapters.
  - Shared domain contracts and infrastructure may live outside these groups.
- Use the source's **verified chapter-content language**, not its display name, repository folder, or URL alone, to assign language. If a site hosts multiple languages, register each language-specific endpoint/experience separately when its content and behavior differ; document any justified exception explicitly.
- Keep separate Indonesian and English adapter registries and report progress separately: inventory, audited, implementation started, implemented, fixture-tested, live-verified, unavailable, and blocked. Never count a source in both language totals unless the two language experiences are separately verified and registered.
- For a mixed-language site such as a site whose language label conflicts with a path or plugin manifest, set status to `LANGUAGE_UNVERIFIED` until the actual chapter text is checked.

### 19.3 Required adapter contract and evidence

Each dedicated adapter must implement and test the applicable operations in the Reading runtime contract:
1. Search titles and handle empty queries/results and pagination.
2. Parse a title/detail page and stable source identity.
3. Enumerate chapters with correct ordering, labels, URLs, and duplicate handling.
4. Extract chapter content accurately and detect empty, blocked, malformed, or changed pages.
5. Normalize and validate URLs; constrain redirects and outbound requests to permitted public destinations.
6. Apply bounded timeouts, retries/backoff, rate limits, and actionable error categories.
7. Include fixture-based tests for normal pages, missing fields, empty results, changed markup/API, duplicate chapters, and network/HTTP failures.
8. Record live verification date, tested functions, observed language, canonical URL/redirects, access limitations, and relevant terms/licensing review. Live tests must stay within authorized access; do not bypass authentication, paywalls, bot challenges, or other access controls.

### 19.4 Implementation workflow and lock criteria

For each language group, process sources in batches while preserving one adapter per site:
`INVENTORY → AUDIT → IMPLEMENT SITE-SPECIFIC ADAPTER → FIXTURE TEST → LIVE SMOKE TEST (where permitted) → RE-AUDIT → LOCK`.

- Do not mark an adapter `LOCKED` unless source-specific tests and evidence are recorded.
- Do not label a source `IMPLEMENTED` solely because its name exists in a registry or it uses a shared generic parser.
- Keep failed, inactive, inaccessible, language-uncertain, and terms-uncertain sources explicitly marked as such; do not inflate implemented counts.
- Adapter names should identify the site (for example, `BacaLightNovelAdapter` or `RoyalRoadAdapter`), and reside in the correct language group. These are naming examples only, not claims that either adapter already exists.
- A website redesign should trigger tests and a re-audit for that site's adapter without silently changing unrelated site adapters.

## 20. Scope clarification — all reading content types

The mandatory site-specific adapter and language-separation rules in Section 19 apply to **all Reading Catalog content types**, not novels alone:

- Manga
- Manhwa
- Manhua
- Novels / light novels

### 20.1 Dedicated adapter per site and content type

- Every supported source website must have its own site-specific adapter for the content type and reading experience it serves. Do not use one generic adapter across unrelated manga, manhwa, manhua, or novel sites.
- A site that provides multiple content types may share only verified common infrastructure. Keep content-type-specific discovery, title/detail parsing, chapter navigation, page/image extraction, or text extraction explicit. If the site's manga and manhwa sections demonstrably use the same stable site contract, document the evidence and test each registered content type separately rather than assuming equivalence.
- For comics, the dedicated adapter must account for that site's chapter-page sequence, image URL extraction, ordering, lazy-loaded image data, and site-specific pagination where applicable. For novels, it must account for that site's chapter text and navigation.
- Keep source-specific adapters separate even if multiple sites use the same CMS or visually similar templates. Shared helpers may assist parsing but must not turn into a generic cross-site adapter.

### 20.2 Language separation for every content type

- Maintain separate Indonesian and English source adapter groups and registries for manga, manhwa, manhua, and novels. A recommended layout is:
  - `reading/manga/id/`, `reading/manga/en/`
  - `reading/manhwa/id/`, `reading/manhwa/en/`
  - `reading/manhua/id/`, `reading/manhua/en/`
  - `reading/novel/id/`, `reading/novel/en/`
- If project naming conventions group manga/manhwa/manhua under a shared comics module, preserve the same logical separation by content type and language in package/registry names and audit reports.
- Verify the actual language of chapter text or content metadata. Do not infer language from a site's name, top-level domain, or a plugin's label alone. Mark unresolved sources `LANGUAGE_UNVERIFIED`.
- Track inventory, audit, implementation, fixture-test, live-verification, unavailable, and blocked status separately for each content-type/language group.

### 20.3 Per-source validation and completion

- Each adapter must have its own fixtures and tests for its site's actual content contract. Comic tests must cover chapter ordering and page/image extraction; novel tests must cover chapter ordering and text extraction. Both must cover empty results, malformed or changed pages, duplicate content, invalid URLs, and network failures as applicable.
- Use the Section 19 workflow for each source: `INVENTORY → AUDIT → IMPLEMENT SITE-SPECIFIC ADAPTER → FIXTURE TEST → LIVE SMOKE TEST (where permitted) → RE-AUDIT → LOCK`.
- Never count a generic parser, source descriptor, registry entry, or unverified language label as a completed adapter. Never mark a source `LOCKED` without recorded source-specific evidence.
- These rules supplement existing catalog-first and metadata rules: metadata remains separate from Reading Catalog source URLs; do not put metadata into the Reading Catalog merely to support an adapter.

## 21. Mandatory language-batch workflow and persistent progress ledger

Adapter implementation must be planned and executed **in language batches**, not as a stop-start sequence that fully tests one adapter before beginning the next. This workflow applies to manga, manhwa, manhua, and novels.

### 21.1 Batch boundaries

- Keep Indonesian (`id`) and English (`en`) adapter work in separate batches and progress records for every content type.
- Default batch order:
  1. Manga — Indonesian
  2. Manga — English
  3. Manhwa — Indonesian
  4. Manhwa — English
  5. Manhua — Indonesian
  6. Manhua — English
  7. Novel — Indonesian
  8. Novel — English
- The order may be adjusted based on the audited inventory, dependencies, or source availability, but the reason and current batch must be recorded. Do not force a source into a language/content-type group it does not actually support.
- Complete the implementation pass for the current batch as a group. Do not require a full test run after each individual adapter. Commit coherent, reviewable implementation increments during the batch so progress is preserved and resumable.
- Once the batch's implementation pass is complete, perform a completeness audit and then run the planned test/verification pass for the batch. Fix failures and re-test before locking it. Testing may be deferred until the batch implementation pass is complete, but must not be silently skipped or reported as passed without evidence.

### 21.2 Required Markdown progress ledger

Maintain a persistent Markdown progress ledger in the repository, recommended path:

`docs/READING_ADAPTER_IMPLEMENTATION_PROGRESS.md`

Update it whenever a batch starts, an adapter changes status, a commit is made, a blocker is discovered, or a batch is handed off. The ledger must be the handoff source of truth so work can resume without re-auditing from scratch.

The ledger must contain:

- Current batch, content type, language, and next concrete action.
- The exact branch and last known commit SHA worked on.
- Overall counts per content type and language: inventoried, audited, implementation started, implemented, fixture-tested, live-verified, blocked, unavailable, and language-unverified.
- One row per source/adapter, with at least: source name, canonical URL/reference, content type, verified language, audit status, implementation status, fixture-test status, live-verification status/date, latest commit or change reference, blocker/next action, and notes/evidence.
- A list of adapters not started, partially implemented, missing source references, awaiting fixtures, awaiting testing, failed tests, and blocked/unavailable sources.
- A batch handoff section that states what was completed, what remains, relevant paths/commits, known risks, and the exact next step.
- A change log so status changes are traceable. Never replace a detailed status with a vague statement such as "batch in progress".

### 21.3 Status vocabulary and counting rules

Use explicit statuses rather than inferring completion from filenames or registry entries:

- `INVENTORIED` — source listed, not yet audited.
- `AUDIT_PENDING` / `AUDITED` — audit state is explicit and evidence is recorded.
- `NOT_STARTED` / `IN_PROGRESS` / `IMPLEMENTED_UNTESTED` — implementation state.
- `FIXTURE_TESTED` / `LIVE_VERIFIED` — only when the corresponding tests or permitted live checks actually passed, with evidence.
- `TEST_FAILED` — a test was run and failed; record the failure and next action.
- `BLOCKED` / `UNAVAILABLE` / `LANGUAGE_UNVERIFIED` — reason must be recorded.
- `LOCKED` — only after implementation, required tests, re-audit, and evidence meet the acceptance criteria.

An adapter may be counted as implemented before the batch test pass only if its site-specific implementation exists and has been reviewed for basic completeness; label it `IMPLEMENTED_UNTESTED` until testing evidence exists. Do not describe an untested batch as verified, production-ready, or locked.

### 21.4 Required batch cycle

For each content-type/language batch, use:

`INVENTORY/RESUME FROM LEDGER → AUDIT SOURCES → IMPLEMENT ALL FEASIBLE SITE-SPECIFIC ADAPTERS → COMMIT COHERENT INCREMENTS → UPDATE LEDGER → BATCH COMPLETENESS AUDIT → FIXTURE TESTS → LIVE SMOKE TESTS (WHERE PERMITTED) → FIX FAILURES AND RE-TEST → RE-AUDIT → LOCK ELIGIBLE ADAPTERS`

- Preserve useful prior audit work; consult the ledger and existing evidence instead of restarting from scratch.
- Do not block implementation of all remaining sources just because one site is inaccessible; record that source as blocked/unavailable and continue with other feasible adapters.
- Do not bypass authentication, paywalls, bot challenges, or other access controls to force a source to work.
- The ledger must be updated before ending or handing off a work session, even if the batch is incomplete.
- A commit is evidence of a code change, not evidence that tests passed. Record implementation and verification statuses independently.


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

# Reading Adapter User List Gap Audit — 2026-10-30

## Scope and method
Compared the user's requested site list against adapter source files under `src/main/kotlin/streamix/readingcatalog/reading/**` and the provider registry in `src/main/kotlin/streamix/api/BackendMain.kt`. This is a static source/registration audit, not a claim that each site's live HTML/API currently works. A provider for one `ReadingKind` does not count as coverage for another kind when its `supports`/chapter path explicitly rejects that kind.

## Confirmed gaps: requested site + content kind does not have a dedicated provider

### Manga — Bahasa Indonesia
- **Komiku.com** — the Manga `KomikuReadingRuntimeProvider` accepts the `komiku.org`, `komikid.org`, and `komiku.to` family, not the separately listed `komiku.com` host. Existing `KomikuComManhuaReadingRuntimeProvider` is MANHUA-only and is not a Manga adapter.

### Manhwa — Bahasa Indonesia
- **Komiku** — existing Komiku provider is MANGA-only; the Komiku Manhua provider is MANHUA-only. No MANHWA-specific provider.
- **Komikindo** — existing provider is MANGA-only and its Manhua counterpart is MANHUA-only. No MANHWA-specific provider.
- **KomikNesia** — existing provider is MANGA-only. No MANHWA-specific provider.
- **West Manga** — existing providers cover MANGA and MANHUA, but no MANHWA-specific provider.
- **MG Komik** — existing providers cover MANGA and MANHUA, but no MANHWA-specific provider.
- **Shinigami** — current native adapter explicitly accepts MANGA only; no MANHWA-specific provider.

### Manhua — Bahasa Indonesia
- **Shinigami** — current native adapter explicitly accepts MANGA only; no MANHUA-specific provider.

## Listed names that should not be treated as missing chapter-reader adapters without a product decision
- **Novel Updates** and **Light Novel Updates** are index/discovery sites, not ordinary chapter-text readers. Their absence is not automatically a missing chapter adapter; add them only if a separate discovery/index integration is intended.
- **Webnovel (Unlocked)** is not a separate site/domain from Webnovel, so it should not produce a duplicate site adapter.
- **Srank Manga** appears in the user's Novel English list but is a manga source name; confirm intended content kind before adding it as a novel reader.

## Name mappings found during the comparison
- **Manga Mania cs / MangaManiacs** maps to the existing MangaManiacs provider.
- **TranslatinOtaku** maps to the existing Translatino provider (host `translatinotaku.net`).
- **Noromax** and **Ota Scans** already have provider classes and registry entries.
- The other listed site names have matching adapter files or a shared/multilingual adapter in the source tree; this statement only means a source/provider candidate exists, not that live behavior is verified.

## Unrequested change detected
A recent implementation pass added `NewManhwaReadingRuntimeProvider`, but **New Manhwa was not in the user's supplied list**. It also conflicts with the existing `canonicalReadingProviderId("new-manhwa") -> "saymanhwa"` alias, which would normalize the new provider ID away from its own registry ID. The unrequested adapter and its registry entry have now been removed. Removal commits: registry `d97ac45848a726580cb6f7ed627230ec237c570f`; adapter file `844ba19d78b51d5f574db67c9de84a638340da18`.

## Recommended implementation order
1. Add dedicated MANHWA adapters for Komiku, Komikindo, KomikNesia, West Manga, MG Komik, and Shinigami.
2. Add a dedicated MANHUA adapter for Shinigami, after verifying its native API supports that content kind.
3. Add the separate Komiku.com Manga host adapter only after confirming the actual chapter URL/domain contract.
4. Add fixture tests for each adapter, then run backend tests/build and GitLab CI; only mark items verified when evidence exists.

## Validation status
- Static file and registry inspection: performed.
- Live source validation: not performed.
- Tests/build/CI: not performed.

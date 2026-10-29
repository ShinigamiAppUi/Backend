# Reading Adapter Implementation Progress

> Source of truth for resumable, language-batched implementation of site-specific adapters for manga, manhwa, manhua, and novels.
>
> **Important:** This is an initial tracking ledger. Existing inventory lists are not proof that adapters have been implemented. Populate adapter rows from the existing audited inventory and preserve source evidence; do not invent canonical URLs or claim tests passed without evidence.

## Current work state

- **Current batch:** Not started / needs explicit selection
- **Content type:** Not selected
- **Language:** Not selected
- **Working branch:** Must be confirmed before implementation; do not touch `main` for implementation unless explicitly authorized.
- **Last known implementation commit:** Not yet recorded for this workflow
- **Next concrete action:** Reconcile the existing inventory and audit evidence into the source-by-source tables below, then select the first batch.
- **Testing policy:** Batch implementation pass first; test after the batch completeness audit. Do not run a full test cycle after every individual adapter unless a targeted check is needed to safely understand a change. Never report unrun tests as passed.

## Batch plan

| Order | Content type | Language | Status | Notes |
|---:|---|---|---|---|
| 1 | Manga | Indonesian (`id`) | `NOT_STARTED` | Build source list from existing inventory and audits. |
| 2 | Manga | English (`en`) | `NOT_STARTED` | Build source list from existing inventory and audits. |
| 3 | Manhwa | Indonesian (`id`) | `NOT_STARTED` | Build source list from existing inventory and audits. |
| 4 | Manhwa | English (`en`) | `NOT_STARTED` | Build source list from existing inventory and audits. |
| 5 | Manhua | Indonesian (`id`) | `NOT_STARTED` | Build source list from existing inventory and audits. |
| 6 | Manhua | English (`en`) | `NOT_STARTED` | Build source list from existing inventory and audits. |
| 7 | Novel | Indonesian (`id`) | `NOT_STARTED` | Existing novel inventory is documented in READING_MANGA_ARCHITECTURE.md; reconcile before starting. |
| 8 | Novel | English (`en`) | `NOT_STARTED` | Existing novel inventory is documented in READING_MANGA_ARCHITECTURE.md; reconcile before starting. |

Batch order can change when the audited inventory or dependencies justify it. Record the reason here. Do not assign a source to a language or content type without evidence.

## Status vocabulary

- Inventory: `INVENTORIED`, `AUDIT_PENDING`, `AUDITED`
- Implementation: `NOT_STARTED`, `IN_PROGRESS`, `IMPLEMENTED_UNTESTED`
- Verification: `FIXTURE_TESTED`, `LIVE_VERIFIED`, `TEST_FAILED`
- Exceptions: `BLOCKED`, `UNAVAILABLE`, `LANGUAGE_UNVERIFIED`
- Final: `LOCKED` only after required implementation, testing, re-audit, and evidence are complete.

Track implementation and verification independently. A commit confirms a change was recorded, not that the change works.

## Per-source adapter tracker

Add one row for every source in the audited inventory. Keep sources distinct until canonical URL and site behavior prove they are aliases. Do not use aggregate counts as a substitute for rows.

| Source / adapter | Canonical URL / reference | Content type | Verified language | Audit status | Implementation status | Fixture test status | Live verification (date/evidence) | Latest commit / path | Blocker / next action | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| _Populate from existing inventory; do not guess_ | — | — | — | `AUDIT_PENDING` | `NOT_STARTED` | Not run | Not run | — | Reconcile source list and references | — |

## Batch completion checklist

For each batch, check these in order:

- [ ] Resume from existing audit/inventory evidence; avoid unnecessary full re-audit.
- [ ] Confirm source URL, content type, and actual content language.
- [ ] Record site-specific behavior and references for each adapter.
- [ ] Implement all feasible adapters in the batch using dedicated site-specific logic.
- [ ] Commit coherent, reviewable increments during the implementation pass.
- [ ] Update every affected source row and record commit SHA(s).
- [ ] Perform a batch completeness audit and list unfinished adapters explicitly.
- [ ] Run fixture tests for the completed implementation batch.
- [ ] Run live smoke tests only where permitted and practical; record limitations.
- [ ] Record failures, fix them, and re-test.
- [ ] Re-audit and mark only evidence-backed adapters `LOCKED`.
- [ ] Update the current batch, counts, blockers, and exact next action before handoff.

## Per-batch handoff log

### No batch completed yet

- **Completed:** Workflow and tracker initialized.
- **Remaining:** Reconcile existing source inventories/audits into per-source rows; choose the first batch; implement and verify it.
- **Known limitation:** This initial ledger does not claim any adapter is implemented or tested.
- **Next step:** Use existing audit documents and the novel inventory in READING_MANGA_ARCHITECTURE.md to populate rows, then start the selected language batch.

## Change log

| Date (UTC) | Batch | Change | Commit | Evidence / follow-up |
|---|---|---|---|---|
| 2026-10-29 | Workflow setup | Created initial language-batch progress ledger; no adapter statuses asserted | See repository commit history | Populate from audited source inventory before implementation |

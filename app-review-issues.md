# Vellum — Bugs and Issues Requiring Fixes

Review date: 2026-07-17

This document contains only confirmed bugs, missing committed functionality, accessibility defects, UI defects, and engineering issues found during the review. It intentionally excludes proposed enhancements and redesign recommendations.

## Critical

### 1. Reading activity can overwrite newer book metadata during sync

Opening a book updates the book row's general `updatedAt` timestamp along with `lastOpenedAt`. Sync resolves the entire book row using that timestamp. An offline device with stale metadata can therefore open a book later and overwrite a newer title, author, series, RTL, or deletion change made on another device.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/core/data/BookDao.kt:24-25`
- `vellum/app/src/main/java/app/vellum/reader/sync/SyncEngine.kt:69-80`

### 2. Sync bundle and book-file writes are not atomic

Pulled book files and `vellum-sync.json` are written directly to their final destinations. If the app, device, storage provider, or external sync service interrupts a write, Vellum can leave a truncated book file or invalid sync JSON with no automatic recovery copy.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/sync/SyncEngine.kt:121-135`
- `vellum/app/src/main/java/app/vellum/reader/sync/SyncEngine.kt:268-270`

### 3. Sync does not remove deleted book files from the shared folder

Tombstoned books are skipped during transfer, but their existing files are not removed from the sync folder. Deleted book content therefore remains in shared storage indefinitely and continues consuming space. The receiving device has the same gap: when a tombstone arrives via merge, `applyLocally` only upserts the row — the local book file, cover image, and FTS rows are never cleaned up (compare the thorough local-delete path in `LibraryViewModel.deleteBook`), so deleted books remain on disk and searchable on the second device.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/sync/SyncEngine.kt:121-137`
- `vellum/app/src/main/java/app/vellum/reader/sync/SyncEngine.kt:98-113`
- `vellum/app/src/main/java/app/vellum/reader/library/LibraryViewModel.kt:263-275`

### 4. Reading-time statistics include inactive and background time

EPUB, PDF, and comic sessions measure the entire lifetime of their ViewModel. Time spent with the app backgrounded, the device locked, search displayed, another reader destination on top, or the reader otherwise inactive is recorded as reading time.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderViewModel.kt:94-96,812-829`
- `vellum/app/src/main/java/app/vellum/reader/pdf/PdfReaderViewModel.kt:48-49,138-150`
- `vellum/app/src/main/java/app/vellum/reader/comic/ComicReaderViewModel.kt:46-47,158-170`

### 5. Reader teardown blocks while writing to the database

All three reader ViewModels use `runBlocking` during `onCleared()`. The EPUB reader can perform both a session insert and a final position write synchronously. This can block navigation or the main thread and can cause visible stalls when the database or storage is busy.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderViewModel.kt:812-849`
- `vellum/app/src/main/java/app/vellum/reader/pdf/PdfReaderViewModel.kt:138-152`
- `vellum/app/src/main/java/app/vellum/reader/comic/ComicReaderViewModel.kt:158-172`

### 6. Search and post-sync asset repair silently stop at 30 books

`searchByTitleOrAuthor()` always applies `LIMIT 30`. Calling it with an empty term is used to construct the title map for passage search and to find books needing covers or full-text indexes. Passage hits for books outside those 30 rows can be discarded, while restored books outside the limit can remain without covers or searchable text.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/core/data/BookDao.kt:58-60`
- `vellum/app/src/main/java/app/vellum/reader/search/SearchViewModel.kt:65-76`
- `vellum/app/src/main/java/app/vellum/reader/library/BookImporter.kt:210-217`

## High

### 7. Privacy-sensitive app data is eligible for Android backup by default

The application does not declare `android:allowBackup`, `android:dataExtractionRules`, or backup-content rules. Android's default backup behavior can include app-private files and databases, which may include book content, reading history, annotations, and settings. This conflicts with the app's stated privacy guarantees.

Evidence:

- `vellum/app/src/main/AndroidManifest.xml:8-12`

### 8. EPUB import can leave partially registered books

EPUB registration inserts the book row before full-text indexing finishes, and the operation is not transactional. If indexing fails after insertion, the library can retain a partially imported book. Import exceptions and null results are not surfaced as actionable UI errors.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/library/BookImporter.kt:172-199`
- `vellum/app/src/main/java/app/vellum/reader/library/LibraryViewModel.kt:196-204`

### 9. Open-with and share imports fail silently

Imports started from an Android `VIEW` or `SEND` intent run in application scope without a visible import status or error result. An unreadable or unsupported file can fail with no explanation to the user.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/MainActivity.kt:35-47`
- `vellum/app/src/main/java/app/vellum/reader/library/BookImporter.kt:28-52`

### 10. In-book search creates duplicate reader destinations

Opening search from a reader and selecting a passage navigates to another reader destination instead of updating or replacing the existing reader. The previous reader ViewModel can remain alive in the back stack, retaining an open publication and continuing its session clock. Repeated searches can create multiple reader instances for the same book.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/MainActivity.kt:88-123`

### 11. Search returns only one result per matching chapter

The FTS query returns a chapter row and calculates only the first occurrence of the search term. Additional matches in the same chapter are not represented as individual results, despite full-text search being marked complete in the build plan.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/core/data/SearchDao.kt:28-50`
- `build-plan.md` outstanding-polish note

### 12. Yearly reading goals are marked complete but are not implemented

The build plan marks reading goals as completed. The insights model and screen contain streak, duration, pages turned, pace, and per-book time, but no goal entity, setting, progress calculation, or goal UI exists.

Evidence:

- `build-plan.md` Phase 8 feature list
- `vellum/app/src/main/java/app/vellum/reader/insights/InsightsScreen.kt:41-51,93-196`

### 13. Database migrations have no schema exports or automated tests

Room schema export is disabled, and the project has no `src/test`, `src/androidTest`, or migration-test source set. Six database versions and their hand-written migrations cannot be regression-tested against exported schemas.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/core/data/VellumDatabase.kt:8-24`
- Missing `vellum/app/src/test`
- Missing `vellum/app/src/androidTest`

### 14. Reader content loses TalkBack semantics in slide mode

The resting page Canvas exposes its visible text to accessibility services only in the curl-rendering branch. The slide animation branch draws the page without equivalent text semantics, so changing the page-turn style changes whether TalkBack can read the page.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderScreen.kt:327-350`

### 15. Page-turn gestures have no accessible Next/Previous actions

The reader depends on left/right tap regions and drag gestures. The full-screen gesture layer does not expose explicit accessibility actions for moving to the next or previous page, making navigation incomplete for switch access and screen-reader users.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderScreen.kt:360-388`

### 16. Theme and markup colour controls are unlabeled

Theme circles in reader settings and colour circles in PDF markup are custom clickable boxes without accessible names. Their meaning and selected state rely primarily on colour.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderSettingsSheet.kt:69-87`
- `vellum/app/src/main/java/app/vellum/reader/pdf/PdfReaderScreen.kt:283-297`

### 17. Light screens display low-contrast system-bar icons

During live testing, the status-bar icons were white against the app's nearly white library and paper backgrounds. The icons were difficult to see and did not meet usable contrast expectations.

Related configuration:

- `vellum/app/src/main/AndroidManifest.xml:8-12`
- `vellum/app/src/main/java/app/vellum/reader/MainActivity.kt:24-31`

## Medium

### 18. The app uses the unwanted default Material purple accent

The application supplies no Vellum-specific Material colour scheme. Default Material 3 purple is therefore used for the FAB, sliders, switches, chips, focus states, selected borders, and other controls. This conflicts with the required calm visual direction.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/MainActivity.kt:28-31`

### 19. No application icon is configured

The manifest does not set `android:icon` or `android:roundIcon`. Lint reports `MissingApplicationIcon`, and the live Android splash displayed generic Android artwork.

Evidence:

- `vellum/app/src/main/AndroidManifest.xml:8-12`
- `vellum/app/build/reports/lint-results-debug.html`

### 20. Reader chrome is overcrowded and obscures content

The text-reader top bar places Back, the book title, Read Aloud, Highlights, Search, and Settings in one row. Long titles are heavily truncated. The top and bottom chrome overlay the page, and the bottom bar covers the final visible text lines while open.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderScreen.kt:687-753`

### 21. Every phone landscape orientation forces two-column reading

Two-column mode is enabled whenever width is greater than height, without a minimum-width check or device-size distinction. Phones are therefore forced into a two-page layout even where the resulting columns are too narrow.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderScreen.kt:190-203`

### 22. Library format labels are stored and shown as author metadata

Imported PDFs receive the author value `PDF`, and imported comics receive `Comic`. The library card displays this field as its caption, producing incorrect metadata rather than a separate format label.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/library/BookImporter.kt:118-131`
- `vellum/app/src/main/java/app/vellum/reader/library/BookImporter.kt:149-163`
- `vellum/app/src/main/java/app/vellum/reader/library/LibraryScreen.kt:383-392`

### 23. Empty-library instructions omit supported comic formats

The empty state tells the user that only EPUB and PDF files can be added even though CBZ and CBR imports are supported.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/library/LibraryScreen.kt:244-251`

### 24. Book-management actions are undiscoverable

Editing metadata, organizing a book, and removing it require entering selection mode with a long press. Book cards contain no visible action or indication that long press is available.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/library/LibraryScreen.kt:75-79,324-393`

### 25. Reading-theme controls have no visible labels

The theme picker displays five circles without their names. Users cannot identify Paper, Sepia, Gray, Black, or High Contrast without selecting them and inferring the result.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderSettingsSheet.kt:69-87`

### 26. Cold-start performance missed the stated target in debug emulator testing

Three measured cold launches of the current debug APK on the configured API 36 emulator took approximately 3.0 to 3.7 seconds. The build plan target is under two seconds. A release-build benchmark is still required, but the current measured configuration does not meet the target.

### 27. Build tool versions emit repeated Kotlin metadata rewrite failures

**Resolved 2026-07-17:** AGP bumped 8.11.1 → 8.13.2 (Gradle wrapper already at 8.13). A full clean `assembleDebug` now passes on the first attempt with no D8 metadata errors; the intermittent first-attempt `dexBuilderDebug` crashes are gone.

The debug build completes, but D8 repeatedly reports unexpected errors while rewriting Kotlin metadata and warns that the Kotlin version is newer than the version understood by the bundled R8. This makes release shrinking and metadata processing unreliable until the toolchain is aligned.

Relevant configuration:

- `vellum/gradle/libs.versions.toml:1-4`

### 28. Release optimization is disabled

The release build has `isMinifyEnabled = false`. The current debug APK is approximately 168 MB before the optional neural voice pack, and unused code and resources are not removed from release output.

Evidence:

- `vellum/app/build.gradle.kts:20-23`

### 29. The workspace is not recognized as a Git repository

**Resolved 2026-07-17:** repository initialized on branch `main` with a full initial commit (`d66297a`) and a `.gitignore` covering build output, IDE state, and local test media.

The workspace root contained an empty `.git` directory. Git commands reported that the project was not a repository, so change history, clean diffs, rollback, and commit verification were unavailable.

---

# Merged findings — second review pass (2026-07-17)

The issues below were found in the same-day deep review (design system, library, EPUB reader, PDF/comic/search, core/data/sync) and are not covered above. Numbering continues from 29; severities follow the same scale. Proposed enhancements from that review (TOC, scrubber, motion, theming direction, etc.) remain excluded from this document by design.

## Critical

### 30. Reading progress is reset to zero every time a book is closed

The EPUB reader's final position write in `onCleared()` hardcodes `progression = 0.0`, overwriting the correct percent-complete that `persistPosition` computed during the session. Every book close corrupts stored progress, so any progress display (library, sync peers) is wrong.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderViewModel.kt:833-849` (the write at `:845`)
- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderViewModel.kt:485` (correct computation)

### 31. PDF ink strokes are corrupted on comma-decimal locales

Stroke points serialize with `"%.4f,%.4f".format(...)` using the default locale. On devices set to German, French, and most European locales, decimals render as `0,5123`; the parser splits on commas and produces garbage or dropped points. Ink drawn on those devices is silently destroyed.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/pdf/PdfReaderViewModel.kt:111` (serialization)
- `vellum/app/src/main/java/app/vellum/reader/pdf/PdfReaderViewModel.kt:158-163` (comma-split parsing)

### 32. Configuration changes re-import the launching intent, and the importer has no duplicate detection

`handleImportIntent(intent)` runs unconditionally in `onCreate`, and the activity's intent survives rotation and process recreation, so an "Open with Vellum" launch re-imports the file on every configuration change. Because `importFromUri` assigns a fresh random UUID and never checks content hash, size, or title, each pass creates a new library entry. (Distinct from the known cross-device pre-first-sync duplication backlog item — this is same-device.)

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/MainActivity.kt:25-33`
- `vellum/app/src/main/java/app/vellum/reader/library/BookImporter.kt:28-53`

### 33. Insights crashes when two books share a title

The per-book time list keys LazyColumn items by book title while the data is grouped by uuid. Two books with the same title produce duplicate keys, which throws `IllegalArgumentException` at runtime.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/insights/InsightsScreen.kt:145` (key by title)
- `vellum/app/src/main/java/app/vellum/reader/insights/InsightsScreen.kt:84-87` (grouped by uuid)

## High

### 34. Landscape comic spreads duplicate a page on every swipe

The landscape pager keeps `pageCount` items while `SpreadView(i)` renders pages `(i, i+1)`, so consecutive swipes show spreads (0,1), (1,2), (2,3) — every page after the first appears twice. Correct model is `ceil(n/2)` pager items rendering `(2i, 2i+1)`. RTL ordering compounds the error.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/comic/ComicReaderScreen.kt:95,108-110,353-355`

### 35. Guided-view tap zones are computed in page space and invert after panel zoom

Forward/back tap thirds use untransformed box coordinates, but pointer positions are inverse-transformed through the zoom `graphicsLayer`. Once the camera focuses a panel on the left side of a page, every visible screen tap maps to page-x < 1/3 and navigates backward (mirrored for right-side panels). Panel-by-panel reading only works for center panels.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/comic/ComicReaderScreen.kt:260-262` (page-space thirds)
- `vellum/app/src/main/java/app/vellum/reader/comic/ComicReaderScreen.kt:186-195` (camera transform)

### 36. Renderer close races in-flight page renders

`onCleared` calls `renderer?.close()` without acquiring the render mutex, while a `produceState` render may still be inside `page.render(...)` on the IO dispatcher. Closing PdfRenderer mid-render throws (or crashes natively), and the exception propagates out of `produceState` and kills the app. The comic reader has the same shape with `store?.close()` racing `ComicPageStore.page`.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/pdf/PdfReaderViewModel.kt:154`
- `vellum/app/src/main/java/app/vellum/reader/pdf/PdfPageRenderer.kt:39-62`
- `vellum/app/src/main/java/app/vellum/reader/comic/ComicReaderViewModel.kt:174`

### 37. Kokoro TTS cancel race can interleave two utterances on one AudioTrack

Starting or re-starting Kokoro playback cancels the previous Job but not the engine; blocking `generate()`/`write()` calls do not observe Job cancellation, and the new job's `resetCancel()` can erase a pause/stop cancel the old job had not yet noticed (it polls once per 250 ms slice). The old utterance keeps playing while the new one starts — two threads writing into the same AudioTrack. Fix shape: `kokoro?.cancel()` before launching, and `resetCancel()` only after `cancelAndJoin()` of the old job.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderViewModel.kt:528-536,610-617`
- `vellum/app/src/main/java/app/vellum/reader/reader/tts/KokoroEngine.kt:68-74`

### 38. keepScreenOn leaks past the reader while TTS is playing

`view.keepScreenOn = (ttsStatus == PLAYING)` is a bare side effect during composition on the shared `AndroidComposeView`, with no `DisposableEffect` reset. Navigating back mid-playback leaves the entire app holding the screen awake.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderScreen.kt:141`

### 39. TTS stops at every chapter boundary, and the "end of chapter" sleep mode is a no-op

Both engines call `stopTts()` when the current chapter's chunks are exhausted — there is no continue-into-next-chapter path, so fall-asleep listening breaks at each chapter. Because stopping at chapter end happens unconditionally, the `TtsSleep.END_OF_CHAPTER` setting has no effect (`cycleSleepTimer` only acts on minute values). Committed sleep-timer functionality that does not work as labeled.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderViewModel.kt:798-801` (system engine)
- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderViewModel.kt:560` (Kokoro)
- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderViewModel.kt:734-744` (sleep modes)

### 40. EPUB images are silently discarded

The block parser drops `img` elements entirely and image-only chapters paginate to zero pages and are skipped. Covers, illustrations, maps, and diagrams vanish with no placeholder or notice — illustrated books render incorrectly.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/html/HtmlBlockParser.kt:98`
- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderViewModel.kt:260-262`

### 41. Rapid taps double-commit a page turn

A second tap mid-curl re-enters `beginCurl`, obtains the in-flight session, and runs `settleCurl` concurrently: `commitTurn` fires twice (double-counting `pagesTurned`, double haptic/rustle) and two `animateTo` calls fight over the same Animatable. Needs an already-settling guard.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderScreen.kt:276,301-312`

### 42. PDF and comic zoom never re-renders — text becomes upscaled raster

PDF pages render once at view width and pinch scales that bitmap up to 4x via `graphicsLayer`; comics scale to 5x a bitmap that was decoded with `inSampleSize` to at most ~2x display width. Zoomed text and line art are blurry. Requires re-rendering the page (or visible region) at the settled scale.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/pdf/PdfReaderScreen.kt:149-151,195`
- `vellum/app/src/main/java/app/vellum/reader/comic/ComicReaderScreen.kt:163-164,238`
- `vellum/app/src/main/java/app/vellum/reader/comic/ComicSource.kt:125-130`

### 43. Intent imports run in an unsupervised scope — one exception kills the app

VIEW/SEND imports launch into `app.appScope`, which has no `CoroutineExceptionHandler`, and only the copy step is wrapped in try/catch. Any exception thrown later in registration (Room upsert, FTS indexing, Readium/ZipFile quirks, rename edge cases) propagates as an unhandled coroutine exception and crashes the process.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/MainActivity.kt:46`
- `vellum/app/src/main/java/app/vellum/reader/VellumApp.kt:57`
- `vellum/app/src/main/java/app/vellum/reader/library/BookImporter.kt:30-37`

## Medium

### 44. PDF and comic progression never reaches 100%

`progression = pageIndex / pageCount` yields (n−1)/n on the last page; finished books show ~99% wherever progress is displayed. Should be `(pageIndex + 1) / pageCount` or a clamped equivalent.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/pdf/PdfReaderViewModel.kt:100`
- `vellum/app/src/main/java/app/vellum/reader/comic/ComicReaderViewModel.kt:113`

### 45. Full-text reindex is a non-atomic delete-then-insert

`indexFullText` deletes all FTS rows for a book, then inserts per chapter. Process death mid-index leaves a partial index that `chapterCountForBook > 0` treats as complete forever, so later chapters become permanently unsearchable. Wrap in a transaction. (Extends issue 8.)

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/library/BookImporter.kt:279-288,216`

### 46. No database indices on any bookUuid column

`annotations`, `pdf_strokes`, `comic_panels`, and `reading_sessions` declare no indices, yet every hot query filters by `bookUuid`, and the `observeForBook` flows feeding Compose re-run full-table scans on any table write. Fix is an additive v7 migration (`CREATE INDEX`) — never destructive, per the settled migration rule.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/core/data/Entities.kt:50,70,84,103`
- `vellum/app/src/main/java/app/vellum/reader/core/data/AnnotationDao.kt:12`, `PdfStrokeDao.kt:12,18`, `ComicPanelDao.kt:12,18,21`

### 47. Sync merge application is non-transactional with per-row lookups

`applyLocally` issues one implicit transaction per row (potentially thousands) and a per-book `byUuid()` query just to preserve cover paths. Slow, and a crash mid-apply leaves a half-merged database until the next sync. Wrap in `withTransaction` (room-ktx already present) and prefetch local books into a map.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/sync/SyncEngine.kt:98-113`

### 48. PDF ink preview ignores the selected colour

While drawing, strokes preview in hardcoded rose regardless of the chosen markup colour, then snap to the correct colour on finger-up.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/pdf/PdfReaderScreen.kt:231`
- `vellum/app/src/main/java/app/vellum/reader/pdf/PdfReaderViewModel.kt:118`

### 49. Failed page decode shows an infinite spinner

A corrupt or missing comic page entry leaves the bitmap null and the loading spinner spinning forever, with no error state or retry. A PDF render exception is the same terminal state (when not crashing outright per issue 36).

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/comic/ComicReaderScreen.kt:172-175`
- `vellum/app/src/main/java/app/vellum/reader/comic/ComicSource.kt:124,130`

### 50. Search reloads the entire library per keystroke and never cancels stale queries

Every debounced query fetches all books just to build a uuid→title map (also subject to the LIMIT 30 bug, issue 6), and sequential `collect` means a slow search delays the next instead of being cancelled (`collectLatest`).

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/search/SearchViewModel.kt:47,65`

### 51. Comic import leaks archive handles on failure

If cover extraction throws during comic registration, the catch returns without closing the comic source/page store, leaking open file handles.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/library/BookImporter.kt:113-115`

### 52. LIKE wildcards are not escaped in title/author search

`%` and `_` in a query are treated as wildcards — searching "100%" misbehaves.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/core/data/BookDao.kt:58-60`

### 53. Reader caches grow without bound and are mutated across threads

`blocksCache`/`paginatedCache` are never evicted (each `PaginatedChapter` retains a `TextLayoutResult` per block — a long session holds every layout ever built), and `blocksCache` is a plain `HashMap` mutated on `Dispatchers.Default` while gesture-path reads can overlap — a concurrent-modification risk.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/reader/ui/ReaderViewModel.kt:121-124,325-330`

### 54. Sync bundle rewrites on every launch and tombstones are never pruned

Auto-sync on library open rewrites `vellum-sync.json` unconditionally with a fresh `exportedAt` even when nothing changed, so the paired sync tool re-uploads the whole bundle forever. Nothing ever GCs tombstones (annotation tombstones keep full quote text; stroke tombstones keep full point strings) or old sessions — the database and bundle grow monotonically.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/library/LibraryViewModel.kt:120-124`
- `vellum/app/src/main/java/app/vellum/reader/sync/SyncEngine.kt:47,198`

### 55. Failure paths are silent — zero logging app-wide

No `Log.e/w/d/i` call exists anywhere in the app. Import, sync, TTS, and voice-pack failure paths swallow exceptions (or reduce them to `e.message`, which is often null for IO/JSON exceptions, yielding a bare "Sync failed"). Debugging any of the issues above over adb is currently blind.

Evidence:

- grep for `Log.` across `vellum/app/src/main/java`: 0 hits
- swallowed exceptions: `BookImporter.kt:34-37,65-67,74-76,113-115,143-145,240-242,253-256,267-271`; `SyncEngine.kt:49-51`; `KokoroVoicePack.kt:72-75`; `EpubLibraryOpener.kt:66-69`

## Low (compact)

- **Pull counter miscounts:** `pulled++` increments even when `openInputStream` returns null — `vellum/app/src/main/java/app/vellum/reader/sync/SyncEngine.kt:125-128`.
- **Landscape comic prefetch is wasted:** prefetch warms full-width bitmaps but `SpreadView` requests `halfWidth` — cache keys never match — `ComicReaderScreen.kt:99,353,358`.
- **Voice-pack integrity:** `isInstalled` checks file existence only, so a kill mid-extract leaves a truncated `model.onnx` that passes; extract progress is a sawtooth (`0.9f + 0.1f * (count % 200)/200`); `connection.disconnect()` never called — `KokoroVoicePack.kt:35-41,51,98`.
- **Stale KDoc contradicts settled JNI workaround:** `KokoroEngine.kt:14-16` still claims `generateWithCallback` streaming — risks the crash workaround being "restored" — vs `:49-54`.
- **Covers stored as lossless PNG:** the quality-90 parameter is a no-op for PNG; WebP/JPEG would cut disk and decode time — `BookImporter.kt:266`.
- **`renameTo`/short-read results unchecked** in importer — `BookImporter.kt:39,48`.
- **Dead code:** `PageCanvas.kt` past its flagged Phase-4 removal deadline (`reader/ui/PageCanvas.kt:14-18`); `pageAspectRatio` unused (`PdfPageRenderer.kt:32-36`) — ironically what a sized page placeholder needs; `PageCurlShader.kt:22,132-135` `direction`/`flipX` uniforms are dead weight with a misleading comment.
- **Search cosmetics:** double ellipsis (SQL snippet plus UI wrapper) — `SearchDao.kt:31` + `SearchScreen.kt:119`; results in rowid order with no rank; in-book results repeat the book's own title — `SearchScreen.kt:124`.
- **LWW ties never converge:** strict `>` with local-first ordering means equal timestamps leave devices permanently disagreeing; no future-clock sanity check — `SyncEngine.kt:71-75`.
- **`octet-stream` missing from intent filters**, so many downloaded EPUBs never offer Vellum in the chooser despite the importer's magic-byte sniffing handling them — `AndroidManifest.xml:22-48`; deprecated `getParcelableExtra` despite minSdk 33 — `MainActivity.kt:40-41`; default `launchMode` stacks a second activity instance on VIEW-while-running (pairs with issue 32) — `MainActivity.kt:24-33`.
- **`deleteBooks` resolves selections against the filtered list** — a concurrently filtered-out book silently survives a confirmed remove; stale selections of synced-away books are never pruned — `LibraryViewModel.kt:164-167`.
- **Session DAO duplication:** both `insert` and `upsert` for the same entity with fully-qualified annotations — `SessionDao.kt:11-18`.


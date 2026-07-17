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

Tombstoned books are skipped during transfer, but their existing files are not removed from the sync folder. Deleted book content therefore remains in shared storage indefinitely and continues consuming space.

Evidence:

- `vellum/app/src/main/java/app/vellum/reader/sync/SyncEngine.kt:121-137`

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

The debug build completes, but D8 repeatedly reports unexpected errors while rewriting Kotlin metadata and warns that the Kotlin version is newer than the version understood by the bundled R8. This makes release shrinking and metadata processing unreliable until the toolchain is aligned.

Relevant configuration:

- `vellum/gradle/libs.versions.toml:1-4`

### 28. Release optimization is disabled

The release build has `isMinifyEnabled = false`. The current debug APK is approximately 168 MB before the optional neural voice pack, and unused code and resources are not removed from release output.

Evidence:

- `vellum/app/build.gradle.kts:20-23`

### 29. The workspace is not recognized as a Git repository

The workspace root contains an empty `.git` directory. Git commands report that the project is not a repository, so change history, clean diffs, rollback, and commit verification are unavailable.


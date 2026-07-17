# Improved Prompt: Premium Ebook Reading App (Personal Build)

You are an expert product designer and senior mobile engineer. Help me design and build a **premium ebook reading app** whose core promise is an experience as close as possible to reading a physical book — calm, immersive, tactile, and beautiful — while offering the rich features only digital can provide.

## Product Vision
- This app is being built for my own daily use (I read 30+ minutes a day and currently use Kindle/Apple Books/Kobo but find them either cluttered or sterile). There are no commercial goals for now — no monetization, no ads, ever.
- Design philosophy: "the interface disappears." Every screen should feel like paper first, software second.
- Quality bar stays premium even though it's personal: build it as if it were a paid flagship app.

## Platform & Tech
- Primary platform: **Android first**. Recommend the framework (native Kotlin/Jetpack Compose vs. Flutter) and justify the tradeoff for rendering performance, typography control, and page-turn fidelity. A web/PWA companion may come in a later phase but is not the primary target.
- Must support EPUB 3 (primary), PDF, and digital comics: CBZ/CBR and fixed-layout EPUB (manga). Full offline functionality. (No Kindle formats — MOBI/AZW3 are legacy or DRM-locked; I'll convert those to EPUB externally, e.g. with Calibre.)
- Recommend the rendering approach (e.g., custom EPUB renderer vs. Readium vs. WebView-based) with pros and cons, since page-turn fidelity and typography depend heavily on this choice.

## Core Reading Experience (highest priority)
1. **Physical-book feel**
   - Realistic, physics-based page-turn animation (curl with lighting/shadow), plus a fast slide/fade option.
   - Paper textures and subtle page-edge visuals; optional book "spine" and page-thickness indicator showing progress through the book.
   - Haptic feedback on page turns (subtle, toggleable).
2. **Typography as a first-class feature**
   - Curated set of 6–10 premium book fonts (serif-forward), with proper ligatures, hyphenation, justification, kerning, widow/orphan control.
   - Fine-grained controls: font size, line height, margins, paragraph spacing, two-page landscape mode on tablets.
3. **Reading environments**
   - Themes: true paper white, warm sepia, gray, true black (OLED), plus an "evening" mode that gradually warms color temperature by time of day.
   - Optional ambient reading modes (e.g., soft page-rustle sounds, focus timer) — off by default.

## Rich Digital Features
- **Annotations**: multi-color highlights, margin notes, freehand markup on PDFs; export annotations to Markdown/Notion/Readwise.
- **In-context tools**: tap-and-hold dictionary, Wikipedia lookup, and translation without leaving the page.
- **Search**: full-text search within a book and across the library.
- **Library management**: collections/shelves, tags, series grouping, metadata editing, cover art fetching, sort/filter, import via cloud drives and email-to-library.
- **Sync**: reading position, highlights, and library synced across devices (design the backend approach — e.g., end-to-end encrypted sync; a simple self-hosted or personal-cloud option is acceptable since this is a personal app).
- **Reading insights**: streaks, time read, pages/hour, estimated time left in chapter/book, yearly reading goals — presented tastefully, never gamified aggressively.
- **Text-to-speech** with natural voices and synchronized highlighting; sleep timer.
- **Accessibility**: dynamic type, screen-reader support, dyslexia-friendly font option, high-contrast mode.

## Digital Comics & Manga (full experience, dedicated phase)
Comics are a first-class wing of the app, not an afterthought — but they get their own reader UX, since none of the typography/page-curl work applies to image-based pages.
- **Formats**: CBZ and CBR archives, plus fixed-layout EPUB (common for manga).
- **Comic reader UX**: smooth pinch-zoom and pan, single-page and two-page spread modes, right-to-left reading direction for manga, and a guided panel-by-panel view for phone-sized screens.
- **Shared foundation**: comics live in the same library (collections, tags, series grouping — series matter even more for comics), same sync, same reading insights, same themes where applicable (e.g., true-black borders on OLED).
- **Quality bar**: fast image decoding and pre-caching so page flips feel instant even in high-resolution scans.

## Constraints & Quality Bar
- Cold start to reading position in under 2 seconds; 60fps page turns even on 800-page books.
- Respect DRM boundaries: support DRM-free files and clearly document what cannot be supported.
- Privacy-first: no tracking of reading content; no analytics needed for a personal build.

## Scope & Phasing (important)
- **Do not cut features to narrow scope.** The full feature set above is the definition of "done."
- Instead, break the full scope into build phases where **every feature is assigned to a phase**, so the complete app is always in sight at every stage of the build.
- Each phase should end with something usable end-to-end for daily reading (e.g., Phase 1 = open an EPUB and read it beautifully, even if the library is bare-bones).
- When a feature depends on an architectural decision (e.g., the rendering engine), flag that dependency explicitly so early phases don't paint later phases into a corner.
- In particular: design the reader as an abstraction that can host both a reflowable-text renderer (EPUB) and an image-sequence renderer (comics/fixed-layout) from Phase 1, even though the full comic experience ships in a later phase.

## Deliverables (respond in this order)
1. A one-paragraph refined product concept and name suggestions.
2. A full feature map, in a table, with **every feature above assigned to a phase** (Phase 1, 2, 3, …) — nothing left out, nothing deferred to "maybe."
3. Recommended tech stack and rendering architecture, with justification.
4. Wireframe-level descriptions (or code) of the 5 key screens: Library, Reader (text), Comic Reader, Annotation view, Settings.
5. A phased development roadmap with rough timelines, matching the feature map from deliverable 2.

Ask me up to 3 clarifying questions before starting if anything materially changes your recommendations.

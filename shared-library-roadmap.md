# Vellum Shared Libraries — Product and Delivery Roadmap

**Status:** Product definition for approval before implementation  
**Created:** 2026-07-23  
**Companion documents:** [Premium app brief](premium-ebook-app-prompt.md) · [Existing build plan](build-plan.md)

---

## 1. North star

Vellum Shared Libraries are calm, invitation-only spaces where trusted people can curate and exchange DRM-free books without turning Vellum into a social network or a file manager. A shared library should feel like entering a beautifully arranged private reading room: members can immediately understand what is available, find a book, and add it to their own offline library with one clear action.

The feature succeeds when:

- Existing readers can continue using Vellum locally without creating an account.
- Joining a library is one tap for a signed-in user and one short email verification for a new user.
- Publishing a well-formed book requires one review screen, not a wizard.
- Downloading normally requires one **Download and add** action.
- Shared organisation is useful without polluting or overwriting personal organisation.
- Every file remains private, access-controlled, and unavailable through permanent public links.
- Reading activity, progress, notes, highlights, and personal shelves remain private by default.

## 2. Product decisions

These decisions are established by the current product discussion and should be treated as the implementation baseline.

| Area | Decision |
|---|---|
| Product name | **Shared Libraries** in navigation; **Vellum Circles** remains a possible warmer marketing name later. |
| Local use | The existing local reader remains fully usable without an account or network connection. |
| Identity | Accounts are required only to create or join an online shared library. Every account has an invisible immutable ID; users interact through email and display name, never a technical ID. |
| Authentication | Passwordless email code or magic link first. Federated sign-in can follow if it materially reduces friction. |
| Access model | Invitation-only for the first release. No public catalogue or anonymous downloading. |
| Roles | Owner, Librarian, and Reader. |
| Files | DRM-free EPUB, PDF, CBZ, and CBR. Existing DRM boundaries remain unchanged. |
| Ownership | The shared-library owner controls the library and its storage quota. Uploaders must have permission to share the content. |
| Download semantics | A download becomes a durable local copy. Revoking membership prevents future access but cannot reliably erase files already downloaded. |
| Storage | Original files and covers live in private cloud object storage. Catalogue, membership, metadata, and audit records live in a relational database. |
| Downloads | Authorised through short-lived signed links; object storage never exposes permanent public URLs. |
| Offline behaviour | Downloaded books use the existing app-private book store and remain readable offline. |
| Metadata | Bibliographic metadata travels automatically. Shared classifications are recommendations. Personal organisation and reading data remain private. |
| Interoperability | Model the catalogue contract on [OPDS 2.0](https://specs.opds.io/opds-2.0) concepts so future OPDS import/export remains possible. |
| Privacy | No advertising, behavioural analytics, reading-content telemetry, or social activity feed. |

## 3. Experience principles

### 3.1 The interface disappears

- Present one primary action per state.
- Use progressive disclosure: advanced metadata, role controls, and conflict tools appear only when needed.
- Remember safe choices, such as a member's organisation preference for a particular shared library.
- Use exception-based review for bulk operations: show only books that need attention.
- Prefer reversible actions and clear undo over repeated confirmation dialogs.
- Never force sign-in until the user chooses an online feature.

### 3.2 Shared and personal remain legible

Shared catalogues should not be merged wholesale into the local grid. The Library title becomes a source selector:

```text
My Library ▾
  My Library
  Family Library
  Saturday Book Club
  ───────────────
  Create or join a library
```

Downloaded books appear in **My Library** with a quiet provenance label on their details page, for example, “From Family Library.” The label is informational and does not make the local book dependent on continued online access.

### 3.3 Calm failure handling

- Upload and download queues survive screen changes and temporary network loss.
- A failed item does not cancel the rest of a batch.
- Errors explain the corrective action in plain language.
- Interrupted transfers resume where supported; otherwise they restart safely without creating duplicates.
- No partially downloaded file is ever registered as a readable book.

## 4. Core user journeys

### 4.1 Create a shared library

`Library source selector → Create shared library → Name and optional description → Create`

Defaults:

- Creator becomes Owner.
- Library begins private with no discoverability.
- A calm empty state offers **Add books** and **Invite people**.
- Cover mosaic and accent treatment are derived from the first uploaded covers; no setup form is required.

Optional visual customisation can be added later without blocking creation.

### 4.2 Invite a specific person

`Invite people → Enter email → Choose role → Send invite`

- The invitation is bound to the intended email.
- The recipient receives an app/deep link with an expiring, single-use token.
- If signed in with that email, the app opens a library preview and offers **Join library**.
- If signed out, the app asks for the email and a one-time verification code, then returns directly to the preview.
- Ordinary members see display names, not other members' email addresses.

### 4.3 Invite a group

`Invite people → Create invite link → Configure → Share link or QR`

Configuration is intentionally small:

- Reader or Librarian role.
- Expiration.
- Maximum uses.
- Optional owner approval before admission.

Owners can revoke a link at any time. A revoked or exhausted link shows a respectful explanation rather than a generic error.

### 4.4 Publish books already in Vellum

`Long-press one or more books → Share → Add to shared library → Select destination → Review → Publish`

The existing Share action becomes a small destination sheet:

- Send to nearby device.
- Add to each shared library where the user is Owner or Librarian.

Vellum pre-fills the publication record from [EPUB package metadata](https://www.w3.org/TR/epub-33/#sec-package-doc) and the local library. Personal collections and tags are suggested but never silently published.

### 4.5 Publish files from a device

`Open shared library → Add books → Files on this device → Multi-select → Review exceptions → Publish`

The review screen shows:

- Number ready to publish.
- Shared category, genres, and collection choices.
- Duplicate or edition conflicts.
- Only the records that need attention.

A single valid book normally needs one confirmation. Bulk tools can apply a category, genres, or shared collection to all selected books.

### 4.6 Desktop publishing

A lightweight authenticated web dashboard is part of the planned product, because bulk drag-and-drop, metadata correction, and collection arrangement are substantially better on a large screen.

The web experience uses the same validation, permissions, records, and upload pipeline as Android. It must not become a separate source of truth.

### 4.7 Browse and download

`Choose shared library → Browse/search → Book details → Download and add`

On the first download from a library, the member chooses a default:

- Apply category and genres — recommended on.
- Import selected shared collections — off by default.
- Ask for every book — optional.

Future downloads follow the remembered preference. A short, undoable message explains what happened:

> Added “Pride and Prejudice” · Applied Fiction and Romance · Added to Essential Classics · **Undo organisation**

### 4.8 Remove or revoke

- Removing an online publication immediately prevents new downloads.
- The stored file enters a recoverable deletion window before permanent removal.
- Existing member downloads remain local.
- Revoking a member removes catalogue and future download access but does not claim to erase durable copies.
- Deleting an entire library requires explicit confirmation and a recovery window.

## 5. Information and metadata model

Metadata is separated into three layers so online curation never damages personal organisation.

| Layer | Examples | Ownership and behaviour |
|---|---|---|
| Canonical publication | Title, contributors, cover, description, identifier/ISBN, language, publisher, publication date, format, series and series position | Travels with the publication and is accepted by default. Local edits are not overwritten silently. |
| Shared classification | Broad category, genres, curated collections, shared tags, curator note | Belongs to one shared library. Offered during download as recommended organisation. |
| Personal overlay | Reading position, status, personal collections, private tags, notes, highlights, reader settings | Belongs only to the member. Never visible to library owners or other members unless a future explicit sharing feature is added. |

### 5.1 Classification import rules

1. Match categories against Vellum's stable broad-category identifiers.
2. Match genres by stable identifier when available, then normalised name and known aliases.
3. Never create a second local “Romance” merely because case or punctuation differs.
4. Treat shared collections as contextual. Ask before creating or mapping them locally.
5. Preview every new collection and any ambiguous match.
6. Preserve provenance so the user can undo organisation imported from a shared library.
7. Never overwrite a locally edited title, author, cover, or series without a clear conflict choice.

### 5.2 Duplicate and edition rules

- Calculate a SHA-256 fingerprint locally before upload when practical.
- Exact fingerprint in the destination library: reuse the existing stored publication and offer to update its organisation.
- Matching identifier and fingerprint: the same edition.
- Matching title/author or identifier but different fingerprint: possible alternate edition; present **Keep both**, **Replace**, or **Cancel** according to role.
- Replacement is versioned and restricted to Owner/Librarian roles.
- A downloaded shared publication receives its own local UUID plus `sourceLibraryUuid` and `sourcePublicationUuid`; server identifiers never replace local identity.

## 6. Proposed information architecture

### Android

- **My Library:** current local-first experience.
- **Shared library source:** curated catalogue, collections, genres, search, and download states.
- **Account sheet:** identity, devices, invitations, and sign-out; not a permanent bottom-navigation destination.
- **Shared-library management:** contextual owner/librarian actions within that library.
- **Transfer destination sheet:** Nearby and eligible shared libraries live together under the existing Share action.

### Shared-library home

The home should prioritise discovery, not administration:

1. Library title, description, and subtle member presence.
2. Continue browsing / recently added.
3. Curated collection rows.
4. Category and genre entry points.
5. Search.
6. Owner controls behind one restrained overflow action.

No activity feed, engagement counters, public profiles, or noisy notification centre is planned.

## 7. System architecture

```text
Android app / Web uploader
          │
          ├── Identity and invitation service
          │
          ├── Shared-library API
          │       └── Relational database
          │           (users, memberships, catalogue, metadata, audit records)
          │
          └── Short-lived signed upload/download URLs
                  └── Private object storage
                      (original books, covers, thumbnails)

Background worker
  └── file validation, metadata extraction, cover generation,
      fingerprint verification, and quarantine handling
```

The Android app continues to use Room and app-private files as the offline source of truth for local reading. Network repositories are introduced behind interfaces rather than coupled directly to Compose screens.

### 7.1 Core server records

- `users`
- `user_identities`
- `devices`
- `shared_libraries`
- `memberships`
- `invitations`
- `invite_links`
- `publications`
- `publication_files`
- `publication_versions`
- `shared_categories`
- `shared_genres`
- `shared_collections`
- `publication_genres`
- `publication_collections`
- `download_grants`
- `audit_events`
- `deletion_jobs`

Every mutable record uses UUIDs, creation/update timestamps, and explicit deletion state. Membership is checked server-side for every catalogue mutation and every file grant.

### 7.2 Object layout

Objects use opaque identifiers, never human book titles:

```text
libraries/{libraryUuid}/publications/{publicationUuid}/versions/{versionUuid}/original.epub
libraries/{libraryUuid}/publications/{publicationUuid}/covers/{coverVersion}.webp
libraries/{libraryUuid}/publications/{publicationUuid}/thumbnails/{size}.webp
```

Storage requirements:

- Private buckets only.
- Encryption in transit and at rest.
- Short-lived signed URLs after an authorisation check.
- Multipart/resumable upload support for large comics and PDFs.
- Integrity verification after upload and before publication.
- Recoverable deletion followed by permanent lifecycle cleanup.
- Per-library storage accounting and quotas.
- Backups for database records; object-version retention appropriate to the recovery policy.

Deduplication is scoped to a shared library. Global cross-user deduplication is deliberately avoided because it complicates privacy, ownership, and deletion guarantees.

## 8. Security, privacy, and trust

### Identity and invitations

- Internal IDs are never used as user-facing credentials.
- Email invitations are bound to the intended verified email.
- Group links are random, expiring, revocable, and optionally usage-limited.
- Tokens are stored hashed where possible and never logged in plaintext.
- Joining is idempotent: reopening an accepted invitation cannot create duplicate membership.
- Role changes and removals are audit events.

### Authorisation

- Default deny for every library and object.
- Owner: membership, storage, deletion, roles, and all catalogue management.
- Librarian: publication and classification management; no ownership transfer or destructive library deletion.
- Reader: catalogue read and authorised download only.
- Signed object links are short-lived and issued only after a fresh membership check.
- Object names are opaque and cannot substitute for authorisation.

### Content safety

- Validate extension, MIME type, magic bytes, and container structure.
- Enforce compressed and expanded-size limits to resist archive bombs.
- Quarantine uploads until validation and fingerprinting complete.
- Reject malformed, unsupported, or known DRM-protected content cleanly.
- Rate-limit invitations, uploads, and download-grant creation.
- Provide owner-facing removal and member-reporting paths before any broader sharing model is considered.

### Privacy boundaries

- No shared reading history by default.
- No owner access to member notes, highlights, progress, or personal library.
- Member emails are visible only where administratively necessary.
- Operational audit records describe administrative actions, not reading behaviour.
- Account deletion, data export, and library ownership transfer must be designed before general release.

## 9. Delivery roadmap

This extends the completed nine-phase local app plan. The labels below express dependency order, not promised calendar dates. Each phase ends with an independently verifiable outcome.

### Phase 10A — Experience prototype and technical decisions

**Build:** High-fidelity Android prototype for source switching, shared-library home, invitation preview, upload review, book details, and organisation import. Define API contracts and threat model. Select backend, object storage, transactional email, hosting region, quota policy, and account-recovery approach.

**Exit:** The complete happy path can be tapped through on phone and tablet with no backend, and every unresolved infrastructure choice has an approved decision record.

### Phase 10B — Optional identity and invitations

**Build:** Passwordless accounts, secure session storage, account sheet, deep links, email invitations, expiring group links, role assignment, join preview, revoke/leave flows, and offline/account-free regression protection.

**Exit:** A new user can follow an invitation, verify once, join, reopen the app, and find the shared library; an existing local-only user experiences no new prompt.

### Phase 10C — Private catalogue and storage foundation

**Build:** Shared-library records, memberships, database policies, private object storage, signed URL service, quotas, catalogue pagination/search, provenance fields, deletion lifecycle, and operational audit events. Shape publication responses around OPDS 2.0 concepts.

**Exit:** An Owner can create a private empty library, invite a Reader, and both see the same access-controlled catalogue shell; unauthorised requests and direct object access fail.

### Phase 10D — Publishing pipeline

**Build:** Publish from My Library and multi-file picker, background/resumable uploads, validation quarantine, metadata extraction, cover generation, SHA-256 duplicate detection, edition conflicts, batch classification, exception-only review, retry/cancel, and first-upload rights confirmation.

**Exit:** An Owner/Librarian can publish mixed valid EPUB/PDF/comic batches, resolve genuine exceptions, survive a network interruption, and never expose a partial or invalid publication.

### Phase 10E — Premium catalogue and local download

**Build:** Shared-library browse design, recently added, curated collections, categories, genres, search, book details, download queue, signed downloads, integrity verification, free-space checks, existing importer integration, duplicate/edition handling, metadata preference, collection mapping, provenance, and undo organisation.

**Exit:** A Reader can find a book, use **Download and add**, go offline, and read it from My Library with the expected metadata and no duplicate classifications.

### Phase 10F — Library management and web uploader

**Build:** Member/role management, invitation administration, storage view, catalogue editing, publication versioning, recoverable delete, ownership transfer, and the responsive desktop bulk uploader using the same APIs and rules.

**Exit:** A non-technical Owner can administer a substantial family or club library without using developer tools or repeatedly editing books one by one on a phone.

### Phase 10G — Hardening and controlled rollout

**Build:** Security review, permission test matrix, accessibility pass, tablet/adaptive-layout pass, large-library performance, large-file/resume testing, concurrency/conflict testing, backup/restore drills, abuse limits, privacy/account deletion flows, support diagnostics, and staged migration/feature flag.

**Exit:** Two real accounts on multiple physical devices can complete create → invite → publish → browse → download → offline read → revoke flows, with documented recovery for every destructive action.

### Phase 11 — Deliberate expansions after the core is proven

Potential later work, explicitly excluded from the first release:

- External OPDS 2.0 catalogue connections and Vellum catalogue export.
- Shared reading lists and curator recommendations.
- Explicitly shared passages or book-club discussion with spoiler controls.
- Public-domain public libraries.
- Institution-managed libraries and larger role structures.
- Revocable lending or DRM/licensing integrations.

No item above should be pulled forward if it compromises the clarity or reliability of the invitation, publishing, and download core.

## 10. Validation matrix

### Experience

- New account, existing account, and local-only user.
- One book and 100-book batch.
- Phone portrait, tablet portrait, and tablet landscape.
- TalkBack, increased font size, high contrast, and reduced motion.
- Slow network, interrupted upload/download, server error, full device storage, and expired invitation.

### Permissions

- Owner, Librarian, Reader, removed member, expired invitation, revoked link, wrong email, and signed-out user.
- Attempt every catalogue and object operation from each role.
- Confirm that a guessed object path or expired signed URL never grants access.

### Content

- EPUB, fixed-layout EPUB, PDF, CBZ, CBR, large comic, malformed archive, renamed extension, duplicate file, alternate edition, missing cover, missing author, and protected/unsupported publication.

### Data integrity

- Fingerprint before and after transfer.
- Retry and cancellation leave no registered partial file.
- Metadata import is reversible.
- Local edits survive remote metadata changes unless explicitly accepted.
- Revocation removes online access without damaging unrelated local data.
- Existing folder sync and shared-library provenance do not create identity loops or duplicate books.

## 11. Quality gates

The feature is not considered complete until all of the following are true:

- Local reading remains account-free and offline-first.
- A signed-in invitation join takes no more than one confirmation.
- A clean single-book publication takes one review confirmation.
- A normal download takes one primary action after preferences are established.
- No permanent public book URL exists.
- Every server mutation and file grant enforces current membership and role.
- Downloads are verified before import and readable offline afterward.
- Duplicate genres and collections are not silently created.
- Personal reading data is inaccessible to shared-library owners and members.
- Removing the app update does not require clearing or replacing the existing local library database.
- Automated tests cover protocol, repository, permission, metadata merge, migration, and interruption behaviour.
- The full flow is verified on two physical Android devices before rollout.

## 12. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Account features make the app feel mandatory-online | Keep identity lazy and optional; never place a sign-in gate in local navigation. |
| Shared classifications clutter My Library | Separate canonical/shared/personal layers; default collection creation off; preview, provenance, and undo. |
| Access revocation is misunderstood | State before download that copies remain local; do not imply DRM-like control. |
| Storage and bandwidth grow unexpectedly | Per-library quotas, visible usage, upload limits, lifecycle cleanup, and scoped deduplication. |
| Copyright misuse | Invite-only launch, uploader rights confirmation, clear owner accountability, removal/reporting, no public discovery. |
| Malicious or damaged files | Quarantine, container limits, file validation, integrity checks, and importer isolation. |
| Metadata conflicts damage local curation | Never silently overwrite local edits; stable identifiers, precedence rules, preview, and undo. |
| Backend choice leaks into the app | Repository interfaces and provider-neutral API/storage contracts. |
| Old and new sync models conflict | Preserve local UUIDs, record remote provenance separately, and test folder-sync interaction explicitly. |
| Administrative features overwhelm the catalogue | Contextual management, role-aware controls, exception-only review, and desktop bulk tools. |

## 13. Decisions required before implementation

Recommended defaults are included so approval can be quick.

| Decision | Recommended starting choice |
|---|---|
| Backend and database | Managed relational backend with row-level authorisation and migrations; keep domain/API interfaces provider-neutral. |
| Object storage | Private S3-compatible storage with signed URLs and resumable multipart uploads. |
| Account method | Passwordless email code first; add Google/Apple only if testing shows meaningful friction. |
| Invitation defaults | Specific-email invites expire in 7 days; group links expire in 24 hours with configurable use limit. |
| Download ownership | Durable local copy with an explicit first-download explanation. |
| Collection import | Off by default; categories and unambiguous genres on by default. |
| Initial quota | Small configurable owner quota with clear usage, rather than an implied unlimited library. |
| Recovery | 30-day recoverable deletion for publications and libraries, subject to storage constraints. |
| Web uploader | Deliver after Android publishing works, but design the API for it from Phase 10A. |
| Rollout | Feature flag and invite-only pilot across two real accounts and multiple devices. |

Implementation should not begin until Phase 10A's backend, identity, storage, region, quota, and recovery decisions are approved. Visual prototyping may begin without committing the system to a provider.

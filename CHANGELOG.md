# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

See `CLAUDE.md` → **Versioning** for how entries get here and how the version
number is chosen.

## [Unreleased]

### Added

- Native Android app (in development, not yet distributed): a new Ledger screen, reachable
  from the Library, shows a hero stat ribbon (total movies, total series, viewings logged,
  average rating, movie minutes watched) computed from your library. This is the stat
  summary only, not yet the full customizable widget board.

## [1.8.0] - 2026-07-13

### Added

- Native Android app (in development, not yet distributed): the selected theme (dark,
  light, noir, matrix) now persists locally and survives an app restart, via a bare
  cycle-through button on the Library screen.

## [1.7.0] - 2026-07-13

### Added

- Native Android app (in development, not yet distributed): episode ratings, episode
  reviews, title status changes, and re-watch logging now write through the same durable
  mutation outbox as marking an episode watched, so all core tracking actions survive
  offline use and process death. The remote push is still stubbed pending a physical device
  for Credential Manager auth.

## [1.6.0] - 2026-07-13

### Added

- Native Android app (in development, not yet distributed): a durable
  mutation outbox for tracking changes offline. Marking an episode watched
  now writes to the local Room database immediately and queues the change
  for remote sync — the queue survives process death and retries until a
  push succeeds, so mutations are never silently dropped. The actual remote
  push is still stubbed pending a physical device for Credential Manager
  auth; queued mutations stay durably pending until that lands.

## [1.5.0] - 2026-07-13

### Added

- Native Android app foundation (in development, not yet distributed): a
  read-only Library and Title detail spine backed by a local Room database.
  Adds the shared database schema and `sync_library_changes` RPC it will
  sync against — additive only, no change to existing web app behavior.

## [1.4.1] - 2026-07-12

### Fixed

- PWA updates now surface a "new version available" toast instead of
  silently swapping the service worker in the background. Previously a
  freshly deployed build could sit undetected behind a stale cached shell
  until the browser's own lazy update check happened to fire, so different
  devices/tabs could render different versions of the app with no way for
  the user to force a resync. The toast's Reload button now deterministically
  activates the new service worker before reloading.

## [1.4.0] - 2026-07-12

### Added

- Import from Letterboxd: Profile → Data & Portability accepts a CSV from a
  Letterboxd data export (watched, ratings, diary, or watchlist). Films are
  matched to TMDB by name and year, ratings copy over on the shared 0.5–5
  scale, diary rewatches become dated viewings, duplicates are skipped, and
  unmatched films are reported instead of guessed.

### Changed

- The Bechdel test badge now uses a bespoke "two figures in conversation" icon
  in place of the generic Venus (♀) symbol, matching the test's actual criteria.
- The Discover page hero now reads "Scout the next reel for the vault." in
  place of "What's missing from your archive?", matching the app's
  projection-room voice.
- The keyboard-focus accessibility controls (skip to content, keyboard
  shortcuts) now appear together as one sliding toolbar instead of two
  separately floating pills.
- The nav bar word mark now collapses into the reel logo below 1280px (was
  1024px), so it no longer crowds the pill nav on mid-size screens.
- The Discover carousels' pause control moved out of the film strip into each
  carousel's header as an always-visible labeled Pause/Play chip, replacing
  the hover-only corner overlay.

### Fixed

- Friend requests can now be withdrawn by their sender; discovery carousels
  only auto-scroll while visible; and the ticket action in Up Next no longer
  crashes the screen.

## [1.3.0] - 2026-07-12

### Added

- Cinema Outings — "I've got tickets" scheduling: a form to log a booked cinema
  trip (showtime, theater, companions, format, previews/runtime buffer, ticket
  price, seat, booking ref) from the title drawer, Up Next watchlist cards, the
  command palette, and right after adding a movie to the watchlist. The drawer
  shows a scheduled-outing banner with Edit/Cancel, and saving offers an
  out-of-app share snippet plus a downloadable `.ics` calendar event with a
  2-hour reminder.
- Cinema Outings — completion & follow-up: Up Next leads with an "On the
  Marquee" section (countdown chips through a pulsing NOW SHOWING, add-to-
  calendar, edit/cancel), a toast and bell notification land the moment a
  showtime's ticket auto-completes, and a "Fresh from the lobby" card offers
  the post-show sheet — star rating, a quick note, recommending the movie to
  friends (companions annotated "was there with you"), or "Didn't make it" to
  revert and reschedule. Movies with a scheduled outing get a 🎟 badge on the
  library poster wall.
- Cinema Outings — in-app plan sharing: "Share plans" from the marquee card,
  drawer banner, or the schedule form's save confirmation opens a friend
  picker that pushes a one-way snapshot (showtime, venue, format, seat — never
  the booking ref) to an accepted friend's bell inbox, alongside the existing
  out-of-app copy/share snippet and `.ics`. The recipient's notification offers
  "I've got tickets too" (adds the title to their watchlist if needed, then
  opens their own prefilled ticket form) and "Add to calendar".
- Cinema Outings — timeline & viewing editor: the drawer's viewing timeline
  renders a perforated-edge ticket-stub line ("at AMC Georgetown · with Alex &
  Sam · IMAX") wherever a viewing has a venue or companions, degrading
  gracefully when only one is present. Every viewing — auto-logged or manually
  entered — now has an inline editor for its date, rating, notes, theater, and
  companions (same chip/autocomplete affordances as the ticket form), and the
  manual "Log a viewing" form gained the same theater/companions fields so
  home viewings can record company too. A completed outing's receipt fields
  (format, ticket price, seat, booking ref) stay editable from that same
  editor even after its showtime is frozen. Deleting an outing's auto-logged
  viewing from the timeline now leaves the outing completed and closes out
  its pending "how was it?" follow-up.
- Cinema Outings — "At the Movies" Ledger panel: an opt-in board widget
  totaling cinema trips (lifetime and this year, with a per-year strip),
  your favorite theater and venue breakdown, most frequent companion, format
  chips (IMAX/3D/…), and a "spent at the movies" sum in your locale's
  currency (hidden until a ticket price is logged). Companion chips
  throughout the feature now show initials for everyone, not just linked
  friends.

## [1.2.0] - 2026-07-11

### Added

- Details drawer — redesigned title presentation with a persistent primary/sidebar
  layout, unified hero metadata and review scores, contained content sections,
  expandable wrapping cast cards, consolidated genres and tags, sidebar viewing
  statistics, scroll-aware provider and trailer fades, and responsive mobile flow.

## [1.1.0] - 2026-07-10

### Added

- Details drawer — "In my home collection" toggle in Where to Watch (KP-002):
  marks a title as owned locally and surfaces an amber "Home Collection"
  source row, visible to friends and shared-link viewers too.
- Details drawer — physical media shelf (KP-003): catalog the physical copies
  you own per title (DVD, Blu-ray, 4K UHD, VHS, LaserDisc) with an optional
  edition note; stored on the title and shown read-only in shared views.
- Details drawer — franchise strip and progress (KP-027, KP-028): movies that
  belong to a TMDB collection now show every film in the franchise in release
  order with a "Watched X/Y" progress bar; entries already in the library open
  in place, the rest can be added directly.
- Discover — "Because You Watched" now shows real TMDB recommendations for
  the selected library title via a new media-proxy `recommendations` action,
  replacing the trending-list stand-in (KP-024).
- Discover — carousels gained a persistent play/pause toggle (KP-036),
  mouse-wheel/trackpad horizontal scrolling (KP-001), and momentum gliding
  after a grab-and-drag release (KP-037).
- Discover — the "Because You Watched" / "More Starring" pickers now include
  a filter input once the option list grows past a handful (KP-030).
- Friends — "Suggested friends" lists people connected to you by invite code
  (they redeemed yours, or you redeemed theirs) with a one-tap friend request
  (KP-026).
- Settings — new About section with the app version, a short description,
  data-source credits (TMDB/OMDb/Wikidata), and repository links (KP-023);
  the sign-in modal footer now shows the running version too.
- Accessibility — a hidden-until-focused "Keyboard shortcuts" button now sits
  right after the skip-nav link and opens the shortcuts panel (KP-035).

### Changed

- Bechdel badge now uses a Venus (♀) icon instead of the "BDT" initialism
  (KP-029).
- The Library view gained its missing display title, and the Discover heading
  now uses the same Fraunces display-title brand styling as the other views
  (KP-031); Discover carousel headers use Title Case (KP-034).
- Top bar — the search button's label/shortcut and the CinemArchive word mark
  now collapse into icons at wider breakpoints so the nav doesn't crowd on
  tablets and laptops (KP-032, KP-033).

### Fixed

- Ledger — "Critical Record" legend rows now connect each star label to its
  count with a dot leader, so wide cards read as one list instead of two
  disconnected columns (KP-038).

## [1.0.3] - 2026-07-10

### Fixed

- Ledger responsive-widget backlog (KP-004–KP-022): revamped all 15 remaining
  affected panels one at a time with fluid charts, preset-aware list/grid
  reflow, adaptive labels and plot sizing, and full-card use at `sm`, `md`,
  `lg`, and `full` widths. Verified that every ledger card stays within its
  horizontal and vertical bounds at 1440×900 and 390×844 viewports.
- Ledger adaptive composition follow-up: widget cards now use one clipped layer
  with consistent rounded corners, vertically balance their contents, and never
  create internal scroll surfaces. Mobile always selects the compact visual
  mode regardless of the saved desktop width. Screening Nights now progresses
  from a compact radar chart to a synchronized radar-and-bar composition;
  Critical Record and The Ensemble likewise switch to purpose-built compact
  legends/lists instead of squeezing their desktop presentations.
- Library — the mobile filters drawer could no longer be dismissed once
  opened: it rendered below the sticky top/bottom nav bars (`z-50` vs the
  nav's `z-[200]`), which visually covered the drawer's close button and
  reset-filters action. The shared sheet now renders at `z-[210]`, matching
  the stacking convention used by other above-chrome overlays, and its
  scrollable content no longer carries the close button off-screen as the
  filter list is scrolled.

## [1.0.2] - 2026-07-10

### Fixed

- Ledger — "By the Genre" widget: genre bubbles no longer overflow the card
  when `topN` is configured toward the high end of its 3–12 range. Bubble size
  (and the `sm`-preset ranked list's row height) is now derived from an
  estimated row count so the widget's fixed-height card fits any configured
  count without forcing an internal scrollbar.

## [1.0.1] - 2026-07-10

### Fixed

- Ledger — "The Run" widget: the monthly screening trend chart no longer forces
  a horizontal scrollbar (previously a fixed per-month pixel width could balloon
  past 6000px on a 10-year "all time" range). The chart now fills its card at
  every board-width preset, with axis labels thinned to fit — including a
  mobile-safe label budget below the `lg` breakpoint, where every preset renders
  full-width. Added at-a-glance total/peak/average stat chips above the chart.
- Ledger — "By the Genre" widget: the genre bubble cloud now fills its card at
  every board-width preset instead of leaving fixed-size bubbles stranded in a
  half-empty card. Bubble diameter is now proportional to the available column
  width (capped per breakpoint) and rows recenter automatically; the narrow `sm`
  preset swaps to a compact ranked bar list at the `lg` breakpoint and up, where
  a bubble cloud would otherwise crowd into a ~4-of-12-column card.
- UI consistency: consolidated the app's five hand-rolled modal close buttons
  (`cinema-modal`, `poster-lightbox`, `trailer-row`, `PersonDetailPanel` /
  `SendRecommendationPanel`, `ShareScopeEditor`, `KeyboardShortcutsHelp`) into a
  single shared `ModalCloseButton`, fixing `ShareScopeEditor`'s close button
  (previously had no hover state at all) and giving every close button a
  keyboard focus ring for free. Added a focus-visible ring to the Matrix/Spider-Noir
  `ChoiceCard` picker, which previously had no visible keyboard focus state.
  Also: `Ledger.tsx`'s two hand-retyped copies of the shared amber CTA button now
  import the constant instead of forking it; the awards badge no longer hardcodes
  a fixed gold hex (now themes correctly under Noir/Matrix); the B&W/Color episode
  pill's text color now routes through a token instead of a raw `#aaa`; and the
  unused shadcn `dialog.tsx` scaffold was removed.

## [1.0.0] - 2026-07-10

Baseline release. Marks the app as built, deployed, and in daily use — all
history before this point is retained in git log, not itemized here.

### Fixed

- Discover view: eliminated synchronous `setState` calls inside `useEffect` bodies
  (detail-modal hydration, "because you watched" / "more starring" defaults, studio
  search loading) in favor of derived state, per React's "you might not need an
  effect" guidance.

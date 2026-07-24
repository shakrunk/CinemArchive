# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

See `CLAUDE.md` → **Versioning** for how entries get here and how the version
number is chosen.

## [Unreleased]

### Added

- Native Android app (in development, not yet distributed): pull-to-refresh on the Discover,
  Library, and Up Next tabs. Discover re-fetches trending titles from TMDB; Library and Up
  Next pull remote changes down via the existing `sync_library_changes` sync (Up Next also
  re-runs cinema-outing completion afterward), the same reconciliation already run on launch
  and app resume.
- Native Android app (in development, not yet distributed): a real sign-in flow — magic-link
  email sign-in, with interactive (not yet backed) UI for passkey and desktop QR-pairing
  sign-in on the same login screen. Once signed in, the app pulls your real library down from
  Supabase via `sync_library_changes` (bootstrap + incremental, on launch/sign-in/resume)
  instead of showing local dev fixtures, and local mutations — title status, ratings,
  reviews, viewings, cinema outings, Ledger layout — now push to the real backend.
- Native Android app (in development, not yet distributed): Cinema Outings — "I've got
  tickets" scheduling on any watchlisted/watching movie, an "On the Marquee" section leading
  Up Next with a live countdown (`TONIGHT · 7:30 PM` → `NOW SHOWING`), and a local completion
  engine that auto-marks the title watched and logs the venue/companions as a viewing once
  `showtime + previews + runtime` passes — reconciled on app launch/resume and, uniquely on
  Android, by a real exact alarm that fires even if the app is closed, prompting a system
  notification — with the title's poster as the notification's hero image — to rate the
  outing. "Add to calendar" uses the native Calendar Provider intent.
  Completion, in-app "Fresh from the lobby" follow-up, and "Didn't make it" revert all run
  entirely on Room, so they work offline; outings now also sync both ways with the real
  backend (`sync_library_changes` gained the `cinema_outings` arm it was missing, and the
  `viewings` arm now carries `companions`/`outing_id`), so an outing scheduled on web or
  another device pulls down correctly instead of only ever pushing up. Notifications inbox
  sync and in-app plan sharing are intentionally deferred pending the friends stack (see
  `docs/superpowers/plans/2026-07-21-android-cinema-outings.md`).
- Native Android app (in development, not yet distributed): a Material 3 Expressive redesign
  of the whole app shell — a four-tab bottom nav (Discover/Library/Up Next/Ledger) with a
  morphing pill selection indicator, a tertiary "New Title" FAB, and a full-screen overlay
  stack (title detail, add-title, profile, appearance, about) replacing the old pushed-screen
  navigation. New reusable components in `core:designsystem` power it throughout: a
  single-select segmented control that morphs into a pill on selection, a connected
  multi-select toggle group, and a draggable half-star rating control with pop animation.
- Native Android app (in development, not yet distributed): Appearance is now reached from a
  Profile overlay (avatar, library stats, sign-out placeholder) rather than a gear-icon
  Settings screen, and splits theme mode (System/Light/Dark) from color palette (Brand,
  Material You dynamic color, and the unlockable Spider-Noir/Matrix palettes) as two
  independent choices instead of one four-way mode. About & Legal moved into its own overlay
  off Profile, with legal entries opening as their own sub-page instead of expanding in place.
- Native Android app (in development, not yet distributed): Profile now has a Permissions
  overlay showing live status for the three permissions the app asks for contextually
  (camera for QR sign-in, notifications and exact alarms for the outing "how was it?" prompt),
  each with a one-tap fix — an in-app request dialog where the OS allows one, otherwise a
  deep link straight to the relevant system Settings screen.
- Native Android app (in development, not yet distributed): Appearance now has a Text section
  with font accessibility controls — a Lexend-based dyslexia-friendly typeface swapped in
  app-wide, and a text-size slider (Small through Extra Large) that stacks on top of the
  device's own system font scale rather than overriding it. Font family and size changes are
  staged against a live preview card and only take effect app-wide once Apply is tapped.
- Native Android app (in development, not yet distributed): the Library screen now has a
  search field, a grid/list view toggle, and multi-select status filter chips, with poster
  art loaded from each title's real TMDB image (falling back to a tint) instead of a bare
  text list.
- Native Android app (in development, not yet distributed): Up Next, a new top-level tab
  showing "continue watching" progress per title (aggregated from local episode data) with a
  one-tap "mark episode watched" action, and a watchlist section below it.
- Native Android app (in development, not yet distributed): Discover, a new top-level tab,
  and the FAB's "New Title" add flow — both browse a local sample catalog rather than live
  TMDB search, since the Android app has no media-proxy client yet; clearly scoped as a GUI
  demo pending that backend work.
- Native Android app (in development, not yet distributed): the Discover tab now shows this
  week's real trending movies and TV shows, fetched live from the `media-proxy` Edge
  Function's `trending` action, with poster art, search, type filtering, and loading/error/
  retry states. The FAB's "New Title" add flow still browses the local sample catalog.
- Native Android app (in development, not yet distributed): a real launcher icon — the same
  film-reel mark as `public/favicon.svg` in void/amber, as an adaptive icon with a themed
  monochrome layer for Android 13+ Material You icon theming. Previously the app shipped with
  no `android:icon` at all and fell back to the OS placeholder.
- Native Android app (in development, not yet distributed): a branded cold-start splash —
  the system splash now shows the film-reel mark on the void background instead of a plain
  flash, then hands off into a Compose splash where the reel keeps spinning over a pulsing
  amber "projector beam" glow before crossfading into the app.
- Native Android app (in development, not yet distributed): the Ledger board's custom layout
  now pulls from `user_prefs.ledger_layout` on sign-in and app launch, not just push — a
  layout customized on web (or another device) now shows up on Android without first making a
  local edit, matching the documented "server wins on load" contract.
- Native Android app (in development, not yet distributed): Ledger widgets now actually apply
  their `timeRange`/`scope` settings (previously persisted and normalized but silently
  ignored by every widget's computation) — e.g. a Genre widget scoped to "Films" now excludes
  TV titles from its tally instead of only hiding the setting. Widgets whose panel exposes a
  "top N" knob (By the Genre, The Auteurs, The Ensemble, Encore Performances, On the Air,
  Second Opinions, In Translation, Still Rolling) also now cap at that panel's default (5 or
  6) when uncustomized, matching the web app's own defaults instead of showing every item.
- Native Android app (in development, not yet distributed): the Ledger board's hero section
  gained a "now showing · {date}" kicker and a narrative sentence (title/screening/hour
  counts) above the stat tiles, and the stat set grew from 4 tiles to 6 — Screenings and Days
  in the dark join Movies, Series, Hours logged, and Avg rating.
- Native Android app (in development, not yet distributed): three Ledger chart upgrades —
  Time in the Dark's heatmap is now a true daily 7×52 grid instead of one week-granularity
  row; Shifting Standards and Premieres & Revivals now render as connected line charts
  instead of bars; The Marathon gained an additive "last 30 nights" activity grid alongside
  its existing streak-count text. Every widget's existing accessible list is unchanged — these
  are decorative-primitive upgrades only.
- Native Android app (in development, not yet distributed): Ledger edit-mode capability
  parity — long-press-and-drag reorder and drag-to-resize (the existing up/down buttons and
  tap-to-cycle-width control stay as keyboard/switch-access-friendly fallbacks), a duplicate
  action per widget, a "Reset to default layout" action with a confirmation dialog, and an
  "Add a widget" palette that now shows a live scaled preview of each panel's actual content,
  a "×N already on board" usage badge, and stays addable even for panels already on the board
  (so a panel can appear multiple times) plus supports long-press-drag to place a new widget
  at a specific position instead of only appending.
- Native Android app (in development, not yet distributed): a per-widget settings sheet in
  Ledger edit mode with segmented Scope (All/Films/Series) and Time range (All time/5 yr/This
  year/12 mo) controls, shown only for panels that actually honor those settings — the last
  piece of `timeRange`/`scope` parity, now that widgets consume them.

### Changed

- Native Android app (in development, not yet distributed): visual parity pass toward the
  web app's cinematic dark-gold aesthetic — all four theme modes now carry a full Material 3
  color scheme (surface container layers, outline, containers) instead of a handful of
  overridden roles; typography now uses the same downloadable Fraunces/Hanken Grotesk/DM
  Mono faces as the web app instead of the system default; card, chip, and icon usage across
  the Library, Title detail, and Ledger screens was brought in line with Material 3
  conventions (proper back-arrow and star icons, filter chips for watch status, bordered
  surface-container cards).
- Native Android app (in development, not yet distributed): the star rating field now
  collapses to a plain read-only star row + numeric value (pencil icon signals it's
  editable) and opens a modal picker to actually set it — mirrors the M3 TimePicker's
  dialog-collapses-to-text pattern. The picker itself now has an unmistakable slider: a
  gradient track that fills in as you drag and a raised, ringed handle sitting on top of
  it, after user testing showed the previous inline drag row wasn't discoverable as a
  slider on its own.
- Native Android app (in development, not yet distributed): motion across the app shell now
  matches Material 3 Expressive's punchier, springier feel — the bottom nav's morphing pill
  indicator (the biggest offender), the segmented control's selection grow, and the star
  rating's pop-on-set animation all settle faster with a visible bounce instead of the
  previous slow, understated spring.
- Native Android app (in development, not yet distributed): the "New Title" FAB now collapses
  to just its "+" glyph while scrolling down the Discover, Library, or Up Next tab, and the
  search bar/filter row directly beneath each tab's title scroll out of the way with it — both
  return once you scroll back up.

### Fixed

- The web app failed to build (`tsc -b` errored on `src/views/UpNext.tsx`) — a prior refactor
  that removed most `useShallow` selector usage dropped the import entirely, but one selector
  in `UpNext` (owner-only `outings`/`titles`/`isSharedView`) still genuinely needed it to avoid
  re-rendering on every store change. Restored the missing import.
- Native Android app (in development, not yet distributed): the Ledger "Still Rolling" widget
  showed the wrong episode-watched count for shows tracked episode-by-episode — sometimes 0
  even with real progress — because it trusted the synced `seasons.episodesWatched` column,
  which is only set once when a season first syncs down and is never updated afterward (only
  `episode_watch_events` rows are written when you mark an episode watched). It now rolls the
  count from actual per-episode watch events instead, falling back to the season column only
  when no episode-level data has synced yet.
- Native Android app (in development, not yet distributed): Continue Watching (and a title's
  own detail screen) showed the wrong episode-watched count for shows tracked episode-by-episode
  — sometimes 0 even with real progress — for the same reason as the Ledger "Still Rolling"
  fix above: both trusted the synced `seasons.episodesWatched` column instead of rolling the
  count from actual per-episode watch events. Continue Watching now also computes which episode
  is next per title from that same accurate watch data, feeding its "S{n} E{n}" label.
- Native Android app (in development, not yet distributed): cinema outings (and, more subtly,
  the venue/companions on already-synced viewings) scheduled before this device's own sync
  cursor could never be pulled down, even after the server gained cinema-outing support —
  `sync_library_changes`'s cursor is one global watermark across every entity type, so
  anything of a newly-supported kind whose `updated_at` predates that watermark was silently
  unreachable on every future incremental sync, no error or gap indicator. `syncNow()` now
  detects it's running with an older sync schema than the client understands and forces one
  full resync from epoch to catch up, rather than trusting the existing cursor.
- Native Android app (in development, not yet distributed): opening a title's detail screen
  from Up Next's Continue Watching (or anywhere else that opens the overlay in place, without
  navigating) always showed whichever title was opened *first*, no matter which one you
  actually tapped. The detail overlay isn't a distinct nav destination, so every open shared
  the same `ViewModelStoreOwner`; `viewModel()` without an explicit key caches its instance by
  call site, so the factory — and the `titleId` baked into it — only ever ran once. The lookup
  is now keyed on `titleId`, so each title gets its own ViewModel instance.
- Native Android app (in development, not yet distributed): the Up Next "On the Marquee" card
  (and any other companions display) could render a companion as a raw JSON blob, e.g. `with
  {"name":"...","friendUserId":"..."}`, instead of a plain name. `companions` is `[{name,
  friendUserId?}]` in Postgres (matching the web app's `Companion[]` type), but the sync pull
  parsed it with `JSONArray.getString()`, which stringifies non-string elements wholesale
  rather than extracting `name`. Rows already pulled under the old parser had the bad string
  baked into Room, so this also bumps the sync schema version to force a one-time full resync.
- Native Android app (in development, not yet distributed): Up Next's "Continue Watching"
  section was renamed to "Next Episode" — CinemArchive isn't a streaming service, so "continue
  watching" implied playback that doesn't exist. Its cards now lead with the next unwatched
  episode's title (falling back to "Episode N" when unnamed) with the series as a smaller
  subtitle below, and show the season/episode number and watched-count on the same row,
  left- and right-aligned respectively. The watchlist section's "Ready whenever you are" label
  — shown even for titles that haven't released yet — now reads "Releases <date>" for an
  unreleased title, matching the website. Rows of the same card type stacked back-to-back also
  now read as one grouped list (only the outermost corners get the full radius) instead of a
  stack of independently rounded cards.

## [1.11.0] - 2026-07-16

### Added

- Native Android app (in development, not yet distributed): the Ledger screen now has all 20
  widgets from the web app's registry (Time in the Dark, Encore Performances, The Run,
  Critical Record, By the Genre, The Auteurs, The Ensemble, Second Opinions, In Translation,
  Screening Nights, The Marathon, Shifting Standards, Premieres & Revivals, The Revival
  House, Still Rolling, and At the Movies, alongside the previous five), each with a real,
  focusable accessible list alongside any chart rather than a tooltip-only fallback.
- Native Android app (in development, not yet distributed): the Ledger board is now
  customizable — add, remove, reorder, resize, and set a custom title/top-N limit per widget,
  via a new Edit mode. The layout persists locally and survives an app restart; syncing it
  across devices isn't wired up yet.
- Native Android app (in development, not yet distributed): the Ledger board now lays out in
  a responsive multi-column grid on wide screens (tablets, landscape) instead of always
  stacking widgets in a single column.

### Changed

- Native Android app (in development, not yet distributed): tracking-mutation conflict
  resolution (last-write-wins by `updated_at`) and Ledger layout sync are now implemented and
  verified live end-to-end against a real backend; neither is wired into the live app yet,
  since that needs a real user session from the still-unbuilt passkey sign-in flow.

## [1.10.0] - 2026-07-13

### Added

- Native Android app (in development, not yet distributed): the Ledger screen gained four
  more stat sections — Feature Lengths (movie runtime breakdown), On the Air (TV network
  breakdown), By the Era (release-decade breakdown), and Coming Attractions (your
  watchlist, with total runtime owed).

## [1.9.0] - 2026-07-13

### Added

- Native Android app (in development, not yet distributed): the selected theme (dark,
  light, noir, matrix) now persists locally and survives an app restart, via a bare
  cycle-through button on the Library screen.
- Native Android app (in development, not yet distributed): a new Ledger screen, reachable
  from the Library, shows a hero stat ribbon (total movies, total series, viewings logged,
  average rating, movie minutes watched) computed from your library. This is the stat
  summary only, not yet the full customizable widget board.

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

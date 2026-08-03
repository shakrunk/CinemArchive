# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

See `CLAUDE.md` → **Versioning** for how entries get here and how the version
number is chosen.

## [Unreleased]

### Added

- Cinema outings now record where you're sitting as auditorium, row and seats rather than one
  free-text "Seat" field, on both clients. Outings saved before this keep their original text
  and still display it — nothing is re-parsed or lost (#221).
- Android: the in-theater ticket view is now two modes instead of one screen. **Scan** shows
  the code full-width at maximum brightness for the ticket-taker; **Auditorium** drops the
  backlight to a theater-safe floor and shows the auditorium in large dim amber on black so
  you can find your seat without lighting up the rows behind you. It opens on whichever
  matches the showtime and switches by tap (#221).
- Android: the Library's "Sort by" gains **Smart**, ordering titles by the most recent thing you
  did with them — added, watched, or any episode watch, rating or review — and it is now the
  default, as it already was on the web app.

### Changed

- Web: the Library's default sort, previously "Last Interaction", is now called **Smart** — the
  same name the Android app uses for it. The ordering itself is unchanged.

### Fixed

- Android: the ticket QR code was rendered light-on-dark under every dark theme (i.e. all of
  them but Light), which most theater scanners cannot read. It is now always black on white
  regardless of theme, with an explicit quiet zone and error-correction level (#221).
- Android: the screen no longer times out while a ticket is open.
- Up Next's "next episode" card no longer offers "Mark watched" for an episode that hasn't
  aired yet on a still-airing series — it now shows the air date instead, on both clients.

## [1.24.0] - 2026-08-03

### Added

- Android: Up Next's "On the Marquee" cards gain a "View ticket" action showing a large seat
  display and a scannable QR code generated from the outing's booking reference, with the
  screen brightness boosted for easier scanning at the theater (#221).

## [1.23.1] - 2026-08-01

### Fixed

- Android: Settings → Updates no longer changes the row's height when the "Check" button swaps
  for the in-progress spinner and back (#188).
- Android: selecting "Status" under Library's "Group by" no longer causes the screen to flicker
  in a collapse/expand loop between the search bar and the newly-inserted status headers (#187).

## [1.23.0] - 2026-08-01

### Added

- Android: the app now adapts to wide/unfolded layouts (foldables opened flat, tablets) instead
  of stretching phone-first chrome across the extra width — bottom navigation switches to a
  leading-edge rail at medium window widths and up, poster grids in Library/Discover use
  adaptive column counts that scale continuously with available width instead of a fixed count,
  search/filter bars and single-column screens (title detail, settings, Up Next) cap to a
  reading-width column instead of stretching edge to edge, and the Ledger widget board's
  existing wide-layout packing now triggers correctly at these widths.

## [1.22.1] - 2026-08-01

### Fixed

- Android: a from-epoch library resync (fresh install, sign-in on a new device, or an app
  reinstall) could crash the app on launch with a `SQLiteConstraintException` if a season,
  viewing, cinema outing, episode, or episode watch event/rating/review row happened to sort
  ahead of the title/season row it depends on in the sync stream — the same ordering hazard a
  prior fix addressed for cast/crew rows only. These rows are now held back and re-applied once
  their parent has landed, instead of being inserted blind.

## [1.22.0] - 2026-07-26

### Added

- Android: the "Credits & Open Source Licenses" entry in About & Legal now shows real, tappable
  attributions — TMDB and OMDb, the app's Google Fonts (Fraunces, Hanken Grotesk, DM Mono,
  Lexend), and every open-source library it ships with its actual license and a link to the
  source or license page — replacing the placeholder summary paragraph.

### Fixed

- Android: the global "+ New Title" FAB no longer appears on the Discover tab, which already
  surfaces per-result add actions inline — matching its existing Ledger-only exclusion.
- Android: poster corner radius on the Discover and Library grids now scales with the
  pinch-to-resize column count instead of staying a flat 16dp — larger at one column, tighter
  once four columns narrow the poster.

## [1.21.1] - 2026-07-26

### Fixed

- Four Ledger widgets that rendered empty or wrong on the native Android app, none of them a
  display bug — the panels were faithfully reporting gaps in what sync had actually delivered:
  - **The Ensemble** and **Second Opinions** / **In Translation** were blank on any library
    synced down from the web app rather than added on the phone. Cast, crew, IMDb rating and
    original language were never included in what the server sends a phone, so those columns
    sat empty locally no matter how many times you synced. They are part of the payload now,
    and the app does one automatic full resync after this update to backfill them.
  - **The Auteurs** counted directors from credit rows alone, so it too came up empty on a
    synced library — while the Library list showed the very same directors, because it reads a
    different column. It now counts the same director field the web app's Auteurs panel does,
    falling back to the credits only for a title that has no director recorded.
  - **Shifting Standards** plotted quarters back to 1994. Titles imported from the old
    MovieTracker data with no watch dates had their "added to library" date filled in with the
    film's *release* date, so a 1994 film looked like it had been in the library since 1994.
    The importer no longer does that, and the affected rows are corrected in place.
- A batch of rows written in the same instant — a title's whole cast, or an import — could be
  split across two sync pages, and everything after the split was silently never delivered.
  Sync pages now always run to the end of such a batch.

## [1.21.0] - 2026-07-26

### Added

- Web: a persistent "System" theme mode that keeps following your OS's light/dark preference
  live, not just at first load. The quick dark/light toggle (top bar and account menu) is now a
  3-way Light/Dark/System segmented control, and Settings → Appearance shows the same control
  alongside the full theme grid so the two never disagree. Picking Light/Dark explicitly (via the
  toggle, the `T` key, or the Appearance grid) breaks out of System mode, same as before.

## [1.20.0] - 2026-07-26

### Added

- Web: `Ctrl+,` / `Cmd+,` now opens Settings from anywhere, alongside the existing numbered nav
  shortcut. Listed in the keyboard shortcuts help (`?`) under Actions.

## [1.19.0] - 2026-07-25

### Changed

- Native Android app (in development, not yet distributed): **the Ledger no longer scrolls
  inside itself.** Every widget was a fixed-height box with its own scrollbar sitting inside the
  page's scrollbar, so reading one panel meant scrolling within it, and getting to the next one
  meant scrolling past it — two scrolls fighting over the same drag, with each widget showing
  about a third of what it had to say. Cards now size to their content, and the row-by-row
  detail underneath each visual collapses behind a button that says what it's hiding ("Show 8
  more decades"). What a widget is *telling* you — its heading, its one-line explanation, its
  headline figure and its chart — is always on screen. The result is one scroll for the whole
  tab, twenty widgets you can take in at a glance, and full width and height for the detail once
  you open it. Panels that were previously a bare list of labels and counts (Feature Lengths, On
  the Air, The Auteurs, In Translation, The Revival House) also gained the same subtitle and
  proportion bars the rest of the board already had.

## [1.18.0] - 2026-07-25

### Added

- Native Android app (in development, not yet distributed): **you can add titles from your
  phone.** Adding was the last mock left in the app — the "Add a title" overlay searched a
  hardcoded list of twelve films, and Add (there and on the Discover grid) only marked the
  poster for as long as the app stayed open, so the library could be tracked from Android but
  only *grown* from the web. It's now real end to end: a debounced TMDB search across films and
  series, a log step for status, rating and notes, and a write that lands in the local library
  immediately — offline included — before syncing up. A series brings its seasons and episodes
  down with it, so it's trackable episode by episode straight away, and every title carries its
  director, cast, genres, studios, certification and critic scores, so the Ledger's Auteurs,
  Ensemble and Second Opinions widgets have something to count. Add on a Discover poster now
  opens the same flow with that title already chosen, and adding something you already own is
  refused with an offer to open the copy you have.

### Fixed

- Native Android app (in development, not yet distributed): a title's cast and crew were wiped
  the first time the library synced after they were saved, so the Ledger's Auteurs and Ensemble
  widgets could never show anything, and a title's critic score and language were erased the
  same way. Syncing a title was deleting everything attached to it and putting the title back —
  seasons and episodes returned on the next sync and hid the damage, but cast and crew had
  nothing to bring them back.
- Native Android app (in development, not yet distributed): rating a title, and rating or
  annotating a viewing from the post-show sheet, never reached the server. Each of those writes
  sends only the field it changed, and the sync layer assumed a field none of them carry, so
  they failed on every attempt and sat in the pending queue forever — visible on the phone,
  absent everywhere else. They now sync.

## [1.17.0] - 2026-07-25

### Changed

- Native Android app (in development, not yet distributed): **Appearance settings** — the text
  controls now sit in their own container, matching the palette group above them instead of
  floating loose on the page; the theme toggle's System/Light/Dark options carry icons; and each
  font option's label is set in the typeface that option would actually apply, so the choice is
  shown rather than named.
- Native Android app (in development, not yet distributed): two more Ledger widgets rebuilt
  around their own data shape, bringing the total to twelve. **By the Era** becomes a timeline
  spine — decades are contiguous positions in time, so the run is gap-filled and a decade you
  own nothing from shows as a hollow tick rather than quietly closing up. **Shifting Standards**
  now draws your all-time average as a reference line and fills the area between it and the
  quarterly averages by sign, so a run of harsher quarters reads as a block below the line
  instead of a slightly lower squiggle.

## [1.16.0] - 2026-07-25

### Fixed

- Native Android app (in development, not yet distributed): **Premieres & Revivals** charted
  first watches and rewatches *added together*, which is the one transformation that makes the
  widget meaningless — a month of pure rediscovery and a month of pure rewatching drew the same
  line. The two are now split across a shared baseline, first watches rising and rewatches
  falling, so the balance the widget is named for is the thing you actually see.

### Changed

- Native Android app (in development, not yet distributed): three more Ledger widgets rebuilt
  around their own data shape, bringing the total to ten. **Premieres & Revivals** is described
  above. **Still Rolling** leads with how many episodes are left across the series shown and
  draws one countable cell per episode, since "six left" is what you act on where "75%
  complete" makes you do the subtraction. **The Ensemble** drops the chart entirely for a poster
  billing block — a cast tally is a ranked list of names, and putting the ranking into the
  typography reads the way the object it describes does.
- Native Android app (in development, not yet distributed): two more Ledger widgets rebuilt
  around their own data shape, bringing the total to seven. **Coming Attractions** now leads
  with the total runtime queued up, draws the backlog as one bar split into per-film segments,
  and gives every row a *running* total — read down the column and you can stop where the
  evening runs out. Series are listed but marked untimed, matching the rule that the estimate
  counts films only. **Critical Record** shows the ten rating buckets as a distribution whose
  shape is the point: bars fade as the rating drops, the most-used bucket is picked out, and
  the row your average falls in is flagged, so "I mostly give fours, and I average just under
  that" reads without arithmetic.

## [1.15.1] - 2026-07-25

### Fixed

- Native Android app (in development, not yet distributed): the in-app **Install** button for
  sideloaded builds did nothing — it downloaded the update and then stopped, with no prompt and
  no error, even with "install unknown apps" granted. The app committed the install session but
  never registered a receiver for the status broadcast the system replies with, and that
  broadcast carries the install-confirmation dialog the app has to launch itself. Nothing
  installs until it does; the granted permission only makes the app eligible to ask. Settings →
  Updates now also shows the download and the "waiting for the system installer" step instead of
  looking inert while they run, and reports install failures in plain language — including the
  signing-key mismatch you get from installing a CI-signed release APK over a locally-built one.
- Restored the `## [1.14.2]` heading in this changelog, dropped by an editing error while
  assembling 1.15.0. Its pull-to-refresh entry had been left sitting under `[1.15.0]`, which
  also put it into the published v1.15.0 release notes.

## [1.15.0] - 2026-07-25

### Changed

- Native Android app (in development, not yet distributed): five Ledger widgets were rebuilt
  around the shape of their own data instead of the shared label/count row every panel used
  to share. **Second Opinions** now plots your score and IMDb's as a dumbbell on one shared
  0–10 rail, sorted so the biggest disagreements lead, with a plain-language summary of which
  way you lean. **The Marathon** renders the last 30 nights as a perforated filmstrip with the
  live run picked out in accent, an oversized current-streak numeral, and a rail showing that
  run against your all-time best. **At the Movies** prints trip count, spend, and average
  price on a torn cinema ticket, with trips-by-year as bars below the tear and venue/companion/
  format tallies as receipt-style leader rows. **Screening Nights** wraps the week into a
  seven-spoke dial so a weekend habit reads as one lobe instead of two bars at opposite ends
  of a chart, with the peak night called out. **By the Genre** shows genre tallies as a field
  of bubbles sized on √(count/max), with a ranked list carrying the exact counts and shares.
  Every panel gained a one-line subtitle saying what it counts.
- Native Android app: the last two of those close parity gaps against the web app, which draws
  a radar for Screening Nights and bubbles for By the Genre where Android had a plain bar chart
  and a flat list. The Marathon's filmstrip also carries a run-encoded description covering all
  30 nights, closing the "no per-night label at all" accessibility gap the web app still has.

## [1.14.2] - 2026-07-25

### Fixed

- Native Android app (in development, not yet distributed): the pull-to-refresh indicator on
  Library, Discover, and Up Next now morphs between M3 Expressive shapes while refreshing
  (circle → cookie → burst → circle) instead of spinning a plain circular arc, and holds a
  checkmark briefly on completion so a successful refresh is actually visible.

## [1.14.1] - 2026-07-25

### Fixed

- Native Android app (in development, not yet distributed): magic-link sign-in failed on
  release builds ("Expected URL scheme 'http' or 'https' but no scheme was found") because the
  release APK build never populated the Supabase URL/key — the CI job now writes them before
  building.

## [1.14.0] - 2026-07-25

### Added

- Web: "Send to a friend" now supports attaching a where-to-watch link, pre-filled from the
  title's own custom link when set — friends see it right in their recommendations inbox.
- Native Android app (in development, not yet distributed): Library's status filter row moved
  behind a filter button, which now also offers sort order (title / newest / top rated),
  grouping by status, a minimum-rating filter, and genre filters.
- Native Android app (in development, not yet distributed): tapping a Discover result already
  in the library now opens the same title-detail screen Library uses, instead of a separate
  preview.

### Fixed

- Native Android app (in development, not yet distributed): two CodeQL-flagged PendingIntents
  (the cinema-outing alarm and its completion notification) now set an explicit package so
  they can't be intercepted by another app.
- Native Android app (in development, not yet distributed): the top-bar "→ Settings" avatar
  now shows the signed-in user's own initial instead of a hardcoded "C", and appears
  consistently on all four tabs (it was previously missing from Discover and Up Next).
- Native Android app (in development, not yet distributed): removed the "+ New Title" FAB from
  the Ledger tab — a stats dashboard isn't a place to add titles.
- Native Android app (in development, not yet distributed): poster corner radius reduced to
  match the app's shape scale (was noticeably rounder than intended).
- Native Android app (in development, not yet distributed): Permissions now shares the same
  seam-grouped card list Appearance's palette picker uses, and Appearance gained a divider
  separating theme/color settings from font/text settings.

## [1.13.0] - 2026-07-24

### Added

- Library: a poster-size control on the grid — compact / default / large, remembered between
  visits. Available at every width, since it is the only way to reach the mobile column counts.
- Native Android app (in development, not yet distributed): pinch the Library and Discover
  grids to resize them between one and four columns. The setting is shared by both tabs and
  remembered; cards shed their metadata line, then their title, as they get narrower.
- Native Android app (in development, not yet distributed): Settings → About now has an
  Updates section — an "Automatically check for updates" toggle (on by default) and a "Check
  for updates" action that always runs regardless of the toggle. Sideloaded installs compare
  themselves against the latest GitHub Release and can install the update in-app when the
  install permission is granted, falling back to opening the release page when it isn't.

### Changed

- Poster cards no longer dim their artwork behind a contrast scrim when nothing is drawn over
  the poster — most visibly on the Up Next tab, where the cards' posters carry no overlaid
  text.
- Title drawer: the episode carousel now comes before the season cast, and Cast & Crew is the
  only collapsible section — the season cast row renders expanded.
- Title drawer: the Cast & Crew preview now shows as many people as fit the available width
  instead of a fixed five.
- The keyboard shortcuts panel is laid out in two columns and no longer needs scrolling.
- Native Android app (in development, not yet distributed): the back gesture is now predictive
  — overlays follow your finger and reveal the screen behind, and an abandoned swipe springs
  back instead of navigating.
- Native Android app (in development, not yet distributed): pull-to-refresh uses the Material 3
  Expressive indicator on Library, Discover and Up Next.
- Native Android app (in development, not yet distributed): Library list rows and the
  Appearance colour palettes are grouped M3 containers rather than loose rows and separate
  cards, and the screen top bars have been tightened so more content fits above the fold.
- Native Android app (in development, not yet distributed): "Source on GitHub" and "Release
  notes" in Settings → About & Legal are buttons with icons rather than plain text.

### Fixed

- The star rating now stays legible in light mode. Previously it inherited the surrounding
  text colour, so on the title drawer's hero — a dark scrim over backdrop art that stays dark
  in both themes — light mode painted dark stars onto a near-black chip.
- The "I've got tickets for…" button and the post-show dismiss button on Up Next cards no
  longer render as a black disc in light mode.
- The Cinema Outing banner in the title drawer no longer sits flush against the content
  beneath it.
- The trailer strip in the title drawer now shows its right-hand edge fade on open, instead of
  only after the first scroll.
- Native Android app (in development, not yet distributed): Discover no longer offers an add
  button for titles already in your library — it previously only knew about titles added in
  the current session, forgetting anything synced down or added on the web.
- Native Android app (in development, not yet distributed): Settings shows your name instead
  of a hardcoded "Cinephile" placeholder.
- Native Android app (in development, not yet distributed): bottom nav icons are centred in
  their indicator pill, and single-select button groups bold the selected option.

## [1.12.1] - 2026-07-24

### Fixed

- Native Android app (in development, not yet distributed): Ledger widgets backed by category
  data (Feature Lengths, On the Air, By the Era, Critical Record, By the Genre, The Auteurs,
  The Ensemble, In Translation, The Revival House) no longer render as a blank, titleless card
  when they have no data — they now show their title plus an explanatory message, matching
  every other widget's empty state.

## [1.12.0] - 2026-07-23

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

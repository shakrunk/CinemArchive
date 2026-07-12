# Known Problems

This document tracks known issues, technical debt, and usability improvements for the CinemArchive codebase.

## Current Backlog

### KP-045: Assess importing watch history/ratings from other platforms

- **Description**: Determine how difficult it would be to import watch history and/or ratings from other platforms (Letterboxd, IMDb, Trakt, Netflix, Simkl, …). CinemArchive already has JSON export/import between its own instances, but nothing that ingests third-party data.
- **Impacted Codebase**: TBD — likely a new import module plus the Add-Title/TMDB matching path
- **Proposed Solution**: Write a feasibility assessment (per-platform export formats, TMDB matching strategy, effort estimates) under `docs/`, then prototype the lowest-effort importer (likely Letterboxd CSV).

---

## Resolved: Follow-up Polish (2026-07-12)

| ID     | Issue                                                            | Resolution |
| ------ | ---------------------------------------------------------------- | ---------- |
| KP-039 | Verify invite-code redeemers appear as suggested friend connects | Stale note — already shipped as KP-026. Verified end to end: migration `20260710130000` is on `main` (applied by `db-migrate.yml`), `list_invite_connections()` covers both `invited_by_you`/`invited_you` lineage and excludes every existing friendship state, `handle_new_user()` guarantees the `profiles` join can't drop a redeemer, and the Friends tab renders "Suggested friends" with a one-tap request. No code change needed. |
| KP-040 | Improve the Bechdel test badge icon                              | Replaced the Venus (♀) glyph with a bespoke `BechdelIcon` in [media-badges.tsx](file:///V:/repos/CinemArchive/src/components/ui/media-badges.tsx) — two figures under a conversation ellipsis, hand-drawn on Lucide's 24px stroke grid so it takes the same size/stroke/color as the stock badge icons. |
| KP-041 | Reword the Discover hero heading to fit the brand vibe           | The search hero in [Discover.tsx](file:///V:/repos/CinemArchive/src/views/Discover.tsx) now reads "Scout the next reel for the *vault.*" under the existing "the acquisitions desk" kicker — swapping the plain question for the projection-room register used across the app. |
| KP-042 | Cohesive GUI for the accessibility buttons                       | The skip link and keyboard-shortcuts button in [App.tsx](file:///V:/repos/CinemArchive/src/App.tsx) now live in a single `nav` toolbar (bordered card, shared pill style) that slides in from above when either control gains keyboard focus — replacing two independently positioned pills with a hardcoded `left-40` offset. The focused pill highlights amber to mark the active control. |
| KP-043 | Collapse the nav bar word mark into the logo sooner              | The [TopBar.tsx](file:///V:/repos/CinemArchive/src/components/TopBar.tsx) word mark moved from `hidden lg:flex` to `hidden xl:flex` — only the spinning reel mark shows below `xl` (1280px), giving the pill nav breathing room at the widths where it was still crowded after KP-033. |
| KP-044 | Make the Discover carousel pause buttons more prominent          | The KP-036 hover-only overlay in the strip's corner is gone; each carousel header (trending/search, Because You Watched, More Starring) now carries an always-visible labeled Pause/Play chip at its right edge, next to the View-more link / taste dropdowns. `DiscoverCarousel` takes the sticky pause as a controlled `paused` prop; the strip keeps only the transient hover/focus pause. |

---

## Resolved: Backlog Clearance (2026-07-10)

**Resolved 2026-07-10.** Every remaining backlog item (KP-001 through KP-003 and
KP-023 through KP-038) shipped in one pass. Summary of what landed per item:

| ID     | Issue                                                            | Resolution |
| ------ | ---------------------------------------------------------------- | ---------- |
| KP-001 | Desktop horizontal scroll support for Discovery carousels        | Carousels already supported mouse drag and hover chevrons; added mouse-wheel/trackpad horizontal scrolling (native non-passive `wheel` listener consuming horizontal-dominant deltas) to complete desktop coverage. |
| KP-002 | Personal "Home Collection" option in Watch Providers list        | "In my home collection" toggle in the Where to Watch header ([watch-providers.tsx](file:///V:/repos/CinemArchive/src/components/ui/watch-providers.tsx)); when set, an amber "Home Collection" source row appears, including in shared/friend views. Persisted as `titles.in_home_collection`. |
| KP-003 | Physical Media Asset Cataloging System (DVD/Blu-ray/etc.)        | Physical media shelf in Where to Watch: catalog copies per title with format (DVD, Blu-ray, 4K UHD, VHS, LaserDisc, Other) and optional edition/notes. Stored as `PhysicalMediaItem[]` in `titles.physical_media` (jsonb); read-only chips in shared views. |
| KP-023 | Missing "About" section on the Settings/Profile page             | About section on [Profile.tsx](file:///V:/repos/CinemArchive/src/views/Profile.tsx) with app version (from `package.json` via Vite `define` → `__APP_VERSION__`), description, TMDB/OMDb/Wikidata credits, and repo links; version footer added to the sign-in modal. |
| KP-024 | TMDB integration for the "Because You Watched" carousel          | New `recommendations` media-proxy action wrapping TMDB `/movie|tv/{id}/recommendations`; `fetchRecommendations` in [media.ts](file:///V:/repos/CinemArchive/src/lib/media.ts); Discover now fetches real similar titles for the selected basis title instead of filtering trending. |
| KP-025 | Local/Dev Server auto-bypass for Authenticated User Features     | Implemented earlier in `b5ba2df`: clicking Sign in on the dev server signs in a mock local session (`src/lib/devAuth.ts`), gated behind `import.meta.env.DEV`. |
| KP-026 | Suggested friend connections for invite code redeemers/creators  | `list_invite_connections()` SECURITY DEFINER RPC (migration `20260710130000`) surfaces users linked by invite lineage who have no friendship row; Friends tab shows them under "Suggested friends" with a one-tap request. |
| KP-027 | Display "Other movies in this franchise" in details              | `FranchiseSection` in [TitleDetailDrawer.tsx](file:///V:/repos/CinemArchive/src/components/TitleDetailDrawer.tsx) fetches the TMDB collection (`collection` media-proxy action) and renders every part in release order; library entries open in place, others can be added. |
| KP-028 | Franchise movie watching progress tracking                       | Same section shows "Watched X/Y" and a progress bar, counting collection parts that exist in the library with `watched` status. |
| KP-029 | Custom icon for Bechdel test badge                               | The badge now renders a Venus (♀) Lucide icon with an sr-only "Bechdel test" label instead of the "BDT" text. |
| KP-030 | Search input in "More starring" carousel filter dropdown         | `TasteDropdown` grows a filter input (auto-focused, Escape closes) once the option list exceeds 8 entries. |
| KP-031 | Uniform page display titles across views                         | Library gained a `kicker` + `display-title` header ("The Library"); Discover's hero was restyled to the same Fraunces display-title identity. |
| KP-032 | Responsive collapse of Nav bar search bar sooner                 | Search label + `⌘K` hint now `hidden xl:inline` (was `lg:inline`). |
| KP-033 | Responsive collapse of Nav bar word mark sooner                  | Word mark/tagline now `hidden lg:flex` (was `sm:flex`); only the reel mark shows below `lg`. |
| KP-034 | Title Case capitalization for Discover carousel titles           | "Because You Watched" and "More Starring" headers now use Title Case. |
| KP-035 | Keyboard shortcuts button next to skip nav button                | A hidden-until-focused "Keyboard shortcuts" button follows the skip-nav link in [App.tsx](file:///V:/repos/CinemArchive/src/App.tsx) and opens the shortcuts dialog. |
| KP-036 | Pause button for Discover page carousels                         | Play/pause overlay button per carousel; stays visible while paused, and the paused state holds independently of the hover pause. |
| KP-037 | Momentum scrolling for grab-and-drag carousel interaction        | Drag velocity is low-pass tracked on pointer move and decays exponentially in the rAF loop after release (clamped, suppressed for stalled pointers and reduced motion). |
| KP-038 | "Critical Record" widget Gestalt design improvements             | Legend rows switched from a two-column grid to a flex row with a dotted leader connecting each star label to its count/percentage, restoring proximity grouping. |

---

## Resolved: Ledger Widget Layout & Responsive Scaling

**Resolved 2026-07-10.** KP-004 through KP-022 now use width-preset-aware,
fluid layouts and have been checked at desktop and mobile viewports. Charts no
longer rely on fixed minimum widths that force internal horizontal scrolling;
ranked/list widgets distribute their content across the available card width.
The follow-up composition pass also makes mobile independent of saved desktop
widths, removes nested card scroll surfaces, vertically balances panel bodies,
and uses alternate compact visual forms when a desktop chart would be crowded.

> [!IMPORTANT]
> **DEVELOPMENT GUIDELINE FOR CODING AGENTS:**
> Do not attempt to batch or implement these widget improvements all at once. To ensure high quality, maintain proper alignment, and preserve the visual uniqueness of each chart/visualization, agents **MUST** focus on a single widget at a time, testing its responsiveness and layout behavior at all sizes (`sm`, `md`, `lg`, `full`) before moving on to the next.

### KP-004: "Time in the Dark" widget scaling

- **Description**: The "Time in the Dark" widget (Activity Heatmap) does not scale or fill its container appropriately at various layout sizes. The visual elements (such as cell spacing and sizes) should adapt to different grid container sizes.
- **Impacted Codebase**: [ActivityHeatmap.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/ActivityHeatmap.tsx)
- **Proposed Solution**: Make the heatmap grids fluid or adjust cell layout configurations based on current container dimensions so it cleanly fills the container space.

### KP-005: "Encore Performances" widget scaling

- **Description**: The "Encore Performances" (Most-rewatched titles) widget needs to scale and fill its container cleanly across small, medium, large, and full grid widths.
- **Impacted Codebase**: [EncorePerformances.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/EncorePerformances.tsx)
- **Proposed Solution**: Update component styles to utilize container-based flex/grid alignments or responsive fonts/paddings.

### KP-007: "Critical Record" widget scaling

- **Description**: The rating distribution chart ("Critical Record") needs to fill its container and align its legends cleanly at various sizes.
- **Impacted Codebase**: [RatingDistribution.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/RatingDistribution.tsx)
- **Proposed Solution**: Adjust responsive radius sizing and list layouts.

### KP-009: "By the Era" widget scaling

- **Description**: The release decade filmstrip ("By the Era") needs to scale and fill the container cleanly.
- **Impacted Codebase**: [DecadeFilmstrip.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/DecadeFilmstrip.tsx)
- **Proposed Solution**: Enhance item spacing and column layout at varying grid widths.

### KP-010: "The Auteurs" widget scaling

- **Description**: The most-watched directors widget ("The Auteurs") needs to scale cleanly.
- **Impacted Codebase**: [TheAuteurs.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/TheAuteurs.tsx)
- **Proposed Solution**: Update row or list layouts to prevent empty space.

### KP-011: "The Ensemble" widget scaling

- **Description**: The most-billed leading cast list ("The Ensemble") needs to scale cleanly.
- **Impacted Codebase**: [TheEnsemble.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/TheEnsemble.tsx)
- **Proposed Solution**: Optimize column distribution and avatar sizing for different grid states.

### KP-012: "Feature Lengths" widget scaling

- **Description**: The movie runtime histogram ("Feature Lengths") needs to fill the container at all widths.
- **Impacted Codebase**: [RuntimeSpectrum.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/RuntimeSpectrum.tsx)
- **Proposed Solution**: Scale histogram bars and axes labels responsiveness.

### KP-013: "On the Air" widget scaling

- **Description**: The top TV networks chart ("On the Air") needs to scale and fit container sizes.
- **Impacted Codebase**: [OnTheAir.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/OnTheAir.tsx)
- **Proposed Solution**: Implement fluid layout elements that adapt dynamically.

### KP-014: "Second Opinions" widget scaling

- **Description**: The ratings vs IMDb comparison widget ("Second Opinions") needs to fit container widths.
- **Impacted Codebase**: [SecondOpinions.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/SecondOpinions.tsx)
- **Proposed Solution**: Ensure comparative chart plots scale and fit inside smaller boxes without clipping.

### KP-015: "In Translation" widget scaling

- **Description**: The original language breakdown chart ("In Translation") needs container-filling fixes.
- **Impacted Codebase**: [InTranslation.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/InTranslation.tsx)
- **Proposed Solution**: Fix alignment of labels and percentage bars.

### KP-016: "Screening Nights" widget scaling

- **Description**: The screenings by day of week bar chart ("Screening Nights") needs container-filling fixes.
- **Impacted Codebase**: [ScreeningNights.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/ScreeningNights.tsx)
- **Proposed Solution**: Adjust spacing and heights to look robust on all sizes.

### KP-017: "The Marathon" widget scaling

- **Description**: The screening streaks widget ("The Marathon") needs container-filling fixes.
- **Impacted Codebase**: [TheMarathon.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/TheMarathon.tsx)
- **Proposed Solution**: Center and size elements proportionately.

### KP-018: "Shifting Standards" widget scaling

- **Description**: The average rating trend chart over time ("Shifting Standards") needs container-filling fixes.
- **Impacted Codebase**: [ShiftingStandards.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/ShiftingStandards.tsx)
- **Proposed Solution**: Scale the trend line and markers to match container dimensions.

### KP-019: "Premieres & Revivals" widget scaling

- **Description**: The encores comparison chart ("Premieres & Revivals") needs container-filling fixes.
- **Impacted Codebase**: [PremieresRevivals.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/PremieresRevivals.tsx)
- **Proposed Solution**: Enhance grid line and chart column responsiveness.

### KP-020: "The Revival House" widget scaling

- **Description**: The film age tracking widget ("The Revival House") needs container-filling fixes.
- **Impacted Codebase**: [TheRevivalHouse.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/TheRevivalHouse.tsx)
- **Proposed Solution**: Adjust flex or grid elements.

### KP-021: "Still Rolling" widget scaling

- **Description**: The series completion widget ("Still Rolling") needs container-filling fixes.
- **Impacted Codebase**: [StillRolling.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/StillRolling.tsx)
- **Proposed Solution**: Optimize layout of multiple series progress bars.

### KP-022: "Coming Attractions" widget scaling

- **Description**: The watchlist weight widget ("Coming Attractions") needs container-filling fixes.
- **Impacted Codebase**: [ComingAttractions.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/ComingAttractions.tsx)
- **Proposed Solution**: Prevent clipping and ensure proper margins.

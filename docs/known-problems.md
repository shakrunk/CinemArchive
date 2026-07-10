# Known Problems

This document tracks known issues, technical debt, and usability improvements for the CinemArchive codebase.

## Current Backlog

| ID | Issue / Feature Request | Component / File | Severity / Type |
|----|------------------------|------------------|-----------------|
| KP-001 | [Desktop horizontal scroll support for Discovery carousels](#kp-001-desktop-horizontal-scroll-support-for-discovery-carousels) | [Discover.tsx](file:///V:/repos/CinemArchive/src/views/Discover.tsx) | Usability / UI |
| KP-002 | [Personal "Home Collection" option in Watch Providers list](#kp-002-personal-home-collection-option-in-watch-providers-list) | [watch-providers.tsx](file:///V:/repos/CinemArchive/src/components/ui/watch-providers.tsx) | Feature / UI |
| KP-003 | [Physical Media Asset Cataloging System (DVD/Blu-ray/etc.)](#kp-003-physical-media-asset-cataloging-system-dvdblu-rayetc) | [TitleDetailDrawer.tsx](file:///V:/repos/CinemArchive/src/components/TitleDetailDrawer.tsx) | Feature / Data |
| KP-004 | [Ledger Widget Layout & Responsive Scaling Backlog](#ledger-widget-layout--responsive-scaling-backlog) | (Various Ledger panels - detailed below) | UI / UX |
| KP-023 | [Missing "About" section on the Settings/Profile page](#kp-023-missing-about-section-on-the-settingsprofile-page) | [Profile.tsx](file:///V:/repos/CinemArchive/src/views/Profile.tsx) | Feature / Settings |
| KP-024 | [TMDB integration for the "Because You Watched" carousel](#kp-024-tmdb-integration-for-the-because-you-watched-carousel) | [Discover.tsx](file:///V:/repos/CinemArchive/src/views/Discover.tsx) | Integration / API |
| KP-025 | [Local/Dev Server auto-bypass for Authenticated User Features](#kp-025-localdev-server-auto-bypass-for-authenticated-user-features) | [auth.ts](file:///V:/repos/CinemArchive/src/lib/auth.ts) | Developer Experience |

---

### KP-001: Desktop horizontal scroll support for Discovery carousels
* **Description**: Movie and TV recommendation carousels on the Discovery view do not support horizontal scroll mechanisms via mouse drag or explicit navigation buttons in desktop web environments. They are currently only scrollable via horizontal swipe/trackpad gestures.
* **Impacted Codebase**: [Discover.tsx](file:///V:/repos/CinemArchive/src/views/Discover.tsx)
* **Proposed Solution**: Add visible left/right hover controls or support mouse drag-to-scroll on desktop viewports.

### KP-002: Personal "Home Collection" option in Watch Providers list
* **Description**: The watch providers section currently pulls streaming, rental, and purchase platforms via the TMDB API, and allows custom external links. It lacks a native option to indicate that a title is owned locally or is part of a physical "Home Collection" (e.g. Blu-ray, DVD, local media server).
* **Impacted Codebase**: 
  - [watch-providers.tsx](file:///V:/repos/CinemArchive/src/components/ui/watch-providers.tsx) (UI rendering)
  - [TitleDetailDrawer.tsx](file:///V:/repos/CinemArchive/src/components/TitleDetailDrawer.tsx) (State management and display)
* **Proposed Solution**: Introduce a checkbox or dedicated "In My Home Collection" toggle in the watch provider settings. When enabled, display a "Home Collection" badge/source in the "Where to Watch" section of the details panel.

### KP-003: Physical Media Asset Cataloging System (DVD/Blu-ray/etc.)
* **Description**: The application needs a dedicated mechanism to track physical media library ownership details. This should catalog physical asset variants (e.g., DVD, Blu-ray, 4K Ultra HD, VHS, LaserDisc) for titles in the library, independent of streaming or virtual watch providers.
* **Impacted Codebase**:
  - [TitleDetailDrawer.tsx](file:///V:/repos/CinemArchive/src/components/TitleDetailDrawer.tsx) (UI for cataloging physical media formats and metadata)
  - [mockData.ts](file:///V:/repos/CinemArchive/src/store/mockData.ts) (Schema changes to `Title` or a new entity to represent physical assets)
* **Proposed Solution**: Design an inventory sub-section or schema model allowing users to checklist and specify properties (e.g., edition, format, packaging) of physical copies in their home library.

---

## Ledger Widget Layout & Responsive Scaling Backlog

> [!IMPORTANT]
> **DEVELOPMENT GUIDELINE FOR CODING AGENTS:** 
> Do not attempt to batch or implement these widget improvements all at once. To ensure high quality, maintain proper alignment, and preserve the visual uniqueness of each chart/visualization, agents **MUST** focus on a single widget at a time, testing its responsiveness and layout behavior at all sizes (`sm`, `md`, `lg`, `full`) before moving on to the next.

### KP-004: "Time in the Dark" widget scaling
* **Description**: The "Time in the Dark" widget (Activity Heatmap) does not scale or fill its container appropriately at various layout sizes. The visual elements (such as cell spacing and sizes) should adapt to different grid container sizes.
* **Impacted Codebase**: [ActivityHeatmap.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/ActivityHeatmap.tsx)
* **Proposed Solution**: Make the heatmap grids fluid or adjust cell layout configurations based on current container dimensions so it cleanly fills the container space.

### KP-005: "Encore Performances" widget scaling
* **Description**: The "Encore Performances" (Most-rewatched titles) widget needs to scale and fill its container cleanly across small, medium, large, and full grid widths.
* **Impacted Codebase**: [EncorePerformances.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/EncorePerformances.tsx)
* **Proposed Solution**: Update component styles to utilize container-based flex/grid alignments or responsive fonts/paddings.

### KP-007: "Critical Record" widget scaling
* **Description**: The rating distribution chart ("Critical Record") needs to fill its container and align its legends cleanly at various sizes.
* **Impacted Codebase**: [RatingDistribution.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/RatingDistribution.tsx)
* **Proposed Solution**: Adjust responsive radius sizing and list layouts.

### KP-009: "By the Era" widget scaling
* **Description**: The release decade filmstrip ("By the Era") needs to scale and fill the container cleanly.
* **Impacted Codebase**: [DecadeFilmstrip.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/DecadeFilmstrip.tsx)
* **Proposed Solution**: Enhance item spacing and column layout at varying grid widths.

### KP-010: "The Auteurs" widget scaling
* **Description**: The most-watched directors widget ("The Auteurs") needs to scale cleanly.
* **Impacted Codebase**: [TheAuteurs.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/TheAuteurs.tsx)
* **Proposed Solution**: Update row or list layouts to prevent empty space.

### KP-011: "The Ensemble" widget scaling
* **Description**: The most-billed leading cast list ("The Ensemble") needs to scale cleanly.
* **Impacted Codebase**: [TheEnsemble.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/TheEnsemble.tsx)
* **Proposed Solution**: Optimize column distribution and avatar sizing for different grid states.

### KP-012: "Feature Lengths" widget scaling
* **Description**: The movie runtime histogram ("Feature Lengths") needs to fill the container at all widths.
* **Impacted Codebase**: [RuntimeSpectrum.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/RuntimeSpectrum.tsx)
* **Proposed Solution**: Scale histogram bars and axes labels responsiveness.

### KP-013: "On the Air" widget scaling
* **Description**: The top TV networks chart ("On the Air") needs to scale and fit container sizes.
* **Impacted Codebase**: [OnTheAir.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/OnTheAir.tsx)
* **Proposed Solution**: Implement fluid layout elements that adapt dynamically.

### KP-014: "Second Opinions" widget scaling
* **Description**: The ratings vs IMDb comparison widget ("Second Opinions") needs to fit container widths.
* **Impacted Codebase**: [SecondOpinions.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/SecondOpinions.tsx)
* **Proposed Solution**: Ensure comparative chart plots scale and fit inside smaller boxes without clipping.

### KP-015: "In Translation" widget scaling
* **Description**: The original language breakdown chart ("In Translation") needs container-filling fixes.
* **Impacted Codebase**: [InTranslation.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/InTranslation.tsx)
* **Proposed Solution**: Fix alignment of labels and percentage bars.

### KP-016: "Screening Nights" widget scaling
* **Description**: The screenings by day of week bar chart ("Screening Nights") needs container-filling fixes.
* **Impacted Codebase**: [ScreeningNights.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/ScreeningNights.tsx)
* **Proposed Solution**: Adjust spacing and heights to look robust on all sizes.

### KP-017: "The Marathon" widget scaling
* **Description**: The screening streaks widget ("The Marathon") needs container-filling fixes.
* **Impacted Codebase**: [TheMarathon.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/TheMarathon.tsx)
* **Proposed Solution**: Center and size elements proportionately.

### KP-018: "Shifting Standards" widget scaling
* **Description**: The average rating trend chart over time ("Shifting Standards") needs container-filling fixes.
* **Impacted Codebase**: [ShiftingStandards.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/ShiftingStandards.tsx)
* **Proposed Solution**: Scale the trend line and markers to match container dimensions.

### KP-019: "Premieres & Revivals" widget scaling
* **Description**: The encores comparison chart ("Premieres & Revivals") needs container-filling fixes.
* **Impacted Codebase**: [PremieresRevivals.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/PremieresRevivals.tsx)
* **Proposed Solution**: Enhance grid line and chart column responsiveness.

### KP-020: "The Revival House" widget scaling
* **Description**: The film age tracking widget ("The Revival House") needs container-filling fixes.
* **Impacted Codebase**: [TheRevivalHouse.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/TheRevivalHouse.tsx)
* **Proposed Solution**: Adjust flex or grid elements.

### KP-021: "Still Rolling" widget scaling
* **Description**: The series completion widget ("Still Rolling") needs container-filling fixes.
* **Impacted Codebase**: [StillRolling.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/StillRolling.tsx)
* **Proposed Solution**: Optimize layout of multiple series progress bars.

### KP-022: "Coming Attractions" widget scaling
* **Description**: The watchlist weight widget ("Coming Attractions") needs container-filling fixes.
* **Impacted Codebase**: [ComingAttractions.tsx](file:///V:/repos/CinemArchive/src/views/ledger/panels/ComingAttractions.tsx)
* **Proposed Solution**: Prevent clipping and ensure proper margins.

---

### KP-023: Missing "About" section on the Settings/Profile page
* **Description**: The Settings/Profile page does not include an "About" or "App Info" section to showcase the app version, details about CinemArchive, and credits/project links.
* **Impacted Codebase**: 
  - [Profile.tsx](file:///V:/repos/CinemArchive/src/views/Profile.tsx)
  - [ProfileModal.tsx](file:///V:/repos/CinemArchive/src/components/ProfileModal.tsx)
* **Proposed Solution**: Append a clean "About" subsection to the Settings view detailing application version info, features, and source repository credits.

### KP-024: TMDB integration for the "Because You Watched" carousel
* **Description**: The "Because You Watched" recommendations row on the Discover page currently falls back to displaying a filtered trending titles list rather than fetching actual similar titles from TMDB based on the source title.
* **Impacted Codebase**: 
  - [Discover.tsx](file:///V:/repos/CinemArchive/src/views/Discover.tsx) (UI & logic)
  - Edge Function [supabase/functions/media-proxy/index.ts](file:///V:/repos/CinemArchive/supabase/functions/media-proxy/index.ts) (which handles TMDB queries)
* **Proposed Solution**: Update the backend media-proxy query to fetch from TMDB's `/movie/{id}/recommendations` or `/tv/{id}/recommendations` endpoints, and retrieve real recommendations.

### KP-025: Local/Dev Server auto-bypass for Authenticated User Features
* **Description**: During local development, logging in or testing authenticated user features (e.g. friends, recommendations, private access) is difficult or requires actual external credentials. Clicking "Sign In" should bypass auth on localhost and automatically enable authenticated-only features.
* **Impacted Codebase**: 
  - [auth.ts](file:///V:/repos/CinemArchive/src/lib/auth.ts)
  - [Profile.tsx](file:///V:/repos/CinemArchive/src/views/Profile.tsx)
  - [ProfileModal.tsx](file:///V:/repos/CinemArchive/src/components/ProfileModal.tsx)
* **Proposed Solution**: Detect if the app is running in a local development environment (e.g. `localhost` / `127.0.0.1` or `import.meta.env.DEV`), and automatically generate mock session credentials when the user triggers the sign-in action.

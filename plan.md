# Project Name: Cinematic Archive (The Projection Room v2)
# Architecture: Jamstack (Static Frontend + BaaS Backend)
# Deployment Target: GitHub Pages
# Core Technologies: Vite, React, TypeScript, Tailwind CSS, Supabase (Database/Auth)

## Phase 0: Foundation & Scaffold (SEQUENTIAL - RUN FIRST)
**Agent Instructions:** This phase must be completed by a single agent before parallelization begins.
* [ ] Initialize Git repository.
* [ ] Scaffold the frontend using `npm create vite@latest . -- --template react-ts`.
* [ ] Run `npx shadcn-ui@latest init` to configure Tailwind and the base shadcn component structure. 
* [ ] Install base UI components: `npx shadcn-ui@latest add button card dialog sheet input dropdown-menu table scroll-area tabs slider`.
* [ ] Apply the dark/gold theme to `globals.css` (Base: Void #0b0907, Amber Highlights: #e9b266).
* [ ] Implement V1 atmospheric CSS classes in `globals.css` (`.grain` noise overlay, `.vignette`, `.projector-beam` header animation).
* [ ] Import V1 Typography via Google Fonts: `Fraunces` (Serif titles), `Hanken Grotesk` (UI Sans), `DM Mono` (Stats).
* [ ] Setup ESLint and Prettier to ensure consistent code styling across future parallel agents.
* [ ] Create `src/store/mockData.ts` to hold dummy movie, TV series, and ledger data.
* [ ] Initialize `graphify` in the repository so future parallel agents can query the codebase structure to avoid blind hallucinations.

---

## Phase 1: Parallel Tracks (Execution of A, B, and C may be done simultaneously)

### Track A: Backend & Data Layer (Sub-Agent 1)
**Dependencies:** None.
* [ ] **Database Schema:** Write a `schema.sql` file. Replace V1's JSON schema with relational tables: `titles` (enum: movie/tv), `seasons` (for TV relations), `viewings` (for re-watch timelines), and `shared_access_keys`.
* [ ] **Row Level Security (RLS):** Write SQL policies ensuring the authenticated user has full CRUD access, while `shared_access_keys` grant time-bound read-only access.
* [ ] **Auth Module:** Create `src/lib/auth.ts` implementing Passkey/WebAuthn login using the Supabase Auth client.
* [ ] **API Proxy:** Create a Supabase Edge Function to query the TMDB API (for core metadata/posters) and OMDb API (for IMDb/RT/Metacritic badges) securely. Implement a caching layer to avoid rate limits.

### Track B: UI Component Library (Sub-Agent 2)
**Dependencies:** Phase 0 (Tailwind config).
* [ ] Create `src/components/ui`.
* [ ] Build isolated, responsive (mobile-first) atomic components:
    * Buttons (Primary Gold, Secondary outline).
    * Typography system (mapping Fraunces, Hanken Grotesk, DM Mono).
    * Inputs (Search bars, text areas, star-rating component).
    * Bottom-sheet Modal wrapper for mobile.
* [ ] Build the "Dynamic Typographic Poster" component (generates a colored background with text for missing artwork).
* [ ] Build the Navigation shell (`TopBar` for desktop, `BottomNav` for mobile).

### Track C: Global State Management (Sub-Agent 3)
**Dependencies:** Phase 0 (mockData).
* [ ] Setup Zustand in `src/store/useAppStore.ts`.
* [ ] Create slices for `library` (array of titles), `ledger` (calculated stats), and `ui` (modal open/close states, grid vs. list view toggle).
* [ ] Implement purely client-side filtering/sorting logic (Title, Director, Tags, Watch Status, Decade chips, Network chips, Min-Rating slider).

---

## Phase 2: Feature Assembly (Execute D, E, and F simultaneously)
**Dependencies:** Tracks B & C must be complete.

### Track D: "The Library" View (Sub-Agent 4)
* [ ] Build `src/views/Library.tsx`.
* [ ] Implement the View Toggle: Switch between Virtualized Media Grid (Poster Wall) and Ledger List (Sortable Table).
* [ ] Integrate the Zustand store's filtering/sorting methods into the view.
* [ ] Build the `TitleDetailDrawer` bottom-sheet modal to display full metadata, external review badges (RT, IMDb), and a vertical timeline plotting all viewing history dates.

### Track E: "The Ledger" View (Sub-Agent 5)
* [ ] Build `src/views/Ledger.tsx`.
* [ ] Integrate a lightweight charting library (e.g., Recharts or Chart.js).
* [ ] Build V1 parity charts: "Summary Statistics Row", "Critical Record" (rating distribution), "Time in the Dark", "Genre Marquee", "Screenings Timeline" (by month), and "The Auteurs" (top directors).
* [ ] Ensure all charts are responsive and touch-friendly for mobile.

### Track F: Title Management (Sub-Agent 6)
* [ ] Build `src/components/AddTitleWorkflow.tsx`.
* [ ] Create the debounced search interface connected to the Edge Function proxy.
* [ ] Build the logging form for dates, ratings (out of 5), and text notes.
* [ ] Build the TV Season Editor (auto-generates seasons, tracks episodes watched, "Start Series", "Mark Season Complete" actions).
* [ ] Implement Optimistic UI updates (updating the Zustand store immediately before backend confirmation).

---

## Phase 3: Integration & Wiring (SEQUENTIAL)
**Dependencies:** All Phase 1 and Phase 2 Tracks must be complete.
**Agent Instructions:** Single agent required to prevent merge conflicts.
* [ ] Replace `mockData.ts` imports with real API calls using the Supabase client.
* [ ] Wire the `AddTitleWorkflow` to the Edge Function.
* [ ] Wire the authentication flow to protect all routes except read-only links with valid tokens.
* [ ] Implement Vite-PWA plugin to generate the Web App Manifest, Service Workers, and asset caching for mobile home-screen installation.

---

## Phase 4: CI/CD & Deployment (SEQUENTIAL)
**Dependencies:** Phase 3.
* [ ] Create `.github/workflows/deploy.yml`.
* [ ] Configure the GitHub Action to install dependencies, run `npm run build`, and deploy the `dist/` directory to the `gh-pages` branch.
* [ ] Ensure the deployment action correctly handles single-page application (SPA) routing fallbacks for GitHub pages (using a `404.html` hack or similar method).

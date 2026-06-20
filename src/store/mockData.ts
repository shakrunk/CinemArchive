export type MediaType = 'movie' | 'tv'
export type WatchStatus = 'watched' | 'watchlist' | 'watching' | 'dropped'

export interface EpisodeWatchEvent {
  id: string
  watchedAt: string  // ISO date (YYYY-MM-DD)
  notes?: string
}

export interface EpisodeRating {
  id: string
  rating: number    // 0–5 (matches title-level rating scale)
  ratedAt: string   // ISO datetime — timestamped when user records it, not when watched
}

export interface EpisodeReview {
  id: string
  reviewText: string
  reviewedAt: string  // ISO datetime — standalone timestamp
}

export interface Episode {
  id: string
  episodeNumber: number
  episodeName?: string
  airDate?: string
  runtime?: number
  synopsis?: string
  stillUrl?: string
  watchEvents: EpisodeWatchEvent[]
  ratings: EpisodeRating[]    // independent historical log
  reviews: EpisodeReview[]    // independent historical log
}

export interface Season {
  id: string
  seasonNumber: number
  episodeCount: number
  episodesWatched: number  // derived from episodes with watch events when episodes[] is present
  airYear?: number
  episodes?: Episode[]
}

export interface Viewing {
  id: string
  titleId: string
  date: string
  rating?: number
  notes?: string
}

export interface Title {
  id: string
  tmdbId: number
  type: MediaType
  title: string
  year: number
  director?: string
  genres: string[]
  posterUrl?: string
  backdropUrl?: string
  synopsis?: string
  runtime?: number
  network?: string
  seasons?: Season[]
  status: WatchStatus
  rating?: number
  notes?: string
  tags: string[]
  addedAt: string
  viewings: Viewing[]
  imdbRating?: number
  rtScore?: number
  metacriticScore?: number
}

export interface LedgerStats {
  totalMovies: number
  totalSeries: number
  totalViewings: number
  avgRating: number
  totalMinutes: number
  topGenres: { genre: string; count: number }[]
  topDirectors: { director: string; count: number }[]
  ratingDistribution: { rating: number; count: number }[]
  viewingsByMonth: { month: string; count: number }[]
}

// ─────────────────────────────────────────────────────────────────────────────
// Library data — migrated from MovieTracker v1 (movies.json) on 2026-06-16.
// Regenerate with: node scripts/migrate-from-v1.mjs
// ─────────────────────────────────────────────────────────────────────────────

export const mockTitles: Title[] = [
  {
    "id": "mt-1",
    "tmdbId": 0,
    "type": "movie",
    "title": "Inception",
    "year": 2010,
    "director": "Christopher Nolan",
    "genres": [
      "Action",
      "Science Fiction",
      "Adventure"
    ],
    "runtime": 148,
    "status": "watched",
    "rating": 5,
    "notes": "Mind-bending and visually stunning.",
    "tags": [
      "rewatch"
    ],
    "addedAt": "2010-07-15",
    "viewings": []
  },
  {
    "id": "mt-2",
    "tmdbId": 0,
    "type": "movie",
    "title": "Pulp Fiction",
    "year": 1994,
    "director": "Quentin Tarantino",
    "genres": [
      "Thriller",
      "Crime",
      "Comedy"
    ],
    "runtime": 154,
    "status": "watched",
    "rating": 1,
    "notes": "Guess I'm not really a Tarantino fan :/",
    "tags": [
      "rewatch"
    ],
    "addedAt": "1994-09-10",
    "viewings": []
  },
  {
    "id": "mt-3",
    "tmdbId": 0,
    "type": "movie",
    "title": "The Shawshank Redemption",
    "year": 1994,
    "director": "Frank Darabont",
    "genres": [
      "Drama",
      "Crime"
    ],
    "runtime": 142,
    "status": "watched",
    "rating": 5,
    "notes": "Uplifting and emotionally resonant.",
    "tags": [
      "rewatch"
    ],
    "addedAt": "1994-09-23",
    "viewings": []
  },
  {
    "id": "mt-4",
    "tmdbId": 0,
    "type": "movie",
    "title": "The Dark Knight",
    "year": 2008,
    "director": "Christopher Nolan",
    "genres": [
      "Action",
      "Crime",
      "Thriller"
    ],
    "runtime": 152,
    "status": "watched",
    "rating": 5,
    "notes": "A superhero masterpiece.",
    "tags": [
      "rewatch"
    ],
    "addedAt": "2008-07-16",
    "viewings": []
  },
  {
    "id": "mt-5",
    "tmdbId": 0,
    "type": "movie",
    "title": "Forrest Gump",
    "year": 1994,
    "director": "Robert Zemeckis",
    "genres": [
      "Comedy",
      "Drama",
      "Romance"
    ],
    "runtime": 142,
    "status": "watched",
    "rating": 1,
    "notes": "Heartwarming but a bit too sentimental for my taste.  Kinda lacks a traditional story stucture.",
    "tags": [
      "rewatch"
    ],
    "addedAt": "1994-06-23",
    "viewings": []
  },
  {
    "id": "mt-6",
    "tmdbId": 0,
    "type": "movie",
    "title": "Interstellar",
    "year": 2014,
    "director": "Christopher Nolan",
    "genres": [
      "Adventure",
      "Drama",
      "Science Fiction"
    ],
    "runtime": 169,
    "status": "watched",
    "rating": 3,
    "notes": "Ambitious and thought-provoking.",
    "tags": [
      "rewatch"
    ],
    "addedAt": "2014-11-05",
    "viewings": []
  },
  {
    "id": "mt-7",
    "tmdbId": 0,
    "type": "movie",
    "title": "GATTACA",
    "year": 1997,
    "director": "Andrew Niccol",
    "genres": [
      "Science Fiction",
      "Drama",
      "Thriller"
    ],
    "runtime": 106,
    "status": "watched",
    "rating": 5,
    "tags": [
      "rewatch"
    ],
    "addedAt": "1997-07-09",
    "viewings": []
  },
  {
    "id": "mt-8",
    "tmdbId": 0,
    "type": "movie",
    "title": "A Bug's Life",
    "year": 1998,
    "director": "John Lasseter, Andrew Stanton",
    "genres": [
      "Animation",
      "Adventure",
      "Comedy"
    ],
    "runtime": 95,
    "status": "watched",
    "rating": 4,
    "tags": [
      "rewatch"
    ],
    "addedAt": "1998-11-25",
    "viewings": []
  },
  {
    "id": "mt-9",
    "tmdbId": 0,
    "type": "movie",
    "title": "The Sixth Sense",
    "year": 1999,
    "director": "M. Night Shyamalan",
    "genres": [
      "Thriller",
      "Mystery"
    ],
    "runtime": 107,
    "status": "watched",
    "rating": 4,
    "tags": [
      "rewatch"
    ],
    "addedAt": "1999-08-06",
    "viewings": []
  },
  {
    "id": "mt-10",
    "tmdbId": 0,
    "type": "movie",
    "title": "The Matrix",
    "year": 1999,
    "director": "The Wachowskis",
    "genres": [
      "Science Fiction",
      "Action"
    ],
    "runtime": 136,
    "status": "watched",
    "rating": 5,
    "tags": [
      "rewatch"
    ],
    "addedAt": "1999-03-31",
    "viewings": []
  },
  {
    "id": "mt-11",
    "tmdbId": 0,
    "type": "movie",
    "title": "Looper",
    "year": 2012,
    "director": "Rian Johnson",
    "genres": [
      "Science Fiction",
      "Action"
    ],
    "runtime": 119,
    "status": "watched",
    "tags": [
      "rewatch"
    ],
    "addedAt": "2012-09-28",
    "viewings": []
  },
  {
    "id": "mt-12",
    "tmdbId": 0,
    "type": "movie",
    "title": "Upgrade",
    "year": 2018,
    "director": "Leigh Whannell",
    "genres": [
      "Science Fiction",
      "Action"
    ],
    "runtime": 100,
    "status": "watched",
    "rating": 4,
    "tags": [
      "rewatch"
    ],
    "addedAt": "2018-06-01",
    "viewings": []
  },
  {
    "id": "mt-13",
    "tmdbId": 0,
    "type": "movie",
    "title": "Everything Everywhere All at Once",
    "year": 2022,
    "director": "Daniel Kwan, Daniel Scheinert",
    "genres": [
      "Science Fiction",
      "Adventure"
    ],
    "runtime": 139,
    "status": "watched",
    "rating": 5,
    "tags": [
      "rewatch"
    ],
    "addedAt": "2022-03-25",
    "viewings": []
  },
  {
    "id": "mt-14",
    "tmdbId": 0,
    "type": "movie",
    "title": "Ex Machina",
    "year": 2015,
    "director": "Alex Garland",
    "genres": [
      "Science Fiction",
      "Thriller"
    ],
    "runtime": 108,
    "status": "watched",
    "rating": 4,
    "tags": [
      "rewatch"
    ],
    "addedAt": "2015-04-10",
    "viewings": []
  },
  {
    "id": "mt-15",
    "tmdbId": 0,
    "type": "movie",
    "title": "Arrival",
    "year": 2016,
    "director": "Denis Villeneuve",
    "genres": [
      "Science Fiction",
      "Drama"
    ],
    "runtime": 116,
    "status": "watched",
    "rating": 3,
    "tags": [
      "rewatch"
    ],
    "addedAt": "2016-11-11",
    "viewings": []
  },
  {
    "id": "mt-16",
    "tmdbId": 0,
    "type": "movie",
    "title": "Coherence",
    "year": 2013,
    "director": "James Ward Byrkit",
    "genres": [
      "Science Fiction",
      "Thriller"
    ],
    "runtime": 89,
    "status": "watched",
    "rating": 4,
    "tags": [],
    "addedAt": "2026-06-06",
    "viewings": [
      {
        "id": "mt-16-v1",
        "titleId": "mt-16",
        "date": "2026-06-06",
        "rating": 4
      }
    ]
  },
  {
    "id": "mt-17",
    "tmdbId": 0,
    "type": "movie",
    "title": "Triangle",
    "year": 2009,
    "director": "Christopher Smith",
    "genres": [
      "Thriller",
      "Mystery"
    ],
    "runtime": 99,
    "status": "watched",
    "rating": 4,
    "tags": [
      "rewatch"
    ],
    "addedAt": "2009-10-16",
    "viewings": []
  },
  {
    "id": "mt-18",
    "tmdbId": 0,
    "type": "movie",
    "title": "Predestination",
    "year": 2014,
    "director": "The Spierig Brothers",
    "genres": [
      "Science Fiction",
      "Thriller"
    ],
    "runtime": 97,
    "status": "watched",
    "rating": 4,
    "tags": [
      "rewatch"
    ],
    "addedAt": "2014-08-28",
    "viewings": []
  },
  {
    "id": "mt-19",
    "tmdbId": 0,
    "type": "movie",
    "title": "Fall",
    "year": 2022,
    "director": "Scott Mann",
    "genres": [
      "Thriller",
      "Survival"
    ],
    "runtime": 107,
    "status": "watched",
    "tags": [
      "rewatch"
    ],
    "addedAt": "2022-08-12",
    "viewings": []
  },
  {
    "id": "mt-20",
    "tmdbId": 0,
    "type": "movie",
    "title": "Searching",
    "year": 2018,
    "director": "Aneesh Chaganty",
    "genres": [
      "Thriller",
      "Mystery"
    ],
    "runtime": 102,
    "status": "watched",
    "tags": [
      "rewatch"
    ],
    "addedAt": "2018-08-24",
    "viewings": []
  },
  {
    "id": "mt-21",
    "tmdbId": 0,
    "type": "movie",
    "title": "Heretic",
    "year": 2024,
    "director": "Scott Beck, Bryan Woods",
    "genres": [
      "Horror",
      "Thriller"
    ],
    "runtime": 111,
    "status": "watched",
    "rating": 5,
    "tags": [
      "rewatch"
    ],
    "addedAt": "2024-11-08",
    "viewings": []
  },
  {
    "id": "mt-22",
    "tmdbId": 0,
    "type": "movie",
    "title": "The Housemaid",
    "year": 2010,
    "director": "Im Sang-soo",
    "genres": [
      "Thriller",
      "Drama"
    ],
    "runtime": 106,
    "status": "watched",
    "rating": 3,
    "tags": [
      "rewatch"
    ],
    "addedAt": "2010-05-13",
    "viewings": []
  },
  {
    "id": "mt-23",
    "tmdbId": 0,
    "type": "movie",
    "title": "Subservience",
    "year": 2024,
    "director": "S.K. Dale",
    "genres": [
      "Science Fiction",
      "Thriller"
    ],
    "runtime": 95,
    "status": "watched",
    "rating": 4,
    "tags": [
      "rewatch"
    ],
    "addedAt": "2024-08-23",
    "viewings": []
  },
  {
    "id": "mt-24",
    "tmdbId": 0,
    "type": "movie",
    "title": "OtherLife",
    "year": 2017,
    "director": "Ben C. Lucas",
    "genres": [
      "Science Fiction",
      "Thriller",
      "Mystery"
    ],
    "runtime": 96,
    "status": "watched",
    "rating": 4,
    "tags": [],
    "addedAt": "2026-05-24",
    "viewings": [
      {
        "id": "mt-24-v1",
        "titleId": "mt-24",
        "date": "2026-05-24",
        "rating": 4
      }
    ]
  },
  {
    "id": "mt-25",
    "tmdbId": 0,
    "type": "movie",
    "title": "Smile",
    "year": 2022,
    "director": "Parker Finn",
    "genres": [
      "Horror",
      "Mystery",
      "Thriller"
    ],
    "runtime": 115,
    "status": "watched",
    "rating": 4,
    "notes": "Concept felt familiar immediately; unsure if watched previously or just recognizing the sequel.",
    "tags": [],
    "addedAt": "2026-05-30",
    "viewings": [
      {
        "id": "mt-25-v1",
        "titleId": "mt-25",
        "date": "2026-05-30",
        "rating": 4
      }
    ]
  },
  {
    "id": "mt-26",
    "tmdbId": 0,
    "type": "movie",
    "title": "Eternal Sunshine of the Spotless Mind",
    "year": 2004,
    "director": "Michel Gondry",
    "genres": [
      "Science Fiction",
      "Drama",
      "Romance"
    ],
    "runtime": 108,
    "status": "watched",
    "tags": [],
    "addedAt": "2025-04-08",
    "viewings": [
      {
        "id": "mt-26-v1",
        "titleId": "mt-26",
        "date": "2025-04-08"
      }
    ]
  },
  {
    "id": "mt-27",
    "tmdbId": 0,
    "type": "movie",
    "title": "How to Train Your Dragon: Homecoming",
    "year": 2019,
    "director": "Tim Johnson",
    "genres": [
      "Animation",
      "Action",
      "Adventure",
      "Family"
    ],
    "runtime": 22,
    "status": "watched",
    "tags": [],
    "addedAt": "2025-04-27",
    "viewings": [
      {
        "id": "mt-27-v1",
        "titleId": "mt-27",
        "date": "2025-04-27"
      }
    ]
  },
  {
    "id": "mt-28",
    "tmdbId": 0,
    "type": "movie",
    "title": "How to Train Your Dragon: Snoggletog Log",
    "year": 2019,
    "director": "Tim Johnson",
    "genres": [
      "Animation",
      "Short",
      "Family"
    ],
    "runtime": 28,
    "status": "watched",
    "tags": [],
    "addedAt": "2025-04-27",
    "viewings": [
      {
        "id": "mt-28-v1",
        "titleId": "mt-28",
        "date": "2025-04-27"
      }
    ]
  },
  {
    "id": "mt-29",
    "tmdbId": 0,
    "type": "movie",
    "title": "Sinners",
    "year": 2025,
    "director": "Ryan Coogler",
    "genres": [
      "Horror",
      "Thriller",
      "Drama"
    ],
    "runtime": 137,
    "status": "watched",
    "rating": 5,
    "tags": [],
    "addedAt": "2025-06-05",
    "viewings": [
      {
        "id": "mt-29-v1",
        "titleId": "mt-29",
        "date": "2025-06-05",
        "rating": 5
      }
    ]
  },
  {
    "id": "mt-30",
    "tmdbId": 0,
    "type": "movie",
    "title": "What Women Want",
    "year": 2000,
    "director": "Nancy Meyers",
    "genres": [
      "Comedy",
      "Romance"
    ],
    "runtime": 127,
    "status": "watched",
    "rating": 3,
    "tags": [],
    "addedAt": "2025-06-28",
    "viewings": [
      {
        "id": "mt-30-v1",
        "titleId": "mt-30",
        "date": "2025-06-28",
        "rating": 3
      }
    ]
  },
  {
    "id": "mt-31",
    "tmdbId": 0,
    "type": "movie",
    "title": "The Hidden Face",
    "year": 2011,
    "director": "Andrés Baiz",
    "genres": [
      "Drama",
      "Mystery",
      "Thriller"
    ],
    "runtime": 97,
    "status": "watched",
    "tags": [],
    "addedAt": "2025-06-30",
    "viewings": [
      {
        "id": "mt-31-v1",
        "titleId": "mt-31",
        "date": "2025-06-30"
      }
    ]
  },
  {
    "id": "mt-32",
    "tmdbId": 0,
    "type": "movie",
    "title": "Thunderbolts*",
    "year": 2025,
    "director": "Jake Schreier",
    "genres": [
      "Action",
      "Adventure",
      "Science Fiction"
    ],
    "runtime": 126,
    "status": "watched",
    "tags": [],
    "addedAt": "2025-07-21",
    "viewings": [
      {
        "id": "mt-32-v1",
        "titleId": "mt-32",
        "date": "2025-07-21"
      }
    ]
  },
  {
    "id": "mt-33",
    "tmdbId": 0,
    "type": "movie",
    "title": "The Pursuit of Happyness",
    "year": 2006,
    "director": "Gabriele Muccino",
    "genres": [
      "Drama"
    ],
    "runtime": 117,
    "status": "watched",
    "rating": 3,
    "tags": [],
    "addedAt": "2025-08-25",
    "viewings": [
      {
        "id": "mt-33-v1",
        "titleId": "mt-33",
        "date": "2025-08-25",
        "rating": 3
      }
    ]
  },
  {
    "id": "mt-34",
    "tmdbId": 0,
    "type": "movie",
    "title": "The Giver",
    "year": 2014,
    "director": "Phillip Noyce",
    "genres": [
      "Science Fiction",
      "Drama"
    ],
    "runtime": 97,
    "status": "watched",
    "rating": 3,
    "tags": [],
    "addedAt": "2025-09-01",
    "viewings": [
      {
        "id": "mt-34-v1",
        "titleId": "mt-34",
        "date": "2025-09-01",
        "rating": 3
      }
    ]
  },
  {
    "id": "mt-35",
    "tmdbId": 0,
    "type": "movie",
    "title": "TRON: Ares",
    "year": 2025,
    "director": "Joachim Rønning",
    "genres": [
      "Science Fiction",
      "Action",
      "Adventure"
    ],
    "runtime": 119,
    "status": "watched",
    "rating": 2,
    "tags": [],
    "addedAt": "2025-11-24",
    "viewings": [
      {
        "id": "mt-35-v1",
        "titleId": "mt-35",
        "date": "2025-11-24",
        "rating": 2
      }
    ]
  },
  {
    "id": "mt-36",
    "tmdbId": 0,
    "type": "movie",
    "title": "The Substance",
    "year": 2024,
    "director": "Coralie Fargeat",
    "genres": [
      "Horror",
      "Science Fiction",
      "Drama"
    ],
    "runtime": 141,
    "status": "watched",
    "rating": 2,
    "notes": "Social commentary is strong.  Still would rather not watch again.  I think body horror is probably just not my thing.",
    "tags": [],
    "addedAt": "2025-12-01",
    "viewings": [
      {
        "id": "mt-36-v1",
        "titleId": "mt-36",
        "date": "2025-12-01",
        "rating": 2
      }
    ]
  },
  {
    "id": "mt-37",
    "tmdbId": 0,
    "type": "movie",
    "title": "Batman: Hush",
    "year": 2019,
    "director": "Justin Copeland",
    "genres": [
      "Animation",
      "Action",
      "Mystery"
    ],
    "runtime": 82,
    "status": "watched",
    "tags": [],
    "addedAt": "2025-12-13",
    "viewings": [
      {
        "id": "mt-37-v1",
        "titleId": "mt-37",
        "date": "2025-12-13"
      }
    ]
  },
  {
    "id": "mt-38",
    "tmdbId": 0,
    "type": "movie",
    "title": "The Father",
    "year": 2020,
    "director": "Florian Zeller",
    "genres": [
      "Drama",
      "Mystery"
    ],
    "runtime": 97,
    "status": "watched",
    "tags": [],
    "addedAt": "2025-12-13",
    "viewings": [
      {
        "id": "mt-38-v1",
        "titleId": "mt-38",
        "date": "2025-12-13"
      }
    ]
  },
  {
    "id": "mt-39",
    "tmdbId": 0,
    "type": "movie",
    "title": "Primer",
    "year": 2004,
    "director": "Shane Carruth",
    "genres": [
      "Science Fiction",
      "Drama",
      "Thriller"
    ],
    "runtime": 77,
    "status": "watched",
    "rating": 5,
    "tags": [],
    "addedAt": "2026-01-02",
    "viewings": [
      {
        "id": "mt-39-v1",
        "titleId": "mt-39",
        "date": "2026-01-02",
        "rating": 5
      }
    ]
  },
  {
    "id": "mt-40",
    "tmdbId": 0,
    "type": "movie",
    "title": "Doctor Strange",
    "year": 2016,
    "director": "Scott Derrickson",
    "genres": [
      "Action",
      "Adventure",
      "Fantasy"
    ],
    "runtime": 115,
    "status": "watched",
    "rating": 3,
    "tags": [],
    "addedAt": "2026-01-25",
    "viewings": [
      {
        "id": "mt-40-v1",
        "titleId": "mt-40",
        "date": "2026-01-25",
        "rating": 3
      }
    ]
  },
  {
    "id": "mt-41",
    "tmdbId": 0,
    "type": "movie",
    "title": "Sky High",
    "year": 2005,
    "director": "Mike Mitchell",
    "genres": [
      "Action",
      "Comedy",
      "Family",
      "Science Fiction"
    ],
    "runtime": 100,
    "status": "watched",
    "tags": [],
    "addedAt": "2026-01-25",
    "viewings": [
      {
        "id": "mt-41-v1",
        "titleId": "mt-41",
        "date": "2026-01-25"
      }
    ]
  },
  {
    "id": "mt-42",
    "tmdbId": 0,
    "type": "movie",
    "title": "Her",
    "year": 2013,
    "director": "Spike Jonze",
    "genres": [
      "Romance",
      "Science Fiction",
      "Drama"
    ],
    "runtime": 126,
    "status": "watched",
    "rating": 4,
    "tags": [],
    "addedAt": "2026-02-20",
    "viewings": [
      {
        "id": "mt-42-v1",
        "titleId": "mt-42",
        "date": "2026-02-20",
        "rating": 4
      }
    ]
  },
  {
    "id": "mt-43",
    "tmdbId": 0,
    "type": "movie",
    "title": "Archive",
    "year": 2020,
    "director": "Gavin Rothery",
    "genres": [
      "Science Fiction",
      "Drama",
      "Mystery"
    ],
    "runtime": 109,
    "status": "watched",
    "rating": 3,
    "tags": [],
    "addedAt": "2026-04-25",
    "viewings": [
      {
        "id": "mt-43-v1",
        "titleId": "mt-43",
        "date": "2026-04-25",
        "rating": 3
      }
    ]
  },
  {
    "id": "mt-44",
    "tmdbId": 0,
    "type": "movie",
    "title": "The Prestige",
    "year": 2006,
    "director": "Christopher Nolan",
    "genres": [
      "Drama",
      "Mystery",
      "Science Fiction"
    ],
    "runtime": 130,
    "status": "watched",
    "rating": 3,
    "notes": "Kinda unimpressed but mostly because the big \"twist\" was a little derivative.",
    "tags": [
      "rewatch"
    ],
    "addedAt": "2006-10-20",
    "viewings": []
  },
  {
    "id": "mt-45",
    "tmdbId": 0,
    "type": "movie",
    "title": "The Game",
    "year": 1997,
    "director": "David Fincher",
    "genres": [
      "Drama",
      "Mystery",
      "Thriller"
    ],
    "runtime": 129,
    "status": "watched",
    "rating": 4,
    "notes": "That was a fun one.",
    "tags": [
      "rewatch"
    ],
    "addedAt": "1997-09-12",
    "viewings": []
  },
  {
    "id": "mt-46",
    "tmdbId": 0,
    "type": "movie",
    "title": "Fracture",
    "year": 2007,
    "director": "Gregory Hoblit",
    "genres": [
      "Crime",
      "Drama",
      "Thriller"
    ],
    "runtime": 113,
    "status": "watched",
    "rating": 4,
    "notes": "Solid 4 / 5 in my book",
    "tags": [],
    "addedAt": "2026-06-01",
    "viewings": [
      {
        "id": "mt-46-v1",
        "titleId": "mt-46",
        "date": "2026-06-01",
        "rating": 4
      }
    ]
  },
  {
    "id": "mt-47",
    "tmdbId": 0,
    "type": "movie",
    "title": "Prisoners",
    "year": 2013,
    "director": "Denis Villeneuve",
    "genres": [
      "Crime",
      "Drama",
      "Mystery",
      "Thriller"
    ],
    "runtime": 153,
    "status": "watched",
    "rating": 3,
    "notes": "The one with the weird recurring maze motif.",
    "tags": [
      "rewatch"
    ],
    "addedAt": "2013-09-20",
    "viewings": []
  },
  {
    "id": "mt-48",
    "tmdbId": 0,
    "type": "movie",
    "title": "Source Code",
    "year": 2011,
    "director": "Duncan Jones",
    "genres": [
      "Science Fiction",
      "Thriller"
    ],
    "runtime": 93,
    "status": "watched",
    "rating": 2,
    "tags": [
      "rewatch"
    ],
    "addedAt": "2011-04-01",
    "viewings": []
  },
  {
    "id": "mt-49",
    "tmdbId": 0,
    "type": "movie",
    "title": "Memento",
    "year": 2000,
    "director": "Christopher Nolan",
    "genres": [
      "Mystery",
      "Thriller"
    ],
    "runtime": 113,
    "status": "watched",
    "rating": 4,
    "tags": [
      "rewatch"
    ],
    "addedAt": "2000-09-05",
    "viewings": []
  },
  {
    "id": "mt-50",
    "tmdbId": 0,
    "type": "movie",
    "title": "Paddington",
    "year": 2015,
    "director": "Paul King",
    "genres": [
      "Adventure",
      "Comedy",
      "Family"
    ],
    "runtime": 95,
    "status": "watched",
    "rating": 4,
    "notes": "Warm and fun",
    "tags": [],
    "addedAt": "2026-06-08",
    "viewings": [
      {
        "id": "mt-50-v1",
        "titleId": "mt-50",
        "date": "2026-06-08",
        "rating": 4
      }
    ]
  },
  {
    "id": "tv-1",
    "tmdbId": 42009,
    "type": "tv",
    "title": "Black Mirror",
    "year": 2011,
    "genres": ["Science Fiction", "Drama", "Thriller"],
    "synopsis": "An anthology series exploring a twisted, high-tech near-future where humanity's greatest innovations and darkest instincts collide.",
    "network": "Netflix",
    "status": "watching",
    "rating": 4,
    "tags": ["anthology"],
    "addedAt": "2026-01-10",
    "viewings": [],
    "seasons": [
      {
        "id": "tv-1-s1",
        "seasonNumber": 1,
        "episodeCount": 3,
        "episodesWatched": 3,
        "airYear": 2011,
        "episodes": [
          {
            "id": "tv-1-s1-e1",
            "episodeNumber": 1,
            "episodeName": "The National Anthem",
            "airDate": "2011-12-04",
            "runtime": 44,
            "watchEvents": [{ "id": "we-tv-1-s1-e1-1", "watchedAt": "2026-01-10" }],
            "ratings": [{ "id": "er-tv-1-s1-e1-1", "rating": 3, "ratedAt": "2026-01-10T21:30:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-1-s1-e2",
            "episodeNumber": 2,
            "episodeName": "Fifteen Million Merits",
            "airDate": "2011-12-11",
            "runtime": 62,
            "watchEvents": [{ "id": "we-tv-1-s1-e2-1", "watchedAt": "2026-01-11" }],
            "ratings": [{ "id": "er-tv-1-s1-e2-1", "rating": 5, "ratedAt": "2026-01-11T22:15:00Z" }],
            "reviews": [{ "id": "rv-tv-1-s1-e2-1", "reviewText": "A devastating critique of talent shows and gamified existence. The ending wrecked me.", "reviewedAt": "2026-01-11T22:45:00Z" }]
          },
          {
            "id": "tv-1-s1-e3",
            "episodeNumber": 3,
            "episodeName": "The Entire History of You",
            "airDate": "2011-12-18",
            "runtime": 49,
            "watchEvents": [{ "id": "we-tv-1-s1-e3-1", "watchedAt": "2026-01-12" }],
            "ratings": [{ "id": "er-tv-1-s1-e3-1", "rating": 4, "ratedAt": "2026-01-12T23:00:00Z" }],
            "reviews": []
          }
        ]
      },
      {
        "id": "tv-1-s2",
        "seasonNumber": 2,
        "episodeCount": 4,
        "episodesWatched": 4,
        "airYear": 2013,
        "episodes": [
          {
            "id": "tv-1-s2-e1",
            "episodeNumber": 1,
            "episodeName": "Be Right Back",
            "airDate": "2013-02-11",
            "runtime": 48,
            "watchEvents": [{ "id": "we-tv-1-s2-e1-1", "watchedAt": "2026-01-18" }],
            "ratings": [{ "id": "er-tv-1-s2-e1-1", "rating": 4, "ratedAt": "2026-01-18T21:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-1-s2-e2",
            "episodeNumber": 2,
            "episodeName": "White Bear",
            "airDate": "2013-02-18",
            "runtime": 42,
            "watchEvents": [{ "id": "we-tv-1-s2-e2-1", "watchedAt": "2026-01-19" }],
            "ratings": [
              { "id": "er-tv-1-s2-e2-1", "rating": 5, "ratedAt": "2026-01-19T22:00:00Z" },
              { "id": "er-tv-1-s2-e2-2", "rating": 4, "ratedAt": "2026-02-01T14:00:00Z" }
            ],
            "reviews": [{ "id": "rv-tv-1-s2-e2-1", "reviewText": "The twist completely reframes everything. Punishing and brilliant.", "reviewedAt": "2026-01-19T22:30:00Z" }]
          },
          {
            "id": "tv-1-s2-e3",
            "episodeNumber": 3,
            "episodeName": "The Waldo Moment",
            "airDate": "2013-02-25",
            "runtime": 43,
            "watchEvents": [{ "id": "we-tv-1-s2-e3-1", "watchedAt": "2026-01-20" }],
            "ratings": [{ "id": "er-tv-1-s2-e3-1", "rating": 2, "ratedAt": "2026-01-20T21:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-1-s2-e4",
            "episodeNumber": 4,
            "episodeName": "White Christmas",
            "airDate": "2014-12-16",
            "runtime": 73,
            "watchEvents": [{ "id": "we-tv-1-s2-e4-1", "watchedAt": "2026-01-21" }],
            "ratings": [{ "id": "er-tv-1-s2-e4-1", "rating": 5, "ratedAt": "2026-01-21T22:00:00Z" }],
            "reviews": [{ "id": "rv-tv-1-s2-e4-1", "reviewText": "Perhaps the best episode of the series. Three interlocking stories, all harrowing.", "reviewedAt": "2026-01-21T22:45:00Z" }]
          }
        ]
      },
      {
        "id": "tv-1-s3",
        "seasonNumber": 3,
        "episodeCount": 6,
        "episodesWatched": 6,
        "airYear": 2016,
        "episodes": [
          {
            "id": "tv-1-s3-e1",
            "episodeNumber": 1,
            "episodeName": "Nosedive",
            "airDate": "2016-10-21",
            "runtime": 63,
            "watchEvents": [{ "id": "we-tv-1-s3-e1-1", "watchedAt": "2026-02-05" }],
            "ratings": [{ "id": "er-tv-1-s3-e1-1", "rating": 4, "ratedAt": "2026-02-05T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-1-s3-e2",
            "episodeNumber": 2,
            "episodeName": "Playtest",
            "airDate": "2016-10-21",
            "runtime": 56,
            "watchEvents": [{ "id": "we-tv-1-s3-e2-1", "watchedAt": "2026-02-06" }],
            "ratings": [{ "id": "er-tv-1-s3-e2-1", "rating": 4, "ratedAt": "2026-02-06T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-1-s3-e3",
            "episodeNumber": 3,
            "episodeName": "Shut Up and Dance",
            "airDate": "2016-10-21",
            "runtime": 52,
            "watchEvents": [{ "id": "we-tv-1-s3-e3-1", "watchedAt": "2026-02-07" }],
            "ratings": [{ "id": "er-tv-1-s3-e3-1", "rating": 5, "ratedAt": "2026-02-07T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-1-s3-e4",
            "episodeNumber": 4,
            "episodeName": "San Junipero",
            "airDate": "2016-10-21",
            "runtime": 61,
            "watchEvents": [{ "id": "we-tv-1-s3-e4-1", "watchedAt": "2026-02-08" }],
            "ratings": [
              { "id": "er-tv-1-s3-e4-1", "rating": 5, "ratedAt": "2026-02-08T21:00:00Z" },
              { "id": "er-tv-1-s3-e4-2", "rating": 5, "ratedAt": "2026-03-15T10:00:00Z" }
            ],
            "reviews": [{ "id": "rv-tv-1-s3-e4-1", "reviewText": "Genuinely beautiful. The rare Black Mirror episode that leaves you feeling hopeful. Still holds on second viewing.", "reviewedAt": "2026-02-08T22:00:00Z" }]
          },
          {
            "id": "tv-1-s3-e5",
            "episodeNumber": 5,
            "episodeName": "Men Against Fire",
            "airDate": "2016-10-21",
            "runtime": 60,
            "watchEvents": [{ "id": "we-tv-1-s3-e5-1", "watchedAt": "2026-02-09" }],
            "ratings": [{ "id": "er-tv-1-s3-e5-1", "rating": 3, "ratedAt": "2026-02-09T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-1-s3-e6",
            "episodeNumber": 6,
            "episodeName": "Hated in the Nation",
            "airDate": "2016-10-21",
            "runtime": 89,
            "watchEvents": [{ "id": "we-tv-1-s3-e6-1", "watchedAt": "2026-02-10" }],
            "ratings": [{ "id": "er-tv-1-s3-e6-1", "rating": 4, "ratedAt": "2026-02-10T22:00:00Z" }],
            "reviews": [{ "id": "rv-tv-1-s3-e6-1", "reviewText": "The longest and most procedural, but the finale lands. An important episode about mob justice.", "reviewedAt": "2026-02-10T23:00:00Z" }]
          }
        ]
      }
    ]
  },
  {
    "id": "tv-2",
    "tmdbId": 95396,
    "type": "tv",
    "title": "Severance",
    "year": 2022,
    "genres": ["Science Fiction", "Thriller", "Drama", "Mystery"],
    "synopsis": "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives. When a mysterious colleague appears outside of work, it begins a journey to discover the truth about their jobs.",
    "network": "Apple TV+",
    "status": "watching",
    "rating": 5,
    "tags": [],
    "addedAt": "2026-03-01",
    "viewings": [],
    "seasons": [
      {
        "id": "tv-2-s1",
        "seasonNumber": 1,
        "episodeCount": 9,
        "episodesWatched": 9,
        "airYear": 2022,
        "episodes": [
          {
            "id": "tv-2-s1-e1",
            "episodeNumber": 1,
            "episodeName": "Good News About Hell",
            "airDate": "2022-02-18",
            "runtime": 53,
            "watchEvents": [{ "id": "we-tv-2-s1-e1-1", "watchedAt": "2026-03-01" }],
            "ratings": [{ "id": "er-tv-2-s1-e1-1", "rating": 4, "ratedAt": "2026-03-01T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-2-s1-e2",
            "episodeNumber": 2,
            "episodeName": "Half Loop",
            "airDate": "2022-02-18",
            "runtime": 38,
            "watchEvents": [{ "id": "we-tv-2-s1-e2-1", "watchedAt": "2026-03-02" }],
            "ratings": [{ "id": "er-tv-2-s1-e2-1", "rating": 4, "ratedAt": "2026-03-02T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-2-s1-e3",
            "episodeNumber": 3,
            "episodeName": "In Perpetuity",
            "airDate": "2022-02-25",
            "runtime": 41,
            "watchEvents": [{ "id": "we-tv-2-s1-e3-1", "watchedAt": "2026-03-03" }],
            "ratings": [{ "id": "er-tv-2-s1-e3-1", "rating": 4, "ratedAt": "2026-03-03T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-2-s1-e4",
            "episodeNumber": 4,
            "episodeName": "The You You Are",
            "airDate": "2022-03-04",
            "runtime": 41,
            "watchEvents": [{ "id": "we-tv-2-s1-e4-1", "watchedAt": "2026-03-05" }],
            "ratings": [{ "id": "er-tv-2-s1-e4-1", "rating": 5, "ratedAt": "2026-03-05T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-2-s1-e5",
            "episodeNumber": 5,
            "episodeName": "The Grim Barbarity of Optics and Design",
            "airDate": "2022-03-11",
            "runtime": 43,
            "watchEvents": [{ "id": "we-tv-2-s1-e5-1", "watchedAt": "2026-03-07" }],
            "ratings": [{ "id": "er-tv-2-s1-e5-1", "rating": 5, "ratedAt": "2026-03-07T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-2-s1-e6",
            "episodeNumber": 6,
            "episodeName": "Hide and Seek",
            "airDate": "2022-03-18",
            "runtime": 41,
            "watchEvents": [{ "id": "we-tv-2-s1-e6-1", "watchedAt": "2026-03-08" }],
            "ratings": [{ "id": "er-tv-2-s1-e6-1", "rating": 4, "ratedAt": "2026-03-08T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-2-s1-e7",
            "episodeNumber": 7,
            "episodeName": "Defiant Jazz",
            "airDate": "2022-03-25",
            "runtime": 39,
            "watchEvents": [{ "id": "we-tv-2-s1-e7-1", "watchedAt": "2026-03-09" }],
            "ratings": [{ "id": "er-tv-2-s1-e7-1", "rating": 5, "ratedAt": "2026-03-09T22:00:00Z" }],
            "reviews": [{ "id": "rv-tv-2-s1-e7-1", "reviewText": "The dance number. Enough said.", "reviewedAt": "2026-03-09T22:30:00Z" }]
          },
          {
            "id": "tv-2-s1-e8",
            "episodeNumber": 8,
            "episodeName": "What Is Dead May Never Die",
            "airDate": "2022-04-01",
            "runtime": 46,
            "watchEvents": [{ "id": "we-tv-2-s1-e8-1", "watchedAt": "2026-03-10" }],
            "ratings": [{ "id": "er-tv-2-s1-e8-1", "rating": 5, "ratedAt": "2026-03-10T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-2-s1-e9",
            "episodeNumber": 9,
            "episodeName": "The We We Are",
            "airDate": "2022-04-08",
            "runtime": 53,
            "watchEvents": [{ "id": "we-tv-2-s1-e9-1", "watchedAt": "2026-03-11" }],
            "ratings": [{ "id": "er-tv-2-s1-e9-1", "rating": 5, "ratedAt": "2026-03-11T22:30:00Z" }],
            "reviews": [{ "id": "rv-tv-2-s1-e9-1", "reviewText": "One of the best season finales I have seen. The final scene is pure cinema.", "reviewedAt": "2026-03-11T23:00:00Z" }]
          }
        ]
      },
      {
        "id": "tv-2-s2",
        "seasonNumber": 2,
        "episodeCount": 10,
        "episodesWatched": 5,
        "airYear": 2025,
        "episodes": [
          {
            "id": "tv-2-s2-e1",
            "episodeNumber": 1,
            "episodeName": "Chikhai Bardo",
            "airDate": "2025-01-17",
            "runtime": 55,
            "watchEvents": [{ "id": "we-tv-2-s2-e1-1", "watchedAt": "2026-06-01" }],
            "ratings": [{ "id": "er-tv-2-s2-e1-1", "rating": 5, "ratedAt": "2026-06-01T22:00:00Z" }],
            "reviews": [{ "id": "rv-tv-2-s2-e1-1", "reviewText": "Three years later and Lumon Industries is just as menacing. Picked right back up.", "reviewedAt": "2026-06-01T22:30:00Z" }]
          },
          {
            "id": "tv-2-s2-e2",
            "episodeNumber": 2,
            "episodeName": "Goodbye, Mrs. Selvig",
            "airDate": "2025-01-17",
            "runtime": 42,
            "watchEvents": [{ "id": "we-tv-2-s2-e2-1", "watchedAt": "2026-06-02" }],
            "ratings": [{ "id": "er-tv-2-s2-e2-1", "rating": 4, "ratedAt": "2026-06-02T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-2-s2-e3",
            "episodeNumber": 3,
            "episodeName": "Who Is Alive?",
            "airDate": "2025-01-24",
            "runtime": 45,
            "watchEvents": [{ "id": "we-tv-2-s2-e3-1", "watchedAt": "2026-06-04" }],
            "ratings": [{ "id": "er-tv-2-s2-e3-1", "rating": 4, "ratedAt": "2026-06-04T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-2-s2-e4",
            "episodeNumber": 4,
            "episodeName": "Woe's Hollow",
            "airDate": "2025-01-31",
            "runtime": 44,
            "watchEvents": [{ "id": "we-tv-2-s2-e4-1", "watchedAt": "2026-06-06" }],
            "ratings": [{ "id": "er-tv-2-s2-e4-1", "rating": 5, "ratedAt": "2026-06-06T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-2-s2-e5",
            "episodeNumber": 5,
            "episodeName": "Trojan's Horse",
            "airDate": "2025-02-07",
            "runtime": 49,
            "watchEvents": [{ "id": "we-tv-2-s2-e5-1", "watchedAt": "2026-06-08" }],
            "ratings": [{ "id": "er-tv-2-s2-e5-1", "rating": 4, "ratedAt": "2026-06-08T22:00:00Z" }],
            "reviews": []
          },
          {
            "id": "tv-2-s2-e6",
            "episodeNumber": 6,
            "episodeName": "Attila",
            "airDate": "2025-02-14",
            "runtime": 50,
            "watchEvents": [],
            "ratings": [],
            "reviews": []
          },
          {
            "id": "tv-2-s2-e7",
            "episodeNumber": 7,
            "episodeName": "The After Lackey",
            "airDate": "2025-02-21",
            "runtime": 48,
            "watchEvents": [],
            "ratings": [],
            "reviews": []
          },
          {
            "id": "tv-2-s2-e8",
            "episodeNumber": 8,
            "episodeName": "Sweet Vitriol",
            "airDate": "2025-02-28",
            "runtime": 47,
            "watchEvents": [],
            "ratings": [],
            "reviews": []
          },
          {
            "id": "tv-2-s2-e9",
            "episodeNumber": 9,
            "episodeName": "Mammalians Nurtured by Wolfmother",
            "airDate": "2025-03-07",
            "runtime": 46,
            "watchEvents": [],
            "ratings": [],
            "reviews": []
          },
          {
            "id": "tv-2-s2-e10",
            "episodeNumber": 10,
            "episodeName": "Cold Harbor",
            "airDate": "2025-03-14",
            "runtime": 55,
            "watchEvents": [],
            "ratings": [],
            "reviews": []
          }
        ]
      }
    ]
  }
]

export const mockLedgerStats: LedgerStats = {
  "totalMovies": 50,
  "totalSeries": 0,
  "totalViewings": 23,
  "avgRating": 3.7,
  "totalMinutes": 5601,
  "topGenres": [
    {
      "genre": "Thriller",
      "count": 23
    },
    {
      "genre": "Science Fiction",
      "count": 22
    },
    {
      "genre": "Drama",
      "count": 20
    },
    {
      "genre": "Mystery",
      "count": 13
    },
    {
      "genre": "Action",
      "count": 11
    },
    {
      "genre": "Adventure",
      "count": 9
    }
  ],
  "topDirectors": [
    {
      "director": "Christopher Nolan",
      "count": 5
    },
    {
      "director": "Denis Villeneuve",
      "count": 2
    },
    {
      "director": "Tim Johnson",
      "count": 2
    },
    {
      "director": "Quentin Tarantino",
      "count": 1
    },
    {
      "director": "Frank Darabont",
      "count": 1
    }
  ],
  "ratingDistribution": [
    {
      "rating": 5,
      "count": 9
    },
    {
      "rating": 4.5,
      "count": 0
    },
    {
      "rating": 4,
      "count": 15
    },
    {
      "rating": 3.5,
      "count": 0
    },
    {
      "rating": 3,
      "count": 10
    },
    {
      "rating": 2.5,
      "count": 0
    },
    {
      "rating": 2,
      "count": 3
    },
    {
      "rating": 1.5,
      "count": 0
    },
    {
      "rating": 1,
      "count": 2
    }
  ],
  "viewingsByMonth": [
    {
      "month": "2025-04",
      "count": 3
    },
    {
      "month": "2025-06",
      "count": 3
    },
    {
      "month": "2025-07",
      "count": 1
    },
    {
      "month": "2025-08",
      "count": 1
    },
    {
      "month": "2025-09",
      "count": 1
    },
    {
      "month": "2025-11",
      "count": 1
    },
    {
      "month": "2025-12",
      "count": 3
    },
    {
      "month": "2026-01",
      "count": 3
    },
    {
      "month": "2026-02",
      "count": 1
    },
    {
      "month": "2026-04",
      "count": 1
    },
    {
      "month": "2026-05",
      "count": 2
    },
    {
      "month": "2026-06",
      "count": 3
    }
  ]
}

export type MediaType = 'movie' | 'tv'
export type WatchStatus = 'watched' | 'watchlist' | 'watching' | 'dropped'

export interface Season {
  id: string
  seasonNumber: number
  episodeCount: number
  episodesWatched: number
  airYear?: number
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

export const mockTitles: Title[] = [
  {
    id: '1',
    tmdbId: 807,
    type: 'movie',
    title: 'Se7en',
    year: 1995,
    director: 'David Fincher',
    genres: ['Crime', 'Drama', 'Mystery', 'Thriller'],
    posterUrl: 'https://image.tmdb.org/t/p/w500/6yoghtyTpznpBik8EngEmJskVUO.jpg',
    synopsis:
      'Two detectives, a rookie and a veteran, hunt a serial killer who uses the seven deadly sins as his motive.',
    runtime: 127,
    status: 'watched',
    rating: 5,
    tags: ['noir', 'psychological', 'neo-noir'],
    addedAt: '2024-01-15',
    imdbRating: 8.6,
    rtScore: 82,
    metacriticScore: 65,
    viewings: [
      { id: 'v1', titleId: '1', date: '2022-10-31', rating: 5, notes: 'Halloween rewatch. Still perfect.' },
      { id: 'v2', titleId: '1', date: '2024-01-15', rating: 5 },
    ],
  },
  {
    id: '2',
    tmdbId: 157336,
    type: 'movie',
    title: 'Interstellar',
    year: 2014,
    director: 'Christopher Nolan',
    genres: ['Adventure', 'Drama', 'Science Fiction'],
    posterUrl: 'https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg',
    synopsis: 'A team of explorers travel through a wormhole in space in an attempt to ensure humanity\'s survival.',
    runtime: 169,
    status: 'watched',
    rating: 4.5,
    tags: ['space', 'epic', 'emotional'],
    addedAt: '2024-02-10',
    imdbRating: 8.7,
    rtScore: 72,
    metacriticScore: 74,
    viewings: [
      { id: 'v3', titleId: '2', date: '2014-11-07', rating: 4.5, notes: 'Cinema. The IMAX experience was transcendent.' },
      { id: 'v4', titleId: '2', date: '2023-05-20', rating: 5 },
    ],
  },
  {
    id: '3',
    tmdbId: 278,
    type: 'movie',
    title: 'The Shawshank Redemption',
    year: 1994,
    director: 'Frank Darabont',
    genres: ['Drama'],
    posterUrl: 'https://image.tmdb.org/t/p/w500/lyQBXzOQSuE59IsHyhrp0qIiPAz.jpg',
    synopsis: 'Two imprisoned men bond over years, finding solace and eventual redemption through acts of common decency.',
    runtime: 142,
    status: 'watched',
    rating: 5,
    tags: ['classic', 'prison', 'hope'],
    addedAt: '2024-01-01',
    imdbRating: 9.3,
    rtScore: 91,
    metacriticScore: 80,
    viewings: [
      { id: 'v5', titleId: '3', date: '2019-03-10', rating: 5 },
      { id: 'v6', titleId: '3', date: '2024-01-01', rating: 5, notes: 'Annual rewatch tradition.' },
    ],
  },
  {
    id: '4',
    tmdbId: 1396,
    type: 'tv',
    title: 'Breaking Bad',
    year: 2008,
    director: 'Vince Gilligan',
    genres: ['Crime', 'Drama', 'Thriller'],
    posterUrl: 'https://image.tmdb.org/t/p/w500/ggFHVNu6YYI5L9pCfOacjizRGt.jpg',
    synopsis: 'A high school chemistry teacher diagnosed with inoperable lung cancer turns to manufacturing and selling methamphetamine.',
    network: 'AMC',
    status: 'watched',
    rating: 5,
    tags: ['prestige-tv', 'crime', 'drama'],
    addedAt: '2024-01-20',
    imdbRating: 9.5,
    rtScore: 96,
    metacriticScore: 99,
    seasons: [
      { id: 's1', seasonNumber: 1, episodeCount: 7, episodesWatched: 7, airYear: 2008 },
      { id: 's2', seasonNumber: 2, episodeCount: 13, episodesWatched: 13, airYear: 2009 },
      { id: 's3', seasonNumber: 3, episodeCount: 13, episodesWatched: 13, airYear: 2010 },
      { id: 's4', seasonNumber: 4, episodeCount: 13, episodesWatched: 13, airYear: 2011 },
      { id: 's5', seasonNumber: 5, episodeCount: 16, episodesWatched: 16, airYear: 2012 },
    ],
    viewings: [
      { id: 'v7', titleId: '4', date: '2023-08-15', rating: 5, notes: 'Finally watched all of it in one month. Incredible.' },
    ],
  },
  {
    id: '5',
    tmdbId: 680,
    type: 'movie',
    title: 'Pulp Fiction',
    year: 1994,
    director: 'Quentin Tarantino',
    genres: ['Crime', 'Drama', 'Thriller'],
    posterUrl: 'https://image.tmdb.org/t/p/w500/fIE3lAGcZDV1G6XM5KmuWnNsPp1.jpg',
    synopsis: 'The lives of two mob hitmen, a boxer, a gangster and his wife intertwine in this crime drama.',
    runtime: 154,
    status: 'watched',
    rating: 5,
    tags: ['postmodern', 'crime', 'cult'],
    addedAt: '2024-01-05',
    imdbRating: 8.9,
    rtScore: 92,
    metacriticScore: 94,
    viewings: [
      { id: 'v8', titleId: '5', date: '2020-06-12', rating: 5 },
      { id: 'v9', titleId: '5', date: '2023-11-22', rating: 5, notes: 'Still hits differently every time.' },
    ],
  },
  {
    id: '6',
    tmdbId: 11,
    type: 'movie',
    title: 'Star Wars: A New Hope',
    year: 1977,
    director: 'George Lucas',
    genres: ['Adventure', 'Action', 'Science Fiction'],
    posterUrl: 'https://image.tmdb.org/t/p/w500/6FfCtAuVAW8XJjZ7eWeLibRLWTw.jpg',
    synopsis: 'Luke Skywalker joins forces with a Jedi Knight, a cocky pilot, and two droids to save the galaxy.',
    runtime: 121,
    status: 'watched',
    rating: 4,
    tags: ['space-opera', 'classic', 'franchise'],
    addedAt: '2024-02-01',
    imdbRating: 8.6,
    rtScore: 93,
    metacriticScore: 90,
    viewings: [
      { id: 'v10', titleId: '6', date: '2024-02-01', rating: 4 },
    ],
  },
  {
    id: '7',
    tmdbId: 1399,
    type: 'tv',
    title: 'Game of Thrones',
    year: 2011,
    genres: ['Action', 'Adventure', 'Drama', 'Fantasy'],
    posterUrl: 'https://image.tmdb.org/t/p/w500/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg',
    synopsis: 'Nine noble families fight for control over the lands of Westeros, while an ancient enemy returns.',
    network: 'HBO',
    status: 'watched',
    rating: 3.5,
    tags: ['fantasy', 'epic', 'prestige-tv'],
    addedAt: '2024-01-25',
    imdbRating: 9.2,
    rtScore: 89,
    seasons: [
      { id: 'gs1', seasonNumber: 1, episodeCount: 10, episodesWatched: 10, airYear: 2011 },
      { id: 'gs2', seasonNumber: 2, episodeCount: 10, episodesWatched: 10, airYear: 2012 },
      { id: 'gs3', seasonNumber: 3, episodeCount: 10, episodesWatched: 10, airYear: 2013 },
      { id: 'gs4', seasonNumber: 4, episodeCount: 10, episodesWatched: 10, airYear: 2014 },
      { id: 'gs5', seasonNumber: 5, episodeCount: 10, episodesWatched: 10, airYear: 2015 },
      { id: 'gs6', seasonNumber: 6, episodeCount: 10, episodesWatched: 10, airYear: 2016 },
      { id: 'gs7', seasonNumber: 7, episodeCount: 7, episodesWatched: 7, airYear: 2017 },
      { id: 'gs8', seasonNumber: 8, episodeCount: 6, episodesWatched: 6, airYear: 2019 },
    ],
    viewings: [
      { id: 'v11', titleId: '7', date: '2019-05-19', rating: 3.5, notes: 'Loved S1-S6. S7-S8 was disappointing.' },
    ],
  },
  {
    id: '8',
    tmdbId: 240,
    type: 'movie',
    title: 'The Godfather Part II',
    year: 1974,
    director: 'Francis Ford Coppola',
    genres: ['Crime', 'Drama'],
    posterUrl: 'https://image.tmdb.org/t/p/w500/hek3koDUyRQk7FIhPXsa6mT2Zc3.jpg',
    synopsis: 'The early life and career of Vito Corleone in 1920s New York is portrayed, while his son Michael expands and tightens his grip on the family crime syndicate.',
    runtime: 202,
    status: 'watchlist',
    rating: undefined,
    tags: ['crime', 'classic', 'sequel'],
    addedAt: '2024-03-01',
    imdbRating: 9.0,
    rtScore: 97,
    metacriticScore: 90,
    viewings: [],
  },
  {
    id: '9',
    tmdbId: 13,
    type: 'movie',
    title: 'Forrest Gump',
    year: 1994,
    director: 'Robert Zemeckis',
    genres: ['Comedy', 'Drama', 'Romance'],
    posterUrl: 'https://image.tmdb.org/t/p/w500/arw2vcBveWOVZr6pxd9XTd1TdQa.jpg',
    synopsis: 'The presidencies of Kennedy and Johnson, Vietnam, Watergate, and other historical events unfold through the perspective of an Alabama man.',
    runtime: 142,
    status: 'watched',
    rating: 4,
    tags: ['classic', 'americana', 'drama'],
    addedAt: '2024-02-15',
    imdbRating: 8.8,
    rtScore: 71,
    metacriticScore: 82,
    viewings: [
      { id: 'v12', titleId: '9', date: '2024-02-15', rating: 4 },
    ],
  },
  {
    id: '10',
    tmdbId: 346698,
    type: 'movie',
    title: 'Barbie',
    year: 2023,
    director: 'Greta Gerwig',
    genres: ['Adventure', 'Comedy', 'Fantasy'],
    posterUrl: 'https://image.tmdb.org/t/p/w500/iuFNMS8U5cb6xfzi51Dbkovj7vM.jpg',
    synopsis: 'Barbie and Ken are having the time of their lives in the colorful and seemingly perfect world of Barbie Land.',
    runtime: 114,
    status: 'watching',
    rating: undefined,
    tags: ['2023', 'feminist', 'comedy'],
    addedAt: '2024-03-10',
    imdbRating: 6.9,
    rtScore: 88,
    viewings: [],
  },
]

export const mockLedgerStats: LedgerStats = {
  totalMovies: 8,
  totalSeries: 2,
  totalViewings: 13,
  avgRating: 4.5,
  totalMinutes: 1701,
  topGenres: [
    { genre: 'Drama', count: 7 },
    { genre: 'Crime', count: 5 },
    { genre: 'Thriller', count: 4 },
    { genre: 'Science Fiction', count: 2 },
    { genre: 'Adventure', count: 2 },
    { genre: 'Fantasy', count: 2 },
  ],
  topDirectors: [
    { director: 'David Fincher', count: 1 },
    { director: 'Christopher Nolan', count: 1 },
    { director: 'Quentin Tarantino', count: 1 },
    { director: 'Francis Ford Coppola', count: 1 },
    { director: 'Greta Gerwig', count: 1 },
  ],
  ratingDistribution: [
    { rating: 5, count: 4 },
    { rating: 4.5, count: 1 },
    { rating: 4, count: 2 },
    { rating: 3.5, count: 1 },
    { rating: 3, count: 0 },
    { rating: 2.5, count: 0 },
    { rating: 2, count: 0 },
    { rating: 1.5, count: 0 },
    { rating: 1, count: 0 },
  ],
  viewingsByMonth: [
    { month: '2024-01', count: 4 },
    { month: '2024-02', count: 3 },
    { month: '2024-03', count: 2 },
    { month: '2023-11', count: 2 },
    { month: '2023-08', count: 1 },
    { month: '2023-05', count: 1 },
    { month: '2022-10', count: 1 },
    { month: '2021-06', count: 1 },
  ],
}

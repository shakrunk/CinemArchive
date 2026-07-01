import { performance } from 'perf_hooks';

// Mock Supabase
let callCount = 0;
const supabase = {
  from: (table) => ({
    upsert: async (data, options) => {
      callCount++;
      // Simulate network latency (e.g., 50ms per request)
      await new Promise(resolve => setTimeout(resolve, 50));
      return { error: null };
    }
  })
};

async function originalUpsert(patch, titleId, userId) {
  if (patch.seasons) {
    for (const s of patch.seasons) {
      const { error } = await supabase
        .from('seasons')
        .upsert(
          {
            title_id: titleId,
            user_id: userId,
            season_number: s.seasonNumber,
            episode_count: s.episodeCount,
            episodes_watched: s.episodesWatched,
            air_year: s.airYear,
          },
          { onConflict: 'title_id,season_number' }
        );
      if (error) throw error;
    }
  }
}

async function optimizedUpsert(patch, titleId, userId) {
  if (patch.seasons && patch.seasons.length > 0) {
    const { error } = await supabase
      .from('seasons')
      .upsert(
        patch.seasons.map(s => ({
          title_id: titleId,
          user_id: userId,
          season_number: s.seasonNumber,
          episode_count: s.episodeCount,
          episodes_watched: s.episodesWatched,
          air_year: s.airYear,
        })),
        { onConflict: 'title_id,season_number' }
      );
    if (error) throw error;
  }
}

async function run() {
  const patch = {
    seasons: Array.from({ length: 20 }, (_, i) => ({
      seasonNumber: i + 1,
      episodeCount: 10,
      episodesWatched: 10,
      airYear: 2000 + i
    }))
  };

  callCount = 0;
  const startOriginal = performance.now();
  await originalUpsert(patch, 'title-123', 'user-456');
  const endOriginal = performance.now();
  console.log(`Original: ${(endOriginal - startOriginal).toFixed(2)}ms, DB calls: ${callCount}`);

  callCount = 0;
  const startOptimized = performance.now();
  await optimizedUpsert(patch, 'title-123', 'user-456');
  const endOptimized = performance.now();
  console.log(`Optimized: ${(endOptimized - startOptimized).toFixed(2)}ms, DB calls: ${callCount}`);
}

run();

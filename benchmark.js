import { performance } from 'perf_hooks';

// Mock supabase client
const createMockSupabase = (delayMs) => ({
  from: (table) => ({
    upsert: async (data, options) => {
      // Simulate network delay
      await new Promise(resolve => setTimeout(resolve, delayMs));
      return { error: null };
    }
  })
});

async function runBenchmark() {
  const supabase = createMockSupabase(50); // 50ms per network call

  const viewings = Array.from({ length: 50 }, (_, i) => ({
    id: `v${i}`,
    date: new Date().toISOString(),
    rating: 5,
    notes: `Note ${i}`
  }));

  const titleId = 't1';
  const userId = 'u1';

  console.log(`Benchmarking with ${viewings.length} viewings...`);

  // --- N+1 Strategy ---
  const startNPlus1 = performance.now();
  for (const v of viewings) {
    const { error } = await supabase.from('viewings').upsert({
      id: v.id,
      title_id: titleId,
      user_id: userId,
      viewed_at: v.date,
      rating: v.rating,
      notes: v.notes,
    });
    if (error) throw error;
  }
  const endNPlus1 = performance.now();
  const timeNPlus1 = endNPlus1 - startNPlus1;

  // --- Bulk Strategy ---
  const startBulk = performance.now();
  if (viewings.length > 0) {
    const mapped = viewings.map(v => ({
      id: v.id,
      title_id: titleId,
      user_id: userId,
      viewed_at: v.date,
      rating: v.rating,
      notes: v.notes,
    }));
    const { error } = await supabase.from('viewings').upsert(mapped);
    if (error) throw error;
  }
  const endBulk = performance.now();
  const timeBulk = endBulk - startBulk;

  console.log(`N+1 strategy: ${timeNPlus1.toFixed(2)} ms`);
  console.log(`Bulk strategy: ${timeBulk.toFixed(2)} ms`);
  console.log(`Improvement: ${(timeNPlus1 / timeBulk).toFixed(2)}x faster`);
}

runBenchmark().catch(console.error);

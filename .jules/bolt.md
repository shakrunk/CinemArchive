## 2024-06-25 - Resolve N+1 Query in Viewing Upserts
**Learning:** Resolving N+1 database queries with Supabase is extremely effective using bulk `.upsert()` with an array of objects rather than running an upsert in a loop. A mock benchmark verified ~49x improvement on 50 records.
**Action:** Always favor bulk database queries with Supabase over loops.
## 2025-02-17 - Resolve N+1 Query in Insertion Loops
**Learning:** Adding a new TV show triggers an N+1 query problem by running separate `supabase.from('episode_crew').insert()` calls for *every single episode* in a loop.
**Action:** Replace the nested loops with `flatMap` to generate arrays of all items across the entire structure, and then perform bulk inserts.

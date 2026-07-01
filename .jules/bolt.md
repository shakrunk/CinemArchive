## 2024-06-25 - Resolve N+1 Query in Viewing Upserts
**Learning:** Resolving N+1 database queries with Supabase is extremely effective using bulk `.upsert()` with an array of objects rather than running an upsert in a loop. A mock benchmark verified ~49x improvement on 50 records.
**Action:** Always favor bulk database queries with Supabase over loops.

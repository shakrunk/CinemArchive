# Known Problems

## Import: viewing `titleId` not resolved after insert

**File:** `src/lib/export-import.ts:51`, `src/components/ProfileModal.tsx` (`handleImportFile`)

`parseImportFile` sets `titleId: undefined` on imported viewings with a comment "will be resolved after insert". The call site in `ProfileModal.tsx` calls `insertTitleToDb` for each new title but never patches the `titleId` back onto the viewing records before writing them. Imported viewings end up with a broken foreign key and fail to associate with their parent title in the DB.

**Fix needed:** After inserting each title, look up its newly assigned `id` and update the corresponding viewing records before batch-inserting them.

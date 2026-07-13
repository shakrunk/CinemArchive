package work.kumarfamilynet.cinemarchive.data

import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

sealed interface PushResult {
    data object Success : PushResult
    data class Retry(val reason: String) : PushResult
}

/** Pushes one queued mutation to the backend. Implemented once a real Supabase network
 *  client and auth session exist — see docs/android-sync-contract.md §4 for the contract
 *  each entity type's push must satisfy (client-generated id, upsert-not-insert). */
interface RemoteMutationWriter {
    suspend fun push(entry: OutboxEntity): PushResult
}

/**
 * Stands in for [RemoteMutationWriter] until real network sync is wired up. Credential
 * Manager/WebAuthn auth is blocked on a physical Android device
 * (docs/android-implementation-status.md), so there is no authenticated client to push
 * through yet. Always retries rather than dropping or falsely marking mutations synced —
 * they stay durable in the outbox and flush correctly once a real writer replaces this one.
 */
class UnconfiguredRemoteMutationWriter : RemoteMutationWriter {
    override suspend fun push(entry: OutboxEntity): PushResult =
        PushResult.Retry("Remote sync not yet configured")
}

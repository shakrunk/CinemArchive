package work.kumarfamilynet.cinemarchive

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.RoomTransactor
import work.kumarfamilynet.cinemarchive.data.AccountRepository
import work.kumarfamilynet.cinemarchive.data.AuthIdentity
import work.kumarfamilynet.cinemarchive.data.AuthRepository
import work.kumarfamilynet.cinemarchive.data.DiscoverRepository
import work.kumarfamilynet.cinemarchive.data.FriendsRepository
import work.kumarfamilynet.cinemarchive.data.FencedSessionSource
import work.kumarfamilynet.cinemarchive.data.LedgerLayoutRepository
import work.kumarfamilynet.cinemarchive.data.LegacyArchive
import work.kumarfamilynet.cinemarchive.data.LegacyArchiveStatus
import work.kumarfamilynet.cinemarchive.data.LegacyRestoreResult
import work.kumarfamilynet.cinemarchive.data.LedgerRepository
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.LibrarySyncRepository
import work.kumarfamilynet.cinemarchive.data.ListsRepository
import work.kumarfamilynet.cinemarchive.data.MutationOutbox
import work.kumarfamilynet.cinemarchive.data.NotificationsRepository
import work.kumarfamilynet.cinemarchive.data.OutingsRepository
import work.kumarfamilynet.cinemarchive.data.OwnerNamespace
import work.kumarfamilynet.cinemarchive.data.RuntimeHandle
import work.kumarfamilynet.cinemarchive.data.UiAttachmentGate
import work.kumarfamilynet.cinemarchive.data.SupabaseLedgerLayoutWriter
import work.kumarfamilynet.cinemarchive.data.SupabaseRemoteMutationWriter
import work.kumarfamilynet.cinemarchive.data.SupabaseRestClient
import work.kumarfamilynet.cinemarchive.data.SyncServices
import work.kumarfamilynet.cinemarchive.data.TitleConflictHandler

/**
 * Everything that belongs to ONE sign-in: its own Room file, DataStores (sync cursor, Ledger
 * layout), outbox, repositories and background jobs — all reading the session only through a
 * [FencedSessionSource] pinned to [identity]. Nothing here is shared with another account, so
 * the offline queue written by A can only ever be pushed under A's token, and B starts from
 * B's own (empty) storage. Torn down completely by [close] before the next runtime is built
 * (see [work.kumarfamilynet.cinemarchive.data.AccountRuntimeManager]).
 *
 * Doubles as the [ViewModelStoreOwner] for the whole signed-in UI: ViewModels are cleared with
 * the runtime, so an Activity-scoped ViewModel built over A's repositories can never survive
 * into B's session.
 */
class AppAccountRuntime(
    private val context: Context,
    appScope: CoroutineScope,
    override val identity: AuthIdentity,
    auth: AuthRepository,
    client: SupabaseRestClient,
    discoverRepository: DiscoverRepository,
    plexClientId: String,
) : RuntimeHandle, ViewModelStoreOwner {
    val ownerId: String get() = identity.userId
    private val ownerKey = OwnerNamespace.key(identity.userId)

    private val auth0 = auth
    @Volatile private var closing = false
    private val residueLock = Any()

    private val job = SupervisorJob(appScope.coroutineContext[Job])
    private val scope = CoroutineScope(
        job + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w("AccountRuntime", "background job failed", e) },
    )

    override val viewModelStore = ViewModelStore()

    private val session = FencedSessionSource(auth, identity)

    private val database = LibraryDatabase.create(context, OwnerNamespace.databaseName(identity.userId))

    private fun dataStore(base: String): DataStore<Preferences> {
        val name = OwnerNamespace.dataStoreName(base, identity.userId)
        return PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
    }

    private val transactor = RoomTransactor(database)

    val tickets: work.kumarfamilynet.cinemarchive.data.TicketRuntime by lazy {
        work.kumarfamilynet.cinemarchive.data.TicketRuntime.create(database,
            work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope(BuildConfig.SUPABASE_URL.trimEnd('/'), ownerId),
            java.io.File(context.filesDir, "ticket-originals"), BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            session::currentSession, { outbox }, ::isCurrent,
            replayBoundary = { action -> librarySyncRepository.withDurableReplay(action) })
    }

    private val ordinaryWriter = SupabaseRemoteMutationWriter(client) { session.currentSession() ?: error("Not signed in") }
    private val importOwner = work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope(BuildConfig.SUPABASE_URL.trimEnd('/'), ownerId)
    private val importWriter = work.kumarfamilynet.cinemarchive.data.BackupImportTransport(client, session, importOwner, ::isCurrent)
    val restoreRepository: work.kumarfamilynet.cinemarchive.data.BackupImportRepository by lazy {
        work.kumarfamilynet.cinemarchive.data.BackupImportRepository(database, outbox, importOwner, ::isCurrent,
            sync = {
                outingsRepository.refreshAlarm()
                librarySyncRepository.syncNow()
                outingsRepository.refreshAlarm()
            },
            replay = { action -> librarySyncRepository.withDurableReplay(action) })
    }

    val moviegoingPreferences: work.kumarfamilynet.cinemarchive.data.MoviegoingPreferencesRepository by lazy {
        work.kumarfamilynet.cinemarchive.data.MoviegoingPreferencesRepository(database, outbox, ownerId, ::isCurrent,
            client, session::currentSession,
            work.kumarfamilynet.cinemarchive.data.DataStoreOutingRecoveryArchive(dataStore("cinemarchive_moviegoing_recovery")),
            synchronize = { librarySyncRepository.syncNow() },
            replayBoundary = { action -> librarySyncRepository.withDurableReplay(action) },
            onQueued = { if (isCurrent()) scope.launch { librarySyncRepository.syncNow() } })
    }

    private val outbox: MutationOutbox = MutationOutbox(
        database.outboxDao(),
        object : work.kumarfamilynet.cinemarchive.data.RemoteMutationWriter {
            override suspend fun push(entry: work.kumarfamilynet.cinemarchive.core.database.OutboxEntity) =
                if (work.kumarfamilynet.cinemarchive.data.isBackupImport(entry)) importWriter.push(entry) else when (entry.entityType) {
                    "ticket_attachment" -> tickets.push(entry)
                    "venue_note", "theater_interest" -> moviegoingPreferences.push(entry)
                    else -> ordinaryWriter.push(entry)
                }
        },
        TitleConflictHandler(database.titleDao(), database.titleReconcileDao()),
        transactor,
        outingOwnerScope = work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope(BuildConfig.SUPABASE_URL.trimEnd('/'), identity.userId),
        appliedHandler = work.kumarfamilynet.cinemarchive.data.AppliedMutationHandler { entry, receipt ->
            check(auth.observeIdentity().value == identity) { "This sign-in has ended" }
            when (entry.entityType) {
                "outing_completion" -> work.kumarfamilynet.cinemarchive.data.OutingCompletionApplier(database, ownerId).apply(entry, receipt)
                "outing_reversal" -> work.kumarfamilynet.cinemarchive.data.OutingReversalApplier(database, ownerId).apply(entry, receipt)
                "venue_note", "theater_interest" -> moviegoingPreferences.apply(entry, receipt)
                "ticket_attachment" -> tickets.apply(entry, receipt)
                "title" -> if (work.kumarfamilynet.cinemarchive.data.isBackupImport(entry))
                    work.kumarfamilynet.cinemarchive.data.BackupImportApplier(database, importOwner).apply(entry, receipt)
                else work.kumarfamilynet.cinemarchive.data.TitleMetadataApplier(database, ownerId).apply(entry, receipt)
                "episode_bulk" -> work.kumarfamilynet.cinemarchive.data.EpisodeBulkApplier(database, ownerId).apply(entry, receipt)
                "title_credits" -> work.kumarfamilynet.cinemarchive.data.CreditReceiptApplier(database, ownerId).apply(entry, receipt)
                "title_catalog" -> work.kumarfamilynet.cinemarchive.data.EpisodeCatalogFillApplier(database, ownerId).apply(entry, receipt)
                "list_item" -> work.kumarfamilynet.cinemarchive.data.ListMembershipApplier(database, ownerId).apply(entry, receipt)
                "cinema_outing" -> work.kumarfamilynet.cinemarchive.data.OutingCommandApplier(database, ownerId).apply(entry, receipt)
                "viewing" -> work.kumarfamilynet.cinemarchive.data.ViewingCommandApplier(database, ownerId) {
                    auth.observeIdentity().value == identity
                }.apply(entry, receipt)
                else -> error("No canonical receipt handler for ${entry.entityType}")
            }
            check(auth.observeIdentity().value == identity) { "This sign-in has ended" }
        },
        pendingProjectionKeys = { entries ->
            check(auth.observeIdentity().value == identity) { "This sign-in has ended" }
            work.kumarfamilynet.cinemarchive.data.CreditReceiptApplier(database, ownerId).protectionKeys(entries) + tickets.protectionKeys(entries) +
                work.kumarfamilynet.cinemarchive.data.viewingHistoryProtectionKeys(entries, ownerId) + moviegoingPreferences.protectionKeys(entries) +
                work.kumarfamilynet.cinemarchive.data.backupImportProtectionKeys(entries, importOwner) +
                work.kumarfamilynet.cinemarchive.data.outingLifecycleProtectionKeys(entries, ownerId)
        },
    )

    /** Edits the server refused because a newer version existed (projection already reconciled). */
    val conflicts get() = outbox.conflicts

    private val alarmScheduler = AndroidOutingAlarmScheduler(context, identity.userId)

    val accountRepository = AccountRepository(client, session::currentSession)
    val episodeBulkRepository by lazy {
        work.kumarfamilynet.cinemarchive.data.EpisodeBulkRepository(database, outbox, ownerId, ::isCurrent,
            work.kumarfamilynet.cinemarchive.data.EpisodeBulkTransport(client, session),
            synchronize = { librarySyncRepository.syncNow() }, replay = { action -> librarySyncRepository.withDurableReplay(action) },
            requestSync = { if (isCurrent()) scope.launch { librarySyncRepository.syncNow() }; Unit })
    }
    val catalogExtrasRepository = work.kumarfamilynet.cinemarchive.data.CatalogExtrasRepository(client, session)
    val titleMetadataRepository = work.kumarfamilynet.cinemarchive.data.TitleMetadataRepository(
        database, outbox, ownerId, session, work.kumarfamilynet.cinemarchive.data.TitleMetadataTransport(client, session),
        synchronize = { librarySyncRepository.syncNow() },
        replayBoundary = { action -> librarySyncRepository.withDurableReplay(action) },
    )
    val notificationsRepository = NotificationsRepository(client, session::currentSession)
    val friendsRepository = FriendsRepository(client, session::currentSession)
    val sharingRepository = work.kumarfamilynet.cinemarchive.data.SharingRepository(
        client, session::currentSession,
    )

    val ledgerLayoutRepository = LedgerLayoutRepository(dataStore("cinemarchive_ledger_layout"), session, SupabaseLedgerLayoutWriter(client))

    val librarySyncRepository = LibrarySyncRepository(
        dataStore = dataStore("cinemarchive_sync"),
        client = client,
        authRepository = session,
        titleDao = database.titleDao(),
        seasonDao = database.seasonDao(),
        episodeDao = database.episodeDao(),
        watchEventDao = database.episodeWatchEventDao(),
        ratingDao = database.episodeRatingDao(),
        reviewDao = database.episodeReviewDao(),
        viewingDao = database.viewingDao(),
        cinemaOutingDao = database.cinemaOutingDao(),
        titleCastDao = database.titleCastDao(),
        titleCrewDao = database.titleCrewDao(),
        listDao = database.listDao(),
        listItemDao = database.listItemDao(),
        personCreditsDao = database.personCreditsDao(),
        pushPending = outbox::flush,
        pendingKeys = outbox::pendingEntityKeys,
        transactor = transactor,
        afterPull = { tickets.refresh() },
        venueNoteDao = database.venueNoteDao(),
        theaterInterestDao = database.theaterInterestDao(),
    )

    val listsRepository = ListsRepository(
        listDao = database.listDao(),
        listItemDao = database.listItemDao(),
        outbox = outbox,
    )

    val libraryRepository = LibraryRepository(
        titleDao = database.titleDao(),
        seasonDao = database.seasonDao(),
        episodeDao = database.episodeDao(),
        watchEventDao = database.episodeWatchEventDao(),
        ratingDao = database.episodeRatingDao(),
        reviewDao = database.episodeReviewDao(),
        viewingDao = database.viewingDao(),
        cinemaOutingDao = database.cinemaOutingDao(),
        titleCastDao = database.titleCastDao(),
        titleCrewDao = database.titleCrewDao(),
        theaterInterestDao = database.theaterInterestDao(),
        outbox = outbox,
        episodeMetadataFetcher = discoverRepository,
        personCreditsDao = database.personCreditsDao(),
        mutationOwnerId = ownerId,
        viewingAliases = database.viewingCompletionAliasDao(),
        isCurrentOwner = { auth.observeIdentity().value == identity },
        moviegoingPreferences = moviegoingPreferences,
        episodeBulkRepository = episodeBulkRepository,
    )

    val syncServices = SyncServices.create(libraryRepository, discoverRepository, session, client, plexClientId,
        work.kumarfamilynet.cinemarchive.data.ProviderImportAdmission(database, outbox, importOwner, ::isCurrent),
        ::isCurrent, { librarySyncRepository.syncNow() })

    val backupRepository by lazy {
        work.kumarfamilynet.cinemarchive.data.LibraryBackupRepository(database,
            work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope(BuildConfig.SUPABASE_URL.trimEnd('/'), ownerId),
            ::isCurrent, synchronize = { librarySyncRepository.syncNow() })
    }

    val creditRefreshRepository = work.kumarfamilynet.cinemarchive.data.CreditRefreshRepository(
        database, outbox,
        work.kumarfamilynet.cinemarchive.data.CreditMetadataFetcher { title ->
            discoverRepository.fetchDetails(work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult(
                title.tmdbId, title.title, title.year, work.kumarfamilynet.cinemarchive.core.model.MediaType.valueOf(title.type), title.posterUrl, title.synopsis,
            ))
        }, ownerId, isCurrentOwner = { auth.observeIdentity().value == identity },
    )

    private val outingLifecycle = work.kumarfamilynet.cinemarchive.data.OutingLifecycleRepository(database, outbox, ownerId) {
        auth.observeIdentity().value == identity
    }
    val outingsRepository = OutingsRepository(
        cinemaOutingDao = database.cinemaOutingDao(),
        viewingDao = database.viewingDao(),
        titleDao = database.titleDao(),
        outbox = outbox,
        venueNoteDao = database.venueNoteDao(),
        alarmScheduler = alarmScheduler,
        moviegoingPreferences = moviegoingPreferences,
        lifecycle = outingLifecycle,
    )

    val outingPlansRepository = work.kumarfamilynet.cinemarchive.data.OutingPlansRepository.create(
        client, ownerId, session::currentSession,
        snapshot = { id -> work.kumarfamilynet.cinemarchive.data.readOutingPlanSnapshot(
            id, database.cinemaOutingDao(), database.titleDao(), outbox,
        ) },
        friendsRepository = friendsRepository,
    )

    val listMembershipRecovery = work.kumarfamilynet.cinemarchive.data.ListMembershipRecoveryRepository(
        database, ownerId, session::currentSession, outbox,
        work.kumarfamilynet.cinemarchive.data.DataStoreOutingRecoveryArchive(dataStore("cinemarchive_list_recovery")),
        work.kumarfamilynet.cinemarchive.data.ListMembershipRecoveryRepository.remote(client, ownerId, session::currentSession),
        replayBoundary = { action -> librarySyncRepository.withDurableReplay(action) },
        synchronize = { librarySyncRepository.syncNow() },
    )

    val outingRecoveryRepository = work.kumarfamilynet.cinemarchive.data.OutingRecoveryRepository(
        ownerId, session::currentSession, database.outboxDao(), database.cinemaOutingDao(),
        database.titleDao(), outbox,
        work.kumarfamilynet.cinemarchive.data.DataStoreOutingRecoveryArchive(dataStore("cinemarchive_outing_recovery")),
        work.kumarfamilynet.cinemarchive.data.OutingRecoveryRepository.remote(client, ownerId, session::currentSession),
    )
    val outingLifecycleRecovery: work.kumarfamilynet.cinemarchive.data.OutingRecoverySource = work.kumarfamilynet.cinemarchive.data.OutingLifecycleRecovery(
        database, outbox, ownerId, client, session::currentSession,
        work.kumarfamilynet.cinemarchive.data.DataStoreOutingRecoveryArchive(dataStore("cinemarchive_outing_lifecycle_recovery")),
        replayBoundary = { action -> librarySyncRepository.withDurableReplay(action) },
        synchronize = { librarySyncRepository.syncNow(); outingsRepository.refreshAlarm() },
    )
    val viewingRecoveryRepository = work.kumarfamilynet.cinemarchive.data.ViewingRecoveryRepository(
        database, ownerId, session::currentSession, outbox,
        work.kumarfamilynet.cinemarchive.data.DataStoreOutingRecoveryArchive(dataStore("cinemarchive_viewing_recovery")),
        work.kumarfamilynet.cinemarchive.data.ViewingRecoveryRepository.remote(client, ownerId, session::currentSession),
        replayBoundary = { action -> librarySyncRepository.withDurableReplay(action) },
    )

    val ledgerRepository = LedgerRepository(
        titleDao = database.titleDao(),
        viewingDao = database.viewingDao(),
        titleCastDao = database.titleCastDao(),
        titleCrewDao = database.titleCrewDao(),
        cinemaOutingDao = database.cinemaOutingDao(),
        watchEventDao = database.episodeWatchEventDao(),
        seasonDao = database.seasonDao(),
        episodeDao = database.episodeDao(),
    )

    private var closed = false

    private val uiGate = UiAttachmentGate()

    /** Called by the shell when a composition starts reading this runtime / is disposed. Counted,
     *  so a rotation (detach + re-attach) is waited for correctly at close. */
    fun onUiAttached() = uiGate.attach()
    fun onUiDetached() = uiGate.detach()

    /** Network work starts here, after the runtime is already published — a slow or offline
     *  network never delays a usable (cached, same-owner) runtime. */
    fun startBackgroundWork() {
        scope.launch {
            // syncNow pushes queued edits first, then pulls (one serialized pipeline; pending
            // rows are protected from the pull).
            librarySyncRepository.syncNow()
            // Must run after sync so a trip completed on another device already reflects locally.
            outingsRepository.completeDueOutings()
        }
        scope.launch { ledgerLayoutRepository.reconcile() }
    }

    private val legacyArchive = LegacyArchive(context)

    /** Count-only look at the pre-isolation database relative to THIS account (see [LegacyArchive]); no content. */
    suspend fun legacyStatus(): LegacyArchiveStatus =
        withContext(Dispatchers.IO) { legacyArchive.inspect(database, ownerKey) }

    /** Explicitly user-confirmed restore of that archive into THIS account (one confirmation;
     *  [includeLocalOnly] also copies the local-only rows, which are never uploaded); then
     *  re-syncs, with the restored pending entries protected from the pull. */
    suspend fun restoreLegacy(includeLocalOnly: Boolean): LegacyRestoreResult =
        legacyArchive.restoreInto(database, transactor, ownerKey, includeLocalOnly)
            .also { if (it is LegacyRestoreResult.Success) scope.launch { librarySyncRepository.syncNow() } }

    /** Runs the completion engine and returns the transitions, for the alarm receiver. */
    suspend fun completeDueOutings() = outingsRepository.completeDueOutings()

    /** Runs [block] inside this runtime's scope: closing the runtime cancels it. */
    suspend fun <T> runOwned(block: suspend () -> T): T = scope.async { block() }.await()

    /** A durable capture survives leaving the viewer; delivery belongs to this account runtime. */
    fun syncTickets() { if (isCurrent()) scope.launch { librarySyncRepository.syncNow() } }

    /** True while this is still exactly the current sign-in and not being torn down. */
    fun isCurrent(): Boolean = !closing && auth0.observeIdentity().value == identity

    /** Runs [post] (an OS-visible side effect such as a notification) only if still current;
     *  atomic with [close]'s "closing" flip, so a post either happens before teardown
     *  (and is then cancelled by it) or never. */
    fun postIfCurrent(post: () -> Unit): Boolean = synchronized(residueLock) {
        if (!isCurrent()) false else { post(); true }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        synchronized(residueLock) { closing = true }
        // Order matters. First wait (deterministically, bounded) for the UI that was reading this
        // runtime to leave composition — the shell stops rendering it as soon as the sign-in changes
        // (see visibleRuntime), so this resolves within a frame; the timeout only bounds a wedged UI
        // thread — so no Room Flow collector is attached when things start shutting down…
        uiGate.awaitDetached(UI_DETACH_TIMEOUT_MS)
        // …then stop everything that could touch the database or network…
        // ViewModels are cleared on the main thread (their viewModelScope is Main.immediate and
        // is cancelled by clear()), BEFORE the runtime scope is joined and the database closed.
        withContext(Dispatchers.Main.immediate) { viewModelStore.clear() }
        job.cancelAndJoin()
        // …then remove this account's OS-level residue (alarm, posted notifications)…
        alarmScheduler.scheduleNext(null)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == ownerKey }.forEach { manager.cancel(it.tag, it.id) }
        // …and only then close the Room file.
        database.close()
    }

    companion object {
        private const val UI_DETACH_TIMEOUT_MS = 3_000L

        /** Notification tag for everything posted on behalf of [userId]. */
        fun notificationTag(userId: String): String = OwnerNamespace.key(userId)
    }
}

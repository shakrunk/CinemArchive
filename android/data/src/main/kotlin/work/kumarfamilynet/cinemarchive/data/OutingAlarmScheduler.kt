package work.kumarfamilynet.cinemarchive.data

import java.time.Instant

/**
 * Arms/disarms the single exact alarm that fires the local completion engine even when the
 * app is fully closed — the one place Android can exceed the web app's completion timing
 * (docs/superpowers/plans/2026-07-21-android-cinema-outings.md §6): a phone can wake for an
 * alarm; a browser tab cannot. The real implementation ([AlarmManager]-backed) lives in the
 * `app` module (it needs to target a manifest-registered [android.content.BroadcastReceiver]),
 * so this interface is the seam [OutingsRepository] depends on instead.
 */
interface OutingAlarmScheduler {
    /** Re-arms for [nextTransitionAt] (the soonest scheduled outing's `endsAt`), replacing any
     *  previously armed alarm. Pass null to disarm entirely (no scheduled outings left). */
    fun scheduleNext(nextTransitionAt: Instant?)
}

/** Default until the app wires a real [OutingAlarmScheduler] — foreground-only reconciliation
 *  (app launch/resume) still works without it, same "safe to omit, never crashes" stance as
 *  [UnconfiguredRemoteMutationWriter]. */
object NoOpOutingAlarmScheduler : OutingAlarmScheduler {
    override fun scheduleNext(nextTransitionAt: Instant?) = Unit
}

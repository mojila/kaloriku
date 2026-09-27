package id.kaloriku.shared.sync

/**
 * Observable state of the phone <-> watch link, shared by both apps so the manual
 * sync buttons can show honest feedback.
 */
data class SyncStatus(
    val running: Boolean = false,
    /** True when at least one paired node is currently reachable. */
    val connected: Boolean = false,
    /** Entries logged here that the peer has not acked yet. */
    val pending: Int = 0,
    /** Number of entries merged in the most recent exchange. */
    val lastMerged: Int = 0,
    val lastSuccessAt: Long? = null,
    val message: String? = null,
) {
    val hasSynced: Boolean get() = lastSuccessAt != null
}

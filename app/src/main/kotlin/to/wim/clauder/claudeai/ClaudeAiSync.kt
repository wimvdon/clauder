package to.wim.clauder.claudeai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the last read of claude.ai's thread lists found, and when. */
data class RemoteSnapshot(val threads: List<RemoteThread>, val fetchedAtMillis: Long, val organization: String)

/**
 * Reads every recently active project's threads from claude.ai, on the phone, with the sign-in kept
 * there. Runs when the Tasks screen opens or refreshes and after each push, at most every
 * [MIN_GAP_MS] (a minute). Failures keep the last good snapshot: claude.ai's view is an extra, the hooks stay
 * the main source.
 */
object ClaudeAiSync {
    private val snapshotFlow = MutableStateFlow<RemoteSnapshot?>(null)
    val snapshot: StateFlow<RemoteSnapshot?> = snapshotFlow.asStateFlow()

    private val running = Mutex()
    private var lastStartMillis = 0L

    /** Forgets the snapshot (on sign-out). */
    fun clear() {
        snapshotFlow.value = null
    }

    /**
     * Refreshes the snapshot unless one ran less than [MIN_GAP_MS] ago. [onOrganization] is called
     * when a different organization than [savedOrganization] answered, so it can be kept.
     */
    suspend fun refresh(
        calls: ClaudeAiCalls,
        savedOrganization: String?,
        nowMillis: Long,
        onOrganization: suspend (String) -> Unit = {},
    ) {
        if (running.isLocked) return
        running.withLock {
            if (nowMillis - lastStartMillis < MIN_GAP_MS) return
            lastStartMillis = nowMillis
            val result = read(calls, savedOrganization, nowMillis) ?: return
            if (result.organization != savedOrganization) onOrganization(result.organization)
            snapshotFlow.value = result
        }
    }

    /** One full read, or null when claude.ai didn't answer the projects call for any organization. */
    suspend fun read(calls: ClaudeAiCalls, savedOrganization: String?, nowMillis: Long): RemoteSnapshot? {
        val (organization, projectsBody) = projectList(calls, savedOrganization) ?: return null
        val threads = RemoteThreads.projects(projectsBody, nowMillis).take(MAX_PROJECTS).flatMap { project ->
            val answer = calls.get(ClaudeAi.Paths.threads(project.id, limit = THREADS_PER_STATUS))
            if (answer.ok) RemoteThreads.threads(answer.body, project) else emptyList()
        }
        return RemoteSnapshot(threads, nowMillis, organization)
    }

    /** The kept organization first; if it doesn't answer, each one in the account's list. */
    private suspend fun projectList(calls: ClaudeAiCalls, saved: String?): Pair<String, String>? {
        suspend fun tryOrg(org: String): Pair<String, String>? {
            calls.organization = org
            val answer = calls.get(ClaudeAi.Paths.channels(limit = MAX_PROJECTS))
            return if (answer.ok) org to answer.body else null
        }
        saved?.let { org -> tryOrg(org)?.let { return it } }
        val orgs = calls.get(ClaudeAi.Paths.ORGANIZATIONS)
        if (!orgs.ok) return null
        return ClaudeAi.organizations(orgs.body).filter { it != saved }.firstNotNullOfOrNull { tryOrg(it) }
    }

    const val MIN_GAP_MS = 60_000L
    const val MAX_PROJECTS = 20
    const val THREADS_PER_STATUS = 20
}

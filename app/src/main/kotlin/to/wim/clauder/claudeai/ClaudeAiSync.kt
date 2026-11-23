package to.wim.clauder.claudeai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the last read of claude.ai's thread lists found, and when, with the projects it read. */
data class RemoteSnapshot(
    val threads: List<RemoteThread>,
    val fetchedAtMillis: Long,
    val organization: String,
    val projects: List<RemoteProject> = emptyList(),
)

/**
 * Reads every recently active project's threads from claude.ai, on the phone, with the sign-in kept
 * there. Runs when the Tasks screen opens or refreshes, after each push and from [ClaudeAiJob], at
 * most every [MIN_GAP_MS] (a minute). Failures keep the last good snapshot: claude.ai's view is an
 * extra, the hooks stay the main source.
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

    /** The snapshot saved before the app last stopped, until a new read replaces it. */
    fun restore(saved: RemoteSnapshot?) {
        if (snapshotFlow.value == null && saved != null) snapshotFlow.value = saved
    }

    /**
     * Refreshes the snapshot unless one ran less than [MIN_GAP_MS] ago, and returns the new one
     * (null when skipped or claude.ai didn't answer). [onOrganization] is called when a different
     * organization than [savedOrganization] answered, so it can be kept.
     */
    suspend fun refresh(
        calls: ClaudeAiCalls,
        savedOrganization: String?,
        nowMillis: Long,
        onOrganization: suspend (String) -> Unit = {},
    ): RemoteSnapshot? {
        if (running.isLocked) return null
        return running.withLock {
            if (nowMillis - lastStartMillis < MIN_GAP_MS) return@withLock null
            lastStartMillis = nowMillis
            val result = read(calls, savedOrganization, nowMillis, snapshotFlow.value) ?: return@withLock null
            if (result.organization != savedOrganization) onOrganization(result.organization)
            snapshotFlow.value = result
            result
        }
    }

    /**
     * One read, or null when claude.ai didn't answer the projects call for any organization. A
     * project whose activity mark is the same as in [previous] keeps the threads read then, unless
     * that read is older than [FULL_READ_MS].
     */
    suspend fun read(calls: ClaudeAiCalls, savedOrganization: String?, nowMillis: Long, previous: RemoteSnapshot? = null): RemoteSnapshot? {
        val (organization, projectsBody) = projectList(calls, savedOrganization) ?: return null
        val reuse = previous?.takeIf { it.organization == organization && nowMillis - it.fetchedAtMillis < FULL_READ_MS }
        val projects = RemoteThreads.projects(projectsBody, nowMillis).take(MAX_PROJECTS)
        val threads = projects.flatMap { project ->
            val before = reuse?.projects?.firstOrNull { it.id == project.id }
            if (before != null && project.threadsMark != null && before.threadsMark == project.threadsMark) {
                return@flatMap reuse.threads.filter { it.project.id == project.id }.map { it.copy(project = project) }
            }
            val answer = calls.get(ClaudeAi.Paths.threads(project.id, limit = THREADS_PER_STATUS))
            if (answer.ok) RemoteThreads.threads(answer.body, project) else emptyList()
        }
        return RemoteSnapshot(threads, nowMillis, organization, projects)
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
    const val FULL_READ_MS = 10 * 60_000L
    const val MAX_PROJECTS = 20
    const val THREADS_PER_STATUS = 20
}

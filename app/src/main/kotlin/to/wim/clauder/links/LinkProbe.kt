package to.wim.clauder.links

/**
 * One way of opening the Claude app, tried from the link tester in test builds. Plain data, so
 * the list and its labels are unit tested; `ui/linktester` turns it into an Android intent.
 */
data class LinkProbe(
    val action: String = VIEW,
    val uri: String? = null,
    /** Limits the intent to this app (like a shortcut that names the Claude app). */
    val pkg: String? = null,
    /** An activity class inside [pkg]: the intent goes straight to it, skipping its filters. */
    val component: String? = null,
    val extras: Map<String, String> = emptyMap(),
    /** The app's own launcher intent instead of everything above. */
    val launcher: Boolean = false,
    /** A full `intent:` URI, as Android writes them, typed by hand. */
    val intentUri: String? = null,
) {
    val label: String
        get() = when {
            launcher -> "Launcher · ${pkg ?: ClaudeApp.PACKAGE}"
            intentUri != null -> intentUri
            else -> buildString {
                append(action.removePrefix("android.intent.action."))
                uri?.let { append(' ').append(it) }
                when {
                    component != null -> append(" → ").append(component.removePrefix(pkg ?: ClaudeApp.PACKAGE))
                    pkg != null -> append(" · Claude app only")
                }
                extras.forEach { (k, v) -> append(" + ").append(k.substringAfterLast('.')).append('=').append(v) }
            }
        }

    companion object {
        const val VIEW = "android.intent.action.VIEW"
    }
}

/** What happened when a probe was tried. */
sealed interface ProbeOutcome {
    data object Started : ProbeOutcome
    data object NoMatch : ProbeOutcome
    data class Failed(val reason: String) : ProbeOutcome
}

/** What Android would open for a probe before it is tried, and the filter that matched. */
data class ProbeResolution(val activity: String?, val exported: Boolean = true, val filter: String? = null)

object LinkProbes {
    private const val APP = ClaudeApp.PACKAGE
    const val DEEP_LINK_ACTIVITY = "$APP.deeplink.DeepLinkActivity"
    const val MAIN_ACTIVITY = "$APP.mainactivity.MainActivity"

    /** Pages that open from the Claude app's own widgets, as far as we know their paths. */
    private val pages = listOf("projects", "project", "artifacts", "code", "new", "recents")

    val all: List<LinkProbe> = buildList {
        add(LinkProbe(launcher = true, pkg = APP))
        pages.forEach { add(LinkProbe(uri = "claude://$it", pkg = APP)) }
        add(LinkProbe(uri = "claude://", pkg = APP))
        add(LinkProbe(uri = "claude://claude.ai/projects", pkg = APP))
        add(LinkProbe(uri = "claude:///projects", pkg = APP))
        pages.forEach { add(LinkProbe(uri = "https://claude.ai/$it", pkg = APP)) }
        add(LinkProbe(uri = "https://claude.ai/projects"))
        add(LinkProbe(uri = "https://claude.ai/artifacts"))
        listOf("projects", "artifacts").forEach { page ->
            add(LinkProbe(uri = "https://claude.ai/$page", pkg = APP, component = DEEP_LINK_ACTIVITY))
            add(LinkProbe(uri = "https://claude.ai/$page", pkg = APP, component = MAIN_ACTIVITY))
            add(LinkProbe(uri = "claude://$page", pkg = APP, component = MAIN_ACTIVITY))
        }
        add(LinkProbe(action = "$APP.intent.action.START_CHAT", pkg = APP))
        add(LinkProbe(pkg = APP, component = DEEP_LINK_ACTIVITY, extras = mapOf("EXTRA_SHORTCUT_TYPE" to "CHAT")))
    }

    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** A probe for text typed into the tester, or null when it isn't a link. */
    fun custom(text: String, claudeOnly: Boolean): LinkProbe? {
        val link = text.trim()
        if (!scheme.containsMatchIn(link)) return null
        if (link.startsWith("intent:")) return LinkProbe(intentUri = link)
        return LinkProbe(uri = link, pkg = if (claudeOnly) APP else null)
    }

    /** A plain-text summary of every probe tried, to paste back into a Claude thread. */
    fun report(
        appVersion: String?,
        activities: List<String>,
        tried: List<Triple<LinkProbe, ProbeResolution?, ProbeOutcome?>>,
    ): String = buildString {
        appendLine("Claude app: ${appVersion ?: "not installed"}")
        if (activities.isNotEmpty()) appendLine("Exported activities: ${activities.joinToString()}")
        tried.forEach { (probe, resolution, outcome) ->
            appendLine(probe.label)
            resolution?.let {
                append("  resolves to: ").append(it.activity ?: "nothing")
                if (it.activity != null && !it.exported) append(" (not exported)")
                appendLine()
                it.filter?.let { f -> append("  filter: ").appendLine(f) }
            }
            outcome?.let {
                append("  result: ")
                appendLine(
                    when (it) {
                        ProbeOutcome.Started -> "started"
                        ProbeOutcome.NoMatch -> "no app handles it"
                        is ProbeOutcome.Failed -> "failed: ${it.reason}"
                    },
                )
            }
        }
    }.trimEnd()
}

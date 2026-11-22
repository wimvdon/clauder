package to.wim.clauder.settings

import java.net.URI

enum class ThemeMode { System, Light, Dark }

/** Everything the app stores. Theme and Worker address are backed up; the token, the FCM token
 * and the pinned shortcuts stay on this phone. */
data class Settings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val workerUrl: String = "",
    val token: String = "",
    val registeredFcmToken: String = "",
) {
    val isConnected: Boolean get() = workerUrl.isNotBlank() && token.isNotBlank()
}

object WorkerUrl {
    /** Accepts "clauder.example.workers.dev" or a full https URL; returns "https://host" or null. */
    fun normalize(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.contains(' ')) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme != "https" || !host.contains('.') || uri.userInfo != null) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "https://$host$port"
    }

    fun host(url: String): String = try {
        URI(url).host.orEmpty()
    } catch (e: java.net.URISyntaxException) {
        ""
    }
}

/** The setup-script line that installs the task hooks in a cloud environment: the Worker serves
 * hooks/install-user-hooks.sh at /install.sh, pointed at itself, so the line never changes. */
object HookSetup {
    fun script(workerHost: String): String =
        "curl -fsS https://$workerHost/install.sh | bash || echo \"WARN: Clauder hooks not installed\""
}

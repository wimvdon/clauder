package to.wim.clauder.claudeai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.wim.clauder.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Calls claude.ai with the sign-in cookie. It says honestly that it is Clauder (User-Agent) and
 * never sends the cookie anywhere but https://claude.ai.
 */
class ClaudeAiClient(private val sessionKey: String) : ClaudeAiCalls {

    override var organization: String? = null

    override suspend fun get(path: String): CallResult = withContext(Dispatchers.IO) {
        request(path, accept = "application/json", readTimeout = 15_000) { connection ->
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            CallResult(connection.responseCode, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        }
    }

    override suspend fun stream(path: String, millis: Long): CallResult = withContext(Dispatchers.IO) {
        request(path, accept = "text/event-stream", readTimeout = millis.toInt()) { connection ->
            val code = connection.responseCode
            if (code !in 200..299) {
                return@request CallResult(code, connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
            }
            val deadline = System.currentTimeMillis() + millis
            val lines = StringBuilder()
            val reader = connection.inputStream.bufferedReader()
            try {
                while (System.currentTimeMillis() < deadline && lines.length < MAX_STREAM_CHARS) {
                    val line = reader.readLine() ?: break
                    lines.append(line).append('\n')
                }
            } catch (e: SocketTimeoutException) {
                // Nothing more came before the time ran out: what was read is the answer.
            }
            CallResult(code, lines.toString())
        }
    }

    private fun request(path: String, accept: String, readTimeout: Int, read: (HttpURLConnection) -> CallResult): CallResult = try {
        val connection = URL(ClaudeAi.BASE + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeout
            connection.instanceFollowRedirects = false
            ClaudeAi.headers(sessionKey, organization, accept).forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setRequestProperty("User-Agent", "Clauder/${BuildConfig.VERSION_NAME} (Android)")
            read(connection)
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        CallResult(error = e.javaClass.simpleName)
    }

    private companion object {
        const val MAX_STREAM_CHARS = 200_000
    }
}

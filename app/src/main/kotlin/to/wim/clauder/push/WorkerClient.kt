package to.wim.clauder.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.wim.clauder.settings.HookStatus
import to.wim.clauder.tasks.TaskList
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the Clauder Worker. Every call needs the Worker's token. */
class WorkerClient(private val baseUrl: String, private val token: String) {

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data object Unauthorized : Result<Nothing>
        data class Failed(val message: String) : Result<Nothing>
    }

    data class TestResult(val sent: Int, val devices: Int, val fcmConfigured: Boolean)

    suspend fun register(fcmToken: String, deviceName: String): Result<Boolean> =
        call("POST", "/devices", JSONObject().put("token", fcmToken).put("name", deviceName).toString()) {
            JSONObject(it).optBoolean("fcmConfigured")
        }

    suspend fun unregister(fcmToken: String): Result<Unit> =
        call("DELETE", "/devices", JSONObject().put("token", fcmToken).toString()) { }

    suspend fun sessions(): Result<List<TaskList>> = call("GET", "/sessions", null) { TaskList.listFromJson(it) }

    /** Marks a session's thread resolved, as the person did in the Claude app. */
    suspend fun resolve(key: String): Result<Boolean> =
        call("POST", "/sessions/resolve", JSONObject().put("key", key).toString()) { JSONObject(it).optBoolean("resolved") }

    suspend fun sendTest(): Result<TestResult> = call("POST", "/test", "{}") {
        val o = JSONObject(it)
        TestResult(o.optInt("sent"), o.optInt("devices"), o.optBoolean("fcmConfigured"))
    }

    /** When the last hook event reached the Worker, and whether its hooks were current (GET /status). */
    suspend fun status(): Result<HookStatus> = call("GET", "/status", null) { HookStatus.fromJson(JSONObject(it)) }

    private suspend fun <T> call(method: String, path: String, body: String?, parse: (String) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = method
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    if (body != null) {
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(body.toByteArray()) }
                    }
                    val code = connection.responseCode
                    when {
                        code == 401 -> Result.Unauthorized
                        code in 200..299 -> Result.Ok(parse(connection.inputStream.bufferedReader().use { it.readText() }))
                        else -> Result.Failed("HTTP $code")
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                Result.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: org.json.JSONException) {
                Result.Failed("Unexpected answer")
            }
        }
}

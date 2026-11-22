package to.wim.clauder.shortcuts

import org.json.JSONArray
import org.json.JSONObject

object PinnedCodec {
    fun encode(list: List<PinnedShortcut>): String = JSONArray(
        list.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("kind", it.kind.name) },
    ).toString()

    fun decode(text: String?): List<PinnedShortcut> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val link = ClaudeLink.parse(o.optString("url")) ?: return@mapNotNull null
                PinnedShortcut(o.optString("id"), o.optString("name"), link.url, link.kind)
            }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }
}

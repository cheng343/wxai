package dev.cheng.wxai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简 OpenAI 兼容客户端（/chat/completions，非流式）。
 * 用 HttpURLConnection + org.json，避免给模块塞 OkHttp/Gson，减小 APK 与在微信进程里的类加载面。
 */
object AiClient {

    data class Result(val text: String?, val error: String?)

    fun complete(cfg: Config, userText: String, extraSystem: String? = null): Result {
        if (cfg.apiKey.isBlank()) return Result(null, "apiKey 为空")
        if (cfg.baseUrl.isBlank()) return Result(null, "baseUrl 为空")

        val endpoint = cfg.baseUrl.trimEnd('/') + "/chat/completions"

        val system = buildString {
            append(cfg.systemPrompt.trim())
            if (!extraSystem.isNullOrBlank()) {
                append("\n\n").append(extraSystem)
            }
        }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system))
            put(JSONObject().put("role", "user").put("content", userText))
        }

        val body = JSONObject().apply {
            put("model", cfg.model)
            put("temperature", cfg.temperature)
            put("stream", false)
            put("messages", messages)
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()

            if (code !in 200..299) {
                L.e("HTTP $code from $endpoint: ${raw.take(300)}")
                return Result(null, "HTTP $code: ${raw.take(200)}")
            }

            val text = parseContent(raw)
            if (text.isNullOrBlank()) {
                L.e("empty completion: ${raw.take(300)}")
                Result(null, "模型返回空内容")
            } else {
                Result(text.trim(), null)
            }
        } catch (t: Throwable) {
            L.e("request failed: $endpoint", t)
            Result(null, t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseContent(raw: String): String? {
        return try {
            val root = JSONObject(raw)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val msg = choices.optJSONObject(0)?.optJSONObject("message")
            msg?.optString("content")?.takeIf { it.isNotBlank() && it != "null" }
        } catch (t: Throwable) {
            L.e("parse completion failed", t)
            null
        }
    }
}

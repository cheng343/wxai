package dev.cheng.wxai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 微信多模态识图与表情包解析引擎（QQ DeepSeek 同款体验）：
 *
 * 1. 自动截获微信图片 (type=3) 与自定义表情包/贴纸 (type=47)；
 * 2. 桥接 WeKit 的 WeMessageApi.downloadImage / cacheAndSaveSticker / saveStickerByMd5 解码落地为标准 JPG/PNG/GIF；
 * 3. 自动将图片/表情包智能下采样压缩并转为 Base64 data URL；
 * 4. 多模态理解：若主模型或配置的 visionModel 支持视觉（如 Qwen2.5-VL / gpt-4o 等），调用大模型提取表情包画面、文字与梗；
 *    若主模型为纯文本模型（如官方 deepseek-chat），自动将识图结果转化为自然描述块并入对话上下文，实现 100% 懂图懂梗！
 */
object ImageBridge {

    @Volatile
    private var wekitClassLoader: ClassLoader? = null

    fun setWeKitClassLoader(cl: ClassLoader) {
        wekitClassLoader = cl
    }

    /**
     * 尝试根据 msgSvrId、md5 或 XML 解析并下载图片/表情包，返回解码后的本地文件路径。
     */
    fun resolveMediaFilePath(
        ctx: Context,
        type: Int,
        msgSvrId: Long,
        rawContent: String,
        imgPath: String?,
    ): String? {
        val wkCl = wekitClassLoader

        // 1. 处理自定义表情包 / 贴纸 (type = 47)
        if (type == 47) {
            val md5 = extractAttr(rawContent, "md5").ifEmpty {
                imgPath?.trim().orEmpty().substringBeforeLast('.').takeIf { it.length == 32 }.orEmpty()
            }
            if (wkCl != null) {
                // 优先调用 WeKit WeMessageApi.cacheAndSaveSticker(msgSvrId)
                try {
                    val apiCls = Class.forName("dev.ujhhgtg.wekit.features.api.core.WeMessageApi", true, wkCl)
                    val inst = apiCls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                    if (msgSvrId > 0L) {
                        val m = apiCls.getMethod("cacheAndSaveSticker", Long::class.javaPrimitiveType)
                        val res = m.invoke(inst, msgSvrId) as? String
                        if (!res.isNullOrBlank() && File(res).exists()) {
                            L.i("sticker resolved via WeMessageApi.cacheAndSaveSticker: $res")
                            return res
                        }
                    }
                    if (md5.isNotEmpty()) {
                        val m = apiCls.getMethod("saveStickerByMd5", String::class.java, String::class.java)
                        val res = m.invoke(inst, md5, null) as? String
                        if (!res.isNullOrBlank() && File(res).exists()) {
                            L.i("sticker resolved via WeMessageApi.saveStickerByMd5: $res")
                            return res
                        }
                    }
                } catch (t: Throwable) {
                    L.w("WeMessageApi sticker resolve failed: ${t.message}")
                }
            }

            // 本地文件查找：微信内部表情包落地路径
            if (md5.isNotEmpty()) {
                val candidateDirs = listOf(
                    File(ctx.filesDir, "emoji"),
                    File(ctx.dataDir, "files/emoji"),
                    File("/data/data/com.tencent.mm/files/emoji"),
                )
                for (dir in candidateDirs) {
                    val f = File(dir, md5)
                    if (f.exists() && f.length() > 0) return f.absolutePath
                    val fGif = File(dir, "$md5.gif")
                    if (fGif.exists() && fGif.length() > 0) return fGif.absolutePath
                }
            }
        }

        // 2. 处理常规图片 (type = 3)
        if (type == 3) {
            if (wkCl != null && msgSvrId > 0L) {
                try {
                    val apiCls = Class.forName("dev.ujhhgtg.wekit.features.api.core.WeMessageApi", true, wkCl)
                    val inst = apiCls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                    val m = apiCls.getMethod("downloadImage", Long::class.javaPrimitiveType)
                    val res = m.invoke(inst, msgSvrId) as? String
                    if (!res.isNullOrBlank() && File(res).exists()) {
                        L.i("image resolved via WeMessageApi.downloadImage: $res")
                        return res
                    }
                } catch (t: Throwable) {
                    L.w("WeMessageApi downloadImage failed: ${t.message}")
                }
            }

            // 微信缩略图/原图文件探测
            if (!imgPath.isNullOrBlank()) {
                val clean = imgPath.trim()
                val f = File(clean)
                if (f.exists() && f.length() > 0) return f.absolutePath
            }
        }

        return null
    }

    /**
     * 将图片文件下采样缩放并转为 Base64 data:image/jpeg;base64,...
     */
    fun fileToBase64DataUrl(filePath: String, maxDim: Int = 1024): String? {
        return try {
            val file = File(filePath)
            if (!file.exists() || file.length() <= 0) return null

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) return null

            var inSample = 1
            var maxSide = Math.max(w, h)
            while (maxSide > maxDim * 1.5) {
                inSample *= 2
                maxSide /= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeFile(filePath, decodeOpts) ?: return null

            // 缩放到 maxDim 以内
            val scaledBmp = if (bmp.width > maxDim || bmp.height > maxDim) {
                val scale = maxDim.toFloat() / Math.max(bmp.width, bmp.height).toFloat()
                val targetW = (bmp.width * scale).toInt().coerceAtLeast(1)
                val targetH = (bmp.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, targetW, targetH, true).also {
                    if (it != bmp) bmp.recycle()
                }
            } else {
                bmp
            }

            val baos = ByteArrayOutputStream()
            scaledBmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            scaledBmp.recycle()
            val bytes = baos.toByteArray()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64"
        } catch (t: Throwable) {
            L.e("fileToBase64DataUrl failed for $filePath", t)
            null
        }
    }

    /**
     * 调用大模型视觉接口理解图片/表情包：
     * 1. 优先使用专门配置的 visionModel（若有），或直接使用兼容 vision 的主模型；
     * 2. 返回 50 字以内的精炼画面、文字与梗描述。
     */
    fun describeImageOrEmoji(
        cfg: Config,
        dataUrl: String,
        type: Int,
    ): String? {
        val endpoint = (if (cfg.visionBaseUrl.isNotBlank()) cfg.visionBaseUrl else cfg.baseUrl).trimEnd('/') + "/chat/completions"
        val apiKey = if (cfg.visionApiKey.isNotBlank()) cfg.visionApiKey else cfg.apiKey
        val model = when {
            cfg.visionModel.isNotBlank() -> cfg.visionModel.trim()
            cfg.model.contains("vl", ignoreCase = true) ||
                cfg.model.contains("vision", ignoreCase = true) ||
                cfg.model.contains("4o", ignoreCase = true) -> cfg.model.trim()
            else -> "Qwen/Qwen2.5-VL-72B-Instruct" // 业内最强开源视觉模型默认备选
        }

        if (apiKey.isBlank()) return null

        val promptText = if (type == 47) {
            "这是微信聊天中的一个表情包。请用简炼、风趣的一句话说明这个表情包的画面内容、人物神态、配字以及想要表达的梗或情绪（35字以内，不要寒暄）："
        } else {
            "这是微信聊天中对方发送的一张图片。请简明扼要概括图中的核心画面、主体对象或关键文字（40字以内）："
        }

        return try {
            val messages = JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray().apply {
                                put(JSONObject().put("type", "text").put("text", promptText))
                                put(
                                    JSONObject().put("type", "image_url").put(
                                        "image_url",
                                        JSONObject().put("url", dataUrl)
                                    )
                                )
                            }
                        )
                    }
                )
            }

            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.3)
                put("max_tokens", 100)
            }

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 40_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } }.orEmpty()
            conn.disconnect()

            if (code in 200..299) {
                val choices = JSONObject(raw).optJSONArray("choices")
                val desc = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")?.trim()
                if (!desc.isNullOrBlank()) {
                    L.i("vision described ($model): $desc")
                    return desc
                }
            } else {
                L.w("vision call failed HTTP $code: ${raw.take(160)}")
            }
            null
        } catch (t: Throwable) {
            L.w("describeImageOrEmoji skipped: ${t.message}")
            null
        }
    }

    private fun extractAttr(xml: String, attr: String): String {
        val pattern = "$attr=\""
        val i = xml.indexOf(pattern)
        if (i < 0) return ""
        val j = xml.indexOf("\"", i + pattern.length)
        if (j < 0) return ""
        return xml.substring(i + pattern.length, j).trim()
    }
}

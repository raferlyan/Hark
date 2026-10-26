package com.raferlyan.hark

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 科大讯飞「语音听写（流式版）」WebAPI，一句话一个会话：
 * [start] 后台建连，[feed] 边录边送，[end] 发结束帧，结果在 [text] / [finished] / [error] 里轮询。
 * 所有网络操作都在自己的线程里，引擎线程只读这几个字段，不会被网络卡住。
 *
 * 文档：https://www.xfyun.cn/doc/asr/voicedictation/API.html
 * WebSocket 只用到了「发文本帧、收文本帧」，自己实现一个最小版本，免得为它引 OkHttp。
 */
class XfIat(private val cfg: Config) {

    class Config(
        val appId: String,
        val apiKey: String,
        val apiSecret: String,
        /** mandarin、cantonese、lmz（四川话）等，方言要先在讯飞控制台开通。 */
        val accent: String,
        /** 方言免切：普通话和 23 种方言混说都能识别（domain=xfime-mianqie），要先在控制台开通。 */
        val dialectFree: Boolean,
        val punctuation: Boolean,
        /** 动态修正（dwa=wpgs）：中间结果会回头改字，实时识别显示更准。 */
        val dynamicCorrection: Boolean,
        /** 讯飞后端断句静音（毫秒）。本地模型断句后会主动发结束帧，这个只是兜底。 */
        val eosMs: Int,
    ) {
        companion object {
            fun from(s: AppSettings): Config? {
                val id = s.xfAppId.trim().ifEmpty { BuildConfig.XF_DEBUG_APP_ID }
                val key = s.xfApiKey.trim().ifEmpty { BuildConfig.XF_DEBUG_API_KEY }
                val secret = s.xfApiSecret.trim().ifEmpty { BuildConfig.XF_DEBUG_API_SECRET }
                if (id.isEmpty() || key.isEmpty() || secret.isEmpty()) return null
                return Config(
                    id, key, secret,
                    accent = s.xfAccent.trim().ifEmpty { "mandarin" },
                    dialectFree = s.xfDialectFree,
                    punctuation = s.xfPunctuation,
                    dynamicCorrection = s.xfDynamicCorrection,
                    eosMs = ((s.endSilenceSec + 1f) * 1000).toInt().coerceIn(1000, 10000),
                )
            }
        }
    }

    /** 目前拼出来的整句结果（动态修正时会回头改）。 */
    @Volatile var text = ""; private set
    /** 讯飞已经返回最后一块结果。 */
    @Volatile var finished = false; private set
    /** 建连失败、鉴权失败、讯飞报错码等，非 null 就表示这次会话废了，调用方改用本地结果。 */
    @Volatile var error: String? = null; private set
    /** 从 [end] 到拿到最终结果用了多久。 */
    @Volatile var finalLatencyMs = 0L; private set

    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    private var socket: Socket? = null
    private var endAt = 0L
    /** 动态修正时按 sn 存每一块结果，rpl 会替换掉一段之前的 sn。 */
    private val pieces = sortedMapOf<Int, String>()

    fun start() {
        Thread(::run, "xf-iat").apply { isDaemon = true; start() }
    }

    fun feed(chunk: FloatArray) {
        if (closed) return
        val b = ByteArray(chunk.size * 2)
        for (i in chunk.indices) {
            val v = (chunk[i].coerceIn(-1f, 1f) * 32767).toInt()
            b[2 * i] = v.toByte()
            b[2 * i + 1] = (v shr 8).toByte()
        }
        queue.add(b)
    }

    /** 音频送完了，让讯飞出最终结果。 */
    fun end() {
        if (endAt == 0L) endAt = System.currentTimeMillis()
        queue.add(END)
    }

    fun cancel() {
        closed = true
        queue.add(END)
        runCatching { socket?.close() }
    }

    // ---------------------------------------------------------------- 发送线程

    private fun run() {
        try {
            val ws = connect()
            Thread({ readLoop(ws) }, "xf-iat-read").apply { isDaemon = true; start() }
            var status = 0
            while (!closed) {
                val first = queue.poll(SEND_IDLE_SEC, TimeUnit.SECONDS) ?: continue
                // 建连期间攒下的音频合并发送；base64 后单帧不能超过 13000 字节
                val buf = ByteArrayOutputStream()
                var last = first === END
                if (!last) buf.write(first)
                while (!last && buf.size() < MAX_FRAME_BYTES) {
                    val next = queue.poll() ?: break
                    if (next === END) last = true else buf.write(next)
                }
                if (buf.size() > 0 || status == 0) {
                    ws.sendText(frame(status, buf.toByteArray()))
                    status = 1
                }
                if (last) {
                    // 第一帧必须是 status=0，所以结束标识总是单独发一帧
                    ws.sendText(frame(2, ByteArray(0)))
                    break
                }
            }
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun frame(status: Int, pcm: ByteArray): String {
        val data = JSONObject()
            .put("status", status)
            .put("format", "audio/L16;rate=${AssistantEngine.SAMPLE_RATE}")
            .put("encoding", "raw")
            .put("audio", Base64.encodeToString(pcm, Base64.NO_WRAP))
        val msg = JSONObject().put("data", data)
        if (status == 0) {
            msg.put("common", JSONObject().put("app_id", cfg.appId))
            msg.put("business", business())
        }
        return msg.toString()
    }

    private fun business() = JSONObject().apply {
        put("language", "zh_cn")
        put("domain", if (cfg.dialectFree) "xfime-mianqie" else "iat")
        put("accent", cfg.accent)
        put("eos", cfg.eosMs)
        put("ptt", if (cfg.punctuation) 1 else 0)
        if (cfg.dynamicCorrection) put("dwa", "wpgs")
    }

    // ---------------------------------------------------------------- 接收线程

    private fun readLoop(ws: WebSocket) {
        try {
            while (!closed) {
                val msg = ws.readText() ?: break
                val o = JSONObject(msg)
                val code = o.optInt("code")
                if (code != 0) {
                    fail("讯飞错误 $code：${o.optString("message")}")
                    return
                }
                val data = o.optJSONObject("data") ?: continue
                data.optJSONObject("result")?.let(::merge)
                if (data.optInt("status") == 2) {
                    if (endAt > 0) finalLatencyMs = System.currentTimeMillis() - endAt
                    finished = true
                    break
                }
            }
        } catch (e: Exception) {
            if (!closed) fail("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            closed = true
            ws.close()
        }
    }

    private fun merge(r: JSONObject) {
        val sb = StringBuilder()
        val ws = r.optJSONArray("ws")
        if (ws != null) for (i in 0 until ws.length()) {
            ws.getJSONObject(i).optJSONArray("cw")?.optJSONObject(0)?.optString("w")?.let(sb::append)
        }
        val sn = r.optInt("sn")
        if (r.optString("pgs") == "rpl") {
            val rg = r.optJSONArray("rg")
            if (rg != null && rg.length() == 2) for (k in rg.getInt(0)..rg.getInt(1)) pieces.remove(k)
        }
        pieces[sn] = sb.toString()
        text = pieces.values.joinToString("")
    }

    private fun fail(why: String) {
        if (error == null && !finished) error = why
        closed = true
        runCatching { socket?.close() }
    }

    // ---------------------------------------------------------------- 鉴权 + WebSocket

    private fun connect(): WebSocket {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date())
        val origin = "host: $HOST\ndate: $date\nGET $PATH HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(cfg.apiSecret.toByteArray(), "HmacSHA256")) }
        val signature = Base64.encodeToString(mac.doFinal(origin.toByteArray()), Base64.NO_WRAP)
        val auth = "api_key=\"${cfg.apiKey}\", algorithm=\"hmac-sha256\", " +
            "headers=\"host date request-line\", signature=\"$signature\""
        val query = "authorization=" + enc(Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP)) +
            "&date=" + enc(date) + "&host=" + enc(HOST)

        val plain = Socket().apply {
            connect(InetSocketAddress(HOST, 443), CONNECT_TIMEOUT_MS)
            soTimeout = READ_TIMEOUT_MS
        }
        socket = plain
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(plain, HOST, 443, true) as SSLSocket
        socket = ssl
        ssl.startHandshake()
        // 裸 SSLSocket 不校验域名，要自己查一遍证书是不是这个域名的
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, ssl.session)) {
            throw IOException("证书和域名不匹配")
        }
        if (closed) throw IOException("已取消")
        return WebSocket.handshake(ssl, HOST, "$PATH?$query")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 最小 WebSocket 客户端：只有文本帧、ping/pong 和 close，够讯飞这一个接口用。 */
    private class WebSocket(private val socket: Socket, private val input: DataInputStream) {
        private val out: OutputStream = socket.getOutputStream()
        private val rnd = SecureRandom()

        @Synchronized
        fun send(opcode: Int, payload: ByteArray) {
            val head = ByteArrayOutputStream()
            head.write(0x80 or opcode)
            val n = payload.size
            when {
                n < 126 -> head.write(0x80 or n)
                n < 65536 -> { head.write(0x80 or 126); head.write(n shr 8); head.write(n and 0xFF) }
                else -> { head.write(0x80 or 127); for (i in 7 downTo 0) head.write(((n.toLong() shr (8 * i)) and 0xFF).toInt()) }
            }
            val mask = ByteArray(4).also(rnd::nextBytes)
            head.write(mask)
            val body = ByteArray(n) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            out.write(head.toByteArray())
            out.write(body)
            out.flush()
        }

        fun sendText(s: String) = send(0x1, s.toByteArray())

        /** 读一条完整的文本消息（拼好分片），连接关闭返回 null。 */
        fun readText(): String? {
            val msg = ByteArrayOutputStream()
            while (true) {
                val b0 = input.readUnsignedByte()
                val b1 = input.readUnsignedByte()
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = input.readUnsignedShort().toLong()
                else if (len == 127L) len = input.readLong()
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also(input::readFully) else null
                val payload = ByteArray(len.toInt()).also(input::readFully)
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    0x8 -> return null
                    0x9 -> send(0xA, payload)
                    0xA -> {}
                    else -> {
                        msg.write(payload)
                        if (fin) return msg.toString("UTF-8")
                    }
                }
            }
        }

        fun close() {
            runCatching { send(0x8, byteArrayOf(0x03, 0xE8.toByte())) } // 1000 正常关闭
            runCatching { socket.close() }
        }

        companion object {
            fun handshake(socket: Socket, host: String, pathAndQuery: String): WebSocket {
                val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
                val req = "GET $pathAndQuery HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\n" +
                    "Connection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
                socket.getOutputStream().apply { write(req.toByteArray()); flush() }
                val input = DataInputStream(socket.getInputStream().buffered())
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val c = input.read()
                    if (c < 0) throw IOException("握手时连接被关闭")
                    head.append(c.toChar())
                }
                val status = head.lineSequence().first()
                if (" 101 " !in "$status ") {
                    // 鉴权失败时讯飞会在 body 里给出原因，比如时间偏差、签名不对、IP 白名单
                    val body = runCatching {
                        val len = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        ByteArray(len.coerceAtMost(2048)).also(input::readFully).toString(Charsets.UTF_8)
                    }.getOrDefault("")
                    throw IOException("握手失败 ${status.trim()} $body".trim())
                }
                return WebSocket(socket, input)
            }
        }
    }

    companion object {
        private const val HOST = "iat-api.xfyun.cn"
        private const val PATH = "/v2/iat"
        private val END = ByteArray(0)
        private const val MAX_FRAME_BYTES = 8000
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS = 10000
        private const val SEND_IDLE_SEC = 1L
    }
}

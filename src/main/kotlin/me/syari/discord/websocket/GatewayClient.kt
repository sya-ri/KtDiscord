package me.syari.discord.websocket

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.syari.discord.ConnectStatus
import me.syari.discord.KtDiscord
import me.syari.discord.KtDiscord.API_VERSION
import me.syari.discord.KtDiscord.LOGGER
import me.syari.discord.KtDiscord.gatewayIntents
import me.syari.discord.KtDiscord.maxShards
import me.syari.discord.KtDiscord.shard
import me.syari.discord.KtDiscord.token
import me.syari.discord.handle.EventHandler.handleEvent
import me.syari.discord.util.ByteArrayUtil.concat
import me.syari.discord.util.ByteArrayUtil.takeLastAsByteArray
import me.syari.discord.util.Timer.timer
import me.syari.discord.util.json.JsonUtil.getObjectOrNull
import me.syari.discord.util.json.JsonUtil.getOrNull
import me.syari.discord.util.json.JsonUtil.json
import me.syari.discord.util.json.JsonUtil.jsonArray
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream

internal object GatewayClient {
    private val GSON = Gson()

    private lateinit var websocket: WebSocket

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var ready: Boolean = false

    @Volatile
    private var heartbeatTask: Job? = null

    @Volatile
    private var heartbeatAckReceived: Boolean = false

    @Volatile
    private var lastSequence: Int? = null

    private val scope = CoroutineScope(Dispatchers.Default + CoroutineName("GatewayClient"))

    private lateinit var gatewayURL: String

    suspend fun connect(gatewayURL: String) {
        this.gatewayURL = gatewayURL
        connect()
    }

    private val mutex = Mutex()

    private suspend fun connect() {
        mutex.withLock {
            val client = OkHttpClient.Builder().build()
            val request =
                Request.Builder().url("$gatewayURL/?v=$API_VERSION&encoding=json&compress=zlib-stream").build()
            websocket = client.newWebSocket(request, Listener)

            while (!ready) {
                delay(100)
            }
        }
    }

    private fun authenticate() {
        send(Opcode.IDENTIFY, json {
            "token" to token

            if (gatewayIntents.isNotEmpty()) {
                var value = 0
                gatewayIntents.forEach {
                    value = value or it.flag
                }
                LOGGER.trace("Intent flag: $value")
                "intents" to value
            }

            "properties" to json {
                "\$os" to "who knows"
                "\$browser" to "who knows"
                "\$device" to "who knows"
            }

            "shard" to jsonArray {
                +JsonPrimitive(shard)
                +JsonPrimitive(maxShards)
            }

            //            activity?.let {
            //                "presence" to it
            //            }
        })
    }

    private fun resume() {
        val sessionId = sessionId ?: throw IllegalArgumentException("sessionId is null")
        send(Opcode.RESUME, json {
            "session_id" to sessionId
            "token" to token
            "seq" to lastSequence
        })
    }

    private fun handleMessage(websocket: WebSocket, text: String) {
        val payloads = GSON.fromJson(text, JsonObject::class.java)
        val opcode = Opcode.getByCode(payloads["op"].asInt)
        val data = payloads.getObjectOrNull("d")
        LOGGER.trace("Receive: $payloads")

        when (opcode) {
            Opcode.HELLO -> {
                LOGGER.debug("Starting heartbeat task")

                val period = data!!["heartbeat_interval"].asLong
                heartbeatAckReceived = true

                heartbeatTask?.cancel()
                heartbeatTask = scope.timer(period) {
                    if (heartbeatAckReceived) {
                        heartbeatAckReceived = false
                        send(Opcode.HEARTBEAT, json { lastSequence?.let { "d" to it } })
                    } else {
                        websocket.close(4000, "Heartbeat ACK wasn't received")
                    }
                }
            }
            Opcode.RECONNECT -> {
                LOGGER.info("Received RECONNECT opcode. Attempting to reconnect.")
                websocket.close(4001, "Received Reconnect Request")
            }
            Opcode.INVALID_SESSION -> {
                LOGGER.info("Received INVALID_SESSION opcode. Attempting to reconnect.")
                websocket.close(4990, "Invalid Session")
            }
            Opcode.HEARTBEAT_ACK -> {
                LOGGER.debug("Received heartbeat ACK")
                heartbeatAckReceived = true
            }
            Opcode.DISPATCH -> {
                val eventType = payloads["t"].asString
                lastSequence = payloads["s"].asString.toInt()

                if (eventType == "READY" || eventType == "RESUMED") {
                    ready = true

                    data?.getOrNull("session_id")?.let {
                        sessionId = it.asString
                    }

                    LOGGER.info("Ready")
                }

                handleEvent(eventType, data!!)
            }
            else -> {
                LOGGER.warn("Received a packet with unknown opcode: $data")
            }
        }
    }

    private fun send(opCode: Opcode, data: JsonObject) {
        val json = json {
            "op" to opCode.code
            "d" to data
        }.toString()

        LOGGER.trace("Sent: $json")
        websocket.send(json)
    }

    object Listener: WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            LOGGER.info("Connected to the gateway")
            KtDiscord.status = ConnectStatus.CONNECTED

            if (sessionId == null) {
                LOGGER.info("Authenticating...")
                authenticate()
            } else {
                LOGGER.info("Resuming the session...")
                resume()
            }
        }

        private val buffer = mutableListOf<ByteArray>()
        private val inflater = Inflater()

        @KtorExperimentalAPI
        private val ZLIB_SUFFIX = hex("0000ffff")

        @KtorExperimentalAPI
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val byteArray = bytes.toByteArray()

            // Add the received data to buffer
            buffer.add(byteArray)

            // Check for zlib suffix
            if (byteArray.size < 4 || !byteArray.takeLastAsByteArray(4).contentEquals(ZLIB_SUFFIX)) {
                return
            }

            // Decompress the buffered data
            val text = ByteArrayOutputStream().use { output ->
                try {
                    InflaterOutputStream(output, inflater).use {
                        it.write(buffer.concat())
                    }
                    output.toString("UTF-8")
                } catch (e: IOException) {
                    LOGGER.error("Error while decompressing payload", e)
                    return
                } finally {
                    buffer.clear()
                }
            }

            // Handle the message
            handleMessage(websocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            LOGGER.info("WebSocket closed with code: $code, reason: '$reason'")

            ready = false
            heartbeatTask?.cancel()

            if (code == 4014) {
                LOGGER.error("Invalid privilege intent(s) are specified. you must first go to your application in the Developer Portal and enable the toggle for the Privileged Intents you wish to use.")
                return
            }

            // Invalidate cache
            if (code == 4007 || code == 4990 || code == 4003) {
                sessionId = null
                lastSequence = null

                // postponedServerEvents.clear()
                // client.users.clear()
                // client.privateChannels.clear()
            }

            scope.launch {
                connect()
            }
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            LOGGER.error("WebSocket Failure ResponseCode: ${response?.code}", throwable)
        }
    }
}
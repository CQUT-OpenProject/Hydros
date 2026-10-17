package dev.uednd.hydros.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

interface OrderPush {
    suspend fun subscribe(auth: Credentials, orderNo: String, onSignal: () -> Unit)
    fun close()
}

/** Polling-only adapter for offline graphs. */
object NoOrderPush : OrderPush {
    override suspend fun subscribe(auth: Credentials, orderNo: String, onSignal: () -> Unit) = Unit
    override fun close() = Unit
}
/** Push is a hint only; HTTP reconciliation is always the authority. */
class MqttOrderPush(private val server: String = "tcp://47.107.37.60:1883") : OrderPush {
    private var client: MqttAsyncClient? = null
    override suspend fun subscribe(auth: Credentials, orderNo: String, onSignal: () -> Unit) = withContext(Dispatchers.IO) {
        close()
        val value = MqttAsyncClient(server, "hydros-${UUID.randomUUID()}", MemoryPersistence())
        client = value
        val topics = arrayOf("app_downRate_${auth.phone}", "app_shutdownOrder_${auth.phone}", "app_uploadData_${auth.phone}")
        val qos = intArrayOf(0, 0, 0)
        value.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (reconnect && client === value) runCatching { value.subscribe(topics, qos) }
            }
            override fun connectionLost(cause: Throwable?) = Unit
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if(client !== value || message == null || message.isRetained) return
                if(matchesOrderPush(orderNo, message.payload, message.isRetained)) onSignal()
            }
        })
        try {
            value.connect(MqttConnectOptions().apply { isCleanSession=true; isAutomaticReconnect=true; connectionTimeout=5; keepAliveInterval=30 }).waitForCompletion(6000)
            value.subscribe(topics, qos).waitForCompletion(6000)
        } catch(error: MqttException) { close(); throw error } // The monitor retries; HTTP polling remains authoritative.
    }
    override fun close() {
        val old = client; client = null
        runCatching { old?.disconnectForcibly(0,0,false) }
        runCatching { old?.close(true) }
    }
}

fun matchesOrderPush(orderNo: String, payload: ByteArray, retained: Boolean): Boolean {
    if(retained || orderNo.isBlank() || payload.size > 16_384) return false
    val obj=runCatching { Json.parseToJsonElement(String(payload, Charsets.UTF_8)) as? JsonObject }.getOrNull()
    return obj?.text("orderNo")==orderNo
}

import io.ktor.client.HttpClient
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.ws
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.HttpMethod
import io.ktor.util.KtorExperimentalAPI
import io.rebble.libpebblecommon.PingPong
import io.rebble.libpebblecommon.blobdb.BlobResponse
import io.rebble.libpebblecommon.blobdb.NotificationSource
import io.rebble.libpebblecommon.blobdb.PushNotification
import io.rebble.libpebblecommon.exceptions.PacketDecodeException
import io.rebble.libpebblecommon.protocolhelpers.PacketRegistry
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.services.notification.NotificationService
import io.rebble.libpebblecommon.util.LazyLock
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.system.exitProcess
import kotlin.test.assertTrue

@ExperimentalUnsignedTypes
class DeviceTests {
    private fun bytesToHex(bytes: UByteArray): String {
        val hexArray = "0123456789ABCDEF".toCharArray()
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = (bytes[j] and 0xFFu).toInt()

            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }
    val phoneHost = "change-me"
    val phonePort = 9000

    @KtorExperimentalAPI
    val client = HttpClient {
        install(WebSockets)
    }

    init {
        if (phoneHost == "change-me") {
            println("NOTE: Need to set phone dev connection host, note that while this is a test it's intended for manually testing packets on a real device")
            exitProcess(0)
        }
        PacketRegistry.setup()
    }

    @KtorExperimentalAPI
    private fun sendWS(packet: PebblePacket, blockResponse: Boolean): PebblePacket? {
        var ret: PebblePacket? = null
        runBlocking {
            client.ws(
                    method = HttpMethod.Get,
                    host = phoneHost,
                    port = phonePort, path = "/"
            ) {
                send(Frame.Binary(true, byteArrayOf(0x01) + packet.serialize().toByteArray()))
                flush()
                while(blockResponse) {
                    val frame = incoming.receive()
                    if (frame is Frame.Binary) {
                        try{
                            ret = PebblePacket.deserialize(frame.data.slice(1 until frame.data.size).toByteArray().toUByteArray())
                            break
                        }catch (e: PacketDecodeException) {
                            println(e.toString())
                        }
                    }
                }
            }
        }
        return ret
    }

    @KtorExperimentalAPI
    @ExperimentalUnsignedTypes
    @ExperimentalStdlibApi
    @Test
    fun sendNotification() {
        val notif = PushNotification(
                sender = "Test Notif",
                subject = "This is a test notification!",
                message = "This is the notification body",
                backgroundColor = 0b11110011u,
                source = NotificationSource.Email
        )
        val lock = LazyLock()
        lock.lock()
        BlobDBService.init { snotif -> sendWS(snotif, false) }
        NotificationService.send(notif) {
            assertTrue(it is BlobResponse.Success, "Reply wasn't success from BlobDB when sending notif")
            lock.unlock()
        }
        lock.syncLock(5000)
    }

    @KtorExperimentalAPI
    @ExperimentalUnsignedTypes
    @Test
    fun sendPing() {
        val res = sendWS(PingPong.Ping(1337u), true)
        val gotPong = res?.endpoint == PingPong.endpoint && (res as? PingPong)?.cookie?.get() == 1337u
        assertTrue(gotPong, "Pong not received within sane amount of time")
    }
}
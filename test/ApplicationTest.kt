import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.withTestApplication
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class ApplicationTest : StringSpec({

    "/" {
        withTestApplication({ module() }) {
            handleRequest(HttpMethod.Get, "/").apply {
                response.status() shouldBe HttpStatusCode.OK
                response.content shouldBe "root"
            }
        }
    }

    "ws/echo" {
        withTestApplication({ module() }) {
            handleWebSocketConversation("/ws/echo") { incoming, outgoing ->
                val textMessages = listOf("111", "222")
                (incoming.receive() as Frame.Text).readText() shouldBe "Hi from server"
                for (msg in textMessages) {
                    outgoing.send(Frame.Text(msg))
                    (incoming.receive() as Frame.Text).readText() shouldBe "You said: $msg"
                }
            }
        }
    }

    fun createMassage(clientNumber: Int, text: String) = "client №$clientNumber: message №$text"

    "ws/chat with many client" {
        withTestApplication({ module() }) {
            val clientCount = 5
            val oneClientSendMessageCount = 10
            val oneClientReceiveMessageCount = (clientCount - 1) * oneClientSendMessageCount
            val latch = CoroutinesLatch(clientCount)
            val messages = ConcurrentHashMap<Int, MutableList<String>>()
            runBlocking(Dispatchers.IO) {
                repeat(clientCount) { clientNumber ->
                    messages[clientNumber] = mutableListOf()
                    launch {
                        handleWebSocketConversation("/ws/chat") { incoming, outgoing ->
                            latch.joinAndWaitOther()
                            var receiveMessageCount = 0
                            launch {
                                launch {
                                    for (frame in incoming) {
                                        if (frame is Frame.Text) {
                                            val message = frame.readText()
                                            if ("message" in message) {
                                                messages[clientNumber]!!.add(message)
                                                receiveMessageCount++
                                                if (receiveMessageCount >= oneClientReceiveMessageCount) {
                                                    break
                                                }
                                            }
                                        }
                                    }
                                }
                                launch {
                                    repeat(oneClientSendMessageCount) {
                                        outgoing.send(
                                            Frame.Text(
                                                createMassage(
                                                    clientNumber = clientNumber,
                                                    text = it.toString()
                                                )
                                            )
                                        )
                                    }
                                }
                            }.join()
                        }
                    }
                }
            }

            val allExpectedMessages = (0 until clientCount).map { clientNumber ->
                (0 until oneClientSendMessageCount).map { messageNumber ->
                    createMassage(
                        clientNumber = clientNumber,
                        text = messageNumber.toString()
                    )
                }
            }.flatten()

            messages.forEach { (clientNumber, clientMessages) ->
                clientMessages shouldHaveSize oneClientReceiveMessageCount
                val clientExpectedMessages = allExpectedMessages.filter { "client №$clientNumber:" !in it }
                clientMessages shouldContainExactlyInAnyOrder clientExpectedMessages
            }
        }
    }

    "!:ws/chat client left chat" {
        withTestApplication({ module() }) {
            val clientCount = 5
            runBlocking(Dispatchers.IO) {
                repeat(clientCount) { clientNumber ->
                    launch {
                        handleWebSocketConversation("/ws/chat") { incoming, outgoing ->
                            val job = launch {
                                launch {
                                    for (frame in incoming) {
                                        if (frame is Frame.Text) {
                                            val text = frame.readText()
                                            println(text)
                                        }
                                    }
                                }
                                launch {
                                    while (true) {
                                        outgoing.send(
                                            Frame.Text(
                                                createMassage(
                                                    clientNumber = clientNumber,
                                                    text = UUID.randomUUID().toString()
                                                )
                                            )
                                        )
                                        delay(Random.nextLong(100L, 200L))
                                    }
                                }
                            }
                            delay(clientNumber * 500L)
                            job.cancelAndJoin()
                        }
                    }
                }
            }
        }
    }

})
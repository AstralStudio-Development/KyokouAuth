package moe.ryuu.kyokouauth.core.avatar

import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

class AvatarHttpResponse(val status: Int, val body: ByteArray)

/** Body allocation is limited in the subscriber, before decoding, not after ofByteArray completes. */
object AvatarHttp {
    private val direct = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
    }
    private val client: HttpClient by lazy {
        HttpClient.newBuilder().proxy(direct).connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build()
    }

    fun get(uri: URI, maximum: Int, timeoutMillis: Long): AvatarHttpResponse {
        require(maximum in 1..AvatarLimits.SKIN_BYTES && timeoutMillis in 1..6_000)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
        return try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMillis))
                .header("User-Agent", "KyokouAuth-RoleAvatar/1").GET().build()
            pending = client.sendAsync(request) { info ->
                val declared = info.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull() ?: -1L
                BoundedSubscriber(maximum, declared)
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw AvatarUnavailableException()
            val response = pending.get(remaining, TimeUnit.NANOSECONDS)
            if (deadline - System.nanoTime() <= 0) throw AvatarUnavailableException()
            AvatarHttpResponse(response.statusCode(), response.body())
        } catch (_: InterruptedException) {
            pending?.cancel(true)
            Thread.currentThread().interrupt()
            throw AvatarUnavailableException()
        } catch (_: Exception) {
            pending?.cancel(true)
            throw AvatarUnavailableException()
        }
    }

    internal class BoundedSubscriber(private val maximum: Int, declared: Long = -1) : HttpResponse.BodySubscriber<ByteArray> {
        private val future = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream(minOf(maximum, 4096))
        private var subscription: Flow.Subscription? = null
        init { if (declared > maximum) future.completeExceptionally(AvatarUnavailableException()) }
        override fun getBody(): CompletionStage<ByteArray> = future
        override fun onSubscribe(incoming: Flow.Subscription) {
            if (subscription != null || future.isDone) incoming.cancel() else {
                subscription = incoming
                incoming.request(1)
            }
        }
        override fun onNext(items: List<ByteBuffer>) {
            if (future.isDone) return
            val incoming = items.sumOf { it.remaining().toLong() }
            if (incoming > maximum - bytes.size()) {
                subscription?.cancel()
                future.completeExceptionally(AvatarUnavailableException())
                return
            }
            val copy = ByteArray(minOf(8192, incoming.toInt()))
            for (item in items) while (item.hasRemaining()) {
                val amount = minOf(copy.size, item.remaining())
                item.get(copy, 0, amount)
                bytes.write(copy, 0, amount)
            }
            subscription?.request(1)
        }
        override fun onError(throwable: Throwable) { future.completeExceptionally(AvatarUnavailableException()) }
        override fun onComplete() { future.complete(bytes.toByteArray()) }
    }
}

class AvatarSkinDownloader {
    fun download(location: URI): ByteArray {
        val response = AvatarHttp.get(AvatarJson.canonicalSkinLocation(location), AvatarLimits.SKIN_BYTES, 2_500)
        if (response.status != 200 || response.body.isEmpty()) throw AvatarUnavailableException()
        return response.body
    }
}

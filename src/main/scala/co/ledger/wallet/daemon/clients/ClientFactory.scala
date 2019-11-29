package co.ledger.wallet.daemon.clients

import java.util.concurrent.{Executors, LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}

import cats.effect.{ContextShift, IO}
import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext.Implicits.global
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.libledger_core.async.ScalaThreadDispatcher
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.proxy.ProxyServer
import org.http4s.client.asynchttpclient.AsyncHttpClient

import scala.concurrent.ExecutionContext

object ClientFactory {
  private[this] val apiC: ApiClient = new ApiClient()(
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool((r: Runnable) => new Thread(r, "api-client")))
  )

  lazy val webSocketClient = new ScalaWebSocketClient
  lazy val httpClient = new ScalaHttpClient()(
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool((r: Runnable) => new Thread(r, "libcore-http-client")))
  )
  lazy val http4sClient: Http4sLibCoreHttpClient = {
    // add ContextShift in context to have ConcurrentEffect[IO]
    implicit val cs: ContextShift[IO] = IO.contextShift(global)
    val client = DaemonConfiguration.proxy match {
      case Some(proxy) =>
        val proxyServer = new ProxyServer.Builder(proxy.host, proxy.port).build()
        val c = new DefaultAsyncHttpClientConfig.Builder().setProxyServer(proxyServer).setRequestTimeout(90000).build()
        AsyncHttpClient.resource[IO](c)
      case None =>
        AsyncHttpClient.resource[IO]()
    }

    // Use allocated instead of use to manually manage the finalisation of resource
    // We don't use the close handle returned since the code is side-effectful.
    client.allocated.map {
      case (c, _) => new Http4sLibCoreHttpClient(c)
    }.unsafeRunSync()
  }
  lazy val threadDispatcher = new ScalaThreadDispatcher(
    ExecutionContext.fromExecutor(new ThreadPoolExecutor(Runtime.getRuntime.availableProcessors() * 4, Runtime.getRuntime.availableProcessors() * 8, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable], (r: Runnable) => new Thread(r, "libcore-thread-dispatcher")))
  )
  lazy val apiClient = apiC
}

package co.ledger.wallet.daemon.clients

import scala.concurrent.ExecutionContext

import java.util.concurrent.{Executors, LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}

import co.ledger.wallet.daemon.libledger_core.async.ScalaThreadDispatcher

object ClientFactory {
  private[this] val apiC: ApiClient = new ApiClient()(
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool((r: Runnable) => new Thread(r, "api-client")))
  )

  lazy val webSocketClient = new ScalaWebSocketClient
  lazy val httpClient = new ScalaHttpClient()(
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool((r: Runnable) => new Thread(r, "libcore-http-client")))
  )
  lazy val threadDispatcher = new ScalaThreadDispatcher(
    ExecutionContext.fromExecutor(new ThreadPoolExecutor(Runtime.getRuntime.availableProcessors() * 4, Runtime.getRuntime.availableProcessors() * 8, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable], (r: Runnable) => new Thread(r, "libcore-thread-dispatcher")))
  )
  lazy val apiClient = apiC
}

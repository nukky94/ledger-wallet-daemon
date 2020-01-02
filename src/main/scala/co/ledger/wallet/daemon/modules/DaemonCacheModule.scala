package co.ledger.wallet.daemon.modules

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

import java.util.concurrent.TimeUnit
import javax.inject.Singleton

import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.database.{DaemonCache, DefaultDaemonCache}
import co.ledger.wallet.daemon.exceptions.AccountSyncException
import co.ledger.wallet.daemon.services.{PoolsService, UsersService}
import com.google.inject.Provides
import com.twitter.concurrent.NamedPoolThreadFactory
import com.twitter.inject.{Injector, TwitterModule}
import com.twitter.util.{Duration, ScheduledThreadPoolTimer, Time}

object DaemonCacheModule extends TwitterModule {

  implicit val ec: ExecutionContext = MDCPropagatingExecutionContext.Implicits.global

  @Singleton
  @Provides
  def provideDaemonCache: DaemonCache = {
    val cache = new DefaultDaemonCache()
    val t0 = System.currentTimeMillis()
    Await.result(cache.dbMigration(), 1.minutes)
    info(s"Database migration end, elapsed time: ${System.currentTimeMillis() - t0} milliseconds")
    cache
  }

  override def singletonPostWarmupComplete(injector: Injector): Unit = {
    val poolsService = injector.instance[PoolsService](classOf[PoolsService])

    def synchronizationTask(): Unit = {
      try {
        Await.result(poolsService.syncOperations, 1.hour).foreach{
          case Success(r) =>
            if (r.syncResult) {
              info(s"Synchronization complete for $r")
            }
            else {
              warn(s"Failed synchronizing $r")
            }
          case Failure(e: AccountSyncException) =>
            error(e.getMessage, e)
          case Failure(t) =>
            error("Failed to synchronize account due to unknown exception", t)
        }
      } catch {
        case t: Throwable => error("The full synchronization timed out in 30 minutes", t)
      }
    }

    def startSynchronization(): Unit = {
      val scheduler = new ScheduledThreadPoolTimer(
        poolSize = 1,
        threadFactory = new NamedPoolThreadFactory("scheduler-thread-pool")
      )
      scheduler.schedule(
        Time.fromSeconds(DaemonConfiguration.Synchronization.initialDelay),
        Duration(DaemonConfiguration.Synchronization.interval, TimeUnit.HOURS))(synchronizationTask())
      info(s"Scheduled synchronization job: initial start in ${DaemonConfiguration.Synchronization.initialDelay} seconds, " +
        s"interval ${DaemonConfiguration.Synchronization.interval} hours")
    }

    val usersService = injector.instance[UsersService](classOf[UsersService])
    DaemonConfiguration.adminUsers.map { user =>
      val existingUser = Await.result(usersService.user(user._1, user._2), 1.minutes)
      if (existingUser.isEmpty) Await.result(usersService.createUser(user._1, user._2), 1.minutes)
    }
    DaemonConfiguration.whiteListUsers.map { user =>
      val existingUser = Await.result(usersService.user(user._1), 1.minutes)
      if (existingUser.isEmpty) Await.result(usersService.createUser(user._1, user._2), 1.minutes)
    }

    if (DaemonConfiguration.updateWalletConfig) {
      Await.result(updateWalletConfig(), 5.minutes)
    }
    startSynchronization()
  }

  private def updateWalletConfig()(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      users <- provideDaemonCache.getUsers
      pools <- Future.traverse(users)(_.pools).map(_.flatten)
      poolWallets <- Future.traverse(pools)(pool => pool.wallets.map((pool, _)))
      _ <- Future.sequence(poolWallets.flatMap { case (pool, wallets) => wallets.map(pool.updateWalletConfig) })
    } yield ()
  }
}

package co.ledger.wallet.daemon.modules

import java.util.concurrent.TimeUnit

import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext.Implicits.global
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.database.{DaemonCache, DefaultDaemonCache}
import co.ledger.wallet.daemon.exceptions.AccountSyncException
import co.ledger.wallet.daemon.services.{PoolsService, UsersService}
import com.google.inject.Provides
import com.twitter.concurrent.NamedPoolThreadFactory
import com.twitter.inject.{Injector, TwitterModule}
import com.twitter.util.{Duration, ScheduledThreadPoolTimer, Time}
import javax.inject.Singleton

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

object DaemonCacheModule extends TwitterModule {

  @Singleton
  @Provides
  def provideDaemonCache: DaemonCache = {
    val cache = new DefaultDaemonCache()
    val futureDbMigration = time("Database migration end, elapsed time: %d milliseconds")(cache.dbMigration)
    Await.result(futureDbMigration, 1.minutes)
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
        case t: Throwable => error("The full synchronization timed out in 60 minutes", t)
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

    val futureAdminUsers =
      time("Create admin users in %d milliseconds") {
        Future.sequence(
          DaemonConfiguration.adminUsers.map {
            case (username, password) =>
              usersService.user(username, password).flatMap {
                case Some(u) => Future.successful(u.id)
                case _ => usersService.createUser(username, password)
              }
          }
        )
      }

    val futureWhiteListUsers =
      time("Create whitelist users in %d milliseconds") {
        Future.sequence(
          DaemonConfiguration.whiteListUsers.map {
            case (pubKey, permissions) =>
              usersService.user(pubKey).flatMap {
                case Some(u) => Future.successful(u.id)
                case _ => usersService.createUser(pubKey, permissions)
              }
          }
        )
      }

    val futureUpdateWalletConfig =
      time(s"Update wallet config? ${DaemonConfiguration.updateWalletConfig}, in %d milliseconds") {
        if (DaemonConfiguration.updateWalletConfig) updateWalletConfig()
        else Future.unit
      }

    // Run blocking operations in parallel and await before starting synchronization
    Await.result(
      for {
        _ <- futureAdminUsers
        _ <- futureWhiteListUsers
        _ <- futureUpdateWalletConfig
      } yield (),
      10.minutes
    )

    startSynchronization()
  }

  private def updateWalletConfig(): Future[Unit] = {
    for {
      users <- provideDaemonCache.getUsers
      pools <- Future.traverse(users)(_.pools()).map(_.flatten)
      poolWallets <- Future.traverse(pools)(pool => pool.wallets.map((pool, _)))
      _ <- Future.sequence(poolWallets.flatMap { case (pool, wallets) => wallets.map(pool.updateWalletConfig) })
    } yield ()
  }
}

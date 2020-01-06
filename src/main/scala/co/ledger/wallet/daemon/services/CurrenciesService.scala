package co.ledger.wallet.daemon.services

import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.exceptions.CurrencyNotFoundException
import co.ledger.wallet.daemon.models.Currency._
import co.ledger.wallet.daemon.models._
import javax.inject.{Inject, Singleton}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

@Singleton
class CurrenciesService @Inject()(daemonCache: DaemonCache) extends DaemonService {

  def currency(
    currencyName: String,
    poolInfo: PoolInfo
  )(implicit ec: ExecutionContext): Future[Option[CurrencyView]] = {
    daemonCache.getCurrency(currencyName, poolInfo).map { currency => currency.map(_.currencyView) }
  }

  def currencies(poolInfo: PoolInfo)(implicit ec: ExecutionContext): Future[Seq[CurrencyView]] = {
    daemonCache.getCurrencies(poolInfo).map { modelCs =>
      modelCs.flatMap(c => Try(c.currencyView).toOption)
    }
  }

  def validateAddress(
    address: String,
    currencyName: String,
    poolInfo: PoolInfo
  )(implicit ec: ExecutionContext): Future[Boolean] = {
    daemonCache.getCurrency(currencyName, poolInfo).flatMap {
      case Some(currency) => Future(currency.validateAddress(address))
      case None => Future.failed(CurrencyNotFoundException(currencyName))
    }
  }
}

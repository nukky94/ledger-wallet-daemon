package co.ledger.wallet.daemon.database

import java.util.UUID

import co.ledger.core.{Account, Currency, Wallet}
import co.ledger.wallet.daemon.database.DefaultDaemonCache.User
import co.ledger.wallet.daemon.exceptions.{AccountNotFoundException, UserNotFoundException, WalletNotFoundException, WalletPoolNotFoundException}
import co.ledger.wallet.daemon.models.Account._
import co.ledger.wallet.daemon.models.Operations.PackedOperationsView
import co.ledger.wallet.daemon.models.Wallet._
import co.ledger.wallet.daemon.models._

import scala.concurrent.{ExecutionContext, Future}

trait DaemonCache {

  def getAccount(accountInfo: AccountInfo)(implicit ec: ExecutionContext): Future[Option[Account]] =
    withWallet(accountInfo.walletInfo)(_.account(accountInfo.accountIndex))

  def withAccount[T](accountInfo: AccountInfo)(f: Account => Future[T])(implicit ec: ExecutionContext): Future[T] =
    withWallet(accountInfo.walletInfo)(w => withAccount(accountInfo.accountIndex, w)(f))

  def withAccount[T](accountIndex: Int, wallet: Wallet)(f: Account => Future[T])(implicit ec: ExecutionContext): Future[T] =
    wallet.account(accountIndex).flatMap {
      case Some(account) => f(account)
      case None => Future.failed(AccountNotFoundException(accountIndex))
    }

  def withAccountAndWallet[T](accountInfo: AccountInfo)(f: (Account, Wallet) => Future[T])(implicit ec: ExecutionContext): Future[T] =
    withAccountAndWalletAndPool(accountInfo) {
      case (account, wallet, _) => f(account, wallet)
    }

  def withAccountAndWalletAndPool[T](accountInfo: AccountInfo)(f: (Account, Wallet, Pool) => Future[T])(implicit ec: ExecutionContext): Future[T] =
    withWalletPool(accountInfo.walletInfo.poolInfo) { pool =>
      withWallet(accountInfo.walletInfo) { wallet =>
        withAccount(accountInfo.accountIndex, wallet) { account =>
          f(account, wallet, pool)
        }
      }
    }

  def getFreshAddresses(accountInfo: AccountInfo)(implicit ec: ExecutionContext): Future[Seq[FreshAddressView]] =
    withAccount(accountInfo)(_.freshAddresses).map(_.map(addr => FreshAddressView(addr.toString, addr.getDerivationPath)))

  def getAddressesInRange(from: Long, to: Long, accountInfo: AccountInfo)(implicit ec: ExecutionContext): Future[Seq[FreshAddressView]] =
    withAccount(accountInfo)(_.getAddressesInRange(from, to)).map(_.map(addr => FreshAddressView(addr.toString, addr.getDerivationPath)))

  def getAccountOperations(batch: Int, fullOp: Int, accountInfo: AccountInfo): Future[PackedOperationsView]


  def getNextBatchAccountOperations(next: UUID, fullOp: Int, accountInfo: AccountInfo): Future[PackedOperationsView]

  def getPreviousBatchAccountOperations(previous: UUID,
                                        fullOp: Int, accountInfo: AccountInfo): Future[PackedOperationsView]

  // ************** currency ************
  def getCurrency(currencyName: String, poolInfo: PoolInfo)(implicit ec: ExecutionContext): Future[Option[Currency]] =
    withWalletPool(poolInfo)(_.currency(currencyName))

  def getCurrencies(poolInfo: PoolInfo)(implicit ec: ExecutionContext): Future[Seq[Currency]] =
    withWalletPool(poolInfo)(_.currencies())

  // ************** wallet *************
  def createWallet(currencyName: String, walletInfo: WalletInfo, isNativeSegwit: Boolean)(implicit ec: ExecutionContext): Future[Wallet] = {
    withWalletPool(walletInfo.poolInfo)(_.addWalletIfNotExist(walletInfo.walletName, currencyName, isNativeSegwit))
  }

  def getWallets(offset: Int, batch: Int, poolInfo: PoolInfo)(implicit ec: ExecutionContext): Future[(Int, Seq[Wallet])] = {
    withWalletPool(poolInfo)(_.wallets(offset, batch))
  }

  def getWallet(walletInfo: WalletInfo)(implicit ec: ExecutionContext): Future[Option[Wallet]] = {
    withWalletPool(walletInfo.poolInfo)(_.wallet(walletInfo.walletName))
  }

  def withWallet[T](walletInfo: WalletInfo)(f: Wallet => Future[T])(implicit ec: ExecutionContext): Future[T] =
    withWalletPool(walletInfo.poolInfo)(p => withWallet(walletInfo.walletName, p)(f))

  def withWallet[T](walletName: String, pool: Pool)(f: Wallet => Future[T])(implicit ec: ExecutionContext): Future[T] =
    pool.wallet(walletName).flatMap {
      case Some(w) => f(w)
      case None => Future.failed(WalletNotFoundException(walletName))
    }

  def withWalletAndPool[T](walletInfo: WalletInfo)(f: (Wallet, Pool) => Future[T])(implicit ec: ExecutionContext): Future[T] =
    withWalletPool(walletInfo.poolInfo)(p => withWallet(walletInfo.walletName, p)(w => f(w, p)))

  // ************** wallet pool *************
  def createWalletPool(poolInfo: PoolInfo, configuration: String)(implicit ec: ExecutionContext): Future[Pool] =
    withUser(poolInfo.pubKey)(_.addPoolIfNotExit(poolInfo.poolName, configuration))

  def getWalletPool(poolInfo: PoolInfo)(implicit ec: ExecutionContext): Future[Option[Pool]] =
    withUser(poolInfo.pubKey)(_.pool(poolInfo.poolName))


  def withWalletPool[T](poolInfo: PoolInfo)(f: Pool => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    getWalletPool(poolInfo).flatMap {
      case Some(pool) => f(pool)
      case None => Future.failed(WalletPoolNotFoundException(poolInfo.poolName))
    }
  }

  def getWalletPools(pubKey: String)(implicit ec: ExecutionContext): Future[Seq[Pool]] =
    withUser(pubKey)(_.pools())

  def deleteWalletPool(poolInfo: PoolInfo)(implicit ec: ExecutionContext): Future[Unit] =
    withUser(poolInfo.pubKey)(_.deletePool(poolInfo.poolName))


  // **************** user ***************
  def withUser[T](pubKey: String)(f: User => Future[T])(implicit ec: ExecutionContext): Future[T] =
    getUser(pubKey).flatMap {
      case Some(user) => f(user)
      case None => Future.failed(UserNotFoundException(pubKey))
    }

  def createUser(pubKey: String, permissions: Int): Future[Long]

  def getUsers: Future[Seq[User]]

  def getUser(pubKey: String): Future[Option[User]]
}

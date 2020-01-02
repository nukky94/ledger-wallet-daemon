package co.ledger.wallet.daemon.services

import scala.concurrent.{ExecutionContext, Future}

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.{Inject, Singleton}

import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.database.DefaultDaemonCache.User
import co.ledger.wallet.daemon.utils.HexUtils
import org.bitcoinj.core.Sha256Hash

@Singleton
class UsersService @Inject()(daemonCache: DaemonCache, ecdsa: ECDSAService) extends DaemonService {

  def user(username: String, password: String)(implicit ec: ExecutionContext): Future[Option[User]] = {
    user(pubKey(username, password))
  }

  def user(pubKey: String)(implicit ec: ExecutionContext): Future[Option[User]] = {
    daemonCache.getUser(pubKey)
  }

  def createUser(publicKey: String, permissions: Int = 0)(implicit ec: ExecutionContext): Future[Long] = {
    info(LogMsgMaker.newInstance("Create user")
      .append("pub_key", publicKey)
      .append("permissions", permissions)
      .toString())
    daemonCache.createUser(publicKey, permissions)
  }

  def createUser(username: String, password: String)(implicit ec: ExecutionContext): Future[Long] = {
    info(LogMsgMaker.newInstance("Create user")
      .append("username", username)
      .toString())

    createUser(pubKey(username, password))
  }

  private def pubKey(username: String, password: String) = {
    val user = s"Basic ${Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))}"
    val privKey = Sha256Hash.hash(user.getBytes(StandardCharsets.UTF_8))
    HexUtils.valueOf(ecdsa.computePublicKey(privKey))
  }
}

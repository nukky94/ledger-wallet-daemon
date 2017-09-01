package co.ledger.wallet.daemon.controllers

import javax.inject.Inject

import co.ledger.wallet.daemon.converters.PoolConverter
import co.ledger.wallet.daemon.services.PoolsService
import co.ledger.wallet.daemon.swagger.DocumentedController
import com.twitter.finagle.http.Request
import com.twitter.util.Future
import co.ledger.wallet.daemon.utils._
import co.ledger.wallet.daemon.services.AuthenticationService.AuthentifiedUserContext._
import co.ledger.wallet.daemon.services.PoolsService.PoolConfiguration

import scala.concurrent.ExecutionContext.Implicits.global

class WalletPoolsController @Inject()(poolsService: PoolsService, poolConverter: PoolConverter) extends DocumentedController {
  import WalletPoolsController._

  get("/pools") {(request: Request) =>
    poolsService.pools(request.user.get).asTwitter().map(_.map(poolConverter.apply))
  }

  post("/pools/:pool_name/create") {(request: Request) =>
    val poolName = request.getParam("pool_name")
    val configuration = PoolConfiguration() // TODO: Deserialize the configuration from the body of the request
    poolsService.createPool(request.user.get, poolName, PoolConfiguration()).asTwitter().map(poolConverter.apply)
  }

}

object WalletPoolsController {

  case class WalletPoolResult(test: String)
}
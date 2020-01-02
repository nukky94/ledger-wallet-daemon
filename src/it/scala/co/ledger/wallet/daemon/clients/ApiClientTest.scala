package co.ledger.wallet.daemon.clients

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import org.junit.Test
import org.scalatest.junit.AssertionsForJUnit

class ApiClientTest extends AssertionsForJUnit {
  private[this] val apiClient = ClientFactory.apiClient

  @Test def verifyQueryBTCFee(): Unit = {
    val feeInfo = Await.result(apiClient.getFees("bitcoin"), Duration.Inf)
    assert(feeInfo.fast >= feeInfo.normal)
    assert(feeInfo.normal >= feeInfo.slow)
    assert(feeInfo.fast >= feeInfo.slow)
  }

  @Test def verifyQueryOtherFee(): Unit = {
    intercept[UnsupportedOperationException] {
      Await.result(apiClient.getFees("other"), Duration.Inf)
    }
  }
}

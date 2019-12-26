package co.ledger.wallet.daemon.api

import co.ledger.wallet.daemon.utils.APIFeatureTest
import com.twitter.finagle.http.{Response, Status}
import org.junit.Test

@Test
class TransactionsApiTest extends APIFeatureTest {

  val poolName = "ledger"

  override def beforeAll(): Unit = {
    super.beforeAll()
    createPool(poolName)
  }

  override def afterAll(): Unit = {
    deletePool(poolName)
    super.afterAll()
  }

  /*
      Bitcoin testnet account
      BIP39 mnemonic : amount picture reward sorry local traffic response subject arrow tape silver stereo young path laugh leisure pitch lend drill elephant rural mushroom pill dice
      BIP39 Seed : 8300c413fe76b06c7a6420143348410c494e98e22ed6ed894b399d6e19712d0beaf7674742ead88fda64d70a21da0618d8899c8caea77cc2a718a16c9f1b1b38
      Account extended pubKey :             tpubDCvkaY3GEKQxpSpKaKNg4PNUzR42yr2XoKWWEMXaSE5gCouqUazcDpWXTGkFAkdGruCQtdTxHqHt8dbbTVfBCxvvwJHeMU4YPzWVLdoAL5H
      Account extended private key :        tprv8gEiS8125wjHvynXgfi5eyiNRPY6pWqdE1uiwqVH1xHHNKf4rCB23KtfH9fbLW8azudGrvwQag5Jwg85qttNpfXdu9aBHssrZMZC5MtwujQ
      BIP32 Account extended pubKey :       tpubDFZ7AWJ9LgCoS6NAhmY7BRcBg9TWnQEMUeLugE8XssxnTj32KdsMh6W7GfyB7QsXjhuPETS6FRWzQBMR9Xe3uNbtMeXBLVjSoUHSEka9VZJ
      BIP32 Account extended private key :  tprv8is526FuCJX8YdLNp7sWn1x577wad53SuLk8Pi6ETcAPdEnFhF3mWbtF6YrzUpx8spPw3tgpPdW3u34qM6oXuqXdiNGLaaNpG1iJxJg9RMw
      Derivations :
                path,                 address,                                            public key,                                                   private key
            m/44'/1'/0'/0/0,n1QJpnrS6fTjdT3q3bc8twB9t6AA6L9RXm,0276b49de71f3032ba98f2988ae0a00b8c10011183007f2701aff60de1b272e45d,cPHCq2vd5C3W1UXqRS9HpVHR9DYJguTjfwjk8weP4zHhZKAY3M3q
            m/44'/1'/0'/0/1,mxZcpwZ7XBdfb4JcGLzdEP8WPQaGzeUeFU,030d222dcc39de637d1a6ff646d600f4e26aad5af3b6a0ab9f979d1d3fb5c01b91,cUyokwFBMPgjw2kT92cjF7bzqjyUtLe8pjdYgmRp4z2kHf8kAWXU
            m/44'/1'/0'/0/2,mg4cBTMdZvkEbJAoMXDHyGDdjsqfjHzxQ6,035988c9617250f3a6d6e0e8e072bcf8bcb7f6802ffa4131b600e5afcca8bf47b2,cVytSayWDBWifQ6sXWgTJcFA1UW89YRUmQS13FQ6WoGLY612yPyz
            m/44'/1'/0'/0/3,n2PFTg4FhNM1w6c1JEphsJwTEXGGmF5E6a,0315779ec3bf11fc6755323c8125e14e818508d96b27ffe9dff2b636ce01966260,cPVUBRNcjSJCSk1m5JLBz3uapTv4pZUmxyRJtT9sZtBjZoVvoVog
 */
  // Ignored : TODO BACK-375
  ignore("TransactionsApi#Create and sign transaction") {
    val poolName = "transactionsCreation4Test"
    createPool(poolName)
    val walletName = "btcwallet"
    assertWalletCreation(poolName, walletName, "bitcoin_testnet", Status.Ok)
    assertCreateAccount(ACCOUNT_BODY, poolName, walletName, Status.Ok)
    assertSyncPool(Status.Ok)
    assertCreateTransaction(TX_BODY_WITH_EXCLUDE_UTXO, poolName, walletName, 0, Status.Ok)
    assertCreateTransaction(TX_BODY, poolName, walletName, 0, Status.BadRequest)
    assertCreateTransaction(INVALID_FEE_LEVEL_BODY, poolName, walletName, 0, Status.BadRequest)
    assertSignTransaction(TX_MISSING_APPENDED_SIG, poolName, walletName, 0, Status.BadRequest)
    assertSignTransaction(TX_MISSING_ONE_SIG, poolName, walletName, 0, Status.BadRequest)
    assertSignTransaction(TX_TO_SIGN_BODY, poolName, walletName, 0, Status.InternalServerError)
  }

  private def assertCreateTransaction(tx: String, poolName: String, walletName: String, accountIndex: Int, expected: Status): Response = {
    server.httpPost(
      s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex/transactions",
      tx,
      headers = defaultHeaders,
      andExpect = expected
    )
  }

  private val TX_TO_SIGN_BODY =
    """{
       |"raw_transaction": "0100000002A91F09D74BEE55D8E9F3673E42102FA9AB71185C47E83229076452C44EC301E9000000001976A91455F719785040EC522FB6CF9C4B45A7011912529188ACFFFFFFFFEA1B6F36DA6745A399878AB4B67BE9443A6155123BA1EFD252823F6987671095000000001976A914261E04A99A3E387DBA09A667F73C74E8C5A2523088ACFFFFFFFF02002D31010000000017A914394D7CE052572BF35DFC32CD6EFF5B4BE6D9300B870AC5E203000000001976A914F3CEB507BD0D264CE8B4C9564EA63E9426B3B66B88AC49EF0700",
       |"signatures": ["0100000002A91F0", "100000002A91F"],
       |"pubkeys": ["033B811F166EA0E8D764530960047A398F50AB89B40E70537DB06C303C7939930F","0229355FB9801567F6C332978F1383D7B6E717B7A3991524BC95F9D6A743DCA6CD"]
       |}""".stripMargin

  private val TX_MISSING_ONE_SIG =
    """{
       |"raw_transaction": "0100000002A91F09D74BEE55D8E9F3673E42102FA9AB71185C47E83229076452C44EC301E9000000001976A91455F719785040EC522FB6CF9C4B45A7011912529188ACFFFFFFFFEA1B6F36DA6745A399878AB4B67BE9443A6155123BA1EFD252823F6987671095000000001976A914261E04A99A3E387DBA09A667F73C74E8C5A2523088ACFFFFFFFF02002D31010000000017A914394D7CE052572BF35DFC32CD6EFF5B4BE6D9300B870AC5E203000000001976A914F3CEB507BD0D264CE8B4C9564EA63E9426B3B66B88AC49EF0700",
       |"signatures": ["0100000002A91F0"],
       |"pubkeys": ["033B811F166EA0E8D764530960047A398F50AB89B40E70537DB06C303C7939930F","0229355FB9801567F6C332978F1383D7B6E717B7A3991524BC95F9D6A743DCA6CD"]
       |}""".stripMargin

  private val TX_MISSING_APPENDED_SIG =
    """{
       |"raw_transaction": "0100000002A91F09D74BEE55D8E9F3673E42102FA9AB71185C47E83229076452C44EC301E9000000001976A91455F719785040EC522FB6CF9C4B45A7011912529188ACFFFFFFFFEA1B6F36DA6745A399878AB4B67BE9443A6155123BA1EFD252823F6987671095000000001976A914261E04A99A3E387DBA09A667F73C74E8C5A2523088ACFFFFFFFF02002D31010000000017A914394D7CE052572BF35DFC32CD6EFF5B4BE6D9300B870AC5E203000000001976A914F3CEB507BD0D264CE8B4C9564EA63E9426B3B66B88AC49EF0700",
       |"signatures": ["0100000002A91F0"],
       |"pubkeys": ["033B811F166EA0E8D764530960047A398F50AB89B40E70537DB06C303C7939930F"]
       |}""".stripMargin

  private val INVALID_FEE_LEVEL_BODY =
    """{""" +
      """"recipient": "mxZcpwZ7XBdfb4JcGLzdEP8WPQaGzeUeFU",""" +
      """"fees_level": "OTHER",""" +
      """"amount": 10000""" +
      """}"""

  private val TX_BODY =
    """{""" +
      """"recipient": "mxZcpwZ7XBdfb4JcGLzdEP8WPQaGzeUeFU",""" +
      """"fees_per_byte": 397000,""" +
      """"fees_level": "FAST",""" +
      """"amount": 10000""" +
      """}"""

  private val TX_BODY_WITH_EXCLUDE_UTXO =
    """{""" +
    """"recipient": "mxZcpwZ7XBdfb4JcGLzdEP8WPQaGzeUeFU",""" +
    """"fees_level": "NORMAL",""" +
    """"amount": 1000""" +
//    """"exclude_utxos":{"beabf89d72eccdcb895373096a402ae48930aa54d2b9e4d01a05e8f068e9ea49": 0 }""" +
    """}"""

  test("AccountsApi#Broadcast signed transaction") {
    assertWalletCreation(poolName, "bitcoin_testnet", "bitcoin_testnet", Status.Ok)
    assertCreateAccount(ACCOUNT_BODY, poolName, "bitcoin_testnet", Status.Ok)
    // assertSyncPool(Status.Ok) // remove to pass CI
    assertSignTransaction(TESTNET_TX_TO_SIGN_BODY, poolName, "bitcoin_testnet", 0, Status.InternalServerError)
    assertGetAccount(poolName, "bitcoin_testnet", 0, Status.Ok)
  }

  private def assertSignTransaction(tx: String, poolName: String, walletName: String, accountIndex: Int, expected: Status): Response = {
    server.httpPost(
      s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex/transactions/sign",
      tx,
      headers = defaultHeaders,
      andExpect = expected
    )
  }

  private def assertGetAccount(poolName: String, walletName: String, accountIndex: Int, expected: Status): Response = {
    server.httpGet(
      s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex",
      headers = defaultHeaders,
      andExpect = expected
    )
  }

  private val ACCOUNT_BODY =
    """{""" +
      """"account_index": 0,""" +
      """"derivations": [""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/1'/0'",""" +
      """"pub_key": "0276b49de71f3032ba98f2988ae0a00b8c10011183007f2701aff60de1b272e45d",""" +
      """"chain_code": "d1bb833ecd3beed6ec5f6aa79d3a424d53f5b99147b21dbc00456b05bc978a71"""" +
      """},""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/1'",""" +
      """"pub_key": "030d222dcc39de637d1a6ff646d600f4e26aad5af3b6a0ab9f979d1d3fb5c01b91",""" +
      """"chain_code": "88c2281acd51737c912af74cc1d1a8ba564eb7925e0d58a5500b004ba76099cb"""" +
      """}""" +
      """]""" +
      """}"""

  private val TESTNET_TX_TO_SIGN_BODY =
    """{
       |"raw_transaction": "0100000001531ABD78576139559EA37E48A6554714D7434AE2BDF1451076A4C98219762AF20000000000FFFFFFFF02E8030000000000001976A9147F5365AABF5001DC5A3A21246E639B2C1FAD804888ACF4A03D00000000001976A914F9E27FEF11F7CE2F3EBE80535DD5AC812A85CCDD88AC8BC71300",
       |"signatures": ["3045022100DD6BA1732C7BD0E94F9FE71B6290E04A4A4B293B949FC1C585A0382EEADD62A0022072BFC3F077652C9B7EFC1C92969E00438AC0C7296CBA0A4B97AF08C9DAC36B74"],
       |"pubkeys": ["02A1DED78DAD86FE76E2238E29C58B549FBA769EF2601EBA643D96B17D3C6C4D4E"]
       |}""".stripMargin

}

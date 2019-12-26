package co.ledger.wallet.daemon.clients

import java.io.{BufferedInputStream, DataOutputStream, OutputStream}
import java.net.{HttpURLConnection, InetSocketAddress, URL}
import java.util

import co.ledger.core._
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import com.sun.xml.internal.messaging.saaj.util.ByteOutputStream
import com.twitter.inject.Logging

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class ScalaHttpClient(implicit val ec: ExecutionContext) extends co.ledger.core.HttpClient with Logging {

  import ScalaHttpClient._

  override def execute(request: HttpRequest): Unit = Future {
    val connection = createConnection(request)

    try {
      info(s"${request.getMethod} ${request.getUrl}")
      setRequestProperties(request, connection)

      if (request.getBody.nonEmpty) {
        connection.setDoOutput(true)
        readRequestBody(request, connection.getOutputStream)
      }

      val statusCode = connection.getResponseCode
      val statusText = connection.getResponseMessage
      val isError = isOnError(statusCode)

      info(s"Received from ${request.getUrl} status=$statusCode error=$isError - statusText=$statusText")

      val headers: util.HashMap[String, String] = getResponseHeaders(connection)
      val bodyResult: HttpReadBodyResult = readResponseBody(connection, isError)
      val proxy: HttpUrlConnection = new ScalaHttpUrlConnection(statusCode, statusText, headers, bodyResult)
      request.complete(proxy, null)
    }
    finally connection.disconnect()
  }.failed.map[co.ledger.core.Error]({
    others: Throwable =>
      new co.ledger.core.Error(ErrorCode.HTTP_ERROR, others.getMessage)
  }).foreach(request.complete(null, _))

  private def getResponseHeaders(connection: HttpURLConnection) = {
    val headers = new util.HashMap[String, String]()
    for ((key, list) <- connection.getHeaderFields.asScala) {
      headers.put(key, list.get(list.size() - 1))
    }
    headers
  }

  private def isOnError(statusCode: Int) = {
    !(statusCode >= 200 && statusCode < 400)
  }

  private def readRequestBody(request: HttpRequest, os: OutputStream) = {
    val dataOs = new DataOutputStream(os)
    try {
      dataOs.write(request.getBody)
      dataOs.flush()
    }
    finally dataOs.close()
  }

  private def setRequestProperties(request: HttpRequest, connection: HttpURLConnection) = {
    connection.setRequestMethod(resolveMethod(request.getMethod))
    for ((key, value) <- request.getHeaders.asScala) {
      connection.setRequestProperty(key, value)
    }
    connection.setRequestProperty("User-Agent", "ledger-lib-core")
    connection.setRequestProperty("Content-Type", "application/json")
  }

  private def createConnection(request: HttpRequest) = {
    DaemonConfiguration.proxy match {
      case None => new URL(request.getUrl).openConnection().asInstanceOf[HttpURLConnection]
      case Some(proxy) =>
        new URL(request.getUrl)
          .openConnection(
            new java.net.Proxy(
              java.net.Proxy.Type.HTTP,
              new InetSocketAddress(proxy.host, proxy.port)))
          .asInstanceOf[HttpURLConnection]
    }
  }

  private def readResponseBody(connection: HttpURLConnection, onError: Boolean): HttpReadBodyResult = {
    val response =
      new BufferedInputStream(if (!onError) connection.getInputStream else connection.getErrorStream)
    val buffer = new Array[Byte](PROXY_BUFFER_SIZE)
    val outputStream = new ByteOutputStream()
    try {
      var size = 0
      do {
        size = response.read(buffer)
        if (size < buffer.length) {
          outputStream.write(buffer.slice(0, size))
        } else {
          outputStream.write(buffer)
        }
      } while (size > 0)
      val data = outputStream.getBytes
      if (onError) info(s"Received ${new String(data)}")
      new HttpReadBodyResult(null, data)
    } catch {
      case t: Throwable =>
        logger.error("Failed to read response body", t)
        val error = new co.ledger.core.Error(ErrorCode.HTTP_ERROR, "An error happened during body reading.")
        new HttpReadBodyResult(error, null)
    } finally {
      outputStream.close()
      response.close()
    }
  }

  private def resolveMethod(method: HttpMethod) = method match {
    case HttpMethod.GET => "GET"
    case HttpMethod.POST => "POST"
    case HttpMethod.PUT => "PUT"
    case HttpMethod.DEL => "DELETE"
  }
}

object ScalaHttpClient {
  val PROXY_BUFFER_SIZE: Int = 4 * 4096
}

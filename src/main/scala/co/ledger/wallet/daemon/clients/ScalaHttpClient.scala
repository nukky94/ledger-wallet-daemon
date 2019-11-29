package co.ledger.wallet.daemon.clients

import java.io.{BufferedInputStream, DataOutputStream}
import java.net.{HttpURLConnection, InetSocketAddress, URL}
import java.util

import cats.effect.IO
import co.ledger.core
import co.ledger.core._
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import com.sun.xml.internal.messaging.saaj.util.ByteOutputStream
import com.twitter.inject.Logging
import org.http4s.client.Client
import org.http4s.{EntityBody, EntityEncoder, Header, Headers, Method, Request, Uri}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class ScalaHttpClient(implicit val ec: ExecutionContext) extends co.ledger.core.HttpClient with Logging {

  import ScalaHttpClient._

  override def execute(request: HttpRequest): Unit = Future {
    val connection = DaemonConfiguration.proxy match {
      case None => new URL(request.getUrl).openConnection().asInstanceOf[HttpURLConnection]
      case Some(proxy) =>
        new URL(request.getUrl)
          .openConnection(
            new java.net.Proxy(
              java.net.Proxy.Type.HTTP,
              new InetSocketAddress(proxy.host, proxy.port)))
          .asInstanceOf[HttpURLConnection]
    }
    connection.setRequestMethod(resolveMethod(request.getMethod))
    for ((key, value) <- request.getHeaders.asScala) {
      connection.setRequestProperty(key, value)
    }
    connection.setRequestProperty("Content-Type", "application/json")
    info(s"${request.getMethod} ${request.getUrl}")
    val body = request.getBody
    if (body.nonEmpty) {
      connection.setDoOutput(true)
      val dataOs = new DataOutputStream(connection.getOutputStream)
      dataOs.write(body)
      dataOs.flush()
      dataOs.close()
    }
    val statusCode = connection.getResponseCode
    val statusText = connection.getResponseMessage
    val isError = !(statusCode >= 200 && statusCode < 400)
    val response =
      new BufferedInputStream(if (!isError) connection.getInputStream else connection.getErrorStream)
    val headers = new util.HashMap[String, String]()
    for ((key, list) <- connection.getHeaderFields.asScala) {
      headers.put(key, list.get(list.size() - 1))
    }
    val proxy: HttpUrlConnection = new co.ledger.core.HttpUrlConnection() {
      private val buffer = new Array[Byte](PROXY_BUFFER_SIZE)

      override def getStatusCode: Int = statusCode

      override def getStatusText: String = statusText

      override def getHeaders: util.HashMap[String, String] = headers

      override def readBody(): HttpReadBodyResult = {
        try {
          val outputStream = new ByteOutputStream()
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
          if (isError) info(s"Received error ${new String(data)}")
          new HttpReadBodyResult(null, data)
        } catch {
          case t: Throwable =>
            t.printStackTrace()
            val error = new co.ledger.core.Error(ErrorCode.HTTP_ERROR, "An error happened during body reading.")
            new HttpReadBodyResult(error, null)
        } finally {
          response.close()
          connection.disconnect()
        }
      }
    }
    request.complete(proxy, null)
  }.failed.map[co.ledger.core.Error]({
    others: Throwable =>
      new co.ledger.core.Error(ErrorCode.HTTP_ERROR, others.getMessage)
  }).foreach(request.complete(null, _))

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

class Http4sLibCoreHttpClient(client: Client[IO]) extends HttpClient {
  override def execute(httpRequest: HttpRequest): Unit = {
    val req = Request(
      method = method(httpRequest.getMethod),
      uri = Uri.unsafeFromString(httpRequest.getUrl),
      body = body(httpRequest.getBody),
      headers = headers(httpRequest.getHeaders.asScala.toMap)
    )
    client.run(req)
      .use { resp =>
        resp.body.compile.toList.map { bodyBytes =>
          new core.HttpUrlConnection {
            override def getStatusCode: Int = resp.status.code

            override def getStatusText: String = resp.status.toString()

            override def getHeaders: util.HashMap[String, String] = {
              new util.HashMap(
                resp.headers.toList.map(h => h.name.value -> h.value).toMap.asJava
              )
            }

            override def readBody(): HttpReadBodyResult =
              new HttpReadBodyResult(null, bodyBytes.toArray)
          }
        }
      }
      .flatMap(c => IO(httpRequest.complete(c, null)))
      .handleErrorWith(t => IO(httpRequest.complete(null,
        new co.ledger.core.Error(ErrorCode.HTTP_ERROR, t.getMessage)
      )))
      .unsafeRunSync()
  }

  private def method(m: HttpMethod): Method = m match {
    case HttpMethod.DEL => Method.DELETE
    case HttpMethod.GET => Method.GET
    case HttpMethod.POST => Method.POST
    case HttpMethod.PUT => Method.PUT
  }

  private def body(b: Array[Byte]): EntityBody[IO] = {
    EntityEncoder.byteArrayEncoder.toEntity(b).body
  }

  private def headers(hs: Map[String, String]): Headers = {
    Headers.of(hs.map(h => Header(h._1, h._2)).toSeq: _*)
  }
}

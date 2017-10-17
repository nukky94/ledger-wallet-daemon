package co.ledger.wallet.daemon.clients

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap}
import javax.annotation.Nullable
import javax.websocket.{ClientEndpoint, ContainerProvider, Session}

import co.ledger.core.{ErrorCode, WebSocketConnection}
import co.ledger.wallet.daemon.services.LogMsgMaker
import com.twitter.inject.Logging
import net.jcip.annotations.ThreadSafe

@ThreadSafe
@ClientEndpoint
class ScalaWebSocketClient extends co.ledger.core.WebSocketClient with Logging{
  import ScalaWebSocketClient._

  override def connect(url: String, connection: WebSocketConnection) = {
    if(connection.getConnectionId > 0 && sessions.containsKey(connection.getConnectionId)) {
      val session = sessions.get(connection.getConnectionId)
      warn(LogMsgMaker.newInstance("Connection already exist, ignore request")
        .append("url", url)
        .append("connection", connection.getConnectionId)
        .append("session", session.getId)
        .append("session_open", session.isOpen)
        .toString())
    } else {
      try {
        val session = webSocketContainer.connectToServer(classOf[ScalaWebSocketClient], URI.create(url))
        connection.onConnect(connectionCount.incrementAndGet())
        sessions.put(connection.getConnectionId, session)
        info(LogMsgMaker.newInstance("Connection established")
          .append("url", url)
          .append("session", session.getId)
          .append("connection", connection.getConnectionId)
          .toString())
      } catch {
        case e: Throwable => {
          error(LogMsgMaker.newInstance("Failed to connect to server")
            .append("url", url)
            .toString(), e)
          connection.onError(ErrorCode.HTTP_ERROR, e.getMessage)
        }
      }
    }
  }

//  @OnMessage
//  def processMessage(message: String, session: Session): Unit = {
//
//  }

  override def send(connection: WebSocketConnection, data: String) = {

    if(!sessions.containsKey(connection.getConnectionId)) {
      warn(LogMsgMaker.newInstance("Fail to send data, no connection")
        .append("connection", connection.getConnectionId)
        .append("data", data)
        .toString())
    } else {
      info(LogMsgMaker.newInstance("Sending data")
        .append("data", data)
        .append("connection", connection.getConnectionId)
        .toString())
      sessions.get(connection.getConnectionId).getBasicRemote.sendText(data)
    }
  }


  override def disconnect(connection: WebSocketConnection) = {
    if (!sessions.containsKey(connection.getConnectionId)) {
      warn(LogMsgMaker.newInstance("Fail to disconnect, not connected")
        .append("connection", connection.getConnectionId)
        .toString())
    } else {
      try {
        @Nullable val session = sessions.remove(connection.getConnectionId)
        if(session != null) session.close()
        info(LogMsgMaker.newInstance("Disconnecting, session closed")
          .append("session", session.getId)
          .append("connection", connection.getConnectionId)
          .toString())
      } catch {
        case e: Throwable => {
          error("Fail to disconnect", e)
          connection.onError(ErrorCode.HTTP_ERROR, e.getMessage)
        }
      } finally {
        connection.onClose()
      }
    }

  }

}

object ScalaWebSocketClient {
  private val webSocketContainer = ContainerProvider.getWebSocketContainer
  private val sessions: ConcurrentMap[Int, Session] = new ConcurrentHashMap[Int, Session]()
  private val connectionCount = new AtomicInteger(0)
}
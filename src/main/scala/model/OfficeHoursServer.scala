package model

import com.corundumstudio.socketio.listener.{DataListener, DisconnectListener}
import com.corundumstudio.socketio.{AckRequest, Configuration, SocketIOClient, SocketIOServer}
import model.database.{Database, DatabaseAPI, TestingDatabase}
import play.api.libs.json.{JsValue, Json}


class OfficeHoursServer() {

  val database: DatabaseAPI = if(Configuration.DEV_MODE){
    new TestingDatabase
  }else{
    new Database
  }

  var usernameToSocket: Map[String, SocketIOClient] = Map()
  var socketToUsername: Map[SocketIOClient, String] = Map()
  var TAtoSocket: Map[String,SocketIOClient]= Map()
  var SocketToTA: Map[SocketIOClient,String]=Map()

  val config: Configuration = new Configuration {
    setHostname("0.0.0.0")
    setPort(8080)
  }

  val server: SocketIOServer = new SocketIOServer(config)

  server.addDisconnectListener(new DisconnectionListener(this))
  server.addEventListener("enter_queue", classOf[String], new EnterQueueListener(this))
  server.addEventListener("ready_for_student", classOf[Nothing], new ReadyForStudentListener(this))
  server.addEventListener("display_TA", classOf[Nothing], new displayTAListener(this))
  server.addEventListener("alert_page", classOf[String], new alertListener(this))
  server.addEventListener("done_helping", classOf[String], new doneHelpingListener(this))




  server.start()

  def queueJSON(): String = {
    val queue: List[StudentInQueue] = database.getQueue
    val queueJSON: List[JsValue] = queue.map((entry: StudentInQueue) => entry.asJsValue())
    Json.stringify(Json.toJson(queueJSON))
  }

}

object OfficeHoursServer {
  def main(args: Array[String]): Unit = {
    new OfficeHoursServer()
  }
}

class DisconnectionListener(server: OfficeHoursServer) extends DisconnectListener {
  override def onDisconnect(socket: SocketIOClient): Unit = {
    if (server.socketToUsername.contains(socket)) {
      val username = server.socketToUsername(socket)

      server.database.removeStudentFromQueue(username)

      server.socketToUsername -= socket
      if (server.usernameToSocket.contains(username)) {
        server.usernameToSocket -= username
      }
    }
    server.server.getBroadcastOperations.sendEvent("queue", server.queueJSON())
  }
}


class EnterQueueListener(server: OfficeHoursServer) extends DataListener[String] {
  override def onData(socket: SocketIOClient, data: String, ackRequest: AckRequest): Unit = {

    val parsed:JsValue=Json.parse(data)
    val username:String= (parsed\"username").as[String]
    val topic:String= (parsed\"topic").as[String]
    val subtopic:String= (parsed\"subtopic").as[String]

    println(topic)
    println(subtopic)

    server.database.addStudentToQueue(StudentInQueue(username, System.nanoTime(),topic,subtopic))
    server.socketToUsername += (socket -> username)
    server.usernameToSocket += (username -> socket)
    server.server.getBroadcastOperations.sendEvent("queue", server.queueJSON())
  }
}


class ReadyForStudentListener(server: OfficeHoursServer) extends DataListener[Nothing] {
  override def onData(socket: SocketIOClient, dirtyMessage: Nothing, ackRequest: AckRequest): Unit = {


    val queue = server.database.getQueue.sortBy(_.timestamp)
    if(queue.nonEmpty){

      val studentToHelp = queue.head
      server.database.removeStudentFromQueue(studentToHelp.username)

//      socket.sendEvent("message", "You are now helping " + studentToHelp.username)
      socket.sendEvent("message", studentToHelp.username)

      if(server.usernameToSocket.contains(studentToHelp.username)){
        server.usernameToSocket(studentToHelp.username).sendEvent("message2", "A TA is ready to help you!")
      }

      server.server.getBroadcastOperations.sendEvent("queue", server.queueJSON())
    }
  }
}

class displayTAListener(server:OfficeHoursServer) extends DataListener[Nothing]{
  override def onData(socketIOClient: SocketIOClient, t: Nothing, ackRequest: AckRequest): Unit = {
    server.server.getBroadcastOperations.sendEvent("queue", server.queueJSON())
  }
}

class alertListener(server: OfficeHoursServer) extends DataListener[String] {
  override def onData(socket: SocketIOClient, username: String, ackRequest: AckRequest): Unit = {
    val socketToSend=server.usernameToSocket(username)
    socketToSend.sendEvent("alert")
  }
}
class doneHelpingListener(server: OfficeHoursServer) extends DataListener[String] {
  override def onData(socket: SocketIOClient, username: String, ackRequest: AckRequest): Unit = {
    println("Done Helping Called")
    val socketToSend=server.usernameToSocket(username)
    socketToSend.sendEvent("done")
  }
}



package colossus.core

import server._
import akka.actor._
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, Selector, SocketChannel}

import colossus.metrics.MetricNamespace
import colossus.metrics.collectors.{Counter, Rate}
import colossus.metrics.logging.ColossusLogging
import colossus.{IOCommand, IOSystem}
import colossus.service.{CallbackExecution, CallbackExecutor}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

/**
  * Contains the configuration for each Worker. Created when Workers are spawned by the WorkerManager.
  * Notice - currently the worker config cannot contain the MetricSystem,
  * because workers are created as a part of creating the MetricSystem
  *
  * @param io The IOSystem to which this Worker belongs
  * @param workerId This Worker's unique id amongst its peers.
  */
case class WorkerConfig(
    io: IOSystem,
    workerId: Int
)

/**
  * This is a Worker's public interface.  This is what can be used to communicate with a Worker, as it
  * wraps the Worker's ActorRef, as well as providing some additional information which can be made public.
  *
  * @param id The Worker's id.
  * @param worker The ActorRef of the Worker
  * @param system The IOSystem to which this Worker belongs
  */
case class WorkerRef private[colossus] (id: Int, worker: ActorRef, system: IOSystem) {

  private[colossus] def generateContext() = new Context(system.generateId(), this)

  /**
    * Send this Worker a message
    * @param message The message to send
    * @param sender  The sendef of the message
    * @return
    */
  def !(message: Any)(implicit sender: ActorRef = ActorRef.noSender): Unit = worker ! message

  /**
    * Bind a new worker item to this worker.  The item should have been created
    * and initialized within this worker to ensure that the worker item's
    * lifecycle is single-threaded.
    */
  def bind[T <: WorkerItem](creator: Context => T): T = {
    val context = generateContext()
    val item    = creator(context)
    worker ! IOCommand.BindWorkerItem(_ => item)
    item
  }

  def unbind(workerItemId: Long) {
    worker ! WorkerCommand.UnbindWorkerItem(workerItemId)
  }

  /**
    * The representation of this worker as a [CallbackExecutor].  Bring this
    * into scope to easily convert Futures into Callbacks.  This uses the
    * default dispatcher of the worker's underlying ActorSystem.
    */
  implicit val callbackExecutor = CallbackExecutor(system.actorSystem.dispatcher, worker)

}

/**
  * This keeps track of all the bound worker items, and properly handles added/removing them
  */
class WorkerItemManager(worker: WorkerRef) extends ColossusLogging {

  private val workerItems = collection.mutable.Map[Long, WorkerItem]()

  private var id: Long = 0L
  def newId(): Long = {
    id += 1
    id
  }

  def get(id: Long): Option[WorkerItem] = workerItems.get(id)

  /**
    * Binds a new worker item to this worker
    */
  def bind(workerItem: WorkerItem) {
    if (workerItem.isBound) {
      error(s"Attempted to bind worker $workerItem that was already bound")
    } else {
      workerItems(workerItem.id) = workerItem
      workerItem.setBind()
    }
  }

  def unbind(id: Long) {
    if (workerItems contains id) {
      val item = workerItems(id)
      workerItems -= id
      item.setUnbind()
    } else {
      error(s"Attempted to unbind worker $id that is not bound to this worker")
    }
  }

  /**
    * Replace an existing worker item with a new one.  This happens, for example,
    * as the last phase of swapping a live connection's handler.  The old
    * WorkerItem is unbound before the new one is bound.  If no WorkerItem with
    * the given id exists, the new one is not bound.  This is to avoid a possible
    * race condition that could occur if a connection is severed during the
    * process of swapping handlers.
    *
    * Returns true if the replace successfully happened, false otherwise
    */
  def replace(newWorkerItem: WorkerItem): Boolean = {
    get(newWorkerItem.id)
      .map { old =>
        unbind(old)
        bind(newWorkerItem)
        false
      }
      .getOrElse {
        error(s"Attempted to swap worker $id that is not bound to this worker")
        false
      }
  }

  def unbind(workerItem: WorkerItem) {
    unbind(workerItem.id)
  }

  def idleCheck(period: FiniteDuration) {
    workerItems.foreach {
      case (_, i: IdleCheck) => i.idleCheck(period)
      case _                 =>
    }
  }
}

private[colossus] class Worker(config: WorkerConfig) extends Actor with ColossusLogging with CallbackExecution {
  import Server._
  import Worker._
  import WorkerManager._
  import config._

  private val parent = context.parent

  private var trace = true

  private val watchedConnections = collection.mutable.Map[ActorRef, ClientConnection]()
  
  private val workerIdTag = Map("worker" -> (io.name + "-" + workerId.toString))

  implicit val ns: MetricNamespace = io.namespace / io.name

  val eventLoops          = Rate("worker/event_loops", "worker-event-loops")
  val numConnections      = Counter("worker/connections", "worker-connections")
  val rejectedConnections = Rate("worker/rejected_connections", "worker-rejected-connections")

  private val selector = Selector.open()

  private val buffer = ByteBuffer.allocateDirect(1024 * 128)

  private val outputBuffer = new DynamicOutBuffer(1024 * 1024 * 4)

  private val activeConnections = collection.mutable.Map[Long, Connection]()

  private val serversToInitializers = collection.mutable.Map[ActorRef, Initializer]()

  val me = WorkerRef(workerId, self, io)

  //collection of all the bound WorkerItems, including connection handlers
  val workerItems                                 = new WorkerItemManager(me)
  def getWorkerItem(id: Long): Option[WorkerItem] = workerItems.get(id)

  override def preStart() {
    super.preStart()
    debug(s"starting worker ${config.workerId}")
    parent ! WorkerReady(me)
    self ! Select
  }

  def receive: Receive = handleCallback orElse accepting

  def accepting: Receive = {
    case Select => {
      selectLoop()
      self ! Select
    }
    case c: IOCommand     => handleIOCommand(c)
    case c: WorkerCommand => handleWorkerCommand(c)
    case CheckIdleConnections => {
      workerItems.idleCheck(WorkerManager.IdleCheckFrequency)
      val time = System.currentTimeMillis
      val timedOut = activeConnections.filter {
        case (_, con) =>
          if (con.isTimedOut(time)) {
            unregisterConnection(con, DisconnectCause.TimedOut)
            true
          } else {
            false
          }
      }
      if (timedOut.nonEmpty) {
        debug(s"Terminated ${timedOut.size} idle connections")
      }
      sender() ! IdleCheckExecuted
    }
    case WorkerManager.RegisterServer(server) =>
      if (!serversToInitializers.contains(server.server)) {
        try {
          serversToInitializers(server.server) = server.config.initializerFactory(InitContext(server, me))
          debug(s"registered server ${server.name}")
          sender ! ServerRegistered
        } catch {
          case NonFatal(e) =>
            error("failed to create initializer", e)
            sender ! RegistrationFailed
        }
      } else {
        warn("attempted to re-register a server")
        sender ! WorkerManager.ServerRegistered
      }
    case WorkerManager.UnregisterServer(server) =>
      unregisterServer(server.server)

    case WorkerManager.ServerShutdownRequest(server) =>
      activeConnections.collect {
        case (id, connection: ServerConnection) if connection.server == server =>
          connection.serverHandler.shutdownRequest()
      }

    case InitializerMessage(server, message) => {
      serversToInitializers
        .find { case (_, initializer) => initializer.server == server }
        .map {
          case (_, initializer) =>
            initializer.receive.orElse[Any, Unit] {
              case unhandled => warn(s"Unhandled message $unhandled for initializer of server ${server.name}")
            }(message)
        }
        .getOrElse {
          error(s"initializer message $message for unknown server ${server.name}")
        }
    }

    case NewConnection(sc, attempt) =>
      serversToInitializers
        .get(sender())
        .map { initializer =>
          Try(initializer.onConnect.apply(ServerContext(initializer.server, initializer.worker.generateContext())))
            .map { handler =>
              registerConnection(sc, initializer.server, handler)
            }
            .getOrElse {
              sc.close()
              initializer.server.server ! Server.ConnectionClosed(0, DisconnectCause.Unhandled)
              rejectedConnections.hit(tags = Map("server" -> initializer.server.name.idString) ++ workerIdTag)
            }
        }
        .getOrElse {
          sender ! Server.ConnectionRefused(sc, attempt)
          error("Received connection from unregistered server!!!")
        }

    case Terminated(handler) =>
      if (watchedConnections contains handler) {
        watchedConnections(handler).close(DisconnectCause.Disconnect) //TODO: Is this right?  Should I be sending Terminated?
        watchedConnections -= handler
      }

    case ConnectionSummaryRequest =>
      val now = System.currentTimeMillis //save a few thousand calls by doing this
      sender ! ConnectionSummary(activeConnections.values.map { _.info(now) }.toSeq)
  }

  def handleIOCommand(cmd: IOCommand) {
    import IOCommand._
    cmd match {
      case BindWorkerItem(itemFactory) =>
        val item = itemFactory(me.generateContext())
        //the item may have already bound itself
        if (!item.isBound) {
          workerItems.bind(item)
        }

      case BindAndConnectWorkerItem(address, itemFactory) =>
        val item = itemFactory(me.generateContext())
        workerItems.bind(item)
        self ! WorkerCommand.Connect(address, item.id)

      case BindWithContext(context, itemFactory) =>
        if (context.worker != me) {
          error("Attempted to bind to worker ${me.id} using a context for worker ${context.worker.id}")
        } else {
          val item = itemFactory(context)
          if (!item.isBound) {
            workerItems.bind(item)
          }
        }
    }
  }

  //start the connection process for either a new client or a reconnecting client
  def clientConnect(address: InetSocketAddress, handler: ClientConnectionHandler) {
    val newChannel = SocketChannel.open()
    newChannel.configureBlocking(false)
    val newKey = newChannel.register(selector, SelectionKey.OP_CONNECT)
    val connection = new ClientConnection(handler.id, handler, me) with LiveConnection {
      val key     = newKey
      val channel = newChannel
    }
    newKey.attach(connection)
    activeConnections(connection.id) = connection
    numConnections.increment(workerIdTag)
    handler match {
      case w: WatchedHandler => {
        watchedConnections(w.watchedActor) = connection
        context.watch(w.watchedActor)
      }
      case _ => {}
    }
    try {
      if (newChannel.connect(address)) {
        //if this returns true it means the connection is already connected (can
        //happen on Windows and maybe other OS's when connecting to localhost),
        //so finish the connect process immediately.  The connection will
        //properly set the key interest ops
        connection.handleConnected()
      }
    } catch {
      case t: Throwable => {
        error(s"Failed to establish connection to $address: $t", t)
        unregisterConnection(connection, DisconnectCause.ConnectFailed(t))
      }
    }
  }

  def handleWorkerCommand(cmd: WorkerCommand) {
    import WorkerCommand._
    cmd match {
      case Message(wid, message) => {
        val responder = sender()
        getWorkerItem(wid)
          .map { item =>
            item.receivedMessage(message, responder)
          }
          .getOrElse {
            error(s"worker received message $message for unknown item $wid")
            responder ! MessageDeliveryFailed(wid, message)
          }
      }
      case s: Schedule => {
        //akka's scheduler doesn't work when an actor is looping messages to
        //itself in a pinned dispatcher (trust me I've tried), so we'll send this
        //to the manager to do it for us this is mostly for scheduling reconnects
        //(yes we have the TaskScheduler, but lets try to get that out of here)
        context.parent ! s
      }
      case UnbindWorkerItem(id) => {
        workerItems.unbind(id)
      }
      case Disconnect(connectionId) => {
        activeConnections.get(connectionId).foreach { con =>
          unregisterConnection(con, DisconnectCause.Disconnect)
        }
      }
      case Kill(connectionId, errorCause) => {
        activeConnections.get(connectionId).foreach { con =>
          unregisterConnection(con, errorCause)
        }
      }
      case Connect(address, id) => {
        getWorkerItem(id)
          .map {
            case handler: ClientConnectionHandler => clientConnect(address, handler)
            case other =>
              error(
                s"Attempted to attach connection ($address) to a worker item that's not a ClientConnectionHandler")
          }
          .getOrElse {
            error(s"Attempted to attach connection (${address}) to non-existant WorkerItem $id")
          }
      }
      case Bind(newHandler) => {
        workerItems.bind(newHandler)
      }
      case SwapHandler(newHandler) => {
        activeConnections.get(newHandler.id).foreach { con =>
          workerItems.replace(newHandler)
          con.setHandler(newHandler)
        }
      }
    }
  }

  /**
    * Registers a new server connection
    */
  def registerConnection(sc: SocketChannel, server: ServerRef, handler: ServerConnectionHandler) {
    val newKey: SelectionKey = sc.register(selector, SelectionKey.OP_READ)
    val connection = new ServerConnection(handler.id, handler, server, me) with LiveConnection {
      val key     = newKey
      val channel = sc
    }
    newKey.attach(connection)
    activeConnections(connection.id) = connection
    numConnections.increment(workerIdTag)
    workerItems.bind(handler)
    handler.connected(connection)
  }

  /** Removes a closed connection and possibly unbinds the associated Connection Handler
    *
    * In general, the only time we don't want to unbind the handler is when the
    * connection is a client connection with the ManualUnbindHandler mixed in
    * and the disconnect cause is an error.  This allows the client to possibly
    * reconnect and still receive messages.
    *
    * Here's the table
    *
    *   Server/Client   ManualUnbindHandler   DisconnectError   Unbind?
    *         S                 -                   -             Y
    *         C                 N                   -             Y
    *         C                 Y                   N             Y
    *         C                 Y                   Y             N
    *
    * It may be the case in the future that we want the ManualUnbindHandler to
    * cause the handler to stay bound even on non-error disconnects, but for
    * now the only reason that mixin exists is for ServiceClient reconnections,
    * so it makes sense for now to err on the side of caution and not risk a
    * leak of bound WorkerItems
    */
  def unregisterConnection(con: Connection, cause: DisconnectCause) {
    activeConnections -= con.id
    con.close(cause)
    numConnections.decrement(workerIdTag)
    (con.handler, cause) match {
      case (m: ManualUnbindHandler, d: DisconnectError) => {}
      case _                                            => workerItems.unbind(con.handler)
    }
  }

  def unregisterServer(handler: ActorRef) {
    if (serversToInitializers contains handler) {
      val initializer = serversToInitializers(handler)
      val closed = activeConnections.collect {
        case (_, s: ServerConnection) if (s.server == initializer.server) => {
          unregisterConnection(s, DisconnectCause.Terminated)
        }
      }
      serversToInitializers -= handler
      initializer.onShutdown()
      info(s"unregistering server ${initializer.server.name} (terminating ${closed.size} associated connections)")
    } else {
      warn(s"Attempted to unregister unknown server actor ${handler.path.toString}")
    }
  }

  def selectLoop() {
    val num = selector.select(1) //need short wait times to register new connections
    eventLoops.hit(tags = workerIdTag)
    implicit val TIME = System.currentTimeMillis
    val selectedKeys  = selector.selectedKeys()
    val it            = selectedKeys.iterator()
    while (it.hasNext) {
      val key: SelectionKey = it.next
      if (!key.isValid) {
        error("KEY IS INVALID")
      } else if (key.isConnectable) {
        val con = key.attachment.asInstanceOf[ClientConnection]
        try {
          con.handleConnected()
        } catch {
          case t: Throwable => {
            unregisterConnection(con, DisconnectCause.ConnectFailed(t))
            key.cancel()
          }
        }
      } else {
        if (key.isReadable) {
          // Read the data
          buffer.clear
          val sc: SocketChannel = key.channel().asInstanceOf[SocketChannel]
          try {
            val len = sc.read(buffer)
            if (len > -1) {
              key.attachment match {
                case connection: Connection => {
                  buffer.flip
                  val data = DataBuffer(buffer, len)
                  connection.handleRead(data)
                } //end case
              }
            } else {
              //reading -1 bytes means the connection has been closed
              key.attachment match {
                case c: Connection => {
                  unregisterConnection(c, DisconnectCause.Closed)
                  key.cancel()
                }
              }
            }
          } catch {
            case t: java.io.IOException => {
              key.attachment match {
                case c: Connection => {
                  //connection reset by peer, sometimes thrown by read when the connection closes
                  unregisterConnection(c, DisconnectCause.Closed)
                }
              }
              sc.close()
              key.cancel()
            }
            case t: Throwable => {
              warn(s"Unknown Error! : ${t.getClass.getName}: ${t.getMessage}")
              if (trace) {
                t.printStackTrace()
              }
              //close the connection to ensure it's not in an undefined state
              key.attachment match {
                case c: Connection => {
                  warn(s"closing connection ${c.id} due to unknown error")
                  unregisterConnection(c, DisconnectCause.Error(t))
                }
                case other => {
                  error(s"Key has bad attachment!! $other")
                }
              }
              sc.close()
              key.cancel()
            }
          }
        }
        //have to test for valid here since it could have just been cancelled above
        if (key.isValid && key.isWritable) {
          key.attachment match {
            case c: Connection =>
              try {
                c.handleWrite(outputBuffer)
                outputBuffer.reset()
              } catch {
                case j: java.io.IOException => {
                  unregisterConnection(c, DisconnectCause.Error(j))
                }
                case other: Throwable => {
                  warn(s"Error handling write: ${other.getClass.getName} : ${other.getMessage}")
                }
              }
            case _ => {}
          }
        }
      }
      it.remove()

    }
  }

  override def postStop() {
    //cleanup
    selector.keys.asScala.foreach { _.channel.close }
    activeConnections.foreach {
      case (_, con) =>
        con.close(DisconnectCause.Terminated)
    }
    serversToInitializers.foreach {
      case (server, initializer) =>
        initializer.onShutdown()
    }
    selector.close()
    info("PEACE OUT")
  }

}

/**
  * Like the server actor, it is critical that instances of this actor get their
  * own thread, since they block when waiting for events.
  *
  * Workers are the "engine" of Colossus.  They are the components which receive and operate on Connections,
  * respond to java NIO select loop events and execute the corresponding reactors in the Delegator and ConnectionHandlers.
  *
  * These are the messages to which a Worker will respond.
  */
//NOTE: we should really divide this and WorkerCommand into public and private classes.
object Worker {
  private[core] case object ConnectionSummaryRequest
  private[core] case object CheckIdleConnections

  case class ConnectionSummary(infos: Seq[ConnectionSnapshot])

  /** Sent from Servers
    *
    * @param sc the underlying socketchannel of the connection
    * @param attempt used when a worker refuses a connection, which can happen if a worker has just restarted and hasn't yet re-registered servers
    */
  private[core] case class NewConnection(sc: SocketChannel, attempt: Int = 1)

  /**
    * Send a message to the initializer belonging to the server
    */
  case class InitializerMessage(server: ServerRef, message: Any)

  case class MessageDeliveryFailed(id: Long, message: Any)
}

/**
  * These are a different class of Commands to which a worker will respond.  These
  * are more relevant to the lifecycle of WorkerItems
  */
sealed trait WorkerCommand
object WorkerCommand {

  /**
    * Bind a worker item to this worker.  Note that the item must be initialized
    * with a context generated by this worker
    *
    * Notice - this is different from IOCommand.BindWorkerItem since this takes
    * an already constructed item meant for this worker, whereas BindWorkerItem
    * is created from outside the IOSystem and takes a function
    */
  case class Bind(item: WorkerItem) extends WorkerCommand

  case class Connect(address: InetSocketAddress, id: Long) extends WorkerCommand
  case class UnbindWorkerItem(id: Long)                    extends WorkerCommand
  case class Schedule(in: FiniteDuration, message: Any)    extends WorkerCommand
  case class Message(id: Long, message: Any)               extends WorkerCommand
  case class Disconnect(id: Long)                          extends WorkerCommand
  case class SwapHandler(newHandler: ConnectionHandler)    extends WorkerCommand

  //similar to Disconnect, this will shut down a connection, however it will
  //treat the disconnect as an error and forward the error cause to the
  //handler.  In the case of a client connection, this can be a way to
  //forcefully kill the connection and trigger a reconnect
  case class Kill(id: Long, error: DisconnectError) extends WorkerCommand
}

import fs2.io.net._
import cats.effect._
import cats.syntax.all._
import com.comcast.ip4s.Port
import fs2._
import scala.util.control.NonFatal
import scala.util.Try
import cats.syntax.validated
import Logger._
import LogLevel._
object Server:

  given loggerName: LoggerName = LoggerName("Server")

  def apply[F[_]: Network: Async](
      db: DB[F],
      port: Option[Port] = Port.fromInt(5005),
      maxConnections: Int = 500
  ) =
    val handler = handleCommand(db)
    Network[F]
      .server(None, port)
      .map { client =>
        Stream.eval(log[F](LogLevel.Info, s"Client connected $client")) >>
          client.reads
            .through(Protocol.read)
            .flatMap(
              handler(_)
                .through(_.map(_.bytes).flatMap(Stream.chunk))
                .through(client.writes)
            )
            .onFinalizeCase { exitCase =>
              log(
                Info,
                s"Client $client stream terminated with exitCase $exitCase"
              )
            }
            .attempt
            .void
      }
      .parJoin(maxConnections)
      .drain

  def handleCommand[F[_]: Concurrent](
      db: DB[F]
  )(protocol: Protocol): Stream[F, Protocol] =
    def invalidCmd =
      Stream.raiseError(new Exception(s"Invalid command $protocol"))

    def validKey(k: Protocol): Either[Throwable, DB.Key] = k match
      case Protocol.Simple(k) =>
        Right(k)
      case Protocol.Bulk(bulk) =>
        Either.catchNonFatal(String(bulk.toArray))
      case _ =>
        Left(new Exception(s"Invalid protocol type for key $k"))

    protocol match
      case Protocol.Arr(cmds) =>
        cmds.size match
          case 1 =>
            cmds(0) match
              case PING =>
                Stream.emit(PONG)
              case _ => invalidCmd

          case 2 =>
            cmds(0) match
              case GET =>
                Stream
                  .fromEither(validKey(cmds(1)))
                  .evalMap { k =>
                    db.get(k, None)
                      .map(_.getOrElse(Protocol.Nil))
                  }

              case SUBSCRIBE =>
                Stream
                  .fromEither(validKey(cmds(1)))
                  .flatMap(k => Stream.resource(db.subscribe(k)).flatten)

              case _ => invalidCmd

          case 3 =>
            cmds(0) match
              case SET =>
                Stream
                  .fromEither(validKey(cmds(1)))
                  .evalMap { k =>
                    db.set(k, cmds(2))
                  }
                  .as(OK)
              case PUBLISH =>
                Stream
                  .fromEither(validKey(cmds(1)))
                  .evalMap(db.set(_, cmds(2)))
                  .as(OK)
              case _ => invalidCmd

          case n =>
            Stream.raiseError(
              new Exception(s"unsupported number of cmds: $n: $cmds")
            )

      case _ =>
        invalidCmd

  private final val GET = Protocol.Bulk(Chunk.array("GET".getBytes))
  private final val SET = Protocol.Bulk(Chunk.array("SET".getBytes))
  private final val OK = Protocol.Bulk(Chunk.array("OK".getBytes))
  private final val PING = Protocol.Bulk(Chunk.array("PING".getBytes))
  private final val PONG = Protocol.Simple("PONG")
  private final val SUBSCRIBE = Protocol.Bulk(Chunk.array("SUBSCRIBE".getBytes))
  private final val PUBLISH = Protocol.Bulk(Chunk.array("PUBLISH".getBytes))

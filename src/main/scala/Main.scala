import cats.effect._
import cats.implicits._
import cats.effect.implicits._
import fs2._
import epollcat.EpollApp

object Main extends EpollApp {
  given loggerName: LoggerName = LoggerName("main")
  def run(args: List[String]): IO[ExitCode] = {
    val stream = for {
      given Logger.Instance[IO] <- Stream.resource(Logger.Instance[IO])

      _ <- Stream.eval(IO.println("Starting"))
      //  = logInstance
      db <- Stream.resource(DB.ref[IO]())
      _ <- Server[IO](db).concurrently(
        Stream.eval(Logger.log[IO](LogLevel.Info, "server started"))
      )
    } yield ()

    stream.compile.drain.as(ExitCode.Success)
  }
}

import cats.effect._
import cats.implicits._
import cats.effect.implicits._
import fs2._

object Main extends IOApp {
  given loggerName: LoggerName = LoggerName("main")
  def run(args: List[String]) = {
    val stream = for {
      db <- Stream.resource(DB.ref[IO]() >>= DB.behindQueue)
      _ <- Server[IO](db).concurrently(
        Stream.eval(Logger.log[IO](LogLevel.Info, "server started"))
      )
    } yield ()

    stream.compile.drain.as(ExitCode.Success)
  }
}

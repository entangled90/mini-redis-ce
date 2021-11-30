import scala.reflect.ClassTag
import cats.effect._
import cats.effect.syntax.all._
import cats._
import cats.syntax.all._
import java.io.PrintStream
import java.time.LocalDateTime
import scala.math.Ordering
import math.Ordered.orderingToOrdered
import cats.effect.std._
import fs2._

opaque type LoggerName = String

object LoggerName:
  def apply(name: String): LoggerName = name
  def forClass[T: ClassTag] = LoggerName(ClassTag[T].getClass.getSimpleName)

// opaque types cannot be used with inline
type LogLevel = Int

object LogLevel:

  inline val Error = 1000
  inline val Warn = 400
  inline val Info = 200
  inline val Debug = 100

  inline val MinLevel = 100

  val Levels = Map(
    Error -> "ERROR",
    Warn -> "WARN",
    Info -> "INFO",
    Debug -> "DEBUG"
  )

object Logger:

  trait Instance[F[_]]:
    def log(str: String): F[Unit]

  object Instance:
    def apply[F[_]: Async]: Resource[F, Instance[F]] =
      for {
        q <- Resource.eval(Queue.bounded[F, String](1024 * 1024))
        inst = new Instance[F] {
          def log(str: String): F[Unit] = q.offer(str)
        }
        _ <- Stream
          .fromQueueUnterminated(q)
          .evalMap(s => Sync[F].delay(println(s)))
          .compile
          .drain
          .background
      } yield inst

    def simple[F[_]: Sync] = new Logger.Instance[F] {
      def log(s: String) = Sync[F].delay(println(s))
    }

  inline def log[F[_]: Sync](
      level: LogLevel,
      msg: String,
      ps: PrintStream = System.out
  )(using name: LoggerName, instance: Instance[F]): F[Unit] =
    inline if level >= LogLevel.MinLevel then
      instance.log(
        s"[${LogLevel.Levels(level)}] ${LocalDateTime.now} ${Thread.currentThread.getName} $name - $msg"
      )
    else Applicative[F].unit

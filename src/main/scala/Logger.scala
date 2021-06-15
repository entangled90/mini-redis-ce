import scala.reflect.ClassTag
import cats.effect._
import cats._
import cats.syntax.all._
import java.io.PrintStream
import java.time.LocalDateTime
import scala.math.Ordering
import math.Ordered.orderingToOrdered

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

  inline val MinLevel = 1000

  val Levels = Map(
    Error -> "ERROR",
    Warn -> "WARN",
    Info -> "INFO",
    Debug -> "DEBUG"
  )

object Logger:
  inline def log[F[_]: Sync](
      level: LogLevel,
      msg: String,
      ps: PrintStream = System.out
  )(using name: LoggerName): F[Unit] = if level >= LogLevel.MinLevel then
    Sync[F].delay {
      ps.synchronized {
        ps.print(
          s"[${LogLevel.Levels(level)}] ${LocalDateTime.now} ${Thread.currentThread.getName} "
        )
        ps.print(name)
        ps.print(" - ")
        ps.println(msg)
      }
    }
  else Applicative[F].unit

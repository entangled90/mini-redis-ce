import fs2._
import cats.syntax.all._
import Protocol._
import cats._
import scala.util.Try

enum Protocol:
  case Simple(s: String)
  case Error(msg: String)
  case Bulk(b: Chunk[Byte])
  case Integer(i: Int)
  case Arr(arr: Vector[Protocol])
  case Nil

  def bytes: Chunk[Byte] = this match
    case Simple(s) =>
      Chunk.array(s"+$s\r\n".getBytes)
    case Error(msg) =>
      Chunk.array(s"-$msg\r\n".getBytes)
    case Integer(i) =>
      Chunk.array(s":$i\r\n".getBytes)
    case Arr(elements) =>
      Chunk.Queue(
        Chunk.array(s"*${elements.length}\r\n".getBytes),
        Chunk.vector(elements).flatMap(_.bytes),
        carriageReturn
      )
    case Bulk(b) =>
      Chunk.Queue(
        Chunk.array(s"$$${b.size}\r\n".getBytes),
        b,
        carriageReturn
      )
    case Nil =>
      NilChunk

  override def toString: String = this match
    case Simple(s)  => "[simple]: s"
    case Error(msg) => "[error]: $msg"
    case Bulk(b) =>
      s"[bulk]: ${Try(new String(b.toArray)).getOrElse(b.toString)}"
    case Nil        => "Nil"
    case Integer(i) => "[int]: i.toString"
    case Arr(arr)   => s"[array]: ${arr.map(_.toString)}"

object Protocol:

  def bulk(s: String) = Bulk(Chunk.array(s.getBytes))

  def read[F[_]: RaiseThrowable](
      stream: Stream[F, Byte]
  ): Stream[F, Protocol] =

    def splitBy(chunk: Chunk[Byte], byte: Byte): Vector[Protocol] =
      if chunk.isEmpty then Vector.empty
      else
        chunk.indexWhere(_ == byte) match {
          case Some(idx) =>
            val (c, rest) = chunk.splitAt(idx)
            Bulk(c) +: splitBy(rest.drop(1), byte)
          case None =>
            Vector(Bulk(chunk))
        }

    def readLen(chunk: Chunk[Byte]): (Int, Chunk[Byte]) =
      val (intPart, after) = untilCarriageRet(chunk)
      (new String(intPart.toArray).toInt, after)

    // FIXME optimize
    def readInt(chunk: Chunk[Byte]) = new String(chunk.toArray).toInt

    def readOne(
        input: Chunk[Byte]
    ): ParseResultSingle =
      if input.isEmpty then Left(new Exception("Empty chunk"))
      else
        val first = input(0)
        val chunk = input.drop(1)
        first match
          case '+' =>
            val (before, after) = untilCarriageRet(chunk)
            Right(Some(Simple(new String(before.toArray))), after)
          case '-' =>
            val (before, after) = untilCarriageRet(chunk)
            Right(Some(Error(new String(before.toArray))), after)

          case ':' =>
            val (before, after) = untilCarriageRet(chunk)
            Right(Some(Integer(readInt(before))), after)
          case '$' =>
            val (len, after) = readLen(chunk)
            val (payload, finalRest) = after.splitAt(len)
            // drop \r\n
            Right(Some((Bulk(payload))), finalRest.drop(2))
          case '*' =>
            val (n, after) = readLen(chunk)
            if n == -1 then Right((Some(Nil), after))
            else
              readN(n, after).map { case (msgs, rest) =>
                (Some(Arr(msgs.toVector)), rest.drop(2))
              }
          case other =>
            val (before, after) = untilCarriageRet(input)
            val splitted = splitBy(before, ' '.toByte)
            if splitted.nonEmpty then Right(Some((Arr(splitted))), after)
            else Right(None, after)

    def readN(
        n: Int,
        chunk: Chunk[Byte]
    ): ParseResultN =
      if n == 0 then Right((Vector.empty, chunk))
      else
        readOne(chunk).flatMap { (msg, rest) =>
          readN(n - 1, rest).map { (msgs, rest) =>
            (msg.toVector ++ msgs, rest)
          }
        }

    Pull.loop { (s: Stream[F, Byte]) =>
      s.pull.uncons.flatMap {
        case Some((chunk, rest)) =>
          readOne(chunk) match {
            case Right((msg, rem)) =>
              val next = if rem.isEmpty then rest else Stream.chunk(rem) ++ rest
              msg.fold(Pull.done)(Pull.output1(_)).as(Some(next))
            case Left(ex) =>
              Pull.raiseError(ex)
          }
        case None =>
          Pull.pure(None)
      }
    }(stream).stream

  // reads until carriage returns and consumes it
  def untilCarriageRet(chunk: Chunk[Byte]): (Chunk[Byte], Chunk[Byte]) =
    chunk.indexWhere(_ == '\r') match {
      case Some(i) =>
        if chunk.size > i + 1 then
          if chunk(i + 1) == '\n' then
            val (before, after) = chunk.splitAt(i)
            (before, after.drop(2))
          else
            val (before, after) = chunk.splitAt(i + 1)
            val (before2, after2) = untilCarriageRet(after)
            (before ++ before2, after2)
        else (chunk, Chunk.empty)
      case None =>
        (chunk, Chunk.empty)
    }

  private val NilChunk = Chunk.array("$-1\r\n".getBytes)

  private val carriageReturn: Chunk[Byte] = Chunk.array("\r\n".getBytes)

  private type ParseResultT[L[_]] =
    Either[Throwable, (L[Protocol], Chunk[Byte])]

  private type ParseResultSingle = ParseResultT[Option]

  private type ParseResultN = ParseResultT[Vector]

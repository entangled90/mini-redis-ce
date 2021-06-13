import fs2._
import cats.syntax.all._
import Protocol._

enum Protocol:
  case Simple(s: String)
  case Error(msg: String)
  case Bulk(b: Array[Byte])
  case Integer(i: Int)
  case Arr(arr: Vector[Protocol])
  case Nil

  def bytes: Chunk[Byte] = this match {
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
        Chunk.array(s"$$${b.length}\r\n".getBytes),
        Chunk.array(b),
        carriageReturn
      )
    case Nil =>
      Chunk.array("$-1\r\n".getBytes)
  }

object Protocol:
  private val carriageReturn: Chunk[Byte] = Chunk.array("\r\n".getBytes)

  def read[F[_]: RaiseThrowable](
      stream: Stream[F, Byte]
  ): Stream[F, Protocol] = {

    def readLen(chunk: Chunk[Byte]): (Int, Chunk[Byte]) = {
      val (intPart, after) = untilCarriageRet(chunk)
      (new String(intPart.toArray).toInt, after)
    }

    def readOne(
        s: Stream[F, Byte]
    ): Pull[F, INothing, Option[(Protocol, Stream[F, Byte])]] =
      s.pull.uncons1.flatMap {
        case Some((first, rest)) =>
          rest.pull.uncons.flatMap {
            case Some((chunk, rest)) =>
              first match {
                case '+' =>
                  val (before, after) = untilCarriageRet(chunk)
                  Pull.pure(
                    Some(
                      (
                        Simple(new String(before.toArray)),
                        Stream.chunk(after) ++ rest
                      )
                    )
                  )
                case '-' =>
                  val (before, after) = untilCarriageRet(chunk)
                  Pull.pure(
                    Some(
                      Error(new String(before.toArray)),
                      Stream.chunk(after) ++ rest
                    )
                  )
                case ':' =>
                  val (before, after) = untilCarriageRet(chunk)
                  Pull.pure(
                    Some(
                      (
                        Integer(new String(before.toArray).toInt),
                        Stream.chunk(after) ++ rest
                      )
                    )
                  )
                case '$' =>
                  val (len, after) = readLen(chunk)
                  val (payload, finalRest) = after.splitAt(len)
                  // drop \r\n
                  Pull.pure(
                    Some(
                      (
                        Bulk(payload.toArray),
                        Stream.chunk(finalRest.drop(2)) ++ rest
                      )
                    )
                  )
                case '*' =>
                  val (n, after) = readLen(chunk)
                  if n == -1 then 
                    Pull.pure(Some(Nil, Stream.chunk(after) ++ rest))
                  else readN(n, Stream.chunk(after) ++ rest).map {
                    case (msgs, rest) =>
                      Some((Arr(msgs.toVector), rest.drop(2)))
                  }
                case invalid =>
                  Pull.pure(Some((Error(s"invalid character $invalid"), rest)))
              }
            case None =>
              readOne(rest)
          }
        case None =>
          Pull.pure(None)
      }

    def readN(
        n: Int,
        stream: Stream[F, Byte]
    ): Pull[F, INothing, (Chunk[Protocol], Stream[F, Byte])] = {
      if n == 0 then Pull.pure((Chunk.empty, stream))
      else
        readOne(stream).flatMap {
          case Some((el, rest)) =>
            readN(n - 1, rest).map { case (chunk, rest) =>
              (Chunk.singleton(el) ++ chunk, rest)
            }
          case None =>
            Pull.pure((Chunk.empty, stream))
        }
    }

    Pull.loop { (s: Stream[F, Byte]) =>
      readOne(s).flatMap { x =>
        x.traverse { case (msg, rest) => Pull.output1(msg).as(rest) }
      }
    }(stream).stream
  }

  // reads until carriage returns and consumes it
  def untilCarriageRet(chunk: Chunk[Byte]): (Chunk[Byte], Chunk[Byte]) = {
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
  }

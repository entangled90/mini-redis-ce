import Protocol._
import fs2._
import cats.effect.SyncIO
import cats.syntax.all._
import java.util.Arrays

class ProtocolTest extends munit.FunSuite:
  test("deserialize examples") {
    List(
      (
        read[SyncIO](
          Stream.chunk(Chunk(80, 73, 78, 71, 13, 10))
        ).compile.lastOrError.unsafeRunSync(),
        Protocol.Arr(Vector(Protocol.bulk("PING")))
      )
    ).map { case (obtained, expected) =>
      assertEquals(obtained, expected)
    }
  }

  test("serialize/deserialize simple") {
    val result = read[SyncIO](
      Stream.chunk(Simple("ciao").bytes)
    ).compile.toVector.unsafeRunSync()
    assertEquals(result, Vector(Simple("ciao")))
  }

  test("serialize/deserialize error") {
    val result = read[SyncIO](
      Stream.chunk(Error("ciao").bytes)
    ).compile.toVector.unsafeRunSync()
    assertEquals(result, Vector(Error("ciao")))
  }
  test("serialize/deserialize bulk") {
    val arr = Chunk.array("ciao".getBytes)
    val bulk = Bulk(arr)
    val Vector(Bulk(obtained)) =
      read[SyncIO](Stream.chunk(bulk.bytes)).compile.toVector.unsafeRunSync()
    assertEquals(obtained, arr)
  }

  test("serialize/deserialize int") {
    val obtained = read[SyncIO](
      Stream.chunk(Integer(1100).bytes)
    ).compile.toVector.unsafeRunSync()
    assertEquals(obtained, obtained, Vector(Integer(1100)))
  }

  test("serialize/deserialize array") {
    val bytes = Arr(Vector(Integer(2), Integer(3))).bytes
    val obtained =
      read[SyncIO](Stream.chunk(bytes)).compile.toVector.unsafeRunSync()
    assertEquals(obtained, Vector(Arr(Vector(Integer(2), Integer(3)))))
  }

  test("inline support") {
    val bytes = Chunk.array("GET PING PONG\r\n".getBytes)
    val obtained =
      read[SyncIO](Stream.chunk(bytes)).compile.lastOrError.unsafeRunSync()
    assertEquals(obtained, Arr(Vector(bulk("GET"), bulk("PING"), bulk("PONG"))))
  }

  test("pipeline support") {
    val bytes = Chunk.array("PING\r\nPING\r\nPING\r\n".getBytes)
    val obtained =
      read[SyncIO](Stream.chunk(bytes)).compile.toVector.unsafeRunSync()
    val ping = Arr(Vector(Protocol.bulk("PING")))
    assertEquals(obtained, Vector(ping, ping, ping))
  }

  test("Carriage return") {
    val (before, after) =
      untilCarriageRet(Chunk.array("ciao\rciao\r\n".getBytes))
    assertEquals(new String(before.toArray), "ciao\rciao")
  }

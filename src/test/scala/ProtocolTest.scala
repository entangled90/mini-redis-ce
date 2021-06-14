import Protocol._
import fs2._
import cats.effect.SyncIO
import java.util.Arrays

class ProtocolTest extends munit.FunSuite:
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
    assertEquals(arr, obtained)
  }

  test("serialize/deserialize int") {
    val obtained = read[SyncIO](
      Stream.chunk(Integer(1100).bytes)
    ).compile.toVector.unsafeRunSync()
    assertEquals(Vector(Integer(1100)), obtained)
  }

  test("serialize/deserialize array") {
    val bytes = Arr(Vector(Integer(2), Integer(3))).bytes
    val obtained =
      read[SyncIO](Stream.chunk(bytes)).compile.toVector.unsafeRunSync()
    assertEquals(Vector(Arr(Vector(Integer(2), Integer(3)))), obtained)
  }

  test("Carriage return") {
    val (before, after) =
      untilCarriageRet(Chunk.array("ciao\rciao\r\n".getBytes))
    assertEquals(new String(before.toArray), "ciao\rciao")
  }

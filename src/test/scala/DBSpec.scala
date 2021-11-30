import munit.CatsEffectSuite
import cats.effect._
import cats.syntax.all._
import scala.concurrent.duration._

class DBSpec extends BaseSuite {
  test("get/put/remove no expire") {
    DB.ref[IO]().use { db =>
      for {
        _ <- db.set("ciao", Protocol.Simple("darwin"))
        res <- db.get("ciao")
        _ <- db.remove("ciao")
        empty <- db.get("ciao")
      } yield {
        assertEquals(res, Some(Protocol.Simple("darwin")))
        assertEquals(empty, None)
      }
    }
  }
  
  test("Basic pub/sub") {
    DB.ref[IO]()
      .use { db =>
        val subscribe = db.subscribe("key")
        val publish = (1 to 3).toList
          .traverse(i => db.publish("key", Protocol.Simple(s"v$i")))
        (subscribe, subscribe).tupled
          .use { case (s1, s2) =>
            (
              s1.take(3).compile.toVector,
              s2.take(3).compile.toVector
            ).parTupled.background
              .use { outcome =>
                publish >> outcome >>= (o => o.embedNever)
              }
          }
          .map { case (v1, v2) =>
            assertEquals(v1, v2)
          }
          .timeout(10.seconds)
      }
  }
}

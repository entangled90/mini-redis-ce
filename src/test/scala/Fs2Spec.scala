import munit.CatsEffectSuite
import cats.effect.IO
import fs2._
import scala.concurrent.duration._

class Fs2Spec extends BaseSuite {
  test("parJoinUnbounded") {
    Stream
      .range(1, 10)
      .map(i =>
        Stream
          .sleep[IO](500.millis)
          .onFinalizeCase(exitCase => IO.delay(println(s"stopping $i with reason $exitCase")))
      )
      .parJoinUnbounded
      .onFinalizeCase(exitCase => IO.delay(s"Main stream terminated with $exitCase"))
      .compile
      .drain
      .background
      .use(x => x)

  }
}

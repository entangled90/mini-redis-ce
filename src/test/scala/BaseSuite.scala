import munit.CatsEffectSuite
import cats.effect._
import cats.syntax.all._
import scala.concurrent.duration._

class BaseSuite extends CatsEffectSuite {

  given logInstance: Logger.Instance[IO] = Logger.Instance.simple[IO]
}

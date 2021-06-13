import scala.concurrent.duration.{given}
import cats.effect._
import fs2.io.net._
import java.net.ConnectException

object Networking {

  // def server[F[_]: Concurrent: Network](host: String, port: Int= 5005) = {
  //   Network[F].server(port = Some(port)).map{client =>

  //   }
  // }
}

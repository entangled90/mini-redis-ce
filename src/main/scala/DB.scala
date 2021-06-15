import fs2._
import fs2.concurrent.Topic
import cats._
import cats.effect._
import cats.syntax.all._
import cats.effect.implicits._
import DB._
import cats.effect.std.Queue
import scala.concurrent.duration._
import Logger._

trait DB[F[_]]:
  def get(k: Key, expireAt: Option[Long] = None): F[Option[Value]]
  def remove(k: Key): F[Unit]
  def set(k: Key, v: Value, expireAt: Option[Long] = None): F[Unit]
  def subscribe(k: Key): Resource[F, Stream[F, Value]]
  def publish(k: Key, v: Value): F[Unit]

object DB:
  type Key = String
  type Value = Protocol

  private final case class Entry(data: Value, id: Long, expiresAt: Option[Long])

  private[DB] final case class State[F[_]](
      entries: Map[Key, Entry],
      pubSub: Map[Key, Topic[F, Value]],
      nextId: Long
  )
  object State:
    private[DB] def empty[F[_]] = State[F](Map.empty, Map.empty, Long.MinValue)

  def create[F[_]: Async: Temporal: Clock: Spawn](
      queueSize: Int = 1024,
      maxQueued: Int = 1024
  ): Resource[F, DB[F]] =
    for {
      ref <- Resource.eval(Ref.of(State.empty[F]))
      db <- Resource.make(RefDB(ref, queueSize, maxQueued).pure[F])(_.close)
    } yield db

  private final class RefDB[F[_]: Async: Temporal: Clock](
      ref: Ref[F, State[F]],
      queueSize: Int = 1024,
      maxQueued: Int = 1024
  ) extends DB[F]:
    given name: LoggerName = LoggerName.forClass[RefDB[F]]

    def get(k: Key, expireAt: Option[Long] = None): F[Option[Value]] = {
      ref
        .modify { s =>
          s.entries.get(k) match {
            case Some(entry) =>
              expireAt
                .map { at =>
                  entry.expiresAt match {
                    case Some(previous) if previous > at =>
                      (s, entry.some)
                    case _ =>
                      (
                        s.copy(entries =
                          s.entries + (k -> entry
                            .copy(expiresAt = Some(at)))
                        ),
                        entry.some
                      )
                  }
                }
                .getOrElse((s, entry.some))
            case None =>
              (s, None)
          }
        }
        .flatMap {
          _.traverse { entry =>
            if entry.expiresAt == expireAt then
              scheduleExpire(k, entry).start.as(entry.data)
            else entry.data.pure[F]
          }
        }
    }
      .flatTap(r => log(LogLevel.Debug, s"GET $k $r expireAt=$expireAt"))

    def remove(k: Key): F[Unit] =
      ref
        .update(s => s.copy(entries = s.entries - k))
        .flatTap(_ => log(LogLevel.Debug, s"REMOVE $k"))

    def set(k: Key, v: Value, expireAt: Option[Long] = None): F[Unit] =
      ref
        .update(s =>
          s.copy(
            entries = s.entries + (k -> Entry(v, s.nextId, expireAt)),
            nextId = s.nextId + 1
          )
        )
        .flatTap(_ => log(LogLevel.Debug, s"SET $k $v expireAt=$expireAt"))

    //FIXME remove topic when there are no subscriebrs left
    def subscribe(k: Key): Resource[F, Stream[F, Value]] = Resource
      .eval {
        for {
          newTopic <- Topic[F, Value]
          topic <- ref.modify(s => getOrInsert(k, s, newTopic))
          // do it only if there was no subscriber
          _ <- Applicative[F].whenA(topic == newTopic) {
            // remove from map when subscribers hits 0
            topic.subscribers
              .dropWhile(_ < 1)
              .filter(_ == 0)
              .head
              .evalMap { _ =>
                ref.update(s =>
                  s.pubSub.get(k) match {
                    // remove from map only if it's the same instance, otherwise we may have come too late
                    case Some(t) if t == topic =>
                      s.copy(pubSub = s.pubSub - k)
                    case _ =>
                      s
                  }
                )
              }
              .compile
              .drain
              .start
          }
        } yield topic
      }
      .evalTap(topic =>
        topic.subscribers.head.compile.lastOrError.flatMap(active =>
          log(LogLevel.Debug, s"SUBSCRIBE $k active=$active")
        )
      )
      .flatMap(_.subscribeAwait(maxQueued))

    def publish(k: Key, v: Value): F[Unit] =
      ref.get
        .flatMap(_.pubSub.get(k).traverse(_.publish1(v)))
        .flatTap(topic =>
          log(
            LogLevel.Debug,
            s"PUBLISH $k $v subscribers-active=${topic.isDefined}"
          )
        )
        .void

    def close: F[Unit] =
      ref.get
        .flatMap(_.pubSub.values.toVector.parTraverse_ {
          _.close.attempt
        })
        .flatTap(_ => log(LogLevel.Info, "CLOSE ALL"))

    private def getOrInsert(
        k: Key,
        state: State[F],
        newTopic: Topic[F, Value]
    ): (State[F], Topic[F, Value]) =
      state.pubSub.get(k) match {
        case Some(topic) =>
          (state, topic)
        case None =>
          (
            state.copy(pubSub = state.pubSub + (k -> newTopic)),
            newTopic
          )
      }
    private def scheduleExpire(k: Key, entry: Entry): F[Unit] = {
      val removeIfSameEntry = get(k, None).flatMap {
        _.traverse { updEntry =>
          if updEntry == entry then remove(k)
          else Applicative[F].unit
        }
      }
      entry.expiresAt.traverse { at =>
        for {
          now <- Clock[F].realTimeInstant
          _ <- Temporal[F]
            .delayBy(removeIfSameEntry, (at - now.toEpochMilli).millis)
        } yield ()
      }.void
    }

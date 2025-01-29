package saferis

import zio.*

import java.sql.Connection
import scala.annotation.experimental

// alias for Transactor
type xa = Transactor
val xa = Transactor

/** A transactor that can run ZIO effects that require a connection provider and a scope.
  *
  * @param cp
  * @param config
  */
final case class Transactor(cp: ConnectionProvider, config: Connection => Unit):
  /** Run a ZIO effect that requires a connection provider and a scope.
    *
    * This method will provide the connection provider and the scope to the ZIO effect.
    *
    * This is intended for statements that do not require a transaction.
    *
    * @param zio
    * @tparam A
    * @return
    */
  def run[A](zio: ZIO[ConnectionProvider & Scope, Throwable, A]): IO[Throwable, A] =
    ZIO.scoped(zio).provideLayer(ZLayer.succeed(cp))

  /** Run a ZIO effect that requires a connection provider and a scope. The connection will be configured to run
    * statements in a transaction.
    *
    * @param zio
    * @return
    */
  def transact[A](zio: ZIO[ConnectionProvider & Scope, Throwable, A]): IO[Throwable, A] =
    val transaction = for
      connection <- cp.getConnection
      _ = config(connection)
      _ = connection.setAutoCommit(false)
      result <- zio.provideSomeLayer[Scope](ZLayer.succeed(ConnectionProvider.FromConnection(connection)))
      _      <- ZIO.attempt(connection.commit())
    yield result
    ZIO.scoped(transaction)
  end transact

end Transactor

object Transactor:
  def run[A](zio: ZIO[ConnectionProvider & Scope, Throwable, A])      = ZIO.serviceWithZIO[Transactor](_.run(zio))
  def transact[A](zio: ZIO[ConnectionProvider & Scope, Throwable, A]) = ZIO.serviceWithZIO[Transactor](_.transact(zio))

  val defaultConfig = ZLayer.succeed((_: Connection) => ())
  val layer         = ZLayer.derive[Transactor]
  val default       = defaultConfig >>> layer
  def withConfig(config: Connection => Unit): ZLayer[ConnectionProvider, Nothing, Transactor] =
    ZLayer.succeed(config) >>> layer
end Transactor

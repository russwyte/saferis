package saferis

import zio.*

import java.sql.Connection

/** A transactor that can run ZIO effects that require a connection provider and a scope.
  *
  * @param connectionProvider
  * @param config
  *   the configuration function (mutation) to apply to the connection before it is used
  */
final class Transactor(connectionProvider: ConnectionProvider, config: Connection => Unit):
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
  def run[A](zio: ZIO[ConnectionProvider & Scope, Throwable, A])(using Trace): IO[Throwable, A] =
    ZIO.scoped(zio).provideLayer(ZLayer.succeed(connectionProvider))

  /** Run a ZIO effect that requires a connection provider and a scope. The connection will be configured to run
    * statements in a transaction.
    *
    * @param zio
    * @return
    */
  def transact[A](zio: ZIO[ConnectionProvider & Scope, Throwable, A])(using Trace): IO[Throwable, A] =
    val transaction = for
      connection <- connectionProvider.getConnection
      _ = config(connection)
      _ = connection.setAutoCommit(false)
      result <- zio
        .provideSomeLayer[Scope](ZLayer.succeed(ConnectionProvider.FromConnection(connection))) <* ZIO
        .attempt(connection.commit())
        .catchNonFatalOrDie: e =>
          connection.rollback()
          ZIO.fail(e)
    yield result
    ZIO.scoped(transaction)
  end transact

end Transactor

object Transactor:
  val defaultConfig = ZLayer.succeed((_: Connection) => ())
  val layer         = ZLayer.derive[Transactor]
  val default       = defaultConfig >>> layer

  /** Allows you to configure (mutate) the connection before it is used in a transaction etc
    *
    * @param config
    * @return
    */
  def withConfig(config: Connection => Unit): ZLayer[ConnectionProvider, Nothing, Transactor] =
    ZLayer.succeed(config) >>> layer
end Transactor

package saferis

import zio.*

import java.sql.Connection

/** A transactor that can run ZIO effects that require a connection provider and a scope.
  *
  * @param connectionProvider
  * @param config
  * @param semaphore
  *   allows us to limit the number of connections that can be used concurrently. If None, then no limit the
  *   configuration function (mutation) to apply to the connection before it is used
  */
final class Transactor(
    connectionProvider: ConnectionProvider,
    config: Connection => Unit,
    semaphore: Option[Semaphore],
):
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
    ZIO
      .scoped:
        ZIO
          .blocking:
            semaphore.fold(zio):
              _.withPermit(zio)
          .provideSomeLayer[Scope](ZLayer.succeed(connectionProvider))

  /** Run a ZIO effect that requires a connection provider and a scope. The connection will be configured to run
    * statements in a transaction.
    *
    * @param zio
    * @return
    */
  def transact[A](zio: ZIO[ConnectionProvider & Scope, Throwable, A])(using Trace): IO[Throwable, A] =
    val transaction = for
      connection <- connectionProvider.getConnection.map: con =>
        config(con)
        con.setAutoCommit(false)
        con
      result <- zio
        .provideSomeLayer[Scope](ZLayer.succeed(ConnectionProvider.FromConnection(connection))) <* ZIO
        .attempt(connection.commit())
        .catchNonFatalOrDie: e =>
          ZIO.attempt(connection.rollback()) *> ZIO.fail(e)
    yield result
    ZIO.scoped:
      ZIO
        .blocking:
          semaphore.fold(transaction):
            _.withPermit(transaction)
  end transact

end Transactor

object Transactor:
  val defaultConfig = ZLayer.succeed((_: Connection) => ())
  val layer =
    ZLayer.derive[Transactor]
  val noSemaphore: ULayer[Option[Semaphore]] = ZLayer.succeed(None)
  val default                                = defaultConfig ++ noSemaphore >>> layer
  def configLayer(config: Connection => Unit) =
    ZLayer.succeed(config)
  def semaphoreLayer(permitCount: Long) =
    ZLayer:
      Semaphore.make(permitCount).map(Some(_))

  /** Construct a transactor layer.
    *
    * @param config
    *   configuration function (mutation) to apply to the connection before it is used - default is no-op
    * @param maxConcurrency
    *   the maximum number of connections that can be used concurrently. default is no limit (-1L)
    * @return
    */
  def layer(
      config: Connection => Unit = _ => (),
      maxConcurrency: Long = -1L,
  ): ZLayer[ConnectionProvider, Nothing, Transactor] = Transactor.configLayer(config) ++
    (if maxConcurrency < 1L then Transactor.noSemaphore
     else Transactor.semaphoreLayer(maxConcurrency)) >>> Transactor.layer
end Transactor

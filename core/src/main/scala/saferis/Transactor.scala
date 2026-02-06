package saferis

import zio.*

import java.sql.Connection

type Configurator = Connection => Unit

/** A transactor that can run ZIO effects that require a connection provider and a scope.
  *
  * @param connectionProvider
  *   provides JDBC connections
  * @param configurator
  *   configuration function (mutation) to apply to the connection before it is used
  * @param semaphore
  *   optional concurrency limiter. Use for SQLite/embedded databases or direct JDBC without pooling. Avoid with
  *   connection pools like HikariCP - they handle queuing more efficiently.
  */
final class Transactor(
    connectionProvider: ConnectionProvider,
    configurator: Configurator,
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
  def run[A](zio: ZIO[ConnectionProvider & Scope, SaferisError, A])(using Trace): IO[SaferisError, A] =
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
  def transact[A](zio: ZIO[ConnectionProvider & Scope, SaferisError, A])(using Trace): IO[SaferisError, A] =
    val transaction = for
      connection <- connectionProvider.getConnection
        .mapError(SaferisError.ConnectionError(_))
        .map: con =>
          configurator(con)
          con.setAutoCommit(false)
          con
      result <- zio
        .provideSomeLayer[Scope](ZLayer.succeed(ConnectionProvider.FromConnection(connection))) <* ZIO
        .attempt(connection.commit())
        .mapError(SaferisError.fromThrowable(_))
        .catchAll: e =>
          ZIO.attempt(connection.rollback()).ignore *> ZIO.fail(e)
    yield result
    ZIO.scoped:
      ZIO
        .blocking:
          semaphore.fold(transaction):
            _.withPermit(transaction)
  end transact

end Transactor

object Transactor:
  val layer: URLayer[ConnectionProvider & Configurator & Option[Semaphore], Transactor] =
    ZLayer.derive[Transactor]

  private def semaphoreLayer(permitCount: Long): ULayer[Option[Semaphore]] =
    ZLayer:
      Semaphore.make(permitCount).map(Some(_))

  /** Construct a Transactor layer.
    *
    * @param configurator
    *   configuration function (mutation) to apply to the connection before it is used - default is no-op
    * @param maxConcurrency
    *   Application-level concurrency limit using a ZIO Semaphore. Default is no limit (-1L).
    *
    * '''When to use:'''
    *   - SQLite or other embedded databases without connection pooling
    *   - Direct JDBC connections without a pool
    *   - When you need concurrency limits below pool size for backpressure
    *
    * '''When NOT to use:'''
    *   - With HikariCP or similar connection pools. The pool handles queuing more efficiently and HikariCP specifically
    *     recommends letting threads wait on the pool rather than limiting concurrency externally. Using a semaphore
    *     with a pool creates double-queuing and adds overhead in high-contention scenarios.
    * @return
    */
  def layer(
      configurator: Configurator = _ => (),
      maxConcurrency: Long = -1L,
  ): URLayer[ConnectionProvider, Transactor] = ZLayer.succeed(configurator) ++
    (if maxConcurrency < 1L then ZLayer.succeed(None)
     else semaphoreLayer(maxConcurrency)) >>> Transactor.layer

  val default: URLayer[ConnectionProvider, Transactor] = layer()

end Transactor

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
  * @param defaultTimeout
  *   optional JDBC statement timeout applied to every statement run through this Transactor. Can be overridden for a
  *   scope by `Saferis.queryTimeout(d)`. `None` means no default cap.
  * @param retryClassifier
  *   classifier deciding whether a thrown exception represents a transient, retryable failure. Such failures surface as
  *   `SaferisError.Retryable`, which the application can use to drive `ZIO.retry` policies. Defaults to a no-op
  *   (everything non-retryable); pass the active dialect's `retryClassifier` (or a user-supplied function) to enable.
  */
final class Transactor(
    connectionProvider: ConnectionProvider,
    configurator: Configurator,
    semaphore: Option[Semaphore],
    defaultTimeout: Option[Duration] = None,
    retryClassifier: SaferisError.RetryClassifier = _ => false,
):

  private val effectiveProvider: ConnectionProvider =
    ConnectionProvider.WithOverrides(connectionProvider, defaultTimeout, retryClassifier)

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
          .provideSomeLayer[Scope](ZLayer.succeed(effectiveProvider))

  /** Run a ZIO effect that requires a connection provider and a scope. The connection will be configured to run
    * statements in a transaction.
    *
    * @param zio
    * @return
    */
  def transact[A](zio: ZIO[ConnectionProvider & Scope, SaferisError, A])(using Trace): IO[SaferisError, A] =
    val transaction = for
      connection <- effectiveProvider.getConnection
        .mapError(SaferisError.ConnectionError(_))
        .map: con =>
          configurator(con)
          con.setAutoCommit(false)
          con
      result <- zio
        .provideSomeLayer[Scope](
          ZLayer.succeed(ConnectionProvider.FromConnection(connection, defaultTimeout, retryClassifier))
        ) <* ZIO
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

  private def semaphoreLayer(permitCount: Long): ULayer[Option[Semaphore]] =
    ZLayer:
      Semaphore.make(permitCount).map(Some(_))

  /** Construct a Transactor layer.
    *
    * @param configurator
    *   configuration function (mutation) to apply to the connection before it is used - default is no-op
    * @param maxConcurrency
    *   Application-level concurrency limit using a ZIO Semaphore. Default is no limit (-1L).
    * @param defaultTimeout
    *   Optional JDBC statement timeout applied to every statement run through this Transactor. Override for a scope
    *   with `Saferis.queryTimeout(d)`. Default is `None`.
    * @param retryClassifier
    *   Optional override of the active dialect's classifier for transient, retryable failures. When `None`, the
    *   `summon[Dialect].retryClassifier` default is used. Pass a function (e.g. one that recognizes a driver-specific
    *   transport error code) to extend or replace the default.
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
      defaultTimeout: Option[Duration] = None,
      retryClassifier: Option[SaferisError.RetryClassifier] = None,
  )(using dialect: Dialect): URLayer[ConnectionProvider, Transactor] =
    val semLayer: ULayer[Option[Semaphore]] =
      if maxConcurrency < 1L then ZLayer.succeed(None) else semaphoreLayer(maxConcurrency)
    val classifier = retryClassifier.getOrElse(dialect.retryClassifier)
    ZLayer.makeSome[ConnectionProvider, Transactor](
      semLayer,
      ZLayer.fromFunction((cp: ConnectionProvider, s: Option[Semaphore]) =>
        Transactor(cp, configurator, s, defaultTimeout, classifier)
      ),
    )
  end layer

  def default(using Dialect): URLayer[ConnectionProvider, Transactor] = layer()

end Transactor

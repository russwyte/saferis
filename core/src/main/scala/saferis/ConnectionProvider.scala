package saferis

import zio.*

import java.sql.Connection
import javax.sql.DataSource

/** A service that provides a connection. We can use this to provide a connection to a ZIO effect. Sometime we want to
  * provide a connection from a DataSource, and sometimes we want to provide a connection directly.
  */
trait ConnectionProvider:
  def getConnection(using Trace): ZIO[Scope, Throwable, Connection]

  /** Default JDBC statement timeout applied to every query executed through this provider.
    *
    * `None` means no provider-level default; per-fragment `withTimeout` and the `Saferis.queryTimeout` aspect can still
    * set timeouts. Override on impls that want to surface a Transactor-wide default.
    */
  def defaultQueryTimeout: Option[Duration] = None

  /** Classifier deciding whether a thrown exception represents a transient, retryable failure.
    *
    * Defaults to a no-op (everything non-retryable). The Transactor populates this from the active Dialect's
    * `retryClassifier`, with an optional user override.
    */
  def retryClassifier: SaferisError.RetryClassifier = _ => false
end ConnectionProvider

object ConnectionProvider:
  /** A connection provider that provides a connection directly.
    *
    * This is handy when we want configure the connection ourselves and then use it in one or more ZIO effects.
    *
    * @param connection
    */
  final case class FromConnection(
      connection: Connection,
      override val defaultQueryTimeout: Option[Duration] = None,
      override val retryClassifier: SaferisError.RetryClassifier = _ => false,
  ) extends ConnectionProvider:
    // note we don't use acquire and release here because we did not acquire the connection - it was provided to us
    private val acquire                     = ZIO.succeed(connection)
    override def getConnection(using Trace) = acquire

  /** A connection provider that provides a connection from a DataSource.
    *
    * @param dataSource
    */
  final case class FromDataSource(dataSource: DataSource) extends ConnectionProvider:
    private val acquire = ZIO.attempt(dataSource.getConnection())
    private val release = (con: Connection) =>
      val res =
        if con == null then ZIO.unit
        else if con.isClosed() then ZIO.unit
        else ZIO.attempt(con.close())
      res.orDie
    override def getConnection(using Trace) = ZIO.acquireRelease(acquire)(release)
  end FromDataSource
  object FromDataSource:
    val layer: ZLayer[DataSource, Nothing, ConnectionProvider] = ZLayer.derive[FromDataSource]

  /** Wraps an existing provider, overriding the default query timeout and/or retry classifier. */
  final case class WithOverrides(
      underlying: ConnectionProvider,
      override val defaultQueryTimeout: Option[Duration] = None,
      override val retryClassifier: SaferisError.RetryClassifier = _ => false,
  ) extends ConnectionProvider:
    override def getConnection(using Trace) = underlying.getConnection
end ConnectionProvider

package saferis

import zio.*

import java.sql.Connection
import javax.sql.DataSource

/** A service that provides a connection. We can use this to provide a connection to a ZIO effect. Sometime we want to
  * provide a connection from a DataSource, and sometimes we want to provide a connection directly.
  */
trait ConnectionProvider:
  def getConnection(using Trace): ZIO[Scope, Throwable, Connection]

object ConnectionProvider:
  /** A connection provider that provides a connection directly.
    *
    * This is handy when we want configure the connection ourselves and then use it in one or more ZIO effects.
    *
    * @param connection
    */
  final case class FromConnection(connection: Connection) extends ConnectionProvider:
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
end ConnectionProvider

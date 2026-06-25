package saferis.docs

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import saferis.*
import zio.*

final class DocsTransactor extends java.util.function.Supplier[AutoCloseable]:
  def get(): AutoCloseable = () => DocsTransactor.container.stop()

object DocsTransactor:
  val container: PostgreSQLContainer[?] =
    val c = new PostgreSQLContainer("postgres:16")
    c.withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
    c.start()
    c

  val transactor: Transactor =
    val dataSource = PGSimpleDataSource()
    dataSource.setURL(container.getJdbcUrl())
    dataSource.setUser(container.getUsername())
    dataSource.setPassword(container.getPassword())
    val connectionProvider: ConnectionProvider = ConnectionProvider.FromDataSource(dataSource)
    Transactor(connectionProvider, _ => (), None)
  end transactor

  val transactorLayer: ZLayer[Any, Nothing, Transactor] =
    ZLayer.succeed(transactor)
end DocsTransactor

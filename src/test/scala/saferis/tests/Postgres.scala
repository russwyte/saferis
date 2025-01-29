package saferis.tests

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import saferis.ConnectionProvider
import zio.*

import javax.sql.DataSource

final case class ContainerConfig(
    initScriptPath: String = "init.sql",
    imageName: String = s"${PostgreSQLContainer.IMAGE}:latest",
)
object ContainerConfig:
  val default = ZLayer.succeed(ContainerConfig())

final case class Postgres(
    config: ContainerConfig
):
  val postgres: PostgreSQLContainer[?] =
    val container: PostgreSQLContainer[?] =
      new PostgreSQLContainer(config.imageName)
        // Disable password checks
        .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
    container.withInitScript(config.initScriptPath)
    container

  def start: Postgres =
    postgres.start()
    this

  val stop: UIO[Unit] =
    ZIO.succeed:
      postgres.stop()
end Postgres

object Postgres:
  val base = ZLayer.derive[Postgres]
  val layer = base >>> ZLayer.scoped:
    ZIO.acquireRelease(ZIO.service[Postgres].map(_.start))(_.stop)
  val default = ContainerConfig.default >>> layer

final case class DataSourceProvider(container: Postgres):
  import container.postgres

  def dataSource =
    val ds = PGSimpleDataSource()
    ds.setURL(postgres.getJdbcUrl())
    ds.setUser(postgres.getUsername())
    ds.setPassword(postgres.getPassword())
    ds
end DataSourceProvider

object DataSourceProvider:
  private val base = ZLayer.derive[DataSourceProvider]
  val datasource: URLayer[Postgres, DataSource] =
    base.flatMap(l => ZLayer.succeed(l.get.dataSource))
  val provider: ZLayer[Postgres, Nothing, ConnectionProvider] =
    DataSourceProvider.datasource >>> ConnectionProvider.FromDataSource.layer
  val default = Postgres.default >>> provider

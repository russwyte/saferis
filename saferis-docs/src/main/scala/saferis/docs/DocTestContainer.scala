package saferis.docs

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import saferis.*
import zio.*

/** Helper for running examples in documentation with a real PostgreSQL database.
  *
  * Usage in mdoc:
  * {{{
  * import saferis.docs.DocTestContainer
  *
  * val xa = DocTestContainer.transactor
  *
  * // Run queries
  * DocTestContainer.run {
  *   xa.run(sql"SELECT 1".queryOne[Int])
  * }
  * }}}
  */
object DocTestContainer:
  // Lazy initialization - container starts on first access
  private lazy val container: PostgreSQLContainer[?] =
    val c = new PostgreSQLContainer("postgres:16")
    c.withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
    c.start()
    c

  private lazy val dataSource: PGSimpleDataSource =
    val ds = PGSimpleDataSource()
    ds.setURL(container.getJdbcUrl())
    ds.setUser(container.getUsername())
    ds.setPassword(container.getPassword())
    ds

  private lazy val connectionProvider: ConnectionProvider =
    ConnectionProvider.FromDataSource(dataSource)

  /** A transactor connected to the test PostgreSQL container. */
  lazy val transactor: Transactor =
    Transactor(connectionProvider, _ => (), None)

  /** Run a ZIO effect using the test transactor.
    *
    * This is a convenience method for mdoc examples.
    */
  def run[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(effect).getOrThrow()
    }

  /** Run an effect that needs a ConnectionProvider. */
  def runWithConnection[A](effect: ZIO[ConnectionProvider & Scope, Throwable, A]): A =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(
          ZIO.scoped {
            effect.provideSome[Scope](ZLayer.succeed(connectionProvider))
          }
        )
        .getOrThrow()
    }
end DocTestContainer

package saferis.docs

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import saferis.*
import zio.*

final class DocsTransactor extends java.util.function.Supplier[AutoCloseable]:
  // Start the container here, during marklit's run-resource acquisition, rather
  // than lazily on first block access. Two reasons:
  //  - Honour marklit's contract that get() performs setup. If we only return
  //    the teardown closure, the container starts inside object <clinit> on the
  //    first block that reads `transactor`; a start failure there poisons
  //    DocsTransactor's static init and every later block fails with the opaque
  //    "Could not initialize class saferis.docs.DocsTransactor$".
  //  - testcontainers discovers its Docker strategy via ServiceLoader, which
  //    uses the *thread context* classloader. Under marklit the executing
  //    thread's TCCL is marklit's own loader (no docs classpath, so no
  //    testcontainers META-INF/services); on a Linux/unix-socket CI runner that
  //    discovery finds nothing and start() throws. We pin the TCCL to this
  //    resource's own loader (which carries the docs classpath) for the start.
  def get(): AutoCloseable =
    val thread   = Thread.currentThread()
    val previous = thread.getContextClassLoader()
    thread.setContextClassLoader(getClass.getClassLoader())
    try
      val c = DocsTransactor.container // force the start now, under the right TCCL
      () => c.stop()
    finally thread.setContextClassLoader(previous)
  end get
end DocsTransactor

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

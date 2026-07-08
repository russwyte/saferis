# Getting Started

[← Back to index](index.md)

## Installation

Add Saferis to your `build.sbt`:

```scala marklit:passthrough
libraryDependencies += "rocks.earlyeffect" %% "saferis" % "0.18.0"
```

Saferis requires ZIO as a provided dependency:

```scala marklit:passthrough
libraryDependencies += "dev.zio" %% "zio" % "2.1.24"
```

## Quick Example

Saferis operations are plain ZIO effects. Throughout these docs the examples are
real `ZIO` programs run against a live PostgreSQL database — `xa` is a `Transactor`
connected to a test container, and `.debug` prints each effect's result:

```scala marklit:silent,id=quickstart
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("quick_users")
case class QuickUser(@generated @key id: Int, name: String, email: String) derives Table
```

Define a table with the `Table` typeclass, then create it, insert rows, and query
it — all type-safe, all against a real database:

```scala marklit:zio-app,extends=quickstart
xa.run(for
  _     <- ddl.createTable[QuickUser](ifNotExists = true)
  _     <- dml.insert(QuickUser(-1, "Alice", "alice@example.com"))
  _     <- dml.insert(QuickUser(-1, "Bob", "bob@example.com"))
  users <- sql"SELECT * FROM ${Table[QuickUser]}".query[QuickUser]
yield users).debug("users")
```

## Anatomy of an Application

In a real application you provide the `Transactor` as a layer and let your
`ZIOAppDefault` run the program — `xa.run(...)` turns a database program into an
ordinary `ZIO` effect:

```scala marklit:compile-only
import saferis.*
import zio.*
import javax.sql.DataSource

@tableName("app_users")
case class AppUser(@generated @key id: Int, name: String) derives Table

object MyApp extends ZIOAppDefault:
  val program: ZIO[Transactor, SaferisError, Chunk[AppUser]] =
    for
      xa    <- ZIO.service[Transactor]
      users <- xa.run(for
                 _     <- ddl.createTable[AppUser](ifNotExists = true)
                 _     <- dml.insert(AppUser(-1, "Alice"))
                 users <- sql"SELECT * FROM ${Table[AppUser]}".query[AppUser]
               yield users)
    yield users

  // Your DataSource (e.g. a HikariCP pool) becomes a ConnectionProvider,
  // which Transactor.layer turns into a Transactor.
  def dataSource: DataSource = ???
  val connectionProvider = ZLayer.succeed(ConnectionProvider.FromDataSource(dataSource))

  def run = program.provide(connectionProvider, Transactor.layer())
```

Next, read [Core Concepts](core-concepts.md) to understand table definitions, the
`sql"..."` interpolator, and the `Transactor`.

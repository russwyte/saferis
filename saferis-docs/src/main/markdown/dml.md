# Data Manipulation Layer (DML)

[← Back to index](index.md)

The DML layer provides CRUD operations for your tables.

```scala marklit:silent,id=dml_readonly
import saferis.*
import zio.*

@tableName("tasks")
case class Task(@generated @key id: Int, title: String, done: Boolean) derives Table

val tasks = Table[Task]
```

## Basic CRUD Operations

```scala marklit:extends=dml_readonly
// SELECT query
println(sql"SELECT * FROM $tasks WHERE ${tasks.done} = ${false}".show)
```

```scala marklit:extends=dml_readonly
// SELECT with multiple conditions
println(sql"SELECT * FROM $tasks WHERE ${tasks.title} LIKE ${"Learn%"} AND ${tasks.done} = ${false}".show)
```

## Running DML Operations

```scala marklit:silent,id=dml_live
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("tasks")
case class Task(@generated @key id: Int, title: String, done: Boolean) derives Table

val tasks = Table[Task]
```

```scala marklit:zio-app,extends=dml_live
// Full workflow with actual database
xa.run(for
  _    <- ddl.createTable[Task](ifNotExists = true)
  _    <- dml.insert(Task(-1, "Task 1", false))
  _    <- dml.insert(Task(-1, "Task 2", false))
  _    <- dml.insert(Task(-1, "Task 3", true))
  all  <- sql"SELECT * FROM $tasks".query[Task]
  done <- sql"SELECT * FROM $tasks WHERE ${tasks.done} = ${true}".query[Task]
yield (all, done)).debug("tasks")
```

## Insert with RETURNING

For databases that support it (PostgreSQL, SQLite), get the inserted row back:

```scala marklit:zio-app,extends=dml_live
xa.run(for
  _      <- ddl.createTable[Task](ifNotExists = true)
  result <- dml.insertReturning(Task(-1, "New Task", false))
yield result).debug("inserted")
```

## Custom Queries

Use the `sql` interpolator for any query:

```scala marklit:zio-app,extends=dml_live
// Query with ordering
xa.run(for
  _      <- ddl.createTable[Task](ifNotExists = true)
  _      <- dml.insert(Task(-1, "Alpha", false))
  _      <- dml.insert(Task(-1, "Beta", true))
  sorted <- sql"SELECT * FROM $tasks ORDER BY ${tasks.title}".query[Task]
yield sorted).debug("sorted")
```

## Update Operations

Update records by primary key or with custom conditions:

```scala marklit:silent,id=dml_items
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("items")
case class Item(@generated @key id: Int, name: String, quantity: Int) derives Table
val items = Table[Item]
```

```scala marklit:zio-app,extends=dml_items
xa.run(for
  _        <- ddl.createTable[Item](ifNotExists = true)
  inserted <- dml.insertReturning(Item(-1, "Widget", 10))

  // Update by primary key
  _ <- dml.update(inserted.copy(quantity = 15))

  // Update with RETURNING (get the updated row back)
  updated <- dml.updateReturning(inserted.copy(name = "Super Widget", quantity = 20))

  // Verify the update
  result <- sql"SELECT * FROM $items WHERE ${items.id} = ${inserted.id}".queryOne[Item]
yield (updated, result)).debug("updated")
```

Update multiple rows with a WHERE clause:

```scala marklit:zio-app,extends=dml_items
xa.run(for
  _ <- ddl.createTable[Item](ifNotExists = true)
  _ <- dml.insert(Item(-1, "Gadget A", 5))
  _ <- dml.insert(Item(-1, "Gadget B", 3))

  // Update all items with quantity < 10
  rowsUpdated <- dml.updateWhere(
    Item(-1, "Low Stock Item", 0),  // Values to set (id ignored)
    sql"${items.quantity} < 10"
  )

  all <- sql"SELECT * FROM $items".query[Item]
yield (rowsUpdated, all)).debug("updateWhere")
```

## Delete Operations

Delete records by primary key or with custom conditions:

```scala marklit:silent,id=dml_logs
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("logs")
case class LogEntry(@generated @key id: Int, level: String, message: String) derives Table
val logs = Table[LogEntry]
```

```scala marklit:zio-app,extends=dml_logs
xa.run(for
  _      <- ddl.createTable[LogEntry](ifNotExists = true)
  entry1 <- dml.insertReturning(LogEntry(-1, "INFO", "Application started"))
  entry2 <- dml.insertReturning(LogEntry(-1, "DEBUG", "Processing request"))
  _      <- dml.insertReturning(LogEntry(-1, "ERROR", "Something failed"))

  // Delete by primary key
  _ <- dml.delete(entry2)

  // Delete with RETURNING (get the deleted row back)
  deleted <- dml.deleteReturning(entry1)

  remaining <- sql"SELECT * FROM $logs".query[LogEntry]
yield (deleted, remaining)).debug("deleted")
```

Delete multiple rows with a WHERE clause:

```scala marklit:zio-app,extends=dml_logs
xa.run(for
  _ <- ddl.createTable[LogEntry](ifNotExists = true)
  _ <- dml.insert(LogEntry(-1, "DEBUG", "Debug 1"))
  _ <- dml.insert(LogEntry(-1, "DEBUG", "Debug 2"))
  _ <- dml.insert(LogEntry(-1, "INFO", "Important info"))

  // Delete all DEBUG entries
  rowsDeleted <- dml.deleteWhere[LogEntry](sql"${logs.level} = ${"DEBUG"}")

  // Delete with WHERE and RETURNING (get all deleted rows)
  deletedEntries <- dml.deleteWhereReturning[LogEntry](sql"${logs.level} = ${"ERROR"}")

  remaining <- sql"SELECT * FROM $logs".query[LogEntry]
yield (rowsDeleted, deletedEntries, remaining)).debug("deleteWhere")
```

## Type-Safe Mutation Builders

Saferis provides type-safe builders for INSERT, UPDATE, and DELETE operations. These builders use Scala 3 macros to extract column names at compile time.

### Insert Builder

Build INSERT statements with type-safe column selectors:

```scala marklit:silent,id=dml_builders
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("builder_users")
case class BuilderUser(@generated @key id: Int, name: String, email: String, age: Int) derives Table
```

```scala marklit:extends=dml_builders
// Type-safe INSERT builder
println(Insert[BuilderUser]
  .value(_.name, "Alice")
  .value(_.email, "alice@example.com")
  .value(_.age, 30)
  .build.sql)
```

```scala marklit:extends=dml_builders
// INSERT with RETURNING clause
println(Insert[BuilderUser]
  .value(_.name, "Bob")
  .value(_.email, "bob@example.com")
  .value(_.age, 25)
  .returning.sql)
```

### Update Builder (Builder/Ready Pattern)

The Update builder uses a **Builder/Ready pattern** to prevent accidental updates of all rows. You must either:
- Call `.where(...)` to specify which rows to update
- Call `.all` to explicitly update all rows

```scala marklit:zio-app,extends=dml_builders
xa.run(for
  _     <- ddl.createTable[BuilderUser](ifNotExists = true)
  _     <- dml.insert(BuilderUser(-1, "Alice", "alice@example.com", 30))
  _     <- dml.insert(BuilderUser(-1, "Bob", "bob@example.com", 25))
  users <- sql"SELECT * FROM ${Table[BuilderUser]}".query[BuilderUser]
yield users).debug("users")
```

```scala marklit:extends=dml_builders
// Update with type-safe WHERE clause
println(Update[BuilderUser]
  .set(_.name, "Alice Updated")
  .set(_.age, 31)
  .where(_.id).eq(1)
  .build.sql)
```

```scala marklit:extends=dml_builders
// Chain multiple WHERE conditions
println(Update[BuilderUser]
  .set(_.email, "new@example.com")
  .where(_.name).eq("Bob")
  .where(_.age).gt(20)
  .build.sql)
```

```scala marklit:extends=dml_builders
// Update with RETURNING clause
println(Update[BuilderUser]
  .set(_.age, 35)
  .where(_.id).eq(1)
  .returning.sql)
```

```scala marklit:extends=dml_builders
// Explicitly update all rows (requires .all)
println(Update[BuilderUser]
  .set(_.age, 0)
  .all  // Required - prevents accidental "UPDATE ... SET" without WHERE
  .build.sql)
```

### Delete Builder (Builder/Ready Pattern)

Like Update, the Delete builder requires either `.where(...)` or `.all`:

```scala marklit:extends=dml_builders
// Delete with type-safe WHERE clause
println(Delete[BuilderUser]
  .where(_.id).eq(1)
  .build.sql)
```

```scala marklit:extends=dml_builders
// Chain multiple WHERE conditions
println(Delete[BuilderUser]
  .where(_.age).lt(18)
  .where(_.name).neq("Admin")
  .build.sql)
```

```scala marklit:extends=dml_builders
// Delete with RETURNING clause
println(Delete[BuilderUser]
  .where(_.email).eq("old@example.com")
  .returning.sql)
```

```scala marklit:extends=dml_builders
// Explicitly delete all rows (requires .all)
println(Delete[BuilderUser]
  .all  // Required - prevents accidental "DELETE FROM ..."
  .build.sql)
```

### Available WHERE Operators

All mutation builders support these operators in WHERE clauses:

| Method | SQL | Description |
|--------|-----|-------------|
| `.eq(value)` | `= ?` | Equality |
| `.neq(value)` | `<> ?` | Not equal |
| `.lt(value)` | `< ?` | Less than |
| `.lte(value)` | `<= ?` | Less than or equal |
| `.gt(value)` | `> ?` | Greater than |
| `.gte(value)` | `>= ?` | Greater than or equal |
| `.isNull()` | `is null` | Null check |
| `.isNotNull()` | `is not null` | Non-null check |

You can also use raw `SqlFragment` for complex conditions:

```scala marklit:extends=dml_builders
// Using SqlFragment for complex WHERE
val users = Table[BuilderUser]
println(Update[BuilderUser]
  .set(_.age, 25)
  .where(sql"${users.name} LIKE ${"A%"}")
  .build.sql)
```

### Complex WHERE with OR and Grouping

Use `andWhere` with a lambda for complex conditions with OR logic:

```scala marklit:silent,id=dml_claim
import saferis.*
import zio.*

@tableName("claim_tasks")
case class ClaimTask(
  @generated @key id: Int,
  deadline: java.time.Instant,
  claimedBy: Option[String],
  claimedUntil: Option[java.time.Instant]
) derives Table
```

```scala marklit:extends=dml_claim
// Query for unclaimed or expired claims
val now = java.time.Instant.now()
println(Update[ClaimTask]
  .set(_.claimedBy, Some("worker-1"))
  .where(_.deadline).lte(now)
  .andWhere(w => w(_.claimedBy).isNull.or(_.claimedUntil).lt(Some(now)))
  .build.sql)
```

This generates: `update ... where deadline <= ? and (claimed_by is null or claimed_until < ?)`

The `andWhere` lambda provides a builder that supports:
- `w(_.column)` - Start a condition on a column
- `.isNull` / `.isNotNull` - Null checks
- `.eq(value)` / `.lt(value)` / etc. - Comparisons
- `.or(_.column)` - Chain with OR
- `.and(_.column)` - Chain with AND

Delete also supports `andWhere`:

```scala marklit:extends=dml_claim
val now2 = java.time.Instant.now()
println(Delete[ClaimTask]
  .where(_.deadline).lt(now2)
  .andWhere(w => w(_.claimedBy).isNotNull.or(_.claimedUntil).lt(Some(now2)))
  .build.sql)
```

### Type-Safe UPDATE with RETURNING

Return updated rows atomically using `returningAs`:

```scala marklit:silent,id=dml_lock
import saferis.*
import zio.*

@tableName("lock_rows")
case class LockRow(
  @key instanceId: String,
  nodeId: String,
  expiresAt: java.time.Instant
) derives Table
```

```scala marklit:extends=dml_lock
// returningAs provides type-safe query execution
val newExpiry = java.time.Instant.now().plusSeconds(60)
println(Update[LockRow]
  .set(_.expiresAt, newExpiry)
  .where(_.instanceId).eq("instance-1")
  .where(_.nodeId).eq("node-1")
  .returningAs
  .build.sql)
```

The `returningAs` method:
- Returns `ReturningQuery[A]` with type-safe `query` and `queryOne` methods
- Only compiles when the dialect supports RETURNING (PostgreSQL, SQLite)
- Uses capability constraint: requires `Dialect & ReturningSupport`

```scala marklit:compile-only
import saferis.*
import zio.*

@tableName("lock_rows")
case class LockRow(@key instanceId: String, nodeId: String, expiresAt: java.time.Instant) derives Table

// Execute and get the updated row
val result: ScopedQuery[Option[LockRow]] = Update[LockRow]
  .set(_.expiresAt, java.time.Instant.now())
  .where(_.instanceId).eq("id")
  .returningAs
  .queryOne  // Returns ScopedQuery[Option[LockRow]]
```

Delete also supports `returningAs`:

```scala marklit:extends=dml_lock
println(Delete[LockRow]
  .where(_.instanceId).eq("instance-1")
  .returningAs
  .build.sql)
```

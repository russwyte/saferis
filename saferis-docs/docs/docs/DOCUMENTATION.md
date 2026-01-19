# Saferis Documentation

A comprehensive guide to Saferis - the type-safe, resource-safe SQL client library for Scala 3 and ZIO.

## Table of Contents

- [Getting Started](#getting-started)
- [Core Concepts](#core-concepts)
- [Dialect System](#dialect-system)
- [Data Definition Layer (DDL)](#data-definition-layer-ddl)
- [Data Manipulation Layer (DML)](#data-manipulation-layer-dml)
- [Pagination](#pagination)
- [Type-Safe Capabilities](#type-safe-capabilities)

---

## Getting Started

### Installation

Add Saferis to your `build.sbt`:

```scala
libraryDependencies += "io.github.russwyte" %% "saferis" % "@VERSION@"
```

Saferis requires ZIO as a provided dependency:

```scala
libraryDependencies += "dev.zio" %% "zio" % "2.1.24"
```

### Quick Example

```scala mdoc:silent
import saferis.*
import saferis.docs.DocTestContainer
import saferis.docs.DocTestContainer.{run, transactor as xa}

// Define a table
@tableName("quick_users")
case class QuickUser(@generated @key id: Int, name: String, email: String) derives Table
```

```scala mdoc
// Create table, insert data, and query
run {
  xa.run(for
    _ <- ddl.createTable[QuickUser]()
    _ <- dml.insert(QuickUser(-1, "Alice", "alice@example.com"))
    _ <- dml.insert(QuickUser(-1, "Bob", "bob@example.com"))
    users <- sql"SELECT * FROM ${Table[QuickUser]}".query[QuickUser]
  yield users)
}
```

---

## Core Concepts

### Table Definitions

Define tables using case classes with the `Table` typeclass:

```scala mdoc:compile-only
import saferis.*

@tableName("products")
case class Product(
  @generated @key id: Long,      // Auto-generated primary key
  @indexed name: String,          // Single-column index
  @uniqueIndex sku: String,       // Unique index
  price: Double,
  inStock: Boolean = true,        // Has default value
  description: Option[String]     // Nullable
) derives Table
```

#### Annotations

| Annotation | Purpose |
|------------|---------|
| `@tableName("name")` | Specifies the SQL table name |
| `@key` | Marks a primary key column |
| `@generated` | Marks an auto-generated column (identity/auto-increment) |
| `@indexed` | Creates a single-column index |
| `@indexed("name")` | Creates a named index (same name = compound index) |
| `@indexed("name", "condition")` | Creates a partial index with WHERE clause |
| `@uniqueIndex` | Creates a unique index |
| `@uniqueIndex("name", "condition")` | Creates a partial unique index with WHERE clause |
| `@unique("name")` | Creates a named unique constraint (same name = compound) |
| `@label("column_name")` | Maps field to a different column name |

#### Automatic Column Properties

Saferis infers column properties from your Scala types:

| Scala Type | SQL Property |
|------------|--------------|
| `T` (non-Option) | `NOT NULL` |
| `Option[T]` | Nullable |
| Field with default value | `DEFAULT <value>` |

```scala mdoc:reset:silent
import saferis.*

@tableName("products")
case class Product(
  @generated @key id: Long,
  @label("product_name") name: String,  // Column is "product_name", NOT NULL
  quantity: Int = 0,                     // NOT NULL, DEFAULT 0
  price: Double,                         // NOT NULL
  notes: Option[String]                  // Nullable (no NOT NULL constraint)
) derives Table
```

```scala mdoc
// See the generated SQL with all column properties
ddl.createTableSql[Product]()
```

#### Compound Primary Keys

Use multiple `@key` annotations to create a composite primary key:

```scala mdoc:reset:silent
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("order_items")
case class OrderItem(
  @key orderId: Long,
  @key productId: Long,
  quantity: Int
) derives Table
```

```scala mdoc
// Show the generated CREATE TABLE SQL with compound primary key
ddl.createTableSql[OrderItem]()
```

```scala mdoc
// Create and use the table
run {
  xa.run(for
    _ <- ddl.createTable[OrderItem]()
    _ <- dml.insert(OrderItem(1, 100, 2))
    _ <- dml.insert(OrderItem(1, 101, 1))  // Same order, different product - OK
    _ <- dml.insert(OrderItem(2, 100, 3))  // Different order, same product - OK
    items <- sql"SELECT * FROM ${Table[OrderItem]}".query[OrderItem]
  yield items)
}
```

#### Compound Indexes and Constraints

Use the same name to group columns:

```scala mdoc:compile-only
import saferis.*

@tableName("events")
case class Event(
  @generated @key id: Long,
  @indexed("tenant_user_idx") tenantId: String,   // Part of compound index
  @indexed("tenant_user_idx") userId: Int,        // Part of compound index
  @unique("natural_key") source: String,          // Part of compound unique
  @unique("natural_key") externalId: String,      // Part of compound unique
  createdAt: java.time.Instant
) derives Table
```

### SQL Interpolation

The `sql"..."` interpolator provides SQL injection protection:

```scala mdoc:reset:silent
import saferis.*

@tableName("products")
case class Product(
  @generated @key id: Long,
  @indexed name: String,
  @uniqueIndex sku: String,
  price: Double,
  inStock: Boolean = true,
  description: Option[String]
) derives Table

val products = Table[Product]
```

```scala mdoc
// Values are safely parameterized
val minPrice = 10.0
val query = sql"SELECT * FROM $products WHERE ${products.price} > $minPrice"
query.show
```

```scala mdoc
// Table and column references are properly escaped
sql"SELECT ${products.name}, ${products.price} FROM $products WHERE ${products.inStock} = ${true}".show
```

### The Transactor

The `Transactor` wraps a `ConnectionProvider` and executes SQL operations:

```scala mdoc:compile-only
import saferis.*
import zio.*
import javax.sql.DataSource

// Assuming you have a DataSource
val dataSource: DataSource = ???

@tableName("users")
case class User(@generated @key id: Int, name: String) derives Table

// From a ConnectionProvider
val provider = ConnectionProvider.FromDataSource(dataSource)
val xa = Transactor(provider, _ => (), None)

// Execute operations
val result = xa.run(
  sql"SELECT * FROM ${Table[User]}".query[User]
)
```

---

## Dialect System

Saferis supports multiple databases with compile-time type safety. Each dialect provides database-specific SQL generation and type mappings.

### Available Dialects

```scala mdoc:compile-only
import saferis.*

// PostgreSQL (default) - full feature support
// No additional import needed - it's the default

// MySQL
import saferis.mysql.{given}

// SQLite
import saferis.sqlite.{given}

// Spark SQL
import saferis.spark.{given}
```

### Feature Comparison

| Feature | PostgreSQL | MySQL | SQLite | Spark |
|---------|------------|-------|--------|-------|
| RETURNING clause | Yes | No | Yes | No |
| JSON operations | Yes | Yes | No | No |
| Array types | Yes | No | No | No |
| UPSERT | Yes | No | No | No |
| IF NOT EXISTS (indexes) | Yes | No | Yes | Yes |
| Window functions | Yes | Yes | Yes | Yes |
| CTEs | Yes | Yes | Yes | Yes |

### Type Mappings

| JDBC Type | PostgreSQL | MySQL | SQLite |
|-----------|------------|-------|--------|
| VARCHAR | varchar(255) | varchar(255) | text |
| INTEGER | integer | int | integer |
| BIGINT | bigint | bigint | integer |
| DOUBLE | double precision | double | real |
| BOOLEAN | boolean | boolean | integer |
| TIMESTAMP | timestamp | timestamp | text |

### Auto-Increment Syntax

| Database | Syntax |
|----------|--------|
| PostgreSQL | `GENERATED ALWAYS AS IDENTITY` |
| MySQL | `AUTO_INCREMENT` |
| SQLite | `AUTOINCREMENT` |

---

## Data Definition Layer (DDL)

The DDL layer provides type-safe schema management operations.

### Creating Tables

```scala mdoc:reset:silent
import saferis.*

@tableName("customers")
case class Customer(
  @generated @key id: Long,
  @indexed name: String,
  @uniqueIndex email: String,
  status: String = "active",
  notes: Option[String]
) derives Table
```

```scala mdoc
// Show index creation SQL
ddl.createIndexesSql[Customer]()
```

### Running DDL Operations

```scala mdoc:reset:silent
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("customers")
case class Customer(
  @generated @key id: Long,
  @indexed name: String,
  @uniqueIndex email: String,
  status: String = "active",
  notes: Option[String]
) derives Table
```

```scala mdoc
// Actually create the table
run { xa.run(ddl.createTable[Customer]()) }
```

### Other DDL Operations

```scala mdoc:compile-only
import saferis.*

@tableName("customers")
case class Customer(
  @generated @key id: Long,
  @indexed name: String,
  @uniqueIndex email: String,
  status: String = "active",
  notes: Option[String]
) derives Table

// Drop table
ddl.dropTable[Customer](ifExists = true)

// Truncate table
ddl.truncateTable[Customer]()

// Add column
ddl.addColumn[Customer, String]("new_column")

// Drop column
ddl.dropColumn[Customer]("old_column")

// Drop index
ddl.dropIndex("idx_name", ifExists = true)
```

### createTable Options

The `createTable` function accepts optional parameters:

```scala mdoc:compile-only
import saferis.*

@tableName("my_table")
case class MyTable(@key id: Int, name: String) derives Table

// Default: creates table and all indexes
ddl.createTable[MyTable]()

// Skip table creation if it already exists
ddl.createTable[MyTable](ifNotExists = true)

// Create table without indexes (create them separately later)
ddl.createTable[MyTable](createIndexes = false)

// Then create indexes separately
ddl.createIndexes[MyTable]()
```

### Partial Indexes

Partial indexes only index rows that match a condition, making them smaller and faster for filtered queries.

#### Via Annotation

Use `@indexed("name", "condition")` or `@uniqueIndex("name", "condition")`:

```scala mdoc:reset:silent
import saferis.*

@tableName("tasks")
case class TaskWithIndex(
  @generated @key id: Int,
  status: String,
  @indexed("idx_pending_tasks", "status = 'pending'") priority: Int
) derives Table
```

```scala mdoc
// Show the generated index SQL with WHERE clause
ddl.createIndexesSql[TaskWithIndex]()
```

#### Via Runtime API

Create partial indexes programmatically:

```scala mdoc:reset:silent
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("jobs")
case class Job(@generated @key id: Int, status: String, retryAt: Option[java.time.Instant]) derives Table
```

```scala mdoc
run {
  xa.run(for
    _ <- ddl.createTable[Job](createIndexes = false)
    // Create a partial index for pending jobs with retry times
    _ <- ddl.createIndex[Job](
      "idx_pending_retry",
      Seq("retryat"),
      where = Some("status = 'pending'")
    )
    _ <- dml.insert(Job(-1, "pending", Some(java.time.Instant.now())))
    _ <- dml.insert(Job(-1, "completed", None))
    jobs <- sql"SELECT * FROM ${Table[Job]}".query[Job]
  yield jobs)
}
```

#### Partial Unique Indexes

Enforce uniqueness only for specific rows:

```scala mdoc:reset:silent
import saferis.*

@tableName("users")
case class UserWithPartialUnique(
  @generated @key id: Int,
  @uniqueIndex("uidx_active_email", "active = true") email: String,
  active: Boolean
) derives Table
```

```scala mdoc
// The unique constraint only applies when active = true
// Multiple inactive users can have the same email
ddl.createIndexesSql[UserWithPartialUnique]()
```

---

## Data Manipulation Layer (DML)

The DML layer provides CRUD operations for your tables.

```scala mdoc:reset:silent
import saferis.*

@tableName("tasks")
case class Task(@generated @key id: Int, title: String, done: Boolean) derives Table

val tasks = Table[Task]
```

### Basic CRUD Operations

```scala mdoc
// SELECT query
sql"SELECT * FROM $tasks WHERE ${tasks.done} = ${false}".show
```

```scala mdoc
// SELECT with multiple conditions
sql"SELECT * FROM $tasks WHERE ${tasks.title} LIKE ${"Learn%"} AND ${tasks.done} = ${false}".show
```

### Running DML Operations

```scala mdoc:reset:silent
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("tasks")
case class Task(@generated @key id: Int, title: String, done: Boolean) derives Table

val tasks = Table[Task]
```

```scala mdoc
// Full workflow with actual database
run {
  xa.run(for
    _ <- ddl.createTable[Task]()
    _ <- dml.insert(Task(-1, "Task 1", false))
    _ <- dml.insert(Task(-1, "Task 2", false))
    _ <- dml.insert(Task(-1, "Task 3", true))
    all <- sql"SELECT * FROM $tasks".query[Task]
    done <- sql"SELECT * FROM $tasks WHERE ${tasks.done} = ${true}".query[Task]
  yield (all, done))
}
```

### Insert with RETURNING

For databases that support it (PostgreSQL, SQLite), get the inserted row back:

```scala mdoc
run { xa.run(dml.insertReturning(Task(-1, "New Task", false))) }
```

### Custom Queries

Use the `sql` interpolator for any query:

```scala mdoc
// Query with ordering
run { xa.run(sql"SELECT * FROM $tasks ORDER BY ${tasks.title}".query[Task]) }
```

### Update Operations

Update records by primary key or with custom conditions:

```scala mdoc:reset:silent
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("items")
case class Item(@generated @key id: Int, name: String, quantity: Int) derives Table
val items = Table[Item]
```

```scala mdoc
run {
  xa.run(for
    _ <- ddl.createTable[Item]()
    inserted <- dml.insertReturning(Item(-1, "Widget", 10))

    // Update by primary key
    _ <- dml.update(inserted.copy(quantity = 15))

    // Update with RETURNING (get the updated row back)
    updated <- dml.updateReturning(inserted.copy(name = "Super Widget", quantity = 20))

    // Verify the update
    result <- sql"SELECT * FROM $items WHERE ${items.id} = ${inserted.id}".queryOne[Item]
  yield (updated, result))
}
```

Update multiple rows with a WHERE clause:

```scala mdoc
run {
  xa.run(for
    _ <- dml.insert(Item(-1, "Gadget A", 5))
    _ <- dml.insert(Item(-1, "Gadget B", 3))

    // Update all items with quantity < 10
    rowsUpdated <- dml.updateWhere(
      Item(-1, "Low Stock Item", 0),  // Values to set (id ignored)
      sql"${items.quantity} < 10"
    )

    all <- sql"SELECT * FROM $items".query[Item]
  yield (rowsUpdated, all))
}
```

### Delete Operations

Delete records by primary key or with custom conditions:

```scala mdoc:reset:silent
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("logs")
case class LogEntry(@generated @key id: Int, level: String, message: String) derives Table
val logs = Table[LogEntry]
```

```scala mdoc
run {
  xa.run(for
    _ <- ddl.createTable[LogEntry]()
    entry1 <- dml.insertReturning(LogEntry(-1, "INFO", "Application started"))
    entry2 <- dml.insertReturning(LogEntry(-1, "DEBUG", "Processing request"))
    _ <- dml.insertReturning(LogEntry(-1, "ERROR", "Something failed"))

    // Delete by primary key
    _ <- dml.delete(entry2)

    // Delete with RETURNING (get the deleted row back)
    deleted <- dml.deleteReturning(entry1)

    remaining <- sql"SELECT * FROM $logs".query[LogEntry]
  yield (deleted, remaining))
}
```

Delete multiple rows with a WHERE clause:

```scala mdoc
run {
  xa.run(for
    _ <- dml.insert(LogEntry(-1, "DEBUG", "Debug 1"))
    _ <- dml.insert(LogEntry(-1, "DEBUG", "Debug 2"))
    _ <- dml.insert(LogEntry(-1, "INFO", "Important info"))

    // Delete all DEBUG entries
    rowsDeleted <- dml.deleteWhere[LogEntry](sql"${logs.level} = ${"DEBUG"}")

    // Delete with WHERE and RETURNING (get all deleted rows)
    deletedEntries <- dml.deleteWhereReturning[LogEntry](sql"${logs.level} = ${"ERROR"}")

    remaining <- sql"SELECT * FROM $logs".query[LogEntry]
  yield (rowsDeleted, deletedEntries, remaining))
}
```

---

## Pagination

Saferis provides type-safe pagination through `PageSpec`, supporting both offset-based and cursor-based (seek) pagination.

```scala mdoc:reset:silent
import saferis.*

@tableName("articles")
case class Article(@generated @key id: Long, title: String, views: Int, published: Boolean) derives Table

val articles = Table[Article]
```

### Offset Pagination

Traditional pagination with LIMIT and OFFSET:

```scala mdoc
// Page 3 with 10 items per page
PageSpec[Article]
  .where(sql"${articles.published} = ${true}")
  .orderBy(articles.views, SortOrder.Desc)
  .limit(10)
  .offset(20)
  .build
  .show
```

### Cursor/Seek Pagination

More efficient for large datasets - uses indexed lookups instead of scanning:

```scala mdoc
// Get next page after a known ID
PageSpec[Article]
  .seekAfter(articles.id, 100L)
  .limit(10)
  .build
  .show
```

```scala mdoc
// Get previous page before a known ID
PageSpec[Article]
  .seekBefore(articles.id, 50L)
  .limit(10)
  .build
  .show
```

### Combined Filters and Sorting

```scala mdoc
PageSpec[Article]
  .where(sql"${articles.published} = ${true}")
  .where(sql"${articles.views} > ${100}")
  .orderBy(articles.views, SortOrder.Desc)
  .orderBy(articles.id, SortOrder.Asc)
  .limit(20)
  .build
  .show
```

### Running Paginated Queries

```scala mdoc:reset:silent
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("articles")
case class Article(@generated @key id: Long, title: String, views: Int, published: Boolean) derives Table

val articles = Table[Article]
```

```scala mdoc
// Setup test data and run pagination
run {
  xa.run(for
    _ <- ddl.createTable[Article]()
    _ <- dml.insert(Article(-1, "First Post", 100, true))
    _ <- dml.insert(Article(-1, "Second Post", 250, true))
    _ <- dml.insert(Article(-1, "Draft", 0, false))
    _ <- dml.insert(Article(-1, "Popular", 1000, true))
    page <- PageSpec[Article]
      .where(sql"${articles.published} = ${true}")
      .orderBy(articles.views, SortOrder.Desc)
      .limit(2)
      .query[Article]
  yield page)
}
```

### Null Handling in Sort Order

Control how NULL values are sorted using `NullOrder`:

```scala mdoc
// NullOrder.First - NULLs appear first
PageSpec[Article]
  .orderBy(articles.views, SortOrder.Asc, NullOrder.First)
  .build
  .show
```

```scala mdoc
// NullOrder.Last - NULLs appear last
PageSpec[Article]
  .orderBy(articles.views, SortOrder.Desc, NullOrder.Last)
  .build
  .show
```

```scala mdoc
// NullOrder.Default - database default behavior (no NULLS clause)
PageSpec[Article]
  .orderBy(articles.views, SortOrder.Asc, NullOrder.Default)
  .build
  .show
```

### Column Extensions

For more concise syntax, use column extension methods:

#### Sorting Extensions

```scala mdoc
// .asc and .desc for simple sorting
PageSpec[Article]
  .orderBy(articles.views.desc)
  .orderBy(articles.title.asc)
  .build
  .show
```

```scala mdoc
// Combined with null handling
PageSpec[Article]
  .orderBy(articles.views.descNullsLast)
  .orderBy(articles.title.ascNullsFirst)
  .build
  .show
```

Available sorting extensions:
- `.asc` - ascending order
- `.desc` - descending order
- `.ascNullsFirst` - ascending, NULLs first
- `.ascNullsLast` - ascending, NULLs last
- `.descNullsFirst` - descending, NULLs first
- `.descNullsLast` - descending, NULLs last

#### Comparison Extensions for Seek Pagination

```scala mdoc
// Use .gt (greater than) for seek-after
PageSpec[Article]
  .seek(articles.id.gt(100L))
  .limit(10)
  .build
  .show
```

```scala mdoc
// Use .lt (less than) for seek-before
PageSpec[Article]
  .seek(articles.id.lt(50L))
  .limit(10)
  .build
  .show
```

Available comparison extensions:
- `.gt(value)` - greater than (for forward pagination)
- `.lt(value)` - less than (for backward pagination)

---

## Type-Safe Capabilities

Saferis uses Scala 3's type system to ensure operations are only available when the database supports them.

### Capability Traits

Each dialect mixes in capability traits that enable specific operations:

| Trait | Operations Enabled |
|-------|-------------------|
| `ReturningSupport` | `insertReturning`, `updateReturning`, `deleteReturning` |
| `JsonSupport` | `jsonExtract`, JSON type mappings |
| `ArraySupport` | `arrayContains`, array type mappings |
| `UpsertSupport` | `upsert` |
| `IndexIfNotExistsSupport` | Conditional index creation |

### Using SpecializedDML

The `SpecializedDML` object provides type-safe operations that only compile when the dialect supports them:

```scala mdoc:reset:silent
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("specialized_items")
case class SpecializedItem(@generated @key id: Int, name: String, category: String) derives Table
```

```scala mdoc
// Create table and use insertReturning (PostgreSQL supports RETURNING)
run {
  xa.run(for
    _ <- ddl.createTable[SpecializedItem]()
    inserted <- dml.insertReturning(SpecializedItem(-1, "Widget", "hardware"))
    _ <- dml.insert(SpecializedItem(-1, "Gadget", "electronics"))
    all <- sql"SELECT * FROM ${Table[SpecializedItem]}".query[SpecializedItem]
  yield (inserted, all))
}
```

### Compile-Time Safety

The `SpecializedDML` object provides operations that require specific dialect capabilities. If your dialect doesn't support a capability, the code won't compile:

```scala mdoc
// Query the items we inserted
run {
  xa.run(sql"SELECT * FROM ${Table[SpecializedItem]} ORDER BY ${Table[SpecializedItem].id}".query[SpecializedItem])
}
```

Available capability-constrained operations in `SpecializedDML`:

| Operation | Required Capability |
|-----------|-------------------|
| `insertReturning` | `ReturningSupport` |
| `updateReturning` | `ReturningSupport` |
| `deleteReturning` | `ReturningSupport` |
| `upsert` | `UpsertSupport` |
| `jsonExtract` | `JsonSupport` |
| `arrayContains` | `ArraySupport` |

### Generic Functions with Capability Constraints

Write functions that require specific capabilities using intersection types:

```scala mdoc
// Functions can require specific dialect capabilities via intersection types.
// This pattern ensures compile-time safety - code won't compile if
// the dialect doesn't support the required capability.

// Example: a function that only works with RETURNING-capable dialects
// def createAndReturn[A <: Product: Table](entity: A)(using
//   Dialect & ReturningSupport,  // <-- Constraint here
//   zio.Trace
// ): ZIO[ConnectionProvider & Scope, Throwable, A] = dml.insertReturning(entity)

// We've already seen this in action - insertReturning works because
// PostgreSQL provides ReturningSupport:
run {
  xa.run(dml.insertReturning(SpecializedItem(-1, "Capability Demo", "demo")))
}
```

---

## Type Support

Saferis provides built-in support for common Scala and Java types.

### java.time Types

All `java.time` types are supported with automatic SQL type mapping:

| Scala Type | PostgreSQL Type | JDBC Type |
|------------|-----------------|-----------|
| `java.time.Instant` | `timestamptz` | TIMESTAMP_WITH_TIMEZONE |
| `java.time.LocalDateTime` | `timestamp` | TIMESTAMP |
| `java.time.LocalDate` | `date` | DATE |
| `java.time.LocalTime` | `time` | TIME |
| `java.time.ZonedDateTime` | `timestamptz` | TIMESTAMP_WITH_TIMEZONE |
| `java.time.OffsetDateTime` | `timestamptz` | TIMESTAMP_WITH_TIMEZONE |

```scala mdoc:reset:silent
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}
import java.time.*

@tableName("events")
case class Event(
  @generated @key id: Int,
  name: String,
  occurredAt: Instant,
  scheduledFor: Option[LocalDateTime],
  eventDate: LocalDate
) derives Table

val events = Table[Event]
```

```scala mdoc
run {
  xa.run(for
    _ <- ddl.createTable[Event]()
    _ <- dml.insert(Event(-1, "Conference", Instant.now(), Some(LocalDateTime.now().plusDays(7)), LocalDate.now()))
    _ <- dml.insert(Event(-1, "Meeting", Instant.now(), None, LocalDate.now().plusDays(1)))
    all <- sql"SELECT * FROM $events".query[Event]
  yield all)
}
```

### UUID Support

UUIDs can be used as primary keys:

```scala mdoc:reset:silent
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}
import java.util.UUID

@tableName("entities")
case class Entity(@key id: UUID, name: String) derives Table
val entities = Table[Entity]
```

```scala mdoc
run {
  xa.run(for
    _ <- ddl.createTable[Entity]()
    id1 = UUID.randomUUID()
    id2 = UUID.randomUUID()
    _ <- dml.insert(Entity(id1, "First Entity"))
    _ <- dml.insert(Entity(id2, "Second Entity"))
    found <- sql"SELECT * FROM $entities WHERE ${entities.id} = $id1".queryOne[Entity]
  yield found)
}
```

### Other Supported Types

| Scala Type | PostgreSQL Type |
|------------|-----------------|
| `String` | `varchar(255)` |
| `Int` | `integer` |
| `Long` | `bigint` |
| `Double` | `double precision` |
| `Float` | `real` |
| `Boolean` | `boolean` |
| `BigDecimal` | `numeric` |
| `Option[T]` | Same as `T`, nullable |

---

## Additional Resources

- [Source Code](https://github.com/russwyte/saferis)
- [Issue Tracker](https://github.com/russwyte/saferis/issues)

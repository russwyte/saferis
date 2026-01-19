# saferis
[![Scala CI](https://github.com/russwyte/saferis/actions/workflows/scala.yml/badge.svg)](https://github.com/russwyte/saferis/actions/workflows/scala.yml)
[![Maven Repository](https://img.shields.io/maven-central/v/io.github.russwyte/saferis_3?logo=apachemaven)](https://mvnrepository.com/artifact/io.github.russwyte/saferis)

*The name is derived from 'safe' and 'eris' (Greek for 'strife' or 'discord')*

**Saferis mitigates the discord of unsafe SQL.** A type-safe, resource-safe SQL client library for Scala 3 and ZIO.

## Key Features

✅ **SQL Injection Protection** - Safe SQL interpolator with compile-time validation
✅ **Multi-Database Support** - Works with PostgreSQL, MySQL, SQLite, and any JDBC database
✅ **Type-Safe Capabilities** - Operations only available when your database supports them
✅ **Resource Safety** - Guaranteed connection and transaction management with ZIO
✅ **Label-Based Decoding** - Column-to-field mapping by name, not position
✅ **Compile-Time Validation** - Table schemas, column names, and SQL verified at compile time
✅ **Type-Safe Pagination** - Efficient offset and cursor-based pagination with PageSpec  

## Quick Example

```scala
import saferis.* // PostgreSQL is the default dialect - no additional imports needed!

@tableName("users")
case class User(@generated @key id: Int, name: String, email: String) derives Table

val userTable = Table[User]

// Safe SQL interpolation - no injection possible
val activeUsers = sql"SELECT * FROM $userTable WHERE ${userTable.name} LIKE ${"John%"}".query[User]

// Type-safe operations only available when database supports them
val newUser = insertReturning(User(-1, "Alice", "alice@example.com")) // PostgreSQL only
```

## Database Dialects

Saferis provides compile-time guarantees that operations are only available when your database supports them:

| Feature | PostgreSQL | MySQL | SQLite |
|---------|------------|-------|--------|
| RETURNING clause | ✅ | ❌ | ✅ |
| JSON operations | ✅ | ✅ | ❌ |
| Array types | ✅ | ❌ | ❌ |
| UPSERT | ✅ | ❌ | ❌ |

Switch databases by changing one import - your code adapts automatically.

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "io.github.russwyte" %% "saferis" % "latest.release"
```

## Getting Started

1. **Define your models** with annotations:
```scala
@tableName("users")
case class User(
  @generated @key id: Int,
  @indexed name: String, 
  @uniqueIndex email: String
) derives Table
```

2. **Use the default PostgreSQL dialect or choose another**:
```scala
import saferis.*                 // PostgreSQL (default)
import saferis.mysql.{given}     // Override with MySQL
import saferis.sqlite.{given}    // Override with SQLite
```

3. **Use safe SQL operations**:
```scala
val userTable = Table[User]

// DDL operations
createTable[User]()
createIndex("name_idx", Seq("name"))

// DML operations with safe interpolation
val users = sql"SELECT * FROM $userTable WHERE ${userTable.name} = ${"Alice"}".query[User]
val newUser = insert(User(-1, "Bob", "bob@example.com"))

// Dialect-specific operations (compile-time checked)
val returning = insertReturning(User(-1, "Charlie", "charlie@example.com")) // PostgreSQL/SQLite only
```

## DDL (Data Definition Layer)

Saferis provides comprehensive DDL support with type-safe table creation from your case class definitions:

### Annotations

```scala
@tableName("events")
case class Event(
  @generated @key id: Long,               // Auto-generated primary key
  @indexed tenantId: String,              // Single-column index
  @indexed("compound_idx") userId: Int,   // Compound index (same name = same index)
  @indexed("compound_idx") eventTime: Long,
  @uniqueIndex email: String,             // Unique index
  @unique("constraint_1") field1: String, // Compound unique constraint
  @unique("constraint_1") field2: String,
  createdAt: Instant,                     // NOT NULL (non-Option types)
  deletedAt: Option[Instant],             // NULLABLE (Option types)
  status: String = "pending",             // DEFAULT value from case class default
) derives Table
```

### Operations

```scala
import saferis.*

// Create table with all indexes
ddl.createTable[Event]()

// Partial indexes (with WHERE clause)
ddl.createIndex[Event]("idx_pending", Seq("next_retry_at"), where = Some("status = 'pending'"))

// Other DDL operations
ddl.dropTable[Event](ifExists = true)
ddl.truncateTable[Event]()
ddl.addColumn[Event, String]("new_column")
ddl.dropColumn[Event]("old_column")
ddl.dropIndex("idx_name", ifExists = true)
```

### Features

- **NOT NULL inference**: Non-Option fields are NOT NULL, Option fields are NULLABLE
- **Default values**: Case class default values become SQL DEFAULT clauses
- **Compound indexes**: Fields with same `@indexed("name")` form multi-column indexes
- **Compound unique constraints**: Fields with same `@unique("name")` form multi-column unique constraints
- **Partial indexes**: Indexes with WHERE clauses via `@indexed("name", "condition")` or `ddl.createIndex(..., where = Some("..."))`
- **Auto-generated keys**: `@generated @key` creates identity/auto-increment columns

## Pagination with PageSpec

Saferis provides type-safe pagination through `PageSpec`, supporting both offset-based and cursor-based (seek) pagination:

### Offset Pagination

```scala
import saferis.*

@tableName("users")
case class User(@generated @key id: Long, name: String, age: Int) derives Table
val users = Table[User]

// Page 2 with 10 items per page
PageSpec[User]
  .where(sql"${users.age} >= 18")
  .orderBy(users.name, SortOrder.Asc)
  .limit(10)
  .offset(10)
  .query[User]
```

### Cursor/Seek Pagination

Seek pagination is more efficient than offset for large datasets - it uses indexed lookups instead of scanning and discarding rows:

```scala
// Get next page after a cursor value
PageSpec[User]
  .seekAfter(users.id, lastSeenId)  // WHERE id > lastSeenId ORDER BY id ASC
  .limit(10)
  .query[User]

// Get previous page
PageSpec[User]
  .seekBefore(users.id, firstSeenId) // WHERE id < firstSeenId ORDER BY id DESC
  .limit(10)
  .query[User]
```

### Combined Operations

```scala
PageSpec[User]
  .where(sql"${users.age} >= 18")
  .where(sql"active = true")          // Multiple WHERE clauses are ANDed
  .orderBy(users.name, SortOrder.Asc)
  .orderBy(users.id, SortOrder.Desc)  // Multiple ORDER BY columns
  .seekAfter(users.id, cursor)
  .limit(20)
  .query[User]
```

### Null Ordering

```scala
PageSpec[User]
  .orderBy(users.age, SortOrder.Asc, NullOrder.Last)   // NULLS LAST
  .orderBy(users.name, SortOrder.Desc, NullOrder.First) // NULLS FIRST
  .query[User]
```

## Why Saferis?

**Compile-Time Safety**: Table schemas, column names, and database capabilities are validated at compile time. No runtime surprises.

**Resource Safety**: Built on ZIO's resource management. Connections and transactions are automatically cleaned up.

**Database Portable**: Write once, run on multiple databases. Switch from SQLite in development to PostgreSQL in production with one line.

**SQL-First**: Direct SQL control when you need it, with safety guarantees. No magic, no query builders unless you want them.

## Documentation

- **[Database Dialect System Guide](SAFERIS_DIALECT_GUIDE.md)** - Comprehensive guide to Saferis's database dialect system, including PostgreSQL, MySQL, and SQLite support with type-safe capabilities

## License

[Apache 2.0](LICENSE)

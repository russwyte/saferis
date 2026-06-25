# Data Definition Layer (DDL)

[← Back to index](index.md)

The DDL layer provides type-safe schema management operations.

## Creating Tables

```scala marklit:silent,id=ddl_customer
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("customers")
case class Customer(
  @generated @key id: Long,
  name: String,
  email: String,
  status: String = "active",
  notes: Option[String]
) derives Table
```

```scala marklit:zio-app,extends=ddl_customer
// Actually create the table
xa.run(ddl.createTable[Customer](ifNotExists = true)).debug("created")
```

## Other DDL Operations

```scala marklit:compile-only
import saferis.*

@tableName("customers")
case class Customer(
  @generated @key id: Long,
  name: String,
  email: String,
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

## createTable Options

The `createTable` function accepts optional parameters:

```scala marklit:compile-only
import saferis.*

@tableName("my_table")
case class MyTable(@key id: Int, name: String) derives Table

// Default: creates table and indexes for compound primary keys
ddl.createTable[MyTable]()

// Skip table creation if it already exists
ddl.createTable[MyTable](ifNotExists = true)

// Create table without indexes (create them separately later)
ddl.createTable[MyTable](createIndexes = false)
```

## Schema DSL for Indexes and Constraints

Use the `Schema` DSL to define indexes, unique constraints, and foreign keys with full DDL generation:

```scala marklit:silent,id=ddl_schema
import saferis.*
import saferis.Schema.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("schema_users")
case class SchemaUser(
  @generated @key id: Int,
  name: String,
  email: String,
  status: String
) derives Table
```

```scala marklit:extends=ddl_schema
// Simple index
println(Schema[SchemaUser]
  .withIndex(_.name)
  .ddl().sql)
```

```scala marklit:extends=ddl_schema
// Unique index
println(Schema[SchemaUser]
  .withUniqueIndex(_.email)
  .ddl().sql)
```

```scala marklit:extends=ddl_schema
// Compound index on multiple columns
println(Schema[SchemaUser]
  .withIndex(_.name).and(_.status).named("idx_name_status")
  .ddl().sql)
```

```scala marklit:extends=ddl_schema
// Partial index with WHERE clause
println(Schema[SchemaUser]
  .withIndex(_.name).where(_.status).eql("active")
  .ddl().sql)
```

```scala marklit:extends=ddl_schema
// Partial unique index - uniqueness only for active users
println(Schema[SchemaUser]
  .withUniqueIndex(_.email).where(_.status).eql("active")
  .ddl().sql)
```

```scala marklit:extends=ddl_schema
// Multiple indexes chained together
println(Schema[SchemaUser]
  .withIndex(_.name)
  .withUniqueIndex(_.email)
  .ddl().sql)
```

```scala marklit:extends=ddl_schema
// Compound unique constraint
println(Schema[SchemaUser]
  .withUniqueConstraint(_.name).and(_.status)
  .ddl().sql)
```

## Creating Tables with Schema

Use `.build` to get an Instance for `ddl.createTable`:

```scala marklit:zio-app,extends=ddl_schema
// Build schema with indexes and create table
val schemaUsers = Schema[SchemaUser]
  .withIndex(_.name)
  .withUniqueIndex(_.email)
  .build

xa.run(ddl.createTable(schemaUsers)).debug("created")
```

## Partial Indexes via Runtime API

Create partial indexes programmatically using `ddl.createIndex`:

```scala marklit:silent,id=ddl_jobs
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("jobs")
case class Job(@generated @key id: Int, status: String, retryAt: Option[java.time.Instant]) derives Table
```

```scala marklit:zio-app,extends=ddl_jobs
xa.run(for
  _ <- ddl.createTable[Job](createIndexes = false)
  // Create a partial index for pending jobs with retry times
  _ <- ddl.createIndex[Job](
    "idx_pending_retry",
    Seq("retryat"),
    where = Some("status = 'pending'")
  )
  _    <- dml.insert(Job(-1, "pending", Some(java.time.Instant.now())))
  _    <- dml.insert(Job(-1, "completed", None))
  jobs <- sql"SELECT * FROM ${Table[Job]}".query[Job]
yield jobs).debug("jobs")
```

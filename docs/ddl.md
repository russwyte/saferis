# Data Definition Layer (DDL)

[← Back to index](index.md)

The DDL layer provides type-safe schema management operations.

## Creating Tables

```scala
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

```scala
// Actually create the table
xa.run(ddl.createTable[Customer](ifNotExists = true)).debug("created")
```

## Other DDL Operations

```scala
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

```scala
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

```scala
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

```scala
// Simple index
println(Schema[SchemaUser]
  .withIndex(_.name)
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null);
create index "idx_schema_users_name" on "schema_users" ("name")
```

```scala
// Unique index
println(Schema[SchemaUser]
  .withUniqueIndex(_.email)
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null);
create unique index "idx_schema_users_email" on "schema_users" ("email")
```

```scala
// Compound index on multiple columns
println(Schema[SchemaUser]
  .withIndex(_.name).and(_.status).named("idx_name_status")
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null);
create index "idx_name_status" on "schema_users" ("name", "status")
```

```scala
// Partial index with WHERE clause
println(Schema[SchemaUser]
  .withIndex(_.name).where(_.status).eql("active")
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null);
create index "idx_schema_users_name" on "schema_users" ("name") where status = 'active'
```

```scala
// Partial unique index - uniqueness only for active users
println(Schema[SchemaUser]
  .withUniqueIndex(_.email).where(_.status).eql("active")
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null);
create unique index "idx_schema_users_email" on "schema_users" ("email") where status = 'active'
```

```scala
// Multiple indexes chained together
println(Schema[SchemaUser]
  .withIndex(_.name)
  .withUniqueIndex(_.email)
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null);
create index "idx_schema_users_name" on "schema_users" ("name");
create unique index "idx_schema_users_email" on "schema_users" ("email")
```

```scala
// Compound unique constraint
println(Schema[SchemaUser]
  .withUniqueConstraint(_.name).and(_.status)
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null, constraint uq_name_status unique (name, status))
```

## Creating Tables with Schema

Use `.build` to get an Instance for `ddl.createTable`:

```scala
// Build schema with indexes and create table
val schemaUsers = Schema[SchemaUser]
  .withIndex(_.name)
  .withUniqueIndex(_.email)
  .build

xa.run(ddl.createTable(schemaUsers)).debug("created")
```

## Partial Indexes via Runtime API

Create partial indexes programmatically using `ddl.createIndex`:

```scala
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("jobs")
case class Job(@generated @key id: Int, status: String, retryAt: Option[java.time.Instant]) derives Table
```

```scala
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

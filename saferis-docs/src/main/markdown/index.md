# Saferis Documentation

A comprehensive guide to Saferis — the type-safe, resource-safe SQL client library for Scala 3 and ZIO.

> Every Scala example on these pages is compiled **and executed** against a real
> PostgreSQL database by [marklit](https://github.com/russwyte/marklit) when the
> docs are built. The output you see below each block is the actual result — if
> the code stopped working, the build would break.

## Installation

Add Saferis to your `build.sbt`:

```scala marklit:passthrough
libraryDependencies += "io.github.russwyte" %% "saferis" % "0.15.0"
```

Saferis requires ZIO as a provided dependency:

```scala marklit:passthrough
libraryDependencies += "dev.zio" %% "zio" % "2.1.24"
```

## Quick Example

```scala marklit:silent,id=quickstart
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

// Define a table
@tableName("quick_users")
case class QuickUser(@generated @key id: Int, name: String, email: String) derives Table
```

```scala marklit:zio-app,extends=quickstart
// Create table, insert data, and query — against a real database
xa.run(for
  _     <- ddl.createTable[QuickUser](ifNotExists = true)
  _     <- dml.insert(QuickUser(-1, "Alice", "alice@example.com"))
  _     <- dml.insert(QuickUser(-1, "Bob", "bob@example.com"))
  users <- sql"SELECT * FROM ${Table[QuickUser]}".query[QuickUser]
yield users).debug("users")
```

## Guide

### Getting Started

- [Getting Started](getting-started.md) — installation and your first query
- [Core Concepts](core-concepts.md) — table definitions, SQL interpolation, the Transactor

### Safety

- [SQL Injection Prevention](sql-injection-prevention.md) — the complete security model
- [Type-Safe Capabilities](capabilities.md) — operations gated by dialect support
- [Error Handling](error-handling.md) — the `SaferisError` hierarchy and pattern matching
- [Statement Timeouts](statement-timeouts.md) — bounding query execution time
- [Retryable Errors](retryable-errors.md) — classifying and retrying transient failures

### Schema

- [Data Definition Layer (DDL)](ddl.md) — creating tables, indexes, and constraints
- [Foreign Key Support](foreign-keys.md) — type-safe foreign key constraints
- [Schema Validation](schema-validation.md) — verifying code matches the database
- [Dialect System](dialect-system.md) — PostgreSQL, MySQL, SQLite, and Spark

### Querying

- [Data Manipulation Layer (DML)](dml.md) — CRUD operations and mutation builders
- [Query Builder](query-builder.md) — type-safe joins, WHERE clauses, and pagination
- [Subqueries](subqueries.md) — IN, EXISTS, correlated subqueries, and derived tables
- [Aggregate Functions](aggregate-functions.md) — MAX, MIN, SUM, COUNT, and COALESCE
- [Conditional Upsert DSL](upsert.md) — INSERT ... ON CONFLICT for PostgreSQL

### Streaming

- [Streaming with ZStream](streaming.md) — lazy row-by-row processing
- [Paged Streaming](paged-streaming.md) — cursor-based pagination with connection release

### Reference

- [Type Support](type-support.md) — `java.time`, UUID, JSON, and built-in mappings
- [Query Execution Methods](query-execution.md) — `query`, `queryOne`, `queryValue`, `execute`

## Additional Resources

- [Source Code](https://github.com/russwyte/saferis)
- [Issue Tracker](https://github.com/russwyte/saferis/issues)

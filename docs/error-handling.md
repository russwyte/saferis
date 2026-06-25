# Error Handling

[← Back to index](index.md)

Saferis uses a principled error type hierarchy defined in [SaferisError.scala](https://github.com/russwyte/saferis/blob/main/core/src/main/scala/saferis/SaferisError.scala) that enables proper pattern matching and eliminates unsafe casting. All database operations return `ZIO` effects with `SaferisError` as the error type.

## Error Categories

| Error Type | When It Occurs |
|------------|----------------|
| `ConstraintViolation` | PRIMARY KEY, FOREIGN KEY, UNIQUE, NOT NULL, or CHECK constraint violated |
| `SyntaxError` | Invalid SQL syntax |
| `DataError` | Data type mismatch, division by zero, etc. |
| `QueryError` | General SQL execution errors |
| `Timeout` | Statement timeout fired (or query was canceled server-side) — see [Statement Timeouts](statement-timeouts.md) |
| `Retryable` | Transient failure the application can reasonably retry — see [Retryable Errors](retryable-errors.md) |
| `ConnectionError` | Cannot acquire database connection |
| `DecodingError` | Cannot decode a column value to the expected Scala type |
| `EncodingError` | Cannot encode a parameter value for the prepared statement |
| `ReturningOperationFailed` | INSERT/UPDATE/DELETE RETURNING returned no rows |
| `SchemaValidation` | Schema verification found mismatches (see [Schema Validation](schema-validation.md)) |
| `Unexpected` | Non-SQL errors (wrapped in Unexpected) |

## SQL Error Classification

SQL errors are automatically categorized based on SQLState codes:

| SQLState Prefix | Error Type | Description |
|-----------------|------------|-------------|
| `23` | `ConstraintViolation` | Integrity constraint violations |
| `42` | `SyntaxError` | Syntax errors or access violations |
| `22` | `DataError` | Data exceptions |
| Other | `QueryError` | General query errors |

## Pattern Matching on Errors

Use pattern matching for type-safe error handling:

```scala
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("error_users")
case class ErrorUser(@generated @key id: Int, email: String, name: String) derives Table
```

```scala
// Handle specific error types with pattern matching
xa.run(for
  _      <- ddl.createTable[ErrorUser](ifNotExists = true)
  _      <- dml.insert(ErrorUser(-1, "alice@example.com", "Alice"))
  _      <- dml.insert(ErrorUser(-1, "bob@example.com", "Bob"))
  result <- sql"SELECT * FROM ${Table[ErrorUser]} WHERE ${Table[ErrorUser].email} = ${"alice@example.com"}".queryOne[ErrorUser].either
yield result match
  case Left(SaferisError.DecodingError(col, expected, _)) =>
    s"Failed to decode column '$col' as $expected"
  case Left(error) =>
    s"Other error: ${error.message}"
  case Right(user) =>
    s"Found user: ${user.map(_.name).getOrElse("none")}"
).debug("result")
```

## Handling Constraint Violations

```scala
import saferis.*
import saferis.Schema.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("unique_emails")
case class UniqueEmail(@generated @key id: Int, email: String) derives Table
```

```scala
val schema = Schema[UniqueEmail].withUniqueConstraint(_.email).build
xa.run(for
  _      <- ddl.createTable(schema)
  _      <- dml.insert(UniqueEmail(-1, "alice@example.com"))
  // Try to insert duplicate email
  result <- dml.insert(UniqueEmail(-1, "alice@example.com")).either
yield result match
  case Left(SaferisError.ConstraintViolation(constraintType, _, _, _)) =>
    s"Constraint violation: $constraintType"
  case Left(error) =>
    s"Other error: ${error.message}"
  case Right(_) =>
    "Insert succeeded"
).debug("result")
```

### What the violation looks like unhandled

The previous example caught the error with `.either`. If you *don't* recover, the
constraint violation propagates as a real failure — the actual `SaferisError` is
shown below the snippet:

```scala
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("crash_keys")
case class CrashKey(@key id: Int, label: String) derives Table

// No .either — inserting a duplicate primary key makes the effect fail with the
// SaferisError constraint violation.
xa.run(for
  _ <- ddl.createTable[CrashKey](ifNotExists = true)
  _ <- dml.insert(CrashKey(1, "first"))
  _ <- dml.insert(CrashKey(1, "duplicate"))  // duplicate primary key → constraint violation
yield ())
```
```
// Scala 3.3.8
// Exception: FiberFailure: ConstraintViolation(UNIQUE,None,org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint "crash_keys_pkey"
  Detail: Key (id)=(1) already exists.,Some(insert into crash_keys (id, label) values (?, ?)))
```

## Converting Raw Exceptions

Use `SaferisError.fromThrowable` to wrap exceptions:

```scala
import saferis.*

// Convert a Throwable to SaferisError
val sqlException = new java.sql.SQLException("duplicate key", "23505")
val error = SaferisError.fromThrowable(sqlException)
// error: SaferisError.ConstraintViolation(...)

// Non-SQL exceptions become Unexpected
val runtimeException = new RuntimeException("something went wrong")
val unexpectedError = SaferisError.fromThrowable(runtimeException)
// unexpectedError: SaferisError.Unexpected(...)
```

## Accessing Error Details

Each error type provides relevant details:

```scala
import saferis.*

def logError(error: SaferisError): String = error match
  case e: SaferisError.ConstraintViolation =>
    s"Constraint ${e.constraintType} violated. SQL: ${e.sql.getOrElse("N/A")}"
  case e: SaferisError.SyntaxError =>
    s"Syntax error: ${e.cause.getMessage}. SQL: ${e.sql.getOrElse("N/A")}"
  case e: SaferisError.DecodingError =>
    s"Failed to decode column '${e.columnName}' as ${e.expectedType}"
  case e: SaferisError.SchemaValidation =>
    s"Schema issues:\n${e.issues.map(_.description).mkString("\n")}"
  case e =>
    e.message
```

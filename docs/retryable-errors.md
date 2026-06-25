# Retryable Errors

[← Back to index](index.md)

Some database failures are transient and worth retrying — connection blips, deadlocks, serialization failures, transport errors on HTTP-tunnelled drivers (Databricks, Snowflake). Saferis classifies such errors as `SaferisError.Retryable` so that a `ZIO.retry` policy can target exactly those cases without your code grokking JDBC error codes.

## The default classifier

Every Dialect ships a default classifier (`Dialect.retryClassifier`) that recognizes standard transient SQLState classes:

- `08xxx` — connection exception (the driver lost or could not establish a connection)
- `40001` — serialization failure
- `40P01` — deadlock detected

If a thrown `SQLException` matches one of these, Saferis emits `SaferisError.Retryable` instead of the usual SQLState-based variant.

## Driving retries with ZIO

```scala
import saferis.*
import zio.*

@tableName("users")
case class User(@generated @key id: Int, name: String) derives Table

def reportWithRetry(xa: Transactor) =
  xa.run(sql"SELECT * FROM ${Table[User]}".query[User])
    .retry(
      Schedule.recurs(3) && Schedule.exponential(100.millis) && Schedule.recurWhile[SaferisError]:
        case _: SaferisError.Retryable => true
        case _                         => false
    )
```

## Supplying a custom classifier

Drivers that tunnel over HTTP (Databricks, Snowflake) can surface transport errors as vendor-specific codes that the standards-based default does not catch. Provide your own classifier on the Transactor — it replaces the dialect default:

```scala
import saferis.*
import zio.*
import java.sql.SQLException

// Treat Databricks vendor code 8000 (HTTP transport error) as transient.
val databricksClassifier: SaferisError.RetryClassifier =
  case e: SQLException =>
    e.getErrorCode == 8000 || SaferisError.defaultRetryClassifier(e)
  case _ => false

val xaLayer = Transactor.layer(retryClassifier = Some(databricksClassifier))
```

Composing with the default (as shown above) keeps the standard SQLState rules and adds your driver-specific quirks on top.

## When *not* to retry

`SaferisError.Retryable` only signals that an error *might* be safe to retry — your application still owns the semantics:

- For **read-only queries**, retrying is generally safe.
- For **writes**, retry only if the operation is idempotent (e.g., upsert by primary key, set-to-fixed-value updates) or if the failure happened before any partial state could be observed.
- For **transactions**, the whole transaction must be re-attempted — a single statement retry inside a failed transaction is meaningless.

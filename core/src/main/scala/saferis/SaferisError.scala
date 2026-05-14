package saferis

/** Principled error type for all Saferis operations.
  *
  * This sealed trait enables proper pattern matching on errors and eliminates the need for unsafe casting.
  */
sealed trait SaferisError:
  def message: String

object SaferisError:

  // === SQL Errors (wrap java.sql.SQLException) ===

  sealed trait SqlError extends SaferisError:
    def sql: Option[String]
    def cause: Throwable

  final case class ConstraintViolation(
      constraintType: String, // "PRIMARY KEY", "FOREIGN KEY", "UNIQUE", "NOT NULL", "CHECK"
      constraintName: Option[String],
      cause: Throwable,
      sql: Option[String] = None,
  ) extends SqlError:
    def message = s"$constraintType constraint violation${constraintName.map(n => s" ($n)").getOrElse("")}"

  final case class SyntaxError(
      cause: Throwable,
      sql: Option[String] = None,
  ) extends SqlError:
    def message = s"SQL syntax error: ${cause.getMessage}"

  final case class DataError(
      cause: Throwable,
      sql: Option[String] = None,
  ) extends SqlError:
    def message = s"Data error: ${cause.getMessage}"

  final case class QueryError(
      cause: Throwable,
      sql: Option[String] = None,
  ) extends SqlError:
    def message = s"Query error: ${cause.getMessage}"

  final case class Timeout(
      cause: java.sql.SQLException,
      sql: Option[String] = None,
  ) extends SqlError:
    def message = s"Query timed out: ${cause.getMessage}"

  /** A transient SQL error that the application can reasonably retry.
    *
    * Saferis classifies errors via the active [[Dialect]]'s `retryClassifier` (overridable on each `Transactor`).
    * Typical examples: network blips on HTTP-tunnelled drivers (Databricks), serialization failures (`40001`),
    * deadlocks (`40P01`), connection-broken states (`08xxx`).
    *
    * Pair with ZIO's retry combinators: `xa.run(query).retry(Schedule.recurs(3) && Schedule.exponential(100.millis))`
    * filtering by `case _: SaferisError.Retryable => true`.
    */
  final case class Retryable(
      cause: Throwable,
      sql: Option[String] = None,
  ) extends SqlError:
    def message = s"Retryable error: ${cause.getMessage}"

  // === Connection Errors ===

  final case class ConnectionError(cause: Throwable) extends SaferisError:
    def message = s"Connection error: ${cause.getMessage}"

  // === Codec Errors ===

  final case class DecodingError(
      columnName: String,
      expectedType: String,
      cause: Throwable,
  ) extends SaferisError:
    def message = s"Failed to decode column '$columnName' as $expectedType: ${cause.getMessage}"

  final case class EncodingError(
      parameterIndex: Int,
      cause: Throwable,
  ) extends SaferisError:
    def message = s"Failed to encode parameter at index $parameterIndex: ${cause.getMessage}"

  // === Operation Errors ===

  final case class ReturningOperationFailed(
      operation: String, // "insert", "update", "delete"
      tableName: String,
  ) extends SaferisError:
    def message = s"$operation returning failed on table '$tableName'"

  // === Schema Validation ===

  final case class SchemaValidation(issues: List[SchemaIssue]) extends SaferisError:
    def message =
      val count   = issues.length
      val summary = if count == 1 then "1 issue" else s"$count issues"
      s"Schema validation failed with $summary:\n${issues.map(i => s"  - ${i.description}").mkString("\n")}"

  // === Unexpected (for truly unexpected non-SQL errors) ===

  final case class Unexpected(cause: Throwable) extends SaferisError:
    def message = s"Unexpected error: ${cause.getMessage}"

  /** A function deciding whether a given throwable represents a transient, retryable failure.
    *
    * Returning `true` causes [[fromThrowable]] to wrap the throwable in [[Retryable]] instead of the usual
    * SQL-state-based categorization. Each [[Dialect]] supplies a default; the `Transactor` can be configured with an
    * override.
    */
  type RetryClassifier = Throwable => Boolean

  /** Standards-based default classifier. Recognizes only well-known SQLState classes that are widely understood as
    * transient. Dialects extend this with driver-specific quirks.
    *
    *   - `08xxx` — connection exception (driver lost or could not establish a connection)
    *   - `40001` — serialization failure
    *   - `40P01` — deadlock detected (PostgreSQL-flavored, but emitted by other servers as well)
    */
  val defaultRetryClassifier: RetryClassifier =
    case e: java.sql.SQLException =>
      val state = Option(e.getSQLState).getOrElse("")
      state.startsWith("08") || state == "40001" || state == "40P01"
    case _ => false

  /** Helper to wrap Throwable into appropriate SaferisError.
    *
    * @param t
    *   the throwable to classify
    * @param sql
    *   the SQL text associated with the failure (when known)
    * @param retryClassifier
    *   if it returns `true` for `t`, the result is a [[Retryable]] regardless of SQLState. Defaults to a no-op so
    *   existing call sites are unchanged.
    */
  def fromThrowable(
      t: Throwable,
      sql: Option[String] = None,
      retryClassifier: RetryClassifier = _ => false,
  ): SaferisError = t match
    case e if retryClassifier(e)                        => Retryable(e, sql)
    case e: java.sql.SQLTimeoutException                => Timeout(e, sql)
    case e: java.sql.SQLException if isQueryCanceled(e) => Timeout(e, sql)
    case e: java.sql.SQLException                       => categorizeSQL(e, sql)
    case e                                              => Unexpected(e)

  /** SQLState `57014` (query_canceled) is the standard SQL state for both server-initiated query cancellation (e.g.
    * PostgreSQL `pg_cancel_backend`) and `Statement.setQueryTimeout` cancellation across most drivers. Some drivers
    * (notably the Postgres JDBC driver) throw plain `SQLException` with this state instead of the more specific
    * `SQLTimeoutException`.
    */
  private def isQueryCanceled(e: java.sql.SQLException): Boolean =
    Option(e.getSQLState).contains("57014")

  private def categorizeSQL(e: java.sql.SQLException, sql: Option[String]): SqlError =
    val state = Option(e.getSQLState).getOrElse("")
    state.take(2) match
      case "23" => ConstraintViolation(inferConstraintType(e), None, e, sql)
      case "42" => SyntaxError(e, sql)
      case "22" => DataError(e, sql)
      case _    => QueryError(e, sql)

  private def inferConstraintType(e: java.sql.SQLException): String =
    val msg = e.getMessage.toLowerCase
    if msg.contains("primary key") then "PRIMARY KEY"
    else if msg.contains("foreign key") then "FOREIGN KEY"
    else if msg.contains("unique") then "UNIQUE"
    else if msg.contains("not null") || msg.contains("null") then "NOT NULL"
    else if msg.contains("check") then "CHECK"
    else "UNKNOWN"

end SaferisError

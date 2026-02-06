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

  /** Helper to wrap Throwable into appropriate SaferisError. */
  def fromThrowable(t: Throwable, sql: Option[String] = None): SaferisError = t match
    case e: java.sql.SQLException => categorizeSQL(e, sql)
    case e                        => Unexpected(e)

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

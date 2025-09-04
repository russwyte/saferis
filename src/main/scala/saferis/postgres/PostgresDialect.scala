package saferis.postgres

import saferis.*
import java.sql.Types

given Dialect = PostgresDialect

/** PostgreSQL dialect implementation providing PostgreSQL-specific type mappings and SQL generation */
object PostgresDialect
    extends Dialect
    with ReturningSupport
    with IndexIfNotExistsSupport
    with AdvancedAlterTableSupport
    with UpsertSupport
    with JsonSupport
    with ArraySupport
    with WindowFunctionSupport
    with CommonTableExpressionSupport:

  val name: String = "PostgreSQL"

  def columnType(jdbcType: Int): String = jdbcType match
    case Types.VARCHAR     => s"varchar($DefaultVarcharLength)"
    case Types.CHAR        => "char"
    case Types.LONGVARCHAR => "text"
    case Types.CLOB        => "text"

    case Types.SMALLINT => "smallint"
    case Types.INTEGER  => "integer"
    case Types.BIGINT   => "bigint"

    case Types.FLOAT   => "real"
    case Types.DOUBLE  => "double precision"
    case Types.REAL    => "real"
    case Types.DECIMAL => "numeric"
    case Types.NUMERIC => "numeric"

    case Types.BOOLEAN => "boolean"
    case Types.BIT     => "boolean"

    case Types.DATE                    => "date"
    case Types.TIME                    => "time"
    case Types.TIMESTAMP               => "timestamp"
    case Types.TIMESTAMP_WITH_TIMEZONE => "timestamptz"

    case Types.BINARY        => "bytea"
    case Types.VARBINARY     => "bytea"
    case Types.LONGVARBINARY => "bytea"
    case Types.BLOB          => "bytea"

    case Types.DATALINK => "text" // URLs stored as text in PostgreSQL
    case Types.ARRAY    => "array"
    case Types.STRUCT   => "jsonb"
    case Types.OTHER    => "jsonb"

    // Fallback to JDBC standard name for unknown types
    case other =>
      try java.sql.JDBCType.valueOf(other).getName.toLowerCase
      catch case _: IllegalArgumentException => "text"

  // === PostgreSQL-specific Auto-increment and Primary Key Support ===

  def autoIncrementClause(isGenerated: Boolean, isPrimaryKey: Boolean, hasCompoundKey: Boolean): String =
    if isGenerated && isPrimaryKey && !hasCompoundKey then " generated always as identity primary key"
    else if isGenerated then " generated always as identity"
    else if isPrimaryKey && !hasCompoundKey then " primary key"
    else ""

  // === PostgreSQL-specific Query Features ===
  // PostgreSQL uses double quotes for identifier escaping
  override def identifierQuote: String = "\""

  // === UpsertSupport implementation ===
  def upsertSql(tableName: String, insertColumns: String, conflictColumns: Seq[String], updateColumns: String): String =
    s"insert into $tableName $insertColumns on conflict (${conflictColumns.mkString(", ")}) do update set $updateColumns"

  // === JsonSupport implementation ===
  def jsonType: String = "jsonb"

  def jsonExtractSql(columnName: String, fieldPath: String): String =
    s"$columnName->>'$fieldPath'"

  // === ArraySupport implementation ===
  def arrayType(elementType: String): String = s"$elementType[]"

  def arrayContainsSql(columnName: String, value: String): String =
    s"$value = ANY($columnName)"

end PostgresDialect

/** Extension methods for working with database types */
extension [A](encoder: saferis.Encoder[A])
  /** Get the database column type string for this encoder */
  def columnType(using dialect: Dialect): String =
    dialect.columnType(encoder.jdbcType)

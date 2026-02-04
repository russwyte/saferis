package saferis.spark

import saferis.*

import java.sql.Types

given Dialect = SparkDialect

/** Spark/Databricks/Hive dialect implementation
  *
  * Key characteristics:
  *   - Uses backticks (`) for identifier quoting (table names, column names)
  *   - Uses single quotes (') for string literals (handled by default Encoder)
  *   - Limited support for constraints (no auto-increment, foreign keys)
  *   - Supports JSON, arrays, maps, and complex types
  *   - Uses Hive-compatible DDL syntax
  */
object SparkDialect
    extends Dialect
    with JsonSupport
    with ArraySupport
    with WindowFunctionSupport
    with CommonTableExpressionSupport:

  val name: String = "Spark SQL"

  def columnType(jdbcType: Int): String = jdbcType match
    // String types - Spark uses STRING instead of VARCHAR
    case Types.VARCHAR     => "string"
    case Types.CHAR        => "string"
    case Types.LONGVARCHAR => "string"
    case Types.CLOB        => "string"

    // Integer types
    case Types.TINYINT  => "tinyint"
    case Types.SMALLINT => "smallint"
    case Types.INTEGER  => "int"
    case Types.BIGINT   => "bigint"

    // Floating point types
    case Types.FLOAT   => "float"
    case Types.DOUBLE  => "double"
    case Types.REAL    => "float"
    case Types.DECIMAL => "decimal(10,0)"
    case Types.NUMERIC => "decimal(10,0)"

    // Boolean type
    case Types.BOOLEAN => "boolean"
    case Types.BIT     => "boolean"

    // Date and time types
    case Types.DATE                    => "date"
    case Types.TIME                    => "timestamp" // Spark doesn't have separate TIME type
    case Types.TIMESTAMP               => "timestamp"
    case Types.TIMESTAMP_WITH_TIMEZONE => "timestamp" // Spark stores timestamps in UTC

    // Binary types
    case Types.BINARY        => "binary"
    case Types.VARBINARY     => "binary"
    case Types.LONGVARBINARY => "binary"
    case Types.BLOB          => "binary"

    // Complex types
    case Types.DATALINK => "string" // URLs stored as string
    case Types.ARRAY    => "array<string>"
    case Types.STRUCT   => "struct<>"
    case Types.OTHER    => "string" // Fallback

    // Fallback for unknown types
    case other =>
      try java.sql.JDBCType.valueOf(other).getName.toLowerCase
      catch case _: IllegalArgumentException => "string"

  // === Spark SQL Auto-increment and Primary Key Support ===
  // Spark SQL does not support auto-increment or primary key constraints in standard DDL
  // Some distributions (like Databricks) may support GENERATED ALWAYS AS IDENTITY
  def autoIncrementClause(isGenerated: Boolean, isPrimaryKey: Boolean, hasCompoundKey: Boolean): String = ???

  // === Spark SQL uses backticks for identifier escaping ===
  // This is critical: backticks are ONLY for identifiers (tables, columns, aliases)
  // String literals must use single quotes (handled by Encoder)
  override def identifierQuote: String = "`"

  // === Override DDL operations for Spark SQL syntax ===

  override def createTableClause(ifNotExists: Boolean): String =
    if ifNotExists then "create table if not exists"
    else "create table"

  override def dropTableSql(tableName: String, ifExists: Boolean): String =
    if ifExists then s"drop table if exists $tableName"
    else s"drop table $tableName"

  override def truncateTableSql(tableName: String): String =
    s"truncate table $tableName"

  // Spark SQL doesn't support indexes at all
  override def createIndexSql(
      indexName: String,
      tableName: String,
      columnNames: Seq[String],
      ifNotExists: Boolean = true,
      where: Option[String] = None,
  ): String = throw new UnsupportedOperationException(
    "Spark SQL does not support CREATE INDEX. Consider using partitioning, bucketing, or Z-ordering instead."
  )

  override def createUniqueIndexSql(
      indexName: String,
      tableName: String,
      columnNames: Seq[String],
      ifNotExists: Boolean = true,
      where: Option[String] = None,
  ): String =
    throw new UnsupportedOperationException(
      "Spark SQL does not support CREATE UNIQUE INDEX. Uniqueness must be enforced at the application level."
    )

  override def dropIndexSql(indexName: String, ifExists: Boolean = false): String =
    throw new UnsupportedOperationException("Spark SQL does not support DROP INDEX")

  // === JsonSupport implementation ===
  // Spark SQL has JSON functions like get_json_object, from_json, to_json
  def jsonType: String = "string" // JSON is stored as STRING type

  def jsonExtractSql(columnName: String, fieldPath: String): String =
    s"get_json_object($columnName, '$$.$fieldPath')"

  // Spark doesn't have a native @> operator, but we can use get_json_object to compare
  def jsonContainsSql(columnName: String, jsonValue: String): String =
    throw new UnsupportedOperationException(
      "Spark SQL does not support JSON containment. Use get_json_object for field extraction instead."
    )

  // Check if a key exists by checking if get_json_object returns non-null
  def jsonHasKeySql(columnName: String, key: String): String =
    s"get_json_object($columnName, '$$.$key') is not null"

  // Check if any of the keys exist
  def jsonHasAnyKeySql(columnName: String, keys: Seq[String]): String =
    val checks = keys.map(k => s"get_json_object($columnName, '$$.$k') is not null")
    checks.mkString("(", " or ", ")")

  // Check if all keys exist
  def jsonHasAllKeysSql(columnName: String, keys: Seq[String]): String =
    val checks = keys.map(k => s"get_json_object($columnName, '$$.$k') is not null")
    checks.mkString("(", " and ", ")")

  // === ArraySupport implementation ===
  def arrayType(elementType: String): String = s"array<$elementType>"

  def arrayContainsSql(columnName: String, value: String): String =
    s"array_contains($columnName, $value)"

end SparkDialect

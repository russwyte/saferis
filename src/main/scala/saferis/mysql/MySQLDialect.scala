package saferis.mysql

import saferis.*
import java.sql.Types

given Dialect = MySQLDialect

/** MySQL dialect implementation providing MySQL-specific type mappings and SQL generation.
  *
  * This demonstrates how different databases have different syntax and features:
  *   - MySQL uses AUTO_INCREMENT instead of GENERATED ALWAYS AS IDENTITY
  *   - MySQL doesn't support IF NOT EXISTS for indexes until version 5.7
  *   - MySQL uses backticks for identifier escaping
  *   - MySQL has different type names for some SQL types
  */
object MySQLDialect extends Dialect with JsonSupport with WindowFunctionSupport with CommonTableExpressionSupport:

  val name: String = "MySQL"

  def columnType(jdbcType: Int): String = jdbcType match
    case Types.VARCHAR     => s"varchar($DefaultVarcharLength)"
    case Types.CHAR        => "char"
    case Types.LONGVARCHAR => "longtext"
    case Types.CLOB        => "longtext"

    case Types.SMALLINT => "smallint"
    case Types.INTEGER  => "int"
    case Types.BIGINT   => "bigint"

    case Types.FLOAT   => "float"
    case Types.DOUBLE  => "double"
    case Types.REAL    => "float"
    case Types.DECIMAL => "decimal"
    case Types.NUMERIC => "decimal"

    case Types.BOOLEAN => "boolean"
    case Types.BIT     => "bit"

    case Types.DATE                    => "date"
    case Types.TIME                    => "time"
    case Types.TIMESTAMP               => "timestamp"
    case Types.TIMESTAMP_WITH_TIMEZONE => "timestamp" // MySQL doesn't have separate timezone type

    case Types.BINARY        => "binary"
    case Types.VARBINARY     => "varbinary(255)"
    case Types.LONGVARBINARY => "longblob"
    case Types.BLOB          => "blob"

    case Types.DATALINK => "text" // URLs stored as text
    case Types.ARRAY    => "json" // MySQL 5.7+ supports JSON
    case Types.STRUCT   => "json"
    case Types.OTHER    => "json"

    // Fallback to JDBC standard name for unknown types
    case other =>
      try java.sql.JDBCType.valueOf(other).getName.toLowerCase
      catch case _: IllegalArgumentException => "text"

  // === MySQL-specific Auto-increment and Primary Key Support ===

  def autoIncrementClause(isGenerated: Boolean, isPrimaryKey: Boolean, hasCompoundKey: Boolean): String =
    if isGenerated && isPrimaryKey && !hasCompoundKey then " auto_increment primary key"
    else if isGenerated then " auto_increment"
    else if isPrimaryKey && !hasCompoundKey then " primary key"
    else ""

  // === MySQL-specific Index Creation ===
  // MySQL doesn't support IF NOT EXISTS for indexes in older versions
  override def createIndexSql(
      indexName: String,
      tableName: String,
      columnNames: Seq[String],
      ifNotExists: Boolean = true,
  ): String =
    // MySQL doesn't support IF NOT EXISTS for indexes in older versions
    s"create index $indexName on $tableName (${columnNames.mkString(", ")})"

  override def createUniqueIndexSql(
      indexName: String,
      tableName: String,
      columnNames: Seq[String],
      ifNotExists: Boolean = true,
  ): String =
    s"create unique index $indexName on $tableName (${columnNames.mkString(", ")})"

  // === MySQL-specific Query Features ===
  // MySQL uses backticks for identifier escaping
  override def identifierQuote: String = "`"

  // === MySQL-specific Table Operations ===
  override def truncateTableSql(tableName: String): String = s"truncate table $tableName"

  override def dropIndexSql(indexName: String, ifExists: Boolean = false): String =
    // MySQL uses different syntax for dropping indexes
    if ifExists then s"drop index if exists $indexName"
    else s"drop index $indexName"

  // === JsonSupport implementation ===
  def jsonType: String = "json"

  def jsonExtractSql(columnName: String, fieldPath: String): String =
    s"JSON_EXTRACT($columnName, '$$.$fieldPath')"

end MySQLDialect

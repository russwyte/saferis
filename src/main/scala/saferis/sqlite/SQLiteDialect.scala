package saferis.sqlite

import saferis.*
import java.sql.Types

/** SQLite dialect implementation providing SQLite-specific type mappings and SQL generation.
  *
  * SQLite characteristics:
  *   - Uses AUTOINCREMENT for auto-increment columns
  *   - Supports IF NOT EXISTS for tables but not for all operations
  *   - Uses double quotes for identifier escaping
  *   - Has a unique type affinity system (simplified here)
  */
object SQLiteDialect extends Dialect with ReturningSupport with CommonTableExpressionSupport with WindowFunctionSupport:

  val name: String = "SQLite"

  // === Type Mappings ===
  def columnType(jdbcType: Int): String = jdbcType match
    case Types.VARCHAR     => "text"
    case Types.LONGVARCHAR => "text"
    case Types.CHAR        => "text"
    case Types.CLOB        => "text"
    case Types.INTEGER     => "integer"
    case Types.BIGINT      => "integer"
    case Types.SMALLINT    => "integer"
    case Types.TINYINT     => "integer"
    case Types.DOUBLE      => "real"
    case Types.FLOAT       => "real"
    case Types.DECIMAL     => "real"
    case Types.NUMERIC     => "real"
    case Types.BOOLEAN     => "integer"
    case Types.DATE        => "text"
    case Types.TIME        => "text"
    case Types.TIMESTAMP   => "text"
    case Types.BLOB        => "blob"
    case Types.BINARY      => "blob"
    case Types.VARBINARY   => "blob"
    case _                 => "text"

  // === Auto-increment Syntax ===
  override def autoIncrementClause(isGenerated: Boolean, isKey: Boolean, hasDefault: Boolean): String =
    (isGenerated, isKey, hasDefault) match
      case (true, true, false)  => " primary key autoincrement"
      case (true, true, true)   => " autoincrement"
      case (false, true, false) => " primary key"
      case (true, false, false) => " autoincrement"
      case _                    => ""

  // === Table Operations ===
  override def addColumnSql(tableName: String, columnName: String, columnDefinition: String): String =
    s"alter table ${escapeIdentifier(tableName)} add column $columnDefinition"

  override def dropColumnSql(tableName: String, columnName: String): String =
    // SQLite has limited support for dropping columns - requires recreating table
    throw new UnsupportedOperationException(
      "SQLite does not support dropping columns directly. Use PRAGMA table_info and recreate table."
    )

  // === Index Operations ===
  override def createIndexSql(
      indexName: String,
      tableName: String,
      columnNames: Seq[String],
      ifNotExists: Boolean = true,
  ): String =
    val ifNotExistsClause = if ifNotExists then " if not exists" else ""
    val columns           = columnNames.map(escapeIdentifier).mkString(", ")
    s"create index$ifNotExistsClause ${escapeIdentifier(indexName)} on ${escapeIdentifier(tableName)} ($columns)"

  override def dropIndexSql(indexName: String, ifExists: Boolean = false): String =
    val ifExistsClause = if ifExists then "if exists " else ""
    s"drop index $ifExistsClause${escapeIdentifier(indexName)}"

  // === SQLite-specific Query Features ===
  override def identifierQuote: String = "\""

  // === SQLite-specific Table Operations ===
  override def truncateTableSql(tableName: String): String =
    // SQLite doesn't have TRUNCATE, use DELETE instead
    s"delete from ${escapeIdentifier(tableName)}"

  // ReturningSupport uses default implementations since SQLite supports RETURNING

end SQLiteDialect

// Provide a given instance for the SQLite dialect
given Dialect = SQLiteDialect

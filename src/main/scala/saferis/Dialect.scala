package saferis

/** Trait representing a database dialect that provides database-specific type mappings and SQL generation.
  *
  * This allows the library to support multiple databases by providing different implementations for each database's
  * specific requirements including column types, auto-increment behavior, index creation, and other SQL syntax
  * variations.
  */
trait Dialect:

  /** Returns the database-specific type string for the given JDBC type.
    *
    * @param jdbcType
    *   The JDBC SQL type constant
    * @return
    *   Database-specific type string
    */
  def columnType(jdbcType: Int): String

  /** Returns the database-specific type string for the given encoder.
    *
    * Default implementation delegates to typeFor(jdbcType), but can be overridden for more specific type mappings.
    *
    * @param encoder
    *   The encoder instance
    * @return
    *   Database-specific type string
    */
  def columnType[A](encoder: Encoder[A]): String = columnType(encoder.jdbcType)

  /** Database name/identifier */
  def name: String

  /** Default length for variable-length types like VARCHAR */
  val DefaultVarcharLength: Int = 255

  // === Auto-increment and Primary Key Support ===

  /** Returns the SQL clause for auto-increment/generated primary key columns.
    *
    * @param isGenerated
    *   Whether the column is marked as generated
    * @param isPrimaryKey
    *   Whether this column is a primary key
    * @param hasCompoundKey
    *   Whether the table has a compound primary key
    * @return
    *   SQL clause for auto-increment behavior (e.g., "GENERATED ALWAYS AS IDENTITY", "AUTO_INCREMENT", etc.)
    */
  def autoIncrementClause(isGenerated: Boolean, isPrimaryKey: Boolean, hasCompoundKey: Boolean): String

  /** Returns the SQL clause for primary key constraint on a single column.
    *
    * @return
    *   SQL clause for primary key (e.g., "PRIMARY KEY")
    */
  def primaryKeyClause: String = "primary key"

  /** Returns the SQL for a compound primary key constraint.
    *
    * @param columnNames
    *   The column names that make up the compound key
    * @return
    *   SQL constraint clause
    */
  def compoundPrimaryKeyClause(columnNames: Seq[String]): String = s"primary key (${columnNames.mkString(", ")})"

  // === Index Creation ===

  /** Returns the SQL for creating a regular index.
    *
    * @param indexName
    *   Name of the index
    * @param tableName
    *   Name of the table
    * @param columnNames
    *   Column names to index
    * @param ifNotExists
    *   Whether to include IF NOT EXISTS clause
    * @return
    *   SQL statement for creating the index
    */
  def createIndexSql(
      indexName: String,
      tableName: String,
      columnNames: Seq[String],
      ifNotExists: Boolean = true,
  ): String =
    val ifNotExistsClause = if ifNotExists then " if not exists" else ""
    s"create index$ifNotExistsClause $indexName on $tableName (${columnNames.mkString(", ")})"
  end createIndexSql

  /** Returns the SQL for creating a unique index.
    *
    * @param indexName
    *   Name of the index
    * @param tableName
    *   Name of the table
    * @param columnNames
    *   Column names to index
    * @param ifNotExists
    *   Whether to include IF NOT EXISTS clause
    * @return
    *   SQL statement for creating the unique index
    */
  def createUniqueIndexSql(
      indexName: String,
      tableName: String,
      columnNames: Seq[String],
      ifNotExists: Boolean = true,
  ): String =
    val ifNotExistsClause = if ifNotExists then " if not exists" else ""
    s"create unique index$ifNotExistsClause $indexName on $tableName (${columnNames.mkString(", ")})"
  end createUniqueIndexSql

  /** Returns the SQL for dropping an index.
    *
    * @param indexName
    *   Name of the index to drop
    * @param ifExists
    *   Whether to include IF EXISTS clause
    * @return
    *   SQL statement for dropping the index
    */
  def dropIndexSql(indexName: String, ifExists: Boolean = false): String =
    val ifExistsClause = if ifExists then " if exists" else ""
    s"drop index$ifExistsClause $indexName"
  end dropIndexSql

  // === Table Operations ===

  /** Returns the SQL for creating a table with IF NOT EXISTS clause.
    *
    * @param ifNotExists
    *   Whether to include IF NOT EXISTS
    * @return
    *   SQL clause for table creation
    */
  def createTableClause(ifNotExists: Boolean): String =
    val ifNotExistsClause = if ifNotExists then " if not exists" else ""
    s"create table$ifNotExistsClause"

  /** Returns the SQL for dropping a table.
    *
    * @param tableName
    *   Name of the table to drop
    * @param ifExists
    *   Whether to include IF EXISTS clause
    * @return
    *   SQL statement for dropping the table
    */
  def dropTableSql(tableName: String, ifExists: Boolean): String =
    val ifExistsClause = if ifExists then " if exists" else ""
    s"drop table$ifExistsClause $tableName"
  end dropTableSql

  /** Returns the SQL for truncating a table.
    *
    * @param tableName
    *   Name of the table to truncate
    * @return
    *   SQL statement for truncating the table
    */
  def truncateTableSql(tableName: String): String = s"truncate table $tableName"

  // === Column Operations ===

  /** Returns the SQL for adding a column to a table.
    *
    * @param tableName
    *   Name of the table
    * @param columnName
    *   Name of the new column
    * @param columnType
    *   Type of the new column
    * @return
    *   SQL statement for adding the column
    */
  def addColumnSql(tableName: String, columnName: String, columnType: String): String =
    s"alter table $tableName add column $columnName $columnType"

  /** Returns the SQL for dropping a column from a table.
    *
    * @param tableName
    *   Name of the table
    * @param columnName
    *   Name of the column to drop
    * @return
    *   SQL statement for dropping the column
    */
  def dropColumnSql(tableName: String, columnName: String): String =
    s"alter table $tableName drop column $columnName"

  // === Query Features ===

  /** Quote character for identifiers (table names, column names, etc.).
    *
    * @return
    *   Quote character (e.g., '"' for PostgreSQL, '`' for MySQL)
    */
  def identifierQuote: String = "\""

  /** Escapes an identifier (table name, column name, etc.) using the database's quoting rules.
    *
    * @param identifier
    *   The identifier to escape
    * @return
    *   Escaped identifier
    */
  def escapeIdentifier(identifier: String): String = s"$identifierQuote$identifier$identifierQuote"

end Dialect

object Dialect:
  /** Get the database-specific type string for an encoder using the current dialect */
  def columnType[A](encoder: Encoder[A])(using dialect: Dialect): String =
    dialect.columnType(encoder)

  /** Get the database-specific type string for a JDBC type using the current dialect */
  def columnType(jdbcType: Int)(using dialect: Dialect): String =
    dialect.columnType(jdbcType)

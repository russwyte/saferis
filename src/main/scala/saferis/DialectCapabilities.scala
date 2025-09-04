package saferis

/** Trait for dialects that support the RETURNING clause in INSERT/UPDATE/DELETE operations */
trait ReturningSupport:
  self: Dialect =>

  /** Returns the SQL for an INSERT statement with RETURNING clause.
    *
    * @param tableName
    *   Name of the table
    * @param insertColumns
    *   Columns being inserted
    * @param returningColumns
    *   Columns to return
    * @return
    *   SQL fragment for INSERT ... RETURNING
    */
  def insertReturningSql(tableName: String, insertColumns: String, returningColumns: String): String =
    s"insert into $tableName $insertColumns returning $returningColumns"

  /** Returns the SQL for an UPDATE statement with RETURNING clause.
    *
    * @param tableName
    *   Name of the table
    * @param setClause
    *   SET clause for the update
    * @param whereClause
    *   WHERE clause for the update
    * @param returningColumns
    *   Columns to return
    * @return
    *   SQL fragment for UPDATE ... RETURNING
    */
  def updateReturningSql(tableName: String, setClause: String, whereClause: String, returningColumns: String): String =
    s"update $tableName set $setClause where $whereClause returning $returningColumns"

  /** Returns the SQL for a DELETE statement with RETURNING clause.
    *
    * @param tableName
    *   Name of the table
    * @param whereClause
    *   WHERE clause for the delete
    * @param returningColumns
    *   Columns to return
    * @return
    *   SQL fragment for DELETE ... RETURNING
    */
  def deleteReturningSql(tableName: String, whereClause: String, returningColumns: String): String =
    s"delete from $tableName where $whereClause returning $returningColumns"
end ReturningSupport

/** Trait for dialects that support IF NOT EXISTS in index creation */
trait IndexIfNotExistsSupport:
  self: Dialect =>

  /** Creates an index with IF NOT EXISTS support.
    *
    * @param indexName
    *   Name of the index
    * @param tableName
    *   Name of the table
    * @param columnNames
    *   Column names to index
    * @param unique
    *   Whether the index should be unique
    * @return
    *   SQL statement for creating the index with IF NOT EXISTS
    */
  def createIndexIfNotExistsSql(
      indexName: String,
      tableName: String,
      columnNames: Seq[String],
      unique: Boolean = false,
  ): String =
    val uniqueClause = if unique then "unique " else ""
    s"create ${uniqueClause}index if not exists $indexName on $tableName (${columnNames.mkString(", ")})"
  end createIndexIfNotExistsSql
end IndexIfNotExistsSupport

/** Trait for dialects that support advanced ALTER TABLE operations */
trait AdvancedAlterTableSupport:
  self: Dialect =>

  /** Returns SQL for renaming a column.
    *
    * @param tableName
    *   Name of the table
    * @param oldColumnName
    *   Current column name
    * @param newColumnName
    *   New column name
    * @return
    *   SQL statement for renaming the column
    */
  def renameColumnSql(tableName: String, oldColumnName: String, newColumnName: String): String =
    s"alter table $tableName rename column $oldColumnName to $newColumnName"

  /** Returns SQL for modifying a column type.
    *
    * @param tableName
    *   Name of the table
    * @param columnName
    *   Name of the column
    * @param newColumnType
    *   New column type
    * @return
    *   SQL statement for modifying the column type
    */
  def modifyColumnTypeSql(tableName: String, columnName: String, newColumnType: String): String =
    s"alter table $tableName alter column $columnName type $newColumnType"
end AdvancedAlterTableSupport

/** Trait for dialects that support UPSERT operations */
trait UpsertSupport:
  self: Dialect =>

  /** Returns SQL for an UPSERT operation.
    *
    * @param tableName
    *   Name of the table
    * @param insertColumns
    *   Columns for insertion
    * @param conflictColumns
    *   Columns that define the conflict
    * @param updateColumns
    *   Columns to update on conflict
    * @return
    *   SQL statement for UPSERT
    */
  def upsertSql(tableName: String, insertColumns: String, conflictColumns: Seq[String], updateColumns: String): String
end UpsertSupport

/** Trait for dialects that support JSON operations */
trait JsonSupport:
  self: Dialect =>

  /** Returns the SQL type for JSON columns */
  def jsonType: String

  /** Returns SQL for extracting a JSON field.
    *
    * @param columnName
    *   Name of the JSON column
    * @param fieldPath
    *   Path to the field (e.g., "user.name")
    * @return
    *   SQL expression for field extraction
    */
  def jsonExtractSql(columnName: String, fieldPath: String): String
end JsonSupport

/** Trait for dialects that support array operations */
trait ArraySupport:
  self: Dialect =>

  /** Returns the SQL type for array columns.
    *
    * @param elementType
    *   The type of array elements
    * @return
    *   SQL type for arrays
    */
  def arrayType(elementType: String): String

  /** Returns SQL for checking if an array contains a value.
    *
    * @param columnName
    *   Name of the array column
    * @param value
    *   Value to check for
    * @return
    *   SQL expression for array containment
    */
  def arrayContainsSql(columnName: String, value: String): String
end ArraySupport

/** Trait for dialects that support window functions */
trait WindowFunctionSupport:
  self: Dialect =>

  /** Returns SQL for ROW_NUMBER() window function.
    *
    * @param partitionBy
    *   Columns to partition by
    * @param orderBy
    *   Columns to order by
    * @return
    *   SQL expression for ROW_NUMBER()
    */
  def rowNumberSql(partitionBy: Seq[String], orderBy: Seq[String]): String =
    val partitionClause = if partitionBy.nonEmpty then s" partition by ${partitionBy.mkString(", ")}" else ""
    val orderClause     = if orderBy.nonEmpty then s" order by ${orderBy.mkString(", ")}" else ""
    s"row_number() over($partitionClause$orderClause)"
end WindowFunctionSupport

/** Trait for dialects that support common table expressions (CTEs) */
trait CommonTableExpressionSupport:
  self: Dialect =>

  /** Returns SQL for a WITH clause.
    *
    * @param cteName
    *   Name of the CTE
    * @param cteQuery
    *   Query for the CTE
    * @param recursive
    *   Whether the CTE is recursive
    * @return
    *   SQL fragment for WITH clause
    */
  def withClauseSql(cteName: String, cteQuery: String, recursive: Boolean = false): String =
    val recursiveClause = if recursive then "recursive " else ""
    s"with $recursiveClause$cteName as ($cteQuery)"
end CommonTableExpressionSupport

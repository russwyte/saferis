package saferis

import zio.*

/** Specialized DML operations that are only available when dialects support specific features */
object SpecializedDML:

  /** Insert with RETURNING clause - only available for dialects that support it */
  inline def insertReturning[A <: Product](entity: A)(using
      table: Table[A],
      dialect: Dialect & ReturningSupport,
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Option[A]] =
    val tableName        = table.name
    val insertColumns    = table.insertColumnsSql.sql
    val returningColumns = table.returningColumnsSql.sql
    val insertSql        = dialect.insertReturningSql(tableName, insertColumns, returningColumns)

    val placeholders = table.insertPlaceholders(entity)
    val sql          = SqlFragment(insertSql, placeholders.flatMap(_.writes))

    sql.queryOne[A]
  end insertReturning

  /** Update with RETURNING clause - only available for dialects that support it */
  inline def updateReturning[A <: Product](entity: A)(using
      table: Table[A],
      dialect: Dialect & ReturningSupport,
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Option[A]] =
    val tableName        = table.name
    val setClause        = table.updateSetClause(entity).sql
    val whereClause      = table.updateWhereClause(entity).sql
    val returningColumns = table.returningColumnsSql.sql
    val updateSql        = dialect.updateReturningSql(tableName, setClause, whereClause, returningColumns)

    val updateWrites = table.updateSetClause(entity).writes
    val keyWrites    = table.updateWhereClause(entity).writes
    val sql          = SqlFragment(updateSql, updateWrites ++ keyWrites)

    sql.queryOne[A]
  end updateReturning

  /** Delete with RETURNING clause - only available for dialects that support it */
  inline def deleteReturning[A <: Product](entity: A)(using
      table: Table[A],
      dialect: Dialect & ReturningSupport,
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Option[A]] =
    val tableName        = table.name
    val whereClause      = table.updateWhereClause(entity).sql
    val returningColumns = table.returningColumnsSql.sql
    val deleteSql        = dialect.deleteReturningSql(tableName, whereClause, returningColumns)

    val keyWrites = table.updateWhereClause(entity).writes
    val sql       = SqlFragment(deleteSql, keyWrites)

    sql.queryOne[A]
  end deleteReturning

  /** UPSERT operation - only available for dialects that support it */
  inline def upsert[A <: Product](entity: A, conflictColumns: Seq[String])(using
      table: Table[A],
      dialect: Dialect & UpsertSupport,
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Int] =
    val tableName     = table.name
    val insertColumns = table.insertColumnsSql.sql
    val updateColumns = table.updateSetClause(entity).sql
    val upsertSql     = dialect.upsertSql(tableName, insertColumns, conflictColumns, updateColumns)

    val insertWrites = table.insertPlaceholders(entity).flatMap(_.writes)
    val updateWrites = table.updateSetClause(entity).writes
    val sql          = SqlFragment(upsertSql, insertWrites ++ updateWrites)

    sql.dml
  end upsert

  /** Create index with IF NOT EXISTS - only available for dialects that support it */
  inline def createIndexIfNotExists[A <: Product](
      indexName: String,
      columnNames: Seq[String],
      unique: Boolean = false,
  )(using
      table: Table[A],
      dialect: Dialect & IndexIfNotExistsSupport,
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Int] =
    val tableName = table.name
    val sql       = SqlFragment(dialect.createIndexIfNotExistsSql(indexName, tableName, columnNames, unique), Seq.empty)
    sql.dml
  end createIndexIfNotExists

  /** JSON field extraction - only available for dialects that support JSON */
  def jsonExtract(columnName: String, fieldPath: String)(using dialect: Dialect & JsonSupport): SqlFragment =
    SqlFragment(dialect.jsonExtractSql(columnName, fieldPath), Seq.empty)

  /** Array containment check - only available for dialects that support arrays */
  def arrayContains(columnName: String, value: String)(using dialect: Dialect & ArraySupport): SqlFragment =
    SqlFragment(dialect.arrayContainsSql(columnName, value), Seq.empty)

  /** Get SQL for UPSERT operation - only available for dialects that support it */
  def upsertSql[A <: Product](
      insertColumns: String,
      conflictColumns: Seq[String],
      updateColumns: String,
  )(using table: Table[A], dialect: Dialect & UpsertSupport): String =
    val tableName = summon[Table[A]].name
    dialect.upsertSql(tableName, insertColumns, conflictColumns, updateColumns)

end SpecializedDML

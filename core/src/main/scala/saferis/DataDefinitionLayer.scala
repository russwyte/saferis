package saferis

import zio.Scope
import zio.Trace
import zio.ZIO

val ddl = DataDefinitionLayer // short alias

object DataDefinitionLayer:

  inline def createTable[A <: Product](
      ifNotExists: Boolean = true,
      createIndexes: Boolean = true,
  )(using table: Table[A], dialect: Dialect)(using trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    // Separate key columns for compound key handling
    val keyColumns     = table.columns.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    val columnDefs = table.columns.map { col =>
      val baseType      = sqlTypeFromColumn(col)
      val notNullClause = if !col.isNullable then " not null" else ""
      val defaultClause = col.defaultClause.map(d => s" $d").getOrElse("")
      val autoIncrement = dialect.autoIncrementClause(col.isGenerated, col.isKey, hasCompoundKey)
      s"${col.label} $baseType$notNullClause$defaultClause$autoIncrement"
    }

    // Add compound primary key constraint if needed
    val primaryKeyConstraint = Option.when(hasCompoundKey) {
      val keyColumnNames = keyColumns.map(_.label)
      dialect.compoundPrimaryKeyClause(keyColumnNames)
    }

    val allConstraints = columnDefs ++ primaryKeyConstraint.toSeq
    val tableName      = table.name
    val createClause   = dialect.createTableClause(ifNotExists)
    val sql            = SqlFragment(s"$createClause $tableName (${allConstraints.mkString(", ")})", Seq.empty)

    for
      result <- sql.dml
      _      <- if createIndexes then DataDefinitionLayer.createIndexes[A]().unit else ZIO.unit
    yield result
  end createTable

  /** Create a table with foreign key constraints from an Instance.
    *
    * Usage:
    * {{{
    *   import saferis.TableAspects.*
    *
    *   val orders = Table[Order]
    *     @@ foreignKey[Order, Int](_.userId).references[User](_.id).onDelete(Cascade)
    *
    *   createTable(orders)
    * }}}
    */
  def createTable[A <: Product](
      instance: Instance[A]
  )(using dialect: Dialect)(using trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    createTable(instance, ifNotExists = true, createIndexes = true)

  def createTable[A <: Product](
      instance: Instance[A],
      ifNotExists: Boolean,
      createIndexes: Boolean,
  )(using dialect: Dialect)(using trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    // Use instance's columns (dealiased for DDL)
    val cols           = instance.columns.map(_.withTableAlias(None))
    val keyColumns     = cols.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    val columnDefs = cols.map { col =>
      val baseType      = sqlTypeFromColumn(col)
      val notNullClause = if !col.isNullable then " not null" else ""
      val defaultClause = col.defaultClause.map(d => s" $d").getOrElse("")
      val autoIncrement = dialect.autoIncrementClause(col.isGenerated, col.isKey, hasCompoundKey)
      s"${col.label} $baseType$notNullClause$defaultClause$autoIncrement"
    }

    val primaryKeyConstraint = Option.when(hasCompoundKey) {
      val keyColumnNames = keyColumns.map(_.label)
      dialect.compoundPrimaryKeyClause(keyColumnNames)
    }

    // Add unique constraints from Schema DSL
    val uniqueConstraintsSql = instance.uniqueConstraintsSql

    // Add foreign key constraints from instance
    val foreignKeyConstraints = instance.foreignKeyConstraints

    val allConstraints = columnDefs ++ primaryKeyConstraint.toSeq ++ uniqueConstraintsSql ++ foreignKeyConstraints
    val tableName      = instance.tableName
    val createClause   = dialect.createTableClause(ifNotExists)
    val sql            = SqlFragment(s"$createClause $tableName (${allConstraints.mkString(", ")})", Seq.empty)

    for
      result <- sql.dml
      _      <- if createIndexes then createIndexesFromInstance(instance).unit else ZIO.unit
    yield result
  end createTable

  /** Create indexes for an Instance from Schema-defined IndexSpecs */
  private def createIndexesFromInstance[A <: Product](instance: Instance[A])(using
      dialect: Dialect
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Seq[Int]] =
    val tableName      = instance.tableName
    val cols           = instance.columns.map(_.withTableAlias(None))
    val keyColumns     = cols.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    // Create indexes from Schema-defined IndexSpecs
    val aspectIndexes = instance.indexes.map { spec =>
      val createSql = spec.toCreateSql(tableName)
      val sql       = dialect match
        case d: IndexIfNotExistsSupport =>
          val indexName = spec.name.getOrElse(s"idx_${tableName}_${spec.columns.mkString("_")}")
          SqlFragment(
            if spec.unique then
              d.createIndexIfNotExistsSql(indexName, tableName, spec.columns, unique = true, where = spec.where)
            else d.createIndexIfNotExistsSql(indexName, tableName, spec.columns, unique = false, where = spec.where),
            Seq.empty,
          )
        case _ => SqlFragment(createSql, Seq.empty)
      sql.dml
    }

    // For compound keys, create a compound index on all key columns
    val compoundKeyIndex = Option.when(hasCompoundKey) {
      val keyColumnNames    = keyColumns.map(_.label)
      val compoundIndexName = s"idx_${tableName}_compound_key"
      val sql               = dialect match
        case d: IndexIfNotExistsSupport =>
          SqlFragment(d.createIndexIfNotExistsSql(compoundIndexName, tableName, keyColumnNames), Seq.empty)
        case _ => SqlFragment(dialect.createIndexSql(compoundIndexName, tableName, keyColumnNames, false), Seq.empty)
      sql.dml
    }

    ZIO.collectAll(aspectIndexes ++ compoundKeyIndex.toSeq)
  end createIndexesFromInstance

  private def sqlTypeFromColumn[R](col: Column[R])(using dialect: Dialect): String = col.columnType

  inline def dropTable[A <: Product](ifExists: Boolean = false)(using
      table: Table[A],
      dialect: Dialect,
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName = table.name
    val sql       = SqlFragment(dialect.dropTableSql(tableName, ifExists), Seq.empty)
    sql.dml

  inline def truncateTable[A <: Product]()(using
      table: Table[A],
      dialect: Dialect,
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName = table.name
    val sql       = SqlFragment(dialect.truncateTableSql(tableName), Seq.empty)
    sql.dml

  inline def addColumn[A <: Product, T](columnName: String)(using
      table: Table[A],
      encoder: Encoder[T],
      dialect: Dialect,
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName  = table.name
    val columnType = dialect.columnType(encoder.jdbcType)
    val sql        = SqlFragment(dialect.addColumnSql(tableName, columnName, columnType), Seq.empty)
    sql.dml
  end addColumn

  inline def dropColumn[A <: Product](columnName: String)(using
      table: Table[A],
      dialect: Dialect,
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName = table.name
    val sql       = SqlFragment(dialect.dropColumnSql(tableName, columnName), Seq.empty)
    sql.dml

  inline def createIndex[A <: Product](
      indexName: String,
      columnNames: Seq[String],
      unique: Boolean = false,
      where: Option[String] = None,
  )(using table: Table[A], dialect: Dialect)(using trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName = table.name
    val sql       =
      if unique then
        SqlFragment(dialect.createUniqueIndexSql(indexName, tableName, columnNames, where = where), Seq.empty)
      else SqlFragment(dialect.createIndexSql(indexName, tableName, columnNames, where = where), Seq.empty)
    sql.dml
  end createIndex

  /** Returns CREATE INDEX SQL for compound key indexes only. Use Instance-based createTable with @@ index aspects for
    * custom indexes.
    */
  inline def createIndexesSql[A <: Product]()(using table: Table[A], dialect: Dialect): String =
    val tableName      = table.name
    val keyColumns     = table.columns.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    // For compound keys, create a compound index on all key columns
    val compoundKeyIndex = Option.when(hasCompoundKey) {
      val keyColumnNames    = keyColumns.map(_.label)
      val compoundIndexName = s"idx_${tableName}_compound_key"
      dialect match
        case d: IndexIfNotExistsSupport => d.createIndexIfNotExistsSql(compoundIndexName, tableName, keyColumnNames)
        case _                          => dialect.createIndexSql(compoundIndexName, tableName, keyColumnNames, false)
    }

    compoundKeyIndex.toSeq.mkString("\n")
  end createIndexesSql

  /** Creates compound key indexes only. Use Instance-based createTable with @@ index aspects for custom indexes.
    */
  inline def createIndexes[A <: Product]()(using
      table: Table[A],
      dialect: Dialect,
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Seq[Int]] =
    val tableName      = table.name
    val keyColumns     = table.columns.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    // For compound keys, create a compound index on all key columns
    val compoundKeyIndex = Option.when(hasCompoundKey) {
      val keyColumnNames    = keyColumns.map(_.label)
      val compoundIndexName = s"idx_${tableName}_compound_key"
      val sql               = dialect match
        case d: IndexIfNotExistsSupport =>
          SqlFragment(d.createIndexIfNotExistsSql(compoundIndexName, tableName, keyColumnNames), Seq.empty)
        case _ => SqlFragment(dialect.createIndexSql(compoundIndexName, tableName, keyColumnNames, false), Seq.empty)
      sql.dml
    }

    ZIO.collectAll(compoundKeyIndex.toSeq)
  end createIndexes

  inline def dropIndex(indexName: String, ifExists: Boolean = false)(using
      dialect: Dialect
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val sql = SqlFragment(dialect.dropIndexSql(indexName, ifExists), Seq.empty)
    sql.dml

end DataDefinitionLayer

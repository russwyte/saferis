package saferis

import zio.Scope
import zio.ZIO
import zio.Trace
import scala.reflect.ClassTag

val ddl = DataDefinitionLayer // short alias

object DataDefinitionLayer:

  inline def createTable[A <: Product: Table as table](
      ifNotExists: Boolean = true,
      createIndexes: Boolean = true,
  )(using dialect: Dialect, trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    // Separate key columns for compound key handling
    val keyColumns     = table.columns.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    val columnDefs = table.columns.map { col =>
      val baseType = sqlTypeFromColumn(col)
      val constraints = dialect.autoIncrementClause(col.isGenerated, col.isKey, hasCompoundKey) +
        (if col.isUnique then " unique" else "")
      s"${col.label} $baseType$constraints"
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

  private def sqlTypeFromColumn[R](col: Column[R])(using dialect: Dialect): String = col.columnType

  inline def dropTable[A <: Product: Table as table](ifExists: Boolean = false)(using
      dialect: Dialect,
      trace: Trace,
  ): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName = table.name
    val sql       = SqlFragment(dialect.dropTableSql(tableName, ifExists), Seq.empty)
    sql.dml

  inline def truncateTable[A <: Product: Table as table]()(using
      dialect: Dialect,
      trace: Trace,
  ): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName = table.name
    val sql       = SqlFragment(dialect.truncateTableSql(tableName), Seq.empty)
    sql.dml

  inline def addColumn[A <: Product: Table as table, T: Encoder as encoder: ClassTag as classTag](columnName: String)(
      using
      dialect: Dialect,
      trace: Trace,
  ): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName  = table.name
    val columnType = dialect.columnType(using encoder, classTag)
    val sql        = SqlFragment(dialect.addColumnSql(tableName, columnName, columnType), Seq.empty)
    sql.dml
  end addColumn

  inline def dropColumn[A <: Product: Table as table](columnName: String)(using
      dialect: Dialect,
      trace: Trace,
  ): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName = table.name
    val sql       = SqlFragment(dialect.dropColumnSql(tableName, columnName), Seq.empty)
    sql.dml

  inline def createIndex[A <: Product: Table as table](
      indexName: String,
      columnNames: Seq[String],
      unique: Boolean = false,
  )(using dialect: Dialect, trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName = table.name
    val sql =
      if unique then SqlFragment(dialect.createUniqueIndexSql(indexName, tableName, columnNames), Seq.empty)
      else SqlFragment(dialect.createIndexSql(indexName, tableName, columnNames), Seq.empty)
    sql.dml
  end createIndex

  inline def createIndexesSql[A <: Product: Table as table]()(using dialect: Dialect): String =
    val tableName      = table.name
    val keyColumns     = table.columns.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    // Create regular indexes for @indexed fields
    val indexedIndexes = table.indexedColumns.map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      dialect match
        case d: IndexIfNotExistsSupport => d.createIndexIfNotExistsSql(indexName, tableName, Seq(col.label))
        case _                          => dialect.createIndexSql(indexName, tableName, Seq(col.label), false)
    }

    // Create unique indexes for @uniqueIndex fields
    val uniqueIndexes = table.uniqueIndexColumns.map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      dialect match
        case d: IndexIfNotExistsSupport =>
          d.createIndexIfNotExistsSql(indexName, tableName, Seq(col.label), unique = true)
        case _ => dialect.createUniqueIndexSql(indexName, tableName, Seq(col.label), false)
    }

    // For compound keys, also create a compound index on all key columns for better query performance
    val compoundKeyIndex = Option.when(hasCompoundKey) {
      val keyColumnNames    = keyColumns.map(_.label)
      val compoundIndexName = s"idx_${tableName}_compound_key"
      dialect match
        case d: IndexIfNotExistsSupport => d.createIndexIfNotExistsSql(compoundIndexName, tableName, keyColumnNames)
        case _                          => dialect.createIndexSql(compoundIndexName, tableName, keyColumnNames, false)
    }

    (indexedIndexes ++ uniqueIndexes ++ compoundKeyIndex.toSeq).mkString("\n")
  end createIndexesSql

  inline def createIndexes[A <: Product: Table as table]()(using
      dialect: Dialect,
      trace: Trace,
  ): ZIO[ConnectionProvider & Scope, Throwable, Seq[Int]] =
    val tableName      = table.name
    val keyColumns     = table.columns.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    // Create regular indexes for @indexed fields
    val indexedIndexes = table.indexedColumns.map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      val sql = dialect match
        case d: IndexIfNotExistsSupport =>
          SqlFragment(d.createIndexIfNotExistsSql(indexName, tableName, Seq(col.label)), Seq.empty)
        case _ => SqlFragment(dialect.createIndexSql(indexName, tableName, Seq(col.label), false), Seq.empty)
      sql.dml
    }

    // Create unique indexes for @uniqueIndex fields
    val uniqueIndexes = table.uniqueIndexColumns.map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      val sql = dialect match
        case d: IndexIfNotExistsSupport =>
          SqlFragment(d.createIndexIfNotExistsSql(indexName, tableName, Seq(col.label), unique = true), Seq.empty)
        case _ => SqlFragment(dialect.createUniqueIndexSql(indexName, tableName, Seq(col.label), false), Seq.empty)
      sql.dml
    }

    // For compound keys, also create a compound index on all key columns for better query performance
    val compoundKeyIndex = Option.when(hasCompoundKey) {
      val keyColumnNames    = keyColumns.map(_.label)
      val compoundIndexName = s"idx_${tableName}_compound_key"
      val sql = dialect match
        case d: IndexIfNotExistsSupport =>
          SqlFragment(d.createIndexIfNotExistsSql(compoundIndexName, tableName, keyColumnNames), Seq.empty)
        case _ => SqlFragment(dialect.createIndexSql(compoundIndexName, tableName, keyColumnNames, false), Seq.empty)
      sql.dml
    }

    ZIO.collectAll(indexedIndexes ++ uniqueIndexes ++ compoundKeyIndex.toSeq)
  end createIndexes

  inline def dropIndex(indexName: String, ifExists: Boolean = false)(using
      dialect: Dialect,
      trace: Trace,
  ): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val sql = SqlFragment(dialect.dropIndexSql(indexName, ifExists), Seq.empty)
    sql.dml

end DataDefinitionLayer

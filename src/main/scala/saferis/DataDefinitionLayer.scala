package saferis

import zio.Scope
import zio.ZIO
import zio.Trace

object DataDefinitionLayer:

  inline def createTable[A <: Product: Table as table](
      ifNotExists: Boolean = true,
      createIndexes: Boolean = true,
  )(using Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val ifNotExistsClause = if ifNotExists then " if not exists" else ""

    // Separate key columns for compound key handling
    val keyColumns     = table.columns.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    val columnDefs = table.columns.map { col =>
      val baseType = sqlTypeFromColumn(col)
      val constraints =
        if col.isKey && col.isGenerated && !hasCompoundKey then " generated always as identity primary key"
        else if col.isKey && col.isGenerated then " generated always as identity"
        else if col.isKey && !hasCompoundKey then " primary key"
        else if col.isUniqueIndex then " unique"
        else ""
      s"${col.label} $baseType$constraints"
    }

    // Add compound primary key constraint if needed
    val primaryKeyConstraint = Option.when(hasCompoundKey) {
      val keyColumnNames = keyColumns.map(_.label).mkString(", ")
      s"primary key ($keyColumnNames)"
    }

    val allConstraints = columnDefs ++ primaryKeyConstraint.toSeq
    val tableName      = table.name
    val sql = SqlFragment(s"create table$ifNotExistsClause $tableName (${allConstraints.mkString(", ")})", Seq.empty)

    for
      result <- sql.dml
      _      <- if createIndexes then DataDefinitionLayer.createIndexes[A]().unit else ZIO.unit
    yield result
  end createTable

  private def sqlTypeFromColumn[R](col: Column[R]): String = col.postgresType

  inline def dropTable[A <: Product: Table as table](ifExists: Boolean = false)(using
      Trace
  ): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val ifExistsClause = if ifExists then " if exists" else ""
    val tableName      = table.name
    val sql            = SqlFragment(s"drop table$ifExistsClause $tableName", Seq.empty)
    sql.dml

  inline def truncateTable[A <: Product: Table as table]()(using
      Trace
  ): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName = table.name
    val sql       = SqlFragment(s"truncate table $tableName", Seq.empty)
    sql.dml

  inline def addColumn[A <: Product: Table as table, T: Encoder as encoder](columnName: String)(using
      Trace
  ): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName  = table.name
    val columnType = encoder.postgresType
    val sql        = SqlFragment(s"alter table $tableName add column $columnName $columnType", Seq.empty)
    sql.dml

  inline def dropColumn[A <: Product: Table as table](columnName: String)(using
      Trace
  ): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName = table.name
    val sql       = SqlFragment(s"alter table $tableName drop column $columnName", Seq.empty)
    sql.dml

  inline def createIndex[A <: Product: Table as table](
      indexName: String,
      columnNames: Seq[String],
      unique: Boolean = false,
  )(using Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val uniqueClause = if unique then "unique " else ""
    val tableName    = table.name
    val sql =
      SqlFragment(s"create ${uniqueClause}index $indexName on $tableName (${columnNames.mkString(", ")})", Seq.empty)
    sql.dml
  end createIndex

  inline def createIndexesSql[A <: Product: Table as table](): String =
    val tableName      = table.name
    val keyColumns     = table.columns.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    // Create regular indexes for @indexed fields
    val indexedIndexes = table.indexedColumns.map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      s"CREATE INDEX IF NOT EXISTS $indexName ON $tableName (${col.label})"
    }

    // Create unique indexes for @uniqueIndex fields
    val uniqueIndexes = table.uniqueIndexColumns.map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      s"CREATE UNIQUE INDEX IF NOT EXISTS $indexName ON $tableName (${col.label})"
    }

    // For compound keys, also create a compound index on all key columns for better query performance
    val compoundKeyIndex = Option.when(hasCompoundKey) {
      val keyColumnNames    = keyColumns.map(_.label).mkString(", ")
      val compoundIndexName = s"idx_${tableName}_compound_key"
      s"CREATE INDEX IF NOT EXISTS $compoundIndexName ON $tableName ($keyColumnNames)"
    }

    (indexedIndexes ++ uniqueIndexes ++ compoundKeyIndex.toSeq).mkString("\n")
  end createIndexesSql

  inline def createIndexes[A <: Product: Table as table]()(using
      Trace
  ): ZIO[ConnectionProvider & Scope, Throwable, Seq[Int]] =
    val tableName      = table.name
    val keyColumns     = table.columns.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    // Create regular indexes for @indexed fields
    val indexedIndexes = table.indexedColumns.map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      val sql       = SqlFragment(s"create index if not exists $indexName on $tableName (${col.label})", Seq.empty)
      sql.dml
    }

    // Create unique indexes for @uniqueIndex fields
    val uniqueIndexes = table.uniqueIndexColumns.map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      val sql = SqlFragment(s"create unique index if not exists $indexName on $tableName (${col.label})", Seq.empty)
      sql.dml
    }

    // For compound keys, also create a compound index on all key columns for better query performance
    val compoundKeyIndex = Option.when(hasCompoundKey) {
      val keyColumnNames    = keyColumns.map(_.label).mkString(", ")
      val compoundIndexName = s"idx_${tableName}_compound_key"
      val sql = SqlFragment(s"create index if not exists $compoundIndexName on $tableName ($keyColumnNames)", Seq.empty)
      sql.dml
    }

    ZIO.collectAll(indexedIndexes ++ uniqueIndexes ++ compoundKeyIndex.toSeq)
  end createIndexes

  inline def dropIndex(indexName: String)(using
      Trace
  ): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val sql = SqlFragment(s"drop index $indexName", Seq.empty)
    sql.dml

end DataDefinitionLayer

package saferis

import zio.Scope
import zio.ZIO
import zio.Trace

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
      val baseType      = sqlTypeFromColumn(col)
      val notNullClause = if !col.isNullable then " not null" else ""
      val defaultClause = col.defaultClause.map(d => s" $d").getOrElse("")
      val autoIncrement = dialect.autoIncrementClause(col.isGenerated, col.isKey, hasCompoundKey)
      // Only add inline UNIQUE for columns without a uniqueGroup (single-column unique constraints)
      val uniqueClause = if col.isUnique && col.uniqueGroup.isEmpty then " unique" else ""
      s"${col.label} $baseType$notNullClause$defaultClause$autoIncrement$uniqueClause"
    }

    // Add compound primary key constraint if needed
    val primaryKeyConstraint = Option.when(hasCompoundKey) {
      val keyColumnNames = keyColumns.map(_.label)
      dialect.compoundPrimaryKeyClause(keyColumnNames)
    }

    // Group columns by uniqueGroup for compound unique constraints
    val compoundUniqueConstraints = table.columns
      .filter(col => col.isUnique && col.uniqueGroup.isDefined)
      .groupBy(_.uniqueGroup.get)
      .map { case (constraintName, cols) =>
        val columnNames = cols.map(_.label)
        s"constraint ${dialect.escapeIdentifier(constraintName)} unique (${columnNames.mkString(", ")})"
      }
      .toSeq

    val allConstraints = columnDefs ++ primaryKeyConstraint.toSeq ++ compoundUniqueConstraints
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
  )(using dialect: Dialect, trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    createTable(instance, ifNotExists = true, createIndexes = true)

  def createTable[A <: Product](
      instance: Instance[A],
      ifNotExists: Boolean,
      createIndexes: Boolean,
  )(using dialect: Dialect, trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    // Use instance's columns (dealiased for DDL)
    val cols           = instance.columns.map(_.withTableAlias(None))
    val keyColumns     = cols.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    val columnDefs = cols.map { col =>
      val baseType      = sqlTypeFromColumn(col)
      val notNullClause = if !col.isNullable then " not null" else ""
      val defaultClause = col.defaultClause.map(d => s" $d").getOrElse("")
      val autoIncrement = dialect.autoIncrementClause(col.isGenerated, col.isKey, hasCompoundKey)
      val uniqueClause  = if col.isUnique && col.uniqueGroup.isEmpty then " unique" else ""
      s"${col.label} $baseType$notNullClause$defaultClause$autoIncrement$uniqueClause"
    }

    val primaryKeyConstraint = Option.when(hasCompoundKey) {
      val keyColumnNames = keyColumns.map(_.label)
      dialect.compoundPrimaryKeyClause(keyColumnNames)
    }

    val compoundUniqueConstraints = cols
      .filter(col => col.isUnique && col.uniqueGroup.isDefined)
      .groupBy(_.uniqueGroup.get)
      .map { case (constraintName, groupCols) =>
        val columnNames = groupCols.map(_.label)
        s"constraint ${dialect.escapeIdentifier(constraintName)} unique (${columnNames.mkString(", ")})"
      }
      .toSeq

    // Add foreign key constraints from instance
    val foreignKeyConstraints = instance.foreignKeyConstraints

    val allConstraints = columnDefs ++ primaryKeyConstraint.toSeq ++ compoundUniqueConstraints ++ foreignKeyConstraints
    val tableName      = instance.tableName
    val createClause   = dialect.createTableClause(ifNotExists)
    val sql            = SqlFragment(s"$createClause $tableName (${allConstraints.mkString(", ")})", Seq.empty)

    for
      result <- sql.dml
      _      <- if createIndexes then createIndexesFromInstance(instance).unit else ZIO.unit
    yield result
  end createTable

  /** Create indexes for an Instance */
  private def createIndexesFromInstance[A <: Product](instance: Instance[A])(using
      dialect: Dialect,
      trace: Trace,
  ): ZIO[ConnectionProvider & Scope, Throwable, Seq[Int]] =
    val tableName      = instance.tableName
    val cols           = instance.columns.map(_.withTableAlias(None))
    val keyColumns     = cols.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    val singleColumnIndexes = instance.indexedColumns.filter(_.indexGroup.isEmpty).map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      val sql       = dialect match
        case d: IndexIfNotExistsSupport =>
          SqlFragment(
            d.createIndexIfNotExistsSql(indexName, tableName, Seq(col.label), where = col.indexCondition),
            Seq.empty,
          )
        case _ =>
          SqlFragment(
            dialect.createIndexSql(indexName, tableName, Seq(col.label), false, col.indexCondition),
            Seq.empty,
          )
      sql.dml
    }

    val compoundIndexes = instance.indexedColumns
      .filter(_.indexGroup.isDefined)
      .groupBy(_.indexGroup.get)
      .map { case (indexName, indexCols) =>
        val columnNames = indexCols.map(_.label)
        val condition   = indexCols.flatMap(_.indexCondition).headOption
        val sql         = dialect match
          case d: IndexIfNotExistsSupport =>
            SqlFragment(d.createIndexIfNotExistsSql(indexName, tableName, columnNames, where = condition), Seq.empty)
          case _ => SqlFragment(dialect.createIndexSql(indexName, tableName, columnNames, false, condition), Seq.empty)
        sql.dml
      }
      .toSeq

    val singleColumnUniqueIndexes = instance.uniqueIndexColumns.filter(_.uniqueIndexGroup.isEmpty).map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      val sql       = dialect match
        case d: IndexIfNotExistsSupport =>
          SqlFragment(
            d.createIndexIfNotExistsSql(
              indexName,
              tableName,
              Seq(col.label),
              unique = true,
              where = col.uniqueIndexCondition,
            ),
            Seq.empty,
          )
        case _ =>
          SqlFragment(
            dialect.createUniqueIndexSql(indexName, tableName, Seq(col.label), false, col.uniqueIndexCondition),
            Seq.empty,
          )
      sql.dml
    }

    val compoundUniqueIndexes = instance.uniqueIndexColumns
      .filter(_.uniqueIndexGroup.isDefined)
      .groupBy(_.uniqueIndexGroup.get)
      .map { case (indexName, indexCols) =>
        val columnNames = indexCols.map(_.label)
        val condition   = indexCols.flatMap(_.uniqueIndexCondition).headOption
        val sql         = dialect match
          case d: IndexIfNotExistsSupport =>
            SqlFragment(
              d.createIndexIfNotExistsSql(indexName, tableName, columnNames, unique = true, where = condition),
              Seq.empty,
            )
          case _ =>
            SqlFragment(dialect.createUniqueIndexSql(indexName, tableName, columnNames, false, condition), Seq.empty)
        sql.dml
      }
      .toSeq

    val compoundKeyIndex = Option.when(hasCompoundKey) {
      val keyColumnNames    = keyColumns.map(_.label)
      val compoundIndexName = s"idx_${tableName}_compound_key"
      val sql               = dialect match
        case d: IndexIfNotExistsSupport =>
          SqlFragment(d.createIndexIfNotExistsSql(compoundIndexName, tableName, keyColumnNames), Seq.empty)
        case _ => SqlFragment(dialect.createIndexSql(compoundIndexName, tableName, keyColumnNames, false), Seq.empty)
      sql.dml
    }

    ZIO.collectAll(
      singleColumnIndexes ++ compoundIndexes ++ singleColumnUniqueIndexes ++ compoundUniqueIndexes ++ compoundKeyIndex.toSeq
    )
  end createIndexesFromInstance

  /** Returns the CREATE TABLE SQL for an Instance without executing it. Pretty-printed with newlines. */
  def createTableSql[A <: Product](instance: Instance[A])(using dialect: Dialect): String =
    createTableSql(instance, ifNotExists = true)

  /** Returns the CREATE TABLE SQL for an Instance without executing it. Pretty-printed with newlines. */
  def createTableSql[A <: Product](instance: Instance[A], ifNotExists: Boolean)(using
      dialect: Dialect
  ): String =
    val cols           = instance.columns.map(_.withTableAlias(None))
    val keyColumns     = cols.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    val columnDefs = cols.map { col =>
      val baseType      = sqlTypeFromColumn(col)
      val notNullClause = if !col.isNullable then " not null" else ""
      val defaultClause = col.defaultClause.map(d => s" $d").getOrElse("")
      val autoIncrement = dialect.autoIncrementClause(col.isGenerated, col.isKey, hasCompoundKey)
      val uniqueClause  = if col.isUnique && col.uniqueGroup.isEmpty then " unique" else ""
      s"${col.label} $baseType$notNullClause$defaultClause$autoIncrement$uniqueClause"
    }

    val primaryKeyConstraint = Option.when(hasCompoundKey) {
      val keyColumnNames = keyColumns.map(_.label)
      dialect.compoundPrimaryKeyClause(keyColumnNames)
    }

    val compoundUniqueConstraints = cols
      .filter(col => col.isUnique && col.uniqueGroup.isDefined)
      .groupBy(_.uniqueGroup.get)
      .map { case (constraintName, groupCols) =>
        val columnNames = groupCols.map(_.label)
        s"constraint ${dialect.escapeIdentifier(constraintName)} unique (${columnNames.mkString(", ")})"
      }
      .toSeq

    // Add foreign key constraints from instance
    val foreignKeyConstraints = instance.foreignKeyConstraints

    val allConstraints = columnDefs ++ primaryKeyConstraint.toSeq ++ compoundUniqueConstraints ++ foreignKeyConstraints
    val tableName      = instance.tableName
    val createClause   = dialect.createTableClause(ifNotExists)
    s"$createClause $tableName (\n  ${allConstraints.mkString(",\n  ")}\n)"
  end createTableSql

  /** Returns the CREATE TABLE SQL without executing it. Pretty-printed with newlines. */
  inline def createTableSql[A <: Product: Table as table](
      ifNotExists: Boolean = true
  )(using dialect: Dialect): String =
    val keyColumns     = table.columns.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    val columnDefs = table.columns.map { col =>
      val baseType      = sqlTypeFromColumn(col)
      val notNullClause = if !col.isNullable then " not null" else ""
      val defaultClause = col.defaultClause.map(d => s" $d").getOrElse("")
      val autoIncrement = dialect.autoIncrementClause(col.isGenerated, col.isKey, hasCompoundKey)
      val uniqueClause  = if col.isUnique && col.uniqueGroup.isEmpty then " unique" else ""
      s"${col.label} $baseType$notNullClause$defaultClause$autoIncrement$uniqueClause"
    }

    val primaryKeyConstraint = Option.when(hasCompoundKey) {
      val keyColumnNames = keyColumns.map(_.label)
      dialect.compoundPrimaryKeyClause(keyColumnNames)
    }

    val compoundUniqueConstraints = table.columns
      .filter(col => col.isUnique && col.uniqueGroup.isDefined)
      .groupBy(_.uniqueGroup.get)
      .map { case (constraintName, cols) =>
        val columnNames = cols.map(_.label)
        s"constraint ${dialect.escapeIdentifier(constraintName)} unique (${columnNames.mkString(", ")})"
      }
      .toSeq

    val allConstraints = columnDefs ++ primaryKeyConstraint.toSeq ++ compoundUniqueConstraints
    val tableName      = table.name
    val createClause   = dialect.createTableClause(ifNotExists)
    s"$createClause $tableName (\n  ${allConstraints.mkString(",\n  ")}\n)"
  end createTableSql

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

  inline def addColumn[A <: Product: Table as table, T: Encoder as encoder](columnName: String)(using
      dialect: Dialect,
      trace: Trace,
  ): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName  = table.name
    val columnType = dialect.columnType(encoder.jdbcType)
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
      where: Option[String] = None,
  )(using dialect: Dialect, trace: Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val tableName = table.name
    val sql       =
      if unique then
        SqlFragment(dialect.createUniqueIndexSql(indexName, tableName, columnNames, where = where), Seq.empty)
      else SqlFragment(dialect.createIndexSql(indexName, tableName, columnNames, where = where), Seq.empty)
    sql.dml
  end createIndex

  inline def createIndexesSql[A <: Product: Table as table]()(using dialect: Dialect): String =
    val tableName      = table.name
    val keyColumns     = table.columns.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    // Create regular indexes for @indexed fields without indexGroup (single-column)
    val singleColumnIndexes = table.indexedColumns.filter(_.indexGroup.isEmpty).map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      dialect match
        case d: IndexIfNotExistsSupport =>
          d.createIndexIfNotExistsSql(indexName, tableName, Seq(col.label), where = col.indexCondition)
        case _ => dialect.createIndexSql(indexName, tableName, Seq(col.label), false, col.indexCondition)
    }

    // Create compound indexes for @indexed fields with indexGroup
    val compoundIndexes = table.indexedColumns
      .filter(_.indexGroup.isDefined)
      .groupBy(_.indexGroup.get)
      .map { case (indexName, cols) =>
        val columnNames = cols.map(_.label)
        // For compound indexes, merge conditions (take the first non-empty condition)
        val condition = cols.flatMap(_.indexCondition).headOption
        dialect match
          case d: IndexIfNotExistsSupport =>
            d.createIndexIfNotExistsSql(indexName, tableName, columnNames, where = condition)
          case _ => dialect.createIndexSql(indexName, tableName, columnNames, false, condition)
      }
      .toSeq

    // Create unique indexes for @uniqueIndex fields without uniqueIndexGroup (single-column)
    val singleColumnUniqueIndexes = table.uniqueIndexColumns.filter(_.uniqueIndexGroup.isEmpty).map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      dialect match
        case d: IndexIfNotExistsSupport =>
          d.createIndexIfNotExistsSql(
            indexName,
            tableName,
            Seq(col.label),
            unique = true,
            where = col.uniqueIndexCondition,
          )
        case _ => dialect.createUniqueIndexSql(indexName, tableName, Seq(col.label), false, col.uniqueIndexCondition)
      end match
    }

    // Create compound unique indexes for @uniqueIndex fields with uniqueIndexGroup
    val compoundUniqueIndexes = table.uniqueIndexColumns
      .filter(_.uniqueIndexGroup.isDefined)
      .groupBy(_.uniqueIndexGroup.get)
      .map { case (indexName, cols) =>
        val columnNames = cols.map(_.label)
        // For compound indexes, merge conditions (take the first non-empty condition)
        val condition = cols.flatMap(_.uniqueIndexCondition).headOption
        dialect match
          case d: IndexIfNotExistsSupport =>
            d.createIndexIfNotExistsSql(indexName, tableName, columnNames, unique = true, where = condition)
          case _ => dialect.createUniqueIndexSql(indexName, tableName, columnNames, false, condition)
      }
      .toSeq

    // For compound keys, also create a compound index on all key columns for better query performance
    val compoundKeyIndex = Option.when(hasCompoundKey) {
      val keyColumnNames    = keyColumns.map(_.label)
      val compoundIndexName = s"idx_${tableName}_compound_key"
      dialect match
        case d: IndexIfNotExistsSupport => d.createIndexIfNotExistsSql(compoundIndexName, tableName, keyColumnNames)
        case _                          => dialect.createIndexSql(compoundIndexName, tableName, keyColumnNames, false)
    }

    (singleColumnIndexes ++ compoundIndexes ++ singleColumnUniqueIndexes ++ compoundUniqueIndexes ++ compoundKeyIndex.toSeq)
      .mkString("\n")
  end createIndexesSql

  inline def createIndexes[A <: Product: Table as table]()(using
      dialect: Dialect,
      trace: Trace,
  ): ZIO[ConnectionProvider & Scope, Throwable, Seq[Int]] =
    val tableName      = table.name
    val keyColumns     = table.columns.filter(_.isKey)
    val hasCompoundKey = keyColumns.length > 1

    // Create regular indexes for @indexed fields without indexGroup (single-column)
    val singleColumnIndexes = table.indexedColumns.filter(_.indexGroup.isEmpty).map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      val sql       = dialect match
        case d: IndexIfNotExistsSupport =>
          SqlFragment(
            d.createIndexIfNotExistsSql(indexName, tableName, Seq(col.label), where = col.indexCondition),
            Seq.empty,
          )
        case _ =>
          SqlFragment(
            dialect.createIndexSql(indexName, tableName, Seq(col.label), false, col.indexCondition),
            Seq.empty,
          )
      sql.dml
    }

    // Create compound indexes for @indexed fields with indexGroup
    val compoundIndexes = table.indexedColumns
      .filter(_.indexGroup.isDefined)
      .groupBy(_.indexGroup.get)
      .map { case (indexName, cols) =>
        val columnNames = cols.map(_.label)
        // For compound indexes, merge conditions (take the first non-empty condition)
        val condition = cols.flatMap(_.indexCondition).headOption
        val sql       = dialect match
          case d: IndexIfNotExistsSupport =>
            SqlFragment(d.createIndexIfNotExistsSql(indexName, tableName, columnNames, where = condition), Seq.empty)
          case _ => SqlFragment(dialect.createIndexSql(indexName, tableName, columnNames, false, condition), Seq.empty)
        sql.dml
      }
      .toSeq

    // Create unique indexes for @uniqueIndex fields without uniqueIndexGroup (single-column)
    val singleColumnUniqueIndexes = table.uniqueIndexColumns.filter(_.uniqueIndexGroup.isEmpty).map { col =>
      val indexName = s"idx_${tableName}_${col.label}"
      val sql       = dialect match
        case d: IndexIfNotExistsSupport =>
          SqlFragment(
            d.createIndexIfNotExistsSql(
              indexName,
              tableName,
              Seq(col.label),
              unique = true,
              where = col.uniqueIndexCondition,
            ),
            Seq.empty,
          )
        case _ =>
          SqlFragment(
            dialect.createUniqueIndexSql(indexName, tableName, Seq(col.label), false, col.uniqueIndexCondition),
            Seq.empty,
          )
      sql.dml
    }

    // Create compound unique indexes for @uniqueIndex fields with uniqueIndexGroup
    val compoundUniqueIndexes = table.uniqueIndexColumns
      .filter(_.uniqueIndexGroup.isDefined)
      .groupBy(_.uniqueIndexGroup.get)
      .map { case (indexName, cols) =>
        val columnNames = cols.map(_.label)
        // For compound indexes, merge conditions (take the first non-empty condition)
        val condition = cols.flatMap(_.uniqueIndexCondition).headOption
        val sql       = dialect match
          case d: IndexIfNotExistsSupport =>
            SqlFragment(
              d.createIndexIfNotExistsSql(indexName, tableName, columnNames, unique = true, where = condition),
              Seq.empty,
            )
          case _ =>
            SqlFragment(dialect.createUniqueIndexSql(indexName, tableName, columnNames, false, condition), Seq.empty)
        sql.dml
      }
      .toSeq

    // For compound keys, also create a compound index on all key columns for better query performance
    val compoundKeyIndex = Option.when(hasCompoundKey) {
      val keyColumnNames    = keyColumns.map(_.label)
      val compoundIndexName = s"idx_${tableName}_compound_key"
      val sql               = dialect match
        case d: IndexIfNotExistsSupport =>
          SqlFragment(d.createIndexIfNotExistsSql(compoundIndexName, tableName, keyColumnNames), Seq.empty)
        case _ => SqlFragment(dialect.createIndexSql(compoundIndexName, tableName, keyColumnNames, false), Seq.empty)
      sql.dml
    }

    ZIO.collectAll(
      singleColumnIndexes ++ compoundIndexes ++ singleColumnUniqueIndexes ++ compoundUniqueIndexes ++ compoundKeyIndex.toSeq
    )
  end createIndexes

  inline def dropIndex(indexName: String, ifExists: Boolean = false)(using
      dialect: Dialect,
      trace: Trace,
  ): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    val sql = SqlFragment(dialect.dropIndexSql(indexName, ifExists), Seq.empty)
    sql.dml

end DataDefinitionLayer

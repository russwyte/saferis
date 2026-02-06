package saferis

import zio.*

import java.sql.{Connection, DatabaseMetaData}
import scala.annotation.unused
import scala.collection.mutable.ListBuffer

/** Schema introspection and validation.
  *
  * Uses JDBC DatabaseMetaData as the portable default. Dialects can implement SchemaIntrospectionSupport for richer
  * metadata (e.g., partial index WHERE clauses).
  */
object SchemaIntrospection:

  /** Introspect a table's schema from the database. */
  def introspect(tableName: String)(using
      dialect: Dialect
  )(using
      Trace
  ): ZIO[ConnectionProvider & Scope, SaferisError, Option[DatabaseTable]] =
    dialect match
      case d: SchemaIntrospectionSupport => d.introspectTable(tableName)
      case _                             => introspectViaJdbc(tableName)

  /** Verify a schema against the database using default options. */
  def verify[A <: Product](instance: Instance[A])(using
      dialect: Dialect
  )(using
      Trace
  ): ZIO[ConnectionProvider & Scope, SaferisError, Unit] =
    verifyWith(instance, VerifyOptions.default)

  /** Verify a schema against the database with custom options. */
  def verifyWith[A <: Product](instance: Instance[A], options: VerifyOptions)(using
      dialect: Dialect
  )(using
      Trace
  ): ZIO[ConnectionProvider & Scope, SaferisError, Unit] =
    for
      dbTableOpt <- introspect(instance.tableName)
      issues = dbTableOpt match
        case None          => List(SchemaIssue.TableNotFound(instance.tableName))
        case Some(dbTable) => compare(instance, dbTable, options)
      _ <- ZIO.when(issues.nonEmpty)(ZIO.fail(SaferisError.SchemaValidation(issues)))
    yield ()

  /** Compare expected schema against actual database table. */
  private def compare[A <: Product](
      instance: Instance[A],
      dbTable: DatabaseTable,
      options: VerifyOptions,
  )(using dialect: Dialect): List[SchemaIssue] =
    val tableName = instance.tableName
    val issues    = ListBuffer.empty[SchemaIssue]

    // Check each expected column
    instance.columns.foreach { col =>
      val columnLabel = col.label
      dbTable.columns.find(_.name.equalsIgnoreCase(columnLabel)) match
        case None =>
          issues += SchemaIssue.MissingColumn(tableName, columnLabel, col.columnType)
        case Some(dbCol) =>
          if options.checkTypes then
            if !isTypeCompatible(col.columnType, dbCol.dataType, options.strictTypeMatching) then
              issues += SchemaIssue.TypeMismatch(tableName, columnLabel, col.columnType, dbCol.dataType)
          if options.checkNullability then
            if col.isNullable != dbCol.isNullable then
              issues += SchemaIssue.NullabilityMismatch(tableName, columnLabel, col.isNullable, dbCol.isNullable)
      end match
    }

    // Check primary key
    val expectedKeys = instance.columns.filter(_.isKey).map(_.label).sorted
    if expectedKeys.nonEmpty && expectedKeys != dbTable.primaryKeyColumns.map(_.toLowerCase).sorted.map { k =>
        instance.columns.find(_.label.equalsIgnoreCase(k)).map(_.label).getOrElse(k)
      }
    then
      val actualKeys = dbTable.primaryKeyColumns
      if expectedKeys.map(_.toLowerCase).sorted != actualKeys.map(_.toLowerCase).sorted then
        issues += SchemaIssue.PrimaryKeyMismatch(tableName, expectedKeys, actualKeys)

    // Check for extra columns
    if options.checkExtraColumns then
      val expectedColumnNames = instance.columns.map(_.label.toLowerCase).toSet
      dbTable.columns.foreach { dbCol =>
        if !expectedColumnNames.contains(dbCol.name.toLowerCase) then
          issues += SchemaIssue.ExtraColumn(tableName, dbCol.name, dbCol.dataType)
      }

    // Check indexes
    if options.checkIndexes then
      instance.indexes.foreach { indexSpec =>
        val columnLabels = indexSpec.columns.map(instance.fieldToLabel)
        val expectedName = indexSpec.name
        findMatchingIndex(dbTable.indexes, columnLabels, indexSpec.unique) match
          case None =>
            issues += SchemaIssue.MissingIndex(tableName, expectedName, columnLabels, indexSpec.unique)
          case Some(dbIdx) if options.strictNameMatching && expectedName.isDefined =>
            if !expectedName.get.equalsIgnoreCase(dbIdx.indexName) then
              issues += SchemaIssue.IndexNameMismatch(tableName, expectedName.get, dbIdx.indexName, columnLabels)
          case _ => // OK
      }
    end if

    // Check unique constraints
    // Note: JDBC doesn't expose unique constraints separately - they appear as unique indexes.
    // So we check both uniqueConstraints and unique indexes.
    if options.checkUniqueConstraints then
      instance.uniqueConstraints.foreach { ucSpec =>
        val columnLabels = ucSpec.columns.map(instance.fieldToLabel)
        val expectedName = ucSpec.constraintName
        // First check dedicated unique constraints, then fall back to unique indexes
        findMatchingUniqueConstraint(dbTable.uniqueConstraints, columnLabels)
          .orElse(findMatchingIndex(dbTable.indexes, columnLabels, expectedUnique = true)) match
          case None =>
            issues += SchemaIssue.MissingUniqueConstraint(tableName, expectedName, columnLabels)
          case Some(dbUc: DatabaseUniqueConstraint) if options.strictNameMatching && expectedName.isDefined =>
            if !expectedName.get.equalsIgnoreCase(dbUc.constraintName) then
              issues += SchemaIssue.UniqueConstraintNameMismatch(
                tableName,
                expectedName.get,
                dbUc.constraintName,
                columnLabels,
              )
          case Some(dbIdx: DatabaseIndex) if options.strictNameMatching && expectedName.isDefined =>
            if !expectedName.get.equalsIgnoreCase(dbIdx.indexName) then
              issues += SchemaIssue.UniqueConstraintNameMismatch(
                tableName,
                expectedName.get,
                dbIdx.indexName,
                columnLabels,
              )
          case _ => // OK
        end match
      }
    end if

    // Check foreign keys
    if options.checkForeignKeys then
      instance.foreignKeys.foreach { fkSpec =>
        val fromLabels   = fkSpec.fromColumns.map(instance.fieldToLabel)
        val toLabels     = fkSpec.toColumns.map(fn => fkSpec.toColumnMap.get(fn).map(_.label).getOrElse(fn))
        val expectedName = fkSpec.constraintName
        findMatchingForeignKey(dbTable.foreignKeys, fromLabels, fkSpec.toTable, toLabels) match
          case None =>
            issues += SchemaIssue.MissingForeignKey(tableName, expectedName, fromLabels, fkSpec.toTable, toLabels)
          case Some(dbFk) if options.strictNameMatching && expectedName.isDefined =>
            if !expectedName.get.equalsIgnoreCase(dbFk.constraintName) then
              issues += SchemaIssue.ForeignKeyNameMismatch(
                tableName,
                expectedName.get,
                dbFk.constraintName,
                fromLabels,
                fkSpec.toTable,
                toLabels,
              )
          case _ => // OK
        end match
      }
    end if

    issues.toList
  end compare

  private def findMatchingIndex(
      indexes: Seq[DatabaseIndex],
      expectedColumns: Seq[String],
      expectedUnique: Boolean,
  ): Option[DatabaseIndex] =
    indexes.find { idx =>
      idx.columns.map(_.toLowerCase) == expectedColumns.map(_.toLowerCase) &&
      idx.isUnique == expectedUnique
    }

  private def findMatchingUniqueConstraint(
      constraints: Seq[DatabaseUniqueConstraint],
      expectedColumns: Seq[String],
  ): Option[DatabaseUniqueConstraint] =
    constraints.find(_.columns.map(_.toLowerCase) == expectedColumns.map(_.toLowerCase))

  private def findMatchingForeignKey(
      foreignKeys: Seq[DatabaseForeignKey],
      fromColumns: Seq[String],
      toTable: String,
      toColumns: Seq[String],
  ): Option[DatabaseForeignKey] =
    foreignKeys.find { fk =>
      fk.fromColumns.map(_.toLowerCase) == fromColumns.map(_.toLowerCase) &&
      fk.toTable.equalsIgnoreCase(toTable) &&
      fk.toColumns.map(_.toLowerCase) == toColumns.map(_.toLowerCase)
    }

  // === Type Compatibility ===

  private def isTypeCompatible(expected: String, actual: String, strict: Boolean): Boolean =
    if strict then expected.equalsIgnoreCase(actual)
    else
      val normalizedExpected = normalizeType(expected)
      val normalizedActual   = normalizeType(actual)
      normalizedExpected == normalizedActual || areTypesInSameFamily(normalizedExpected, normalizedActual)

  private def normalizeType(t: String): String =
    t.toLowerCase.replaceAll("\\(.*\\)", "").trim

  private def areTypesInSameFamily(t1: String, t2: String): Boolean =
    val integerTypes   = Set("integer", "int", "int4", "serial", "bigint", "int8", "bigserial", "smallint", "int2")
    val textTypes      = Set("varchar", "text", "character varying", "char", "character", "bpchar")
    val numericTypes   = Set("numeric", "decimal", "real", "float", "float4", "float8", "double precision", "double")
    val boolTypes      = Set("boolean", "bool", "bit")
    val timestampTypes = Set("timestamp", "timestamptz", "timestamp with time zone", "timestamp without time zone")
    val jsonTypes      = Set("json", "jsonb")

    val families = Seq(integerTypes, textTypes, numericTypes, boolTypes, timestampTypes, jsonTypes)
    families.exists(family => family.contains(t1) && family.contains(t2))
  end areTypesInSameFamily

  // === JDBC Introspection ===

  private def introspectViaJdbc(tableName: String)(using
      Trace
  ): ZIO[ConnectionProvider & Scope, SaferisError, Option[DatabaseTable]] =
    (for
      provider <- ZIO.service[ConnectionProvider]
      conn     <- provider.getConnection
      result   <- ZIO.attemptBlocking(introspectConnection(conn, tableName))
    yield result).mapError(SaferisError.fromThrowable(_))

  private def introspectConnection(conn: Connection, tableName: String): Option[DatabaseTable] =
    val meta   = conn.getMetaData
    val schema = conn.getSchema
    val tables = meta.getTables(null, schema, tableName, Array("TABLE"))
    try
      if !tables.next() then
        // Try case-insensitive search
        val tablesLower = meta.getTables(null, schema, tableName.toLowerCase, Array("TABLE"))
        try
          if !tablesLower.next() then
            val tablesUpper = meta.getTables(null, schema, tableName.toUpperCase, Array("TABLE"))
            try if !tablesUpper.next() then None else Some(buildTableFromMetadata(meta, schema, tableName.toUpperCase))
            finally tablesUpper.close()
          else Some(buildTableFromMetadata(meta, schema, tableName.toLowerCase))
        finally tablesLower.close()
      else Some(buildTableFromMetadata(meta, schema, tableName))
    finally tables.close()
    end try
  end introspectConnection

  private def buildTableFromMetadata(meta: DatabaseMetaData, schema: String, tableName: String): DatabaseTable =
    val columns           = getColumns(meta, schema, tableName)
    val primaryKeys       = getPrimaryKeys(meta, schema, tableName)
    val indexes           = getIndexes(meta, schema, tableName, primaryKeys)
    val uniqueConstraints = getUniqueConstraints(meta, schema, tableName)
    val foreignKeys       = getForeignKeys(meta, schema, tableName)

    DatabaseTable(tableName, columns, primaryKeys, indexes, uniqueConstraints, foreignKeys)

  private def getColumns(meta: DatabaseMetaData, schema: String, tableName: String): Seq[DatabaseColumn] =
    val rs      = meta.getColumns(null, schema, tableName, null)
    val columns = ListBuffer.empty[DatabaseColumn]
    try
      while rs.next() do
        columns += DatabaseColumn(
          name = rs.getString("COLUMN_NAME"),
          dataType = rs.getString("TYPE_NAME"),
          isNullable = rs.getString("IS_NULLABLE") == "YES",
          isPrimaryKey = false, // Will be set from PK info
          defaultValue = Option(rs.getString("COLUMN_DEF")),
          ordinalPosition = rs.getInt("ORDINAL_POSITION"),
        )
    finally rs.close()
    end try
    columns.toSeq
  end getColumns

  private def getPrimaryKeys(meta: DatabaseMetaData, schema: String, tableName: String): Seq[String] =
    val rs   = meta.getPrimaryKeys(null, schema, tableName)
    val keys = ListBuffer.empty[(String, Int)]
    try while rs.next() do keys += (rs.getString("COLUMN_NAME") -> rs.getInt("KEY_SEQ"))
    finally rs.close()
    keys.sortBy(_._2).map(_._1).toSeq

  private def getIndexes(
      meta: DatabaseMetaData,
      schema: String,
      tableName: String,
      primaryKeys: Seq[String],
  ): Seq[DatabaseIndex] =
    val rs      = meta.getIndexInfo(null, schema, tableName, false, false)
    val indexes = ListBuffer.empty[(String, String, Boolean, Int)]
    try
      while rs.next() do
        val indexName = rs.getString("INDEX_NAME")
        val colName   = rs.getString("COLUMN_NAME")
        if indexName != null && colName != null then
          indexes += ((indexName, colName, !rs.getBoolean("NON_UNIQUE"), rs.getInt("ORDINAL_POSITION")))
    finally rs.close()

    indexes
      .groupBy(_._1)
      .map { case (name, cols) =>
        val sortedCols = cols.sortBy(_._4).map(_._2).toSeq
        val isUnique   = cols.headOption.exists(_._3)
        DatabaseIndex(name, sortedCols, isUnique, None)
      }
      .toSeq
      .filterNot { idx =>
        // Filter out primary key index
        idx.columns.map(_.toLowerCase) == primaryKeys.map(_.toLowerCase)
      }
  end getIndexes

  private def getUniqueConstraints(
      @unused meta: DatabaseMetaData,
      @unused schema: String,
      @unused tableName: String,
  ): Seq[DatabaseUniqueConstraint] =
    // JDBC doesn't have a direct method for unique constraints separate from indexes
    // Unique constraints typically show up as unique indexes, handled in getIndexes
    // Return empty - unique constraints will be detected as unique indexes
    Seq.empty

  private def getForeignKeys(meta: DatabaseMetaData, schema: String, tableName: String): Seq[DatabaseForeignKey] =
    val rs  = meta.getImportedKeys(null, schema, tableName)
    val fks = ListBuffer.empty[(String, String, String, String, String, Int, String, String)]
    try
      while rs.next() do
        fks += ((
          rs.getString("FK_NAME"),
          rs.getString("FKCOLUMN_NAME"),
          rs.getString("PKTABLE_NAME"),
          rs.getString("PKCOLUMN_NAME"),
          rs.getShort("DELETE_RULE") match
            case java.sql.DatabaseMetaData.importedKeyCascade    => "CASCADE"
            case java.sql.DatabaseMetaData.importedKeySetNull    => "SET NULL"
            case java.sql.DatabaseMetaData.importedKeySetDefault => "SET DEFAULT"
            case java.sql.DatabaseMetaData.importedKeyRestrict   => "RESTRICT"
            case _                                               => "NO ACTION"
          ,
          rs.getInt("KEY_SEQ"),
          rs.getShort("UPDATE_RULE") match
            case java.sql.DatabaseMetaData.importedKeyCascade    => "CASCADE"
            case java.sql.DatabaseMetaData.importedKeySetNull    => "SET NULL"
            case java.sql.DatabaseMetaData.importedKeySetDefault => "SET DEFAULT"
            case java.sql.DatabaseMetaData.importedKeyRestrict   => "RESTRICT"
            case _                                               => "NO ACTION"
          ,
          rs.getString("FK_NAME"),
        ))
    finally rs.close()
    end try

    fks
      .groupBy(_._1)
      .map { case (name, cols) =>
        val sorted   = cols.sortBy(_._6)
        val fromCols = sorted.map(_._2).toSeq
        val toTable  = sorted.headOption.map(_._3).getOrElse("")
        val toCols   = sorted.map(_._4).toSeq
        val onDelete = sorted.headOption.map(_._5).getOrElse("NO ACTION")
        val onUpdate = sorted.headOption.map(_._7).getOrElse("NO ACTION")
        DatabaseForeignKey(name, fromCols, toTable, toCols, onDelete, onUpdate)
      }
      .toSeq
  end getForeignKeys
end SchemaIntrospection

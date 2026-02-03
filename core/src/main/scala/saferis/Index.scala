package saferis

/** Represents a complete index specification ready for DDL generation.
  *
  * @tparam A
  *   The table type this index belongs to
  * @param columns
  *   Column name(s) for the index
  * @param name
  *   Optional custom index name (auto-generated if None)
  * @param unique
  *   Whether this is a unique index
  * @param where
  *   Optional WHERE clause for partial indexes (pre-rendered SQL)
  */
final case class IndexSpec[A <: Product](
    columns: Seq[String],
    name: Option[String] = None,
    unique: Boolean = false,
    where: Option[String] = None,
):
  /** Generate CREATE INDEX SQL for this spec */
  def toCreateSql(tableName: String)(using dialect: Dialect): String =
    val indexName = name.getOrElse(s"idx_${tableName}_${columns.mkString("_")}")
    if unique then dialect.createUniqueIndexSql(indexName, tableName, columns, false, where)
    else dialect.createIndexSql(indexName, tableName, columns, false, where)
end IndexSpec

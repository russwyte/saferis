package saferis

/** Represents a complete unique constraint specification ready for DDL generation.
  *
  * @tparam A
  *   The table type this constraint belongs to
  * @param columns
  *   Column field names for the unique constraint (will be converted to labels at SQL generation time)
  * @param constraintName
  *   Optional custom constraint name (auto-generated if None)
  */
final case class UniqueConstraintSpec[A <: Product](
    columns: Seq[String],
    constraintName: Option[String] = None,
):
  /** Generate UNIQUE constraint SQL for this spec
    *
    * @param fieldToLabel
    *   Function to convert field names to column labels (respects @label annotations)
    */
  def toConstraintSql(fieldToLabel: String => String): String =
    val columnLabels = columns.map(fieldToLabel)
    val name         = constraintName.getOrElse(s"uq_${columnLabels.mkString("_")}")
    s"constraint $name unique (${columnLabels.mkString(", ")})"
end UniqueConstraintSpec

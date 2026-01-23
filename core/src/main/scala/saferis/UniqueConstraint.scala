package saferis

/** Represents a complete unique constraint specification ready for DDL generation.
  *
  * @tparam A
  *   The table type this constraint belongs to
  * @param columns
  *   Column name(s) for the unique constraint
  * @param constraintName
  *   Optional custom constraint name (auto-generated if None)
  */
final case class UniqueConstraintSpec[A <: Product](
    columns: Seq[String],
    constraintName: Option[String] = None,
):
  /** Generate UNIQUE constraint SQL for this spec */
  def toConstraintSql: String =
    val name = constraintName.getOrElse(s"uq_${columns.mkString("_")}")
    s"constraint $name unique (${columns.mkString(", ")})"
end UniqueConstraintSpec

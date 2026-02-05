package saferis

/** Actions that can be taken when a referenced row is deleted or updated */
enum ForeignKeyAction:
  case NoAction, Cascade, SetNull, SetDefault, Restrict

  /** Returns the SQL clause for this action */
  def toSql: String = this match
    case NoAction   => "no action"
    case Cascade    => "cascade"
    case SetNull    => "set null"
    case SetDefault => "set default"
    case Restrict   => "restrict"
end ForeignKeyAction

/** Represents a complete foreign key specification
  *
  * @tparam From
  *   The source table type containing the foreign key column(s)
  * @tparam To
  *   The referenced table type
  * @param fromColumns
  *   Column field names in the source table (will be converted to labels at SQL generation time)
  * @param toTable
  *   Name of the referenced table
  * @param toColumns
  *   Column field names in the referenced table (will be converted to labels at SQL generation time)
  * @param toColumnMap
  *   Column map from target table for field name to label conversion
  * @param onDelete
  *   Action to take when referenced row is deleted
  * @param onUpdate
  *   Action to take when referenced row is updated
  * @param constraintName
  *   Optional custom constraint name
  */
final case class ForeignKeySpec[From <: Product, To <: Product](
    fromColumns: Seq[String],
    toTable: String,
    toColumns: Seq[String],
    toColumnMap: Map[String, Column[?]] = Map.empty,
    onDelete: ForeignKeyAction = ForeignKeyAction.NoAction,
    onUpdate: ForeignKeyAction = ForeignKeyAction.NoAction,
    constraintName: Option[String] = None,
):
  /** Generates the SQL FOREIGN KEY constraint clause
    *
    * @param fromFieldToLabel
    *   Function to convert source table field names to column labels (respects @label annotations)
    */
  def toConstraintSql(fromFieldToLabel: String => String): String =
    val constraintClause = constraintName.fold("")(n => s"constraint $n ")
    val fromColsSql      = fromColumns.map(fromFieldToLabel).mkString(", ")
    // Use toColumnMap if available, otherwise use field name directly
    val toFieldToLabel: String => String = fn => toColumnMap.get(fn).map(_.label).getOrElse(fn)
    val toColsSql                        = toColumns.map(toFieldToLabel).mkString(", ")
    val onDeleteClause = if onDelete == ForeignKeyAction.NoAction then "" else s" on delete ${onDelete.toSql}"
    val onUpdateClause = if onUpdate == ForeignKeyAction.NoAction then "" else s" on update ${onUpdate.toSql}"
    s"${constraintClause}foreign key ($fromColsSql) references $toTable ($toColsSql)$onDeleteClause$onUpdateClause"
end ForeignKeySpec

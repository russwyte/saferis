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
  *   Column name(s) in the source table
  * @param toTable
  *   Name of the referenced table
  * @param toColumns
  *   Column name(s) in the referenced table
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
    onDelete: ForeignKeyAction = ForeignKeyAction.NoAction,
    onUpdate: ForeignKeyAction = ForeignKeyAction.NoAction,
    constraintName: Option[String] = None,
):
  /** Generates the SQL FOREIGN KEY constraint clause */
  def toConstraintSql: String =
    val constraintClause = constraintName.fold("")(n => s"constraint $n ")
    val fromColsSql      = fromColumns.mkString(", ")
    val toColsSql        = toColumns.mkString(", ")
    val onDeleteClause   = if onDelete == ForeignKeyAction.NoAction then "" else s" on delete ${onDelete.toSql}"
    val onUpdateClause   = if onUpdate == ForeignKeyAction.NoAction then "" else s" on update ${onUpdate.toSql}"
    s"${constraintClause}foreign key ($fromColsSql) references $toTable ($toColsSql)$onDeleteClause$onUpdateClause"
end ForeignKeySpec

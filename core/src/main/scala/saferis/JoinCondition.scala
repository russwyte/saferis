package saferis

/** Internal representation of a condition in ON or WHERE clauses.
  *
  * These are used internally by the join/where builders and converted to SqlFragment for execution.
  */
sealed trait JoinCondition:
  /** Generate the SQL string for this condition */
  def toSql(using Dialect): String

  /** Get any Write instances needed for prepared statement binding */
  def writes: Seq[Write[?]]

/** Binary condition: column op column (e.g., t1.id = t2.user_id) */
final case class BinaryCondition(
    leftAlias: String,
    leftColumn: String,
    operator: JoinOperator,
    rightAlias: String,
    rightColumn: String,
) extends JoinCondition:
  def toSql(using d: Dialect): String =
    // Only escape alias, not column name - allows PostgreSQL case folding to work correctly
    s"${d.escapeIdentifier(leftAlias)}.$leftColumn ${operator.sql} ${d.escapeIdentifier(rightAlias)}.$rightColumn"

  def writes: Seq[Write[?]] = Seq.empty
end BinaryCondition

/** Unary condition: column IS NULL / IS NOT NULL */
final case class UnaryCondition(
    alias: String,
    column: String,
    operator: JoinOperator, // IsNull or IsNotNull
) extends JoinCondition:
  def toSql(using d: Dialect): String =
    // Only escape alias, not column name - allows PostgreSQL case folding to work correctly
    s"${d.escapeIdentifier(alias)}.$column ${operator.sql}"

  def writes: Seq[Write[?]] = Seq.empty
end UnaryCondition

/** Literal condition: column op ? (prepared statement style)
  *
  * Values are NEVER interpolated into SQL - always bound via ? placeholders. This follows the same pattern as the
  * sql"..." interpolator.
  */
final case class LiteralCondition(
    alias: String,
    column: String,
    operator: JoinOperator,
    write: Write[?],
) extends JoinCondition:
  def toSql(using d: Dialect): String =
    // Only escape alias, not column name - allows PostgreSQL case folding to work correctly
    s"${d.escapeIdentifier(alias)}.$column ${operator.sql} ?"

  def writes: Seq[Write[?]] = Seq(write)
end LiteralCondition

object JoinCondition:
  /** Convert a sequence of conditions to SQL with AND between them */
  def toSqlFragment(conditions: Seq[JoinCondition])(using Dialect): SqlFragment =
    if conditions.isEmpty then SqlFragment("", Seq.empty)
    else
      val sql    = conditions.map(_.toSql).mkString(" AND ")
      val writes = conditions.flatMap(_.writes)
      SqlFragment(sql, writes)

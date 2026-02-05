package saferis

/** Internal representation of a condition in ON or WHERE clauses.
  *
  * These are used internally by the query builders and converted to SqlFragment for execution.
  */
sealed trait Condition:
  /** Generate the SQL string for this condition */
  def toSql: String

  /** Get any Write instances needed for prepared statement binding */
  def writes: Seq[Write[?]]

/** Binary condition: column op column (e.g., t1.id = t2.user_id) */
final case class BinaryCondition(
    leftAlias: Alias,
    leftColumn: Column[?],
    operator: Operator,
    rightAlias: Alias,
    rightColumn: Column[?],
) extends Condition:
  def toSql: String =
    s"${leftAlias.toSql}.${leftColumn.label} ${operator.sql} ${rightAlias.toSql}.${rightColumn.label}"

  def writes: Seq[Write[?]] = Seq.empty
end BinaryCondition

/** Unary condition: column IS NULL / IS NOT NULL */
final case class UnaryCondition(
    alias: Alias,
    column: Column[?],
    operator: Operator, // IsNull or IsNotNull
) extends Condition:
  def toSql: String =
    s"${alias.toSql}.${column.label} ${operator.sql}"

  def writes: Seq[Write[?]] = Seq.empty
end UnaryCondition

/** Literal condition: column op ? (prepared statement style)
  *
  * Values are NEVER interpolated into SQL - always bound via ? placeholders. This follows the same pattern as the
  * sql"..." interpolator.
  */
final case class LiteralCondition(
    alias: Alias,
    column: Column[?],
    operator: Operator,
    write: Write[?],
) extends Condition:
  def toSql: String =
    s"${alias.toSql}.${column.label} ${operator.sql} ?"

  def writes: Seq[Write[?]] = Seq(write)
end LiteralCondition

/** Condition comparing a column to the EXCLUDED pseudo-table (for upsert WHERE clauses).
  *
  * In PostgreSQL: `ON CONFLICT (id) DO UPDATE SET ... WHERE table.column = EXCLUDED.column`
  */
final case class ExcludedCondition(
    alias: Alias,
    column: Column[?],
    operator: Operator,
    excludedColumn: Column[?],
) extends Condition:
  def toSql: String =
    s"${alias.toSql}.${column.label} ${operator.sql} EXCLUDED.${excludedColumn.label}"

  def writes: Seq[Write[?]] = Seq.empty
end ExcludedCondition

object Condition:
  /** Convert a sequence of conditions to SQL with AND between them */
  def toSqlFragment(conditions: Seq[Condition]): SqlFragment =
    if conditions.isEmpty then SqlFragment("", Seq.empty)
    else
      val sql    = conditions.map(_.toSql).mkString(" AND ")
      val writes = conditions.flatMap(_.writes)
      SqlFragment(sql, writes)

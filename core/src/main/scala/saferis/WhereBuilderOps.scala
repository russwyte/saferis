package saferis

/** Shared trait for WHERE clause builders.
  *
  * Provides all WHERE operator implementations (eq, gt, isNull, in, etc.) that can be reused by Query's
  * WhereBuilder1/2/etc and mutation builders (UpdateWhereBuilder, DeleteWhereBuilder).
  *
  * @tparam Parent
  *   The type returned after adding a predicate (e.g., Query1[A], Update[A, HasWhere])
  * @tparam T
  *   The column type being filtered
  */
trait WhereBuilderOps[Parent, T]:
  /** The table alias for column qualification */
  protected def whereAlias: Alias

  /** The column being filtered */
  protected def whereColumn: Column[T]

  /** Add a predicate to the parent and return the updated parent */
  protected def addPredicate(predicate: SqlFragment): Parent

  // === Literal comparison operators ===

  private def completeLiteral(operator: Operator, value: T)(using enc: Encoder[T]): Parent =
    val write     = enc(value)
    val condition = LiteralCondition(whereAlias, whereColumn, operator, write)
    val whereFrag = Condition.toSqlFragment(Vector(condition))(using saferis.postgres.PostgresDialect)
    addPredicate(whereFrag)

  /** Compare to literal value with equality */
  def eq(value: T)(using Encoder[T]): Parent =
    completeLiteral(Operator.Eq, value)

  /** Compare to literal value with not equal */
  def neq(value: T)(using Encoder[T]): Parent =
    completeLiteral(Operator.Neq, value)

  /** Compare to literal value with less than */
  def lt(value: T)(using Encoder[T]): Parent =
    completeLiteral(Operator.Lt, value)

  /** Compare to literal value with less than or equal */
  def lte(value: T)(using Encoder[T]): Parent =
    completeLiteral(Operator.Lte, value)

  /** Compare to literal value with greater than */
  def gt(value: T)(using Encoder[T]): Parent =
    completeLiteral(Operator.Gt, value)

  /** Compare to literal value with greater than or equal */
  def gte(value: T)(using Encoder[T]): Parent =
    completeLiteral(Operator.Gte, value)

  /** Compare with custom operator */
  def op(operator: Operator)(value: T)(using Encoder[T]): Parent =
    completeLiteral(operator, value)

  // === Unary operators ===

  /** IS NULL check */
  def isNull(): Parent =
    val condition = UnaryCondition(whereAlias, whereColumn, Operator.IsNull)
    val whereFrag = Condition.toSqlFragment(Vector(condition))(using saferis.postgres.PostgresDialect)
    addPredicate(whereFrag)

  /** IS NOT NULL check */
  def isNotNull(): Parent =
    val condition = UnaryCondition(whereAlias, whereColumn, Operator.IsNotNull)
    val whereFrag = Condition.toSqlFragment(Vector(condition))(using saferis.postgres.PostgresDialect)
    addPredicate(whereFrag)

  // === Subquery operators ===

  /** IN subquery - check if value is in the results of another query.
    *
    * Type-safe: the subquery must return the same type T as this column.
    */
  def in(subquery: SelectQuery[T]): Parent =
    given Dialect   = saferis.postgres.PostgresDialect
    val subquerySql = subquery.build
    val inSql       = s"${whereAlias.toSql}.${whereColumn.label} IN (${subquerySql.sql})"
    val whereFrag   = SqlFragment(inSql, subquerySql.writes)
    addPredicate(whereFrag)

  /** NOT IN subquery - type-safe variant */
  def notIn(subquery: SelectQuery[T]): Parent =
    given Dialect   = saferis.postgres.PostgresDialect
    val subquerySql = subquery.build
    val notInSql    = s"${whereAlias.toSql}.${whereColumn.label} NOT IN (${subquerySql.sql})"
    val whereFrag   = SqlFragment(notInSql, subquerySql.writes)
    addPredicate(whereFrag)

end WhereBuilderOps

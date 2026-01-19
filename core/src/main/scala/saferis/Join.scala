package saferis

import zio.Trace
import java.util.concurrent.atomic.AtomicInteger

/** Join types for SQL JOIN operations */
enum JoinType:
  case Inner, Left, Right, Full, Cross

  def toSql: String = this match
    case Inner => "inner join"
    case Left  => "left join"
    case Right => "right join"
    case Full  => "full join"
    case Cross => "cross join"

/** Internal representation of a join clause */
final case class JoinClause(
    tableName: String,
    alias: String,
    joinType: JoinType,
    condition: SqlFragment,
)

/** Alias generator for auto-aliasing tables in joins */
private[saferis] object AliasGenerator:
  private val counter = AtomicInteger(0)

  def next(): String = s"t${counter.incrementAndGet()}"

  /** Reset counter (useful for testing) */
  def reset(): Unit = counter.set(0)

  /** Create an aliased Instance from a Table - bypasses the macro for type parameter compatibility */
  def aliasedInstance[A <: Product](alias: String)(using table: Table[A]): Instance[A] =
    val columns = table.columns.map(_.withTableAlias(Some(alias)))
    Instance[A](table.name, columns, Some(alias), Vector.empty)
end AliasGenerator

// ============================================================================
// JoinSpec1 - Single table (entry point)
// ============================================================================

/** JoinSpec for a single table - the starting point for building joins.
  *
  * Usage:
  * {{{
  *   JoinSpec[User]
  *     .innerJoin[Order].on(_.id).eq(_.userId)
  *     .where(_.name).eq("Alice")
  *     .limit(20)
  *     .build
  * }}}
  */
final case class JoinSpec1[A <: Product: Table](
    baseInstance: Instance[A],
    wherePredicates: Vector[SqlFragment] = Vector.empty,
    sorts: Vector[Sort[?]] = Vector.empty,
    limitValue: Option[Int] = None,
    offsetValue: Option[Long] = None,
):
  /** Add a WHERE predicate */
  def where(predicate: SqlFragment): JoinSpec1[A] =
    copy(wherePredicates = wherePredicates :+ predicate)

  /** Add an ORDER BY clause */
  def orderBy(sort: Sort[?]): JoinSpec1[A] =
    copy(sorts = sorts :+ sort)

  /** Set LIMIT */
  def limit(n: Int): JoinSpec1[A] = copy(limitValue = Some(n))

  /** Set OFFSET */
  def offset(n: Long): JoinSpec1[A] = copy(offsetValue = Some(n))

  /** Start an INNER JOIN */
  def innerJoin[B <: Product: Table]: JoinBuilder1[A, B] =
    JoinBuilder1(this, JoinType.Inner)

  /** Start a LEFT JOIN */
  def leftJoin[B <: Product: Table]: JoinBuilder1[A, B] =
    JoinBuilder1(this, JoinType.Left)

  /** Start a RIGHT JOIN */
  def rightJoin[B <: Product: Table]: JoinBuilder1[A, B] =
    JoinBuilder1(this, JoinType.Right)

  /** Start a FULL JOIN */
  def fullJoin[B <: Product: Table]: JoinBuilder1[A, B] =
    JoinBuilder1(this, JoinType.Full)

  /** Build the SQL fragment for this query */
  def build: SqlFragment =
    var result = SqlFragment(s"select * from ${baseInstance.sql}", Seq.empty)

    // WHERE clause
    if wherePredicates.nonEmpty then
      val joined = Placeholder.join(wherePredicates, " and ")
      result = result :+ SqlFragment(" where ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    // ORDER BY clause
    if sorts.nonEmpty then
      val sortFragments = sorts.map(_.toSqlFragment)
      val joined        = Placeholder.join(sortFragments, ", ")
      result = result :+ SqlFragment(" order by ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    // LIMIT/OFFSET
    limitValue.foreach(n => result = result :+ SqlFragment(s" limit $n", Seq.empty))
    offsetValue.foreach(n => result = result :+ SqlFragment(s" offset $n", Seq.empty))

    result
  end build

  /** Execute query */
  inline def query[R <: Product: Table](using Trace): ScopedQuery[Seq[R]] = build.query[R]

  /** Execute query returning first row */
  inline def queryOne[R <: Product: Table](using Trace): ScopedQuery[Option[R]] = build.queryOne[R]

end JoinSpec1

// ============================================================================
// JoinBuilder1 - Building ON clause for first join
// ============================================================================

final case class JoinBuilder1[A <: Product: Table, B <: Product: Table](
    spec: JoinSpec1[A],
    joinType: JoinType,
):
  /** Start a type-safe ON condition by selecting a column from the left table (A).
    *
    * Usage:
    * {{{
    *   JoinSpec[User]
    *     .innerJoin[Order].on(_.id).eq(_.userId)
    *     .build
    * }}}
    */
  inline def on[T](inline selector: A => T): OnConditionBuilder1[A, B, T] =
    val alias          = AliasGenerator.next()
    val bInstance      = AliasGenerator.aliasedInstance[B](alias)
    val leftFieldName  = Macros.extractFieldName[A, T](selector)
    val leftColumnName = spec.baseInstance.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = spec.baseInstance.alias.getOrElse(spec.baseInstance.tableName)
    OnConditionBuilder1(spec, joinType, leftAlias, leftColumnName, alias, bInstance)
end JoinBuilder1

// ============================================================================
// OnConditionBuilder1 - Building ON condition for first join
// ============================================================================

/** Builder for the first ON condition - awaiting the right-hand side.
  *
  * Created by `JoinBuilder1.on(_.column)`, completed by `.eq(_.column)` etc.
  */
final case class OnConditionBuilder1[A <: Product: Table, B <: Product: Table, T](
    spec: JoinSpec1[A],
    joinType: JoinType,
    leftAlias: String,
    leftColumn: String,
    rightAlias: String,
    rightInstance: Instance[B],
):
  private def complete(operator: JoinOperator, rightColumnLabel: String): OnConditionChain1[A, B] =
    val condition = BinaryCondition(leftAlias, leftColumn, operator, rightAlias, rightColumnLabel)
    OnConditionChain1(spec, joinType, leftAlias, rightAlias, rightInstance, Vector(condition))

  private inline def getRightColumnLabel[T2](inline selector: B => T2): String =
    val fieldName = Macros.extractFieldName[B, T2](selector)
    rightInstance.fieldNamesToColumns(fieldName).label

  /** Complete with equality (=) */
  inline def eq(inline selector: B => T): OnConditionChain1[A, B] =
    complete(JoinOperator.Eq, getRightColumnLabel(selector))

  /** Complete with not equal (<>) */
  inline def neq(inline selector: B => T): OnConditionChain1[A, B] =
    complete(JoinOperator.Neq, getRightColumnLabel(selector))

  /** Complete with less than (<) */
  inline def lt(inline selector: B => T): OnConditionChain1[A, B] =
    complete(JoinOperator.Lt, getRightColumnLabel(selector))

  /** Complete with less than or equal (<=) */
  inline def lte(inline selector: B => T): OnConditionChain1[A, B] =
    complete(JoinOperator.Lte, getRightColumnLabel(selector))

  /** Complete with greater than (>) */
  inline def gt(inline selector: B => T): OnConditionChain1[A, B] =
    complete(JoinOperator.Gt, getRightColumnLabel(selector))

  /** Complete with greater than or equal (>=) */
  inline def gte(inline selector: B => T): OnConditionChain1[A, B] =
    complete(JoinOperator.Gte, getRightColumnLabel(selector))

  /** Complete with a custom operator */
  inline def op(operator: JoinOperator)(inline selector: B => T): OnConditionChain1[A, B] =
    complete(operator, getRightColumnLabel(selector))

  /** Complete with IS NULL (unary - the left column IS NULL) */
  def isNull(): OnConditionChain1[A, B] =
    val condition = UnaryCondition(leftAlias, leftColumn, JoinOperator.IsNull)
    OnConditionChain1(spec, joinType, leftAlias, rightAlias, rightInstance, Vector(condition))

  /** Complete with IS NOT NULL (unary - the left column IS NOT NULL) */
  def isNotNull(): OnConditionChain1[A, B] =
    val condition = UnaryCondition(leftAlias, leftColumn, JoinOperator.IsNotNull)
    OnConditionChain1(spec, joinType, leftAlias, rightAlias, rightInstance, Vector(condition))
end OnConditionBuilder1

// ============================================================================
// OnConditionChain1 - Chaining additional ON conditions
// ============================================================================

/** Chain state after first ON condition - allows .and() or finalization.
  *
  * Usage:
  * {{{
  *   JoinSpec[User]
  *     .innerJoin[Order].on(_.id).eq(_.userId)
  *     .and(_.status).eq(_.orderStatus)    // chain more conditions
  *     .build
  * }}}
  */
final case class OnConditionChain1[A <: Product: Table, B <: Product: Table](
    spec: JoinSpec1[A],
    joinType: JoinType,
    leftAlias: String,
    rightAlias: String,
    rightInstance: Instance[B],
    conditions: Vector[JoinCondition],
):
  /** Add another condition from the left table (A) - defaults to comparing with right table */
  inline def and[T2](inline selector: A => T2): OnConditionBuilderAnd1[A, B, T2, A] =
    val fieldName   = Macros.extractFieldName[A, T2](selector)
    val columnLabel = spec.baseInstance.fieldNamesToColumns(fieldName).label
    OnConditionBuilderAnd1(this, leftAlias, columnLabel)

  /** Add another condition explicitly from the left table (A) */
  inline def andLeft[T2](inline selector: A => T2): OnConditionBuilderAnd1[A, B, T2, A] =
    val fieldName   = Macros.extractFieldName[A, T2](selector)
    val columnLabel = spec.baseInstance.fieldNamesToColumns(fieldName).label
    OnConditionBuilderAnd1(this, leftAlias, columnLabel)

  /** Add another condition explicitly from the right table (B) */
  inline def andRight[T2](inline selector: B => T2): OnConditionBuilderAnd1[A, B, T2, B] =
    val fieldName   = Macros.extractFieldName[B, T2](selector)
    val columnLabel = rightInstance.fieldNamesToColumns(fieldName).label
    OnConditionBuilderAnd1(this, rightAlias, columnLabel)

  /** Finalize and create the JoinSpec2 */
  def done(using Dialect): JoinSpec2[A, B] =
    val bTable        = summon[Table[B]]
    val conditionFrag = JoinCondition.toSqlFragment(conditions)
    JoinSpec2(
      spec.baseInstance,
      rightInstance,
      Vector(JoinClause(bTable.name, rightAlias, joinType, conditionFrag)),
      spec.wherePredicates,
      spec.sorts,
      spec.limitValue,
      spec.offsetValue,
    )
  end done

  /** Build the SQL fragment directly */
  def build(using Dialect): SqlFragment = done.build

  /** Continue with ORDER BY (implicitly finalizes) */
  def orderBy(sort: Sort[?])(using Dialect): JoinSpec2[A, B] =
    done.orderBy(sort)

  /** Continue with LIMIT (implicitly finalizes) */
  def limit(n: Int)(using Dialect): JoinSpec2[A, B] =
    done.limit(n)

  /** Continue with OFFSET (implicitly finalizes) */
  def offset(n: Long)(using Dialect): JoinSpec2[A, B] =
    done.offset(n)

  /** Continue with WHERE using SqlFragment (implicitly finalizes) */
  def where(predicate: SqlFragment)(using Dialect): JoinSpec2[A, B] =
    done.where(predicate)

  /** Start a type-safe WHERE condition from the first table (A) */
  inline def where[T](inline selector: A => T)(using Dialect): WhereConditionBuilder2[A, B, T] =
    val alias       = spec.baseInstance.alias.getOrElse(spec.baseInstance.tableName)
    val fieldName   = Macros.extractFieldName[A, T](selector)
    val columnLabel = spec.baseInstance.fieldNamesToColumns(fieldName).label
    WhereConditionBuilder2(done, alias, columnLabel)

  /** Start a type-safe WHERE condition from the second table (B) */
  inline def whereFrom[T](inline selector: B => T)(using Dialect): WhereConditionBuilder2[A, B, T] =
    val alias       = rightInstance.alias.getOrElse(rightInstance.tableName)
    val fieldName   = Macros.extractFieldName[B, T](selector)
    val columnLabel = rightInstance.fieldNamesToColumns(fieldName).label
    WhereConditionBuilder2(done, alias, columnLabel)

  /** Add IS NULL WHERE condition on first table */
  inline def whereIsNull[T](inline selector: A => T)(using Dialect): JoinSpec2[A, B] =
    val alias       = spec.baseInstance.alias.getOrElse(spec.baseInstance.tableName)
    val fieldName   = Macros.extractFieldName[A, T](selector)
    val columnLabel = spec.baseInstance.fieldNamesToColumns(fieldName).label
    val condition   = UnaryCondition(alias, columnLabel, JoinOperator.IsNull)
    val whereFrag   = JoinCondition.toSqlFragment(Vector(condition))
    done.where(whereFrag)

  /** Add IS NOT NULL WHERE condition on first table */
  inline def whereIsNotNull[T](inline selector: A => T)(using Dialect): JoinSpec2[A, B] =
    val alias       = spec.baseInstance.alias.getOrElse(spec.baseInstance.tableName)
    val fieldName   = Macros.extractFieldName[A, T](selector)
    val columnLabel = spec.baseInstance.fieldNamesToColumns(fieldName).label
    val condition   = UnaryCondition(alias, columnLabel, JoinOperator.IsNotNull)
    val whereFrag   = JoinCondition.toSqlFragment(Vector(condition))
    done.where(whereFrag)

  /** Add IS NULL WHERE condition on second table */
  inline def whereIsNullFrom[T](inline selector: B => T)(using Dialect): JoinSpec2[A, B] =
    val alias       = rightInstance.alias.getOrElse(rightInstance.tableName)
    val fieldName   = Macros.extractFieldName[B, T](selector)
    val columnLabel = rightInstance.fieldNamesToColumns(fieldName).label
    val condition   = UnaryCondition(alias, columnLabel, JoinOperator.IsNull)
    val whereFrag   = JoinCondition.toSqlFragment(Vector(condition))
    done.where(whereFrag)

  /** Add IS NOT NULL WHERE condition on second table */
  inline def whereIsNotNullFrom[T](inline selector: B => T)(using Dialect): JoinSpec2[A, B] =
    val alias       = rightInstance.alias.getOrElse(rightInstance.tableName)
    val fieldName   = Macros.extractFieldName[B, T](selector)
    val columnLabel = rightInstance.fieldNamesToColumns(fieldName).label
    val condition   = UnaryCondition(alias, columnLabel, JoinOperator.IsNotNull)
    val whereFrag   = JoinCondition.toSqlFragment(Vector(condition))
    done.where(whereFrag)

  /** Continue with another join (implicitly finalizes) */
  def innerJoin[C <: Product: Table](using Dialect): JoinBuilder2[A, B, C] =
    done.innerJoin[C]

  def leftJoin[C <: Product: Table](using Dialect): JoinBuilder2[A, B, C] =
    done.leftJoin[C]

  def rightJoin[C <: Product: Table](using Dialect): JoinBuilder2[A, B, C] =
    done.rightJoin[C]

  def fullJoin[C <: Product: Table](using Dialect): JoinBuilder2[A, B, C] =
    done.fullJoin[C]
end OnConditionChain1

// ============================================================================
// OnConditionBuilderAnd1 - Building chained ON condition
// ============================================================================

/** Builder for chained ON conditions (after .and()).
  *
  * The From type parameter tracks which table the column came from.
  */
final case class OnConditionBuilderAnd1[A <: Product: Table, B <: Product: Table, T, From <: Product](
    chain: OnConditionChain1[A, B],
    fromAlias: String,
    fromColumn: String,
):
  private def completeWith(operator: JoinOperator, toAlias: String, toColumnLabel: String): OnConditionChain1[A, B] =
    val condition = BinaryCondition(fromAlias, fromColumn, operator, toAlias, toColumnLabel)
    chain.copy(conditions = chain.conditions :+ condition)

  private inline def getRightColumnLabel[T2](inline selector: B => T2): String =
    val fieldName = Macros.extractFieldName[B, T2](selector)
    chain.rightInstance.fieldNamesToColumns(fieldName).label

  private inline def getLeftColumnLabel[T2](inline selector: A => T2): String =
    val fieldName = Macros.extractFieldName[A, T2](selector)
    chain.spec.baseInstance.fieldNamesToColumns(fieldName).label

  /** Complete with equality comparing to right table */
  inline def eq(inline selector: B => T): OnConditionChain1[A, B] =
    completeWith(JoinOperator.Eq, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with not equal comparing to right table */
  inline def neq(inline selector: B => T): OnConditionChain1[A, B] =
    completeWith(JoinOperator.Neq, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with less than comparing to right table */
  inline def lt(inline selector: B => T): OnConditionChain1[A, B] =
    completeWith(JoinOperator.Lt, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with less than or equal comparing to right table */
  inline def lte(inline selector: B => T): OnConditionChain1[A, B] =
    completeWith(JoinOperator.Lte, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with greater than comparing to right table */
  inline def gt(inline selector: B => T): OnConditionChain1[A, B] =
    completeWith(JoinOperator.Gt, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with greater than or equal comparing to right table */
  inline def gte(inline selector: B => T): OnConditionChain1[A, B] =
    completeWith(JoinOperator.Gte, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with a custom operator comparing to right table */
  inline def op(operator: JoinOperator)(inline selector: B => T): OnConditionChain1[A, B] =
    completeWith(operator, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with equality comparing to left table */
  inline def eqLeft(inline selector: A => T): OnConditionChain1[A, B] =
    completeWith(JoinOperator.Eq, chain.leftAlias, getLeftColumnLabel(selector))

  /** Complete with IS NULL (unary) */
  def isNull(): OnConditionChain1[A, B] =
    val condition = UnaryCondition(fromAlias, fromColumn, JoinOperator.IsNull)
    chain.copy(conditions = chain.conditions :+ condition)

  /** Complete with IS NOT NULL (unary) */
  def isNotNull(): OnConditionChain1[A, B] =
    val condition = UnaryCondition(fromAlias, fromColumn, JoinOperator.IsNotNull)
    chain.copy(conditions = chain.conditions :+ condition)
end OnConditionBuilderAnd1

// ============================================================================
// WhereConditionBuilder2 - Building type-safe WHERE conditions
// ============================================================================

/** Builder for type-safe WHERE conditions.
  *
  * Supports both column-to-literal and column-to-column comparisons. Literal values are bound as prepared statement
  * parameters (never interpolated into SQL).
  *
  * Usage:
  * {{{
  *   JoinSpec[User]
  *     .innerJoin[Order].on(_.id).eq(_.userId)
  *     .where(_.status).eq("active")           // column-to-literal
  *     .and(_.age).gte(18)
  *     .build
  * }}}
  */
final case class WhereConditionBuilder2[A <: Product: Table, B <: Product: Table, T](
    spec: JoinSpec2[A, B],
    fromAlias: String,
    fromColumn: String,
):
  private def completeLiteral[V](operator: JoinOperator, value: V)(using enc: Encoder[V]): WhereConditionChain2[A, B] =
    val write     = enc(value)
    val condition = LiteralCondition(fromAlias, fromColumn, operator, write)
    WhereConditionChain2(spec, Vector(condition))

  private def completeColumn(operator: JoinOperator, toAlias: String, toColumn: String): WhereConditionChain2[A, B] =
    val condition = BinaryCondition(fromAlias, fromColumn, operator, toAlias, toColumn)
    WhereConditionChain2(spec, Vector(condition))

  // === Literal comparisons (bound as prepared statement parameters) ===

  /** Compare to literal value with equality */
  def eq(value: T)(using Encoder[T]): WhereConditionChain2[A, B] =
    completeLiteral(JoinOperator.Eq, value)

  /** Compare to literal value with not equal */
  def neq(value: T)(using Encoder[T]): WhereConditionChain2[A, B] =
    completeLiteral(JoinOperator.Neq, value)

  /** Compare to literal value with less than */
  def lt(value: T)(using Encoder[T]): WhereConditionChain2[A, B] =
    completeLiteral(JoinOperator.Lt, value)

  /** Compare to literal value with less than or equal */
  def lte(value: T)(using Encoder[T]): WhereConditionChain2[A, B] =
    completeLiteral(JoinOperator.Lte, value)

  /** Compare to literal value with greater than */
  def gt(value: T)(using Encoder[T]): WhereConditionChain2[A, B] =
    completeLiteral(JoinOperator.Gt, value)

  /** Compare to literal value with greater than or equal */
  def gte(value: T)(using Encoder[T]): WhereConditionChain2[A, B] =
    completeLiteral(JoinOperator.Gte, value)

  /** Compare to literal value with custom operator */
  def op(operator: JoinOperator)(value: T)(using Encoder[T]): WhereConditionChain2[A, B] =
    completeLiteral(operator, value)

  // === Column comparisons (no literal values) ===

  private inline def getColumnLabel[X <: Product, T2](instance: Instance[X])(inline selector: X => T2): String =
    val fieldName = Macros.extractFieldName[X, T2](selector)
    instance.fieldNamesToColumns(fieldName).label

  /** Compare to column from the other table (B) */
  inline def eqCol(inline selector: B => T): WhereConditionChain2[A, B] =
    val toAlias       = spec.t2.alias.getOrElse(spec.t2.tableName)
    val toColumnLabel = getColumnLabel(spec.t2)(selector)
    completeColumn(JoinOperator.Eq, toAlias, toColumnLabel)

  /** Compare to column from the other table (B) with greater than or equal */
  inline def gteCol(inline selector: B => T): WhereConditionChain2[A, B] =
    val toAlias       = spec.t2.alias.getOrElse(spec.t2.tableName)
    val toColumnLabel = getColumnLabel(spec.t2)(selector)
    completeColumn(JoinOperator.Gte, toAlias, toColumnLabel)

  /** Compare to column from the other table (B) with less than or equal */
  inline def lteCol(inline selector: B => T): WhereConditionChain2[A, B] =
    val toAlias       = spec.t2.alias.getOrElse(spec.t2.tableName)
    val toColumnLabel = getColumnLabel(spec.t2)(selector)
    completeColumn(JoinOperator.Lte, toAlias, toColumnLabel)

  /** Compare to column from the other table (B) with custom operator */
  inline def opCol(operator: JoinOperator)(inline selector: B => T): WhereConditionChain2[A, B] =
    val toAlias       = spec.t2.alias.getOrElse(spec.t2.tableName)
    val toColumnLabel = getColumnLabel(spec.t2)(selector)
    completeColumn(operator, toAlias, toColumnLabel)

  // === Unary operators ===

  /** IS NULL check */
  def isNull(): WhereConditionChain2[A, B] =
    val condition = UnaryCondition(fromAlias, fromColumn, JoinOperator.IsNull)
    WhereConditionChain2(spec, Vector(condition))

  /** IS NOT NULL check */
  def isNotNull(): WhereConditionChain2[A, B] =
    val condition = UnaryCondition(fromAlias, fromColumn, JoinOperator.IsNotNull)
    WhereConditionChain2(spec, Vector(condition))
end WhereConditionBuilder2

// ============================================================================
// WhereConditionChain2 - Chaining WHERE conditions
// ============================================================================

/** Chain state after WHERE condition - allows .and() or finalization.
  *
  * Usage:
  * {{{
  *   JoinSpec[User]
  *     .innerJoin[Order].on(_.id).eq(_.userId)
  *     .where(_.status).eq("active")
  *     .and(_.age).gte(18)
  *     .andFrom[Order](_.amount).gt(100)
  *     .build
  * }}}
  */
final case class WhereConditionChain2[A <: Product: Table, B <: Product: Table](
    spec: JoinSpec2[A, B],
    whereConditions: Vector[JoinCondition],
):
  /** Add another WHERE condition from the first table (A) */
  inline def and[T2](inline selector: A => T2): WhereConditionBuilder2[A, B, T2] =
    val alias       = spec.t1.alias.getOrElse(spec.t1.tableName)
    val fieldName   = Macros.extractFieldName[A, T2](selector)
    val columnLabel = spec.t1.fieldNamesToColumns(fieldName).label
    WhereConditionBuilder2(specWithConditions, alias, columnLabel)

  /** Add another WHERE condition from the second table (B) */
  inline def andFrom[T2](inline selector: B => T2): WhereConditionBuilder2[A, B, T2] =
    val alias       = spec.t2.alias.getOrElse(spec.t2.tableName)
    val fieldName   = Macros.extractFieldName[B, T2](selector)
    val columnLabel = spec.t2.fieldNamesToColumns(fieldName).label
    WhereConditionBuilder2(specWithConditions, alias, columnLabel)

  /** Add IS NULL condition on first table */
  inline def andIsNull[T](inline selector: A => T): WhereConditionChain2[A, B] =
    val alias       = spec.t1.alias.getOrElse(spec.t1.tableName)
    val fieldName   = Macros.extractFieldName[A, T](selector)
    val columnLabel = spec.t1.fieldNamesToColumns(fieldName).label
    val condition   = UnaryCondition(alias, columnLabel, JoinOperator.IsNull)
    copy(whereConditions = whereConditions :+ condition)

  /** Add IS NOT NULL condition on first table */
  inline def andIsNotNull[T](inline selector: A => T): WhereConditionChain2[A, B] =
    val alias       = spec.t1.alias.getOrElse(spec.t1.tableName)
    val fieldName   = Macros.extractFieldName[A, T](selector)
    val columnLabel = spec.t1.fieldNamesToColumns(fieldName).label
    val condition   = UnaryCondition(alias, columnLabel, JoinOperator.IsNotNull)
    copy(whereConditions = whereConditions :+ condition)

  /** Add IS NULL condition on second table */
  inline def andIsNullFrom[T](inline selector: B => T): WhereConditionChain2[A, B] =
    val alias       = spec.t2.alias.getOrElse(spec.t2.tableName)
    val fieldName   = Macros.extractFieldName[B, T](selector)
    val columnLabel = spec.t2.fieldNamesToColumns(fieldName).label
    val condition   = UnaryCondition(alias, columnLabel, JoinOperator.IsNull)
    copy(whereConditions = whereConditions :+ condition)

  /** Add IS NOT NULL condition on second table */
  inline def andIsNotNullFrom[T](inline selector: B => T): WhereConditionChain2[A, B] =
    val alias       = spec.t2.alias.getOrElse(spec.t2.tableName)
    val fieldName   = Macros.extractFieldName[B, T](selector)
    val columnLabel = spec.t2.fieldNamesToColumns(fieldName).label
    val condition   = UnaryCondition(alias, columnLabel, JoinOperator.IsNotNull)
    copy(whereConditions = whereConditions :+ condition)

  private def specWithConditions: JoinSpec2[A, B] =
    val whereFrag = JoinCondition.toSqlFragment(whereConditions)(using saferis.postgres.PostgresDialect)
    spec.copy(wherePredicates = spec.wherePredicates :+ whereFrag)

  /** Build the SQL fragment */
  def build(using Dialect): SqlFragment =
    val whereFrag = JoinCondition.toSqlFragment(whereConditions)
    spec.copy(wherePredicates = spec.wherePredicates :+ whereFrag).build

  /** Continue with ORDER BY */
  def orderBy(sort: Sort[?])(using Dialect): JoinSpec2[A, B] =
    val whereFrag = JoinCondition.toSqlFragment(whereConditions)
    spec.copy(wherePredicates = spec.wherePredicates :+ whereFrag).orderBy(sort)

  /** Continue with LIMIT */
  def limit(n: Int)(using Dialect): JoinSpec2[A, B] =
    val whereFrag = JoinCondition.toSqlFragment(whereConditions)
    spec.copy(wherePredicates = spec.wherePredicates :+ whereFrag).limit(n)

  /** Continue with OFFSET */
  def offset(n: Long)(using Dialect): JoinSpec2[A, B] =
    val whereFrag = JoinCondition.toSqlFragment(whereConditions)
    spec.copy(wherePredicates = spec.wherePredicates :+ whereFrag).offset(n)
end WhereConditionChain2

// ============================================================================
// JoinSpec2 - Two tables joined
// ============================================================================

final case class JoinSpec2[A <: Product: Table, B <: Product: Table](
    t1: Instance[A],
    t2: Instance[B],
    joins: Vector[JoinClause],
    wherePredicates: Vector[SqlFragment] = Vector.empty,
    sorts: Vector[Sort[?]] = Vector.empty,
    limitValue: Option[Int] = None,
    offsetValue: Option[Long] = None,
):
  /** Add a WHERE predicate */
  def where(predicate: SqlFragment): JoinSpec2[A, B] =
    copy(wherePredicates = wherePredicates :+ predicate)

  /** Add an ORDER BY clause */
  def orderBy(sort: Sort[?]): JoinSpec2[A, B] =
    copy(sorts = sorts :+ sort)

  /** Set LIMIT */
  def limit(n: Int): JoinSpec2[A, B] = copy(limitValue = Some(n))

  /** Set OFFSET */
  def offset(n: Long): JoinSpec2[A, B] = copy(offsetValue = Some(n))

  /** Start an INNER JOIN with a third table */
  def innerJoin[C <: Product: Table]: JoinBuilder2[A, B, C] =
    JoinBuilder2(this, JoinType.Inner)

  /** Start a LEFT JOIN with a third table */
  def leftJoin[C <: Product: Table]: JoinBuilder2[A, B, C] =
    JoinBuilder2(this, JoinType.Left)

  /** Start a RIGHT JOIN with a third table */
  def rightJoin[C <: Product: Table]: JoinBuilder2[A, B, C] =
    JoinBuilder2(this, JoinType.Right)

  /** Start a FULL JOIN with a third table */
  def fullJoin[C <: Product: Table]: JoinBuilder2[A, B, C] =
    JoinBuilder2(this, JoinType.Full)

  /** Build the SQL fragment for this query */
  def build: SqlFragment =
    var result = SqlFragment(s"select * from ${t1.sql}", Seq.empty)

    // Add joins
    for join <- joins do
      val joinSql = s" ${join.joinType.toSql} ${join.tableName} as ${join.alias}"
      result = result :+ SqlFragment(joinSql, Seq.empty)
      if join.condition.sql.nonEmpty then result = result :+ SqlFragment(" on ", Seq.empty) :+ join.condition

    // WHERE clause
    if wherePredicates.nonEmpty then
      val joined = Placeholder.join(wherePredicates, " and ")
      result = result :+ SqlFragment(" where ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    // ORDER BY clause
    if sorts.nonEmpty then
      val sortFragments = sorts.map(_.toSqlFragment)
      val joined        = Placeholder.join(sortFragments, ", ")
      result = result :+ SqlFragment(" order by ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    // LIMIT/OFFSET
    limitValue.foreach(n => result = result :+ SqlFragment(s" limit $n", Seq.empty))
    offsetValue.foreach(n => result = result :+ SqlFragment(s" offset $n", Seq.empty))

    result
  end build

  /** Execute query */
  inline def query[R <: Product: Table](using Trace): ScopedQuery[Seq[R]] = build.query[R]

  /** Execute query returning first row */
  inline def queryOne[R <: Product: Table](using Trace): ScopedQuery[Option[R]] = build.queryOne[R]

end JoinSpec2

// ============================================================================
// JoinBuilder2 - Building ON clause for second join
// ============================================================================

final case class JoinBuilder2[A <: Product: Table, B <: Product: Table, C <: Product: Table](
    spec: JoinSpec2[A, B],
    joinType: JoinType,
):
  /** Start a type-safe ON condition from the first table (A) */
  inline def on[T](inline selector: A => T): OnConditionBuilder2[A, B, C, T, A] =
    val alias          = AliasGenerator.next()
    val cInstance      = AliasGenerator.aliasedInstance[C](alias)
    val leftFieldName  = Macros.extractFieldName[A, T](selector)
    val leftColumnName = spec.t1.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = spec.t1.alias.getOrElse(spec.t1.tableName)
    OnConditionBuilder2(spec, joinType, leftAlias, leftColumnName, alias, cInstance)

  /** Start a type-safe ON condition from the second table (B) - the "previous" table */
  inline def onPrev[T](inline selector: B => T): OnConditionBuilder2[A, B, C, T, B] =
    val alias          = AliasGenerator.next()
    val cInstance      = AliasGenerator.aliasedInstance[C](alias)
    val leftFieldName  = Macros.extractFieldName[B, T](selector)
    val leftColumnName = spec.t2.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = spec.t2.alias.getOrElse(spec.t2.tableName)
    OnConditionBuilder2(spec, joinType, leftAlias, leftColumnName, alias, cInstance)
end JoinBuilder2

// ============================================================================
// OnConditionBuilder2 - Building ON condition for second join
// ============================================================================

/** Builder for the ON condition of the second join - awaiting the right-hand side.
  *
  * From type parameter tracks which table the left column came from.
  */
final case class OnConditionBuilder2[
    A <: Product: Table,
    B <: Product: Table,
    C <: Product: Table,
    T,
    From <: Product,
](
    spec: JoinSpec2[A, B],
    joinType: JoinType,
    leftAlias: String,
    leftColumn: String,
    rightAlias: String,
    rightInstance: Instance[C],
):
  private def complete(operator: JoinOperator, rightColumnLabel: String): OnConditionChain2[A, B, C] =
    val condition = BinaryCondition(leftAlias, leftColumn, operator, rightAlias, rightColumnLabel)
    OnConditionChain2(spec, joinType, rightAlias, rightInstance, Vector(condition))

  private inline def getRightColumnLabel[T2](inline selector: C => T2): String =
    val fieldName = Macros.extractFieldName[C, T2](selector)
    rightInstance.fieldNamesToColumns(fieldName).label

  /** Complete with equality (=) */
  inline def eq(inline selector: C => T): OnConditionChain2[A, B, C] =
    complete(JoinOperator.Eq, getRightColumnLabel(selector))

  /** Complete with not equal (<>) */
  inline def neq(inline selector: C => T): OnConditionChain2[A, B, C] =
    complete(JoinOperator.Neq, getRightColumnLabel(selector))

  /** Complete with less than (<) */
  inline def lt(inline selector: C => T): OnConditionChain2[A, B, C] =
    complete(JoinOperator.Lt, getRightColumnLabel(selector))

  /** Complete with less than or equal (<=) */
  inline def lte(inline selector: C => T): OnConditionChain2[A, B, C] =
    complete(JoinOperator.Lte, getRightColumnLabel(selector))

  /** Complete with greater than (>) */
  inline def gt(inline selector: C => T): OnConditionChain2[A, B, C] =
    complete(JoinOperator.Gt, getRightColumnLabel(selector))

  /** Complete with greater than or equal (>=) */
  inline def gte(inline selector: C => T): OnConditionChain2[A, B, C] =
    complete(JoinOperator.Gte, getRightColumnLabel(selector))

  /** Complete with a custom operator */
  inline def op(operator: JoinOperator)(inline selector: C => T): OnConditionChain2[A, B, C] =
    complete(operator, getRightColumnLabel(selector))

  /** Complete with IS NULL (unary) */
  def isNull(): OnConditionChain2[A, B, C] =
    val condition = UnaryCondition(leftAlias, leftColumn, JoinOperator.IsNull)
    OnConditionChain2(spec, joinType, rightAlias, rightInstance, Vector(condition))

  /** Complete with IS NOT NULL (unary) */
  def isNotNull(): OnConditionChain2[A, B, C] =
    val condition = UnaryCondition(leftAlias, leftColumn, JoinOperator.IsNotNull)
    OnConditionChain2(spec, joinType, rightAlias, rightInstance, Vector(condition))
end OnConditionBuilder2

// ============================================================================
// OnConditionChain2 - Chaining additional ON conditions for second join
// ============================================================================

final case class OnConditionChain2[A <: Product: Table, B <: Product: Table, C <: Product: Table](
    spec: JoinSpec2[A, B],
    joinType: JoinType,
    rightAlias: String,
    rightInstance: Instance[C],
    conditions: Vector[JoinCondition],
):
  /** Add another condition from the first table (A) */
  inline def and[T2](inline selector: A => T2): OnConditionBuilderAnd2[A, B, C, T2, A] =
    val fieldName   = Macros.extractFieldName[A, T2](selector)
    val columnLabel = spec.t1.fieldNamesToColumns(fieldName).label
    val alias       = spec.t1.alias.getOrElse(spec.t1.tableName)
    OnConditionBuilderAnd2(this, alias, columnLabel)

  /** Add another condition from the second table (B) */
  inline def andFrom[T2](inline selector: B => T2): OnConditionBuilderAnd2[A, B, C, T2, B] =
    val fieldName   = Macros.extractFieldName[B, T2](selector)
    val columnLabel = spec.t2.fieldNamesToColumns(fieldName).label
    val alias       = spec.t2.alias.getOrElse(spec.t2.tableName)
    OnConditionBuilderAnd2(this, alias, columnLabel)

  /** Add another condition from the new table (C) */
  inline def andRight[T2](inline selector: C => T2): OnConditionBuilderAnd2[A, B, C, T2, C] =
    val fieldName   = Macros.extractFieldName[C, T2](selector)
    val columnLabel = rightInstance.fieldNamesToColumns(fieldName).label
    OnConditionBuilderAnd2(this, rightAlias, columnLabel)

  /** Finalize and create the JoinSpec3 */
  def done(using Dialect): JoinSpec3[A, B, C] =
    val cTable        = summon[Table[C]]
    val conditionFrag = JoinCondition.toSqlFragment(conditions)
    JoinSpec3(
      spec.t1,
      spec.t2,
      rightInstance,
      spec.joins :+ JoinClause(cTable.name, rightAlias, joinType, conditionFrag),
      spec.wherePredicates,
      spec.sorts,
      spec.limitValue,
      spec.offsetValue,
    )
  end done

  /** Build the SQL fragment directly */
  def build(using Dialect): SqlFragment = done.build

  /** Continue with ORDER BY */
  def orderBy(sort: Sort[?])(using Dialect): JoinSpec3[A, B, C] = done.orderBy(sort)

  /** Continue with LIMIT */
  def limit(n: Int)(using Dialect): JoinSpec3[A, B, C] = done.limit(n)

  /** Continue with OFFSET */
  def offset(n: Long)(using Dialect): JoinSpec3[A, B, C] = done.offset(n)

  /** Continue with WHERE using SqlFragment */
  def where(predicate: SqlFragment)(using Dialect): JoinSpec3[A, B, C] = done.where(predicate)

  /** Continue with another join */
  def innerJoin[D <: Product: Table](using Dialect): JoinBuilder3[A, B, C, D] = done.innerJoin[D]
  def leftJoin[D <: Product: Table](using Dialect): JoinBuilder3[A, B, C, D]  = done.leftJoin[D]
  def rightJoin[D <: Product: Table](using Dialect): JoinBuilder3[A, B, C, D] = done.rightJoin[D]
  def fullJoin[D <: Product: Table](using Dialect): JoinBuilder3[A, B, C, D]  = done.fullJoin[D]
end OnConditionChain2

// ============================================================================
// OnConditionBuilderAnd2 - Building chained ON condition for second join
// ============================================================================

final case class OnConditionBuilderAnd2[
    A <: Product: Table,
    B <: Product: Table,
    C <: Product: Table,
    T,
    From <: Product,
](
    chain: OnConditionChain2[A, B, C],
    fromAlias: String,
    fromColumn: String,
):
  private def completeWith(operator: JoinOperator, toAlias: String, toColumnLabel: String): OnConditionChain2[A, B, C] =
    val condition = BinaryCondition(fromAlias, fromColumn, operator, toAlias, toColumnLabel)
    chain.copy(conditions = chain.conditions :+ condition)

  private inline def getRightColumnLabel[T2](inline selector: C => T2): String =
    val fieldName = Macros.extractFieldName[C, T2](selector)
    chain.rightInstance.fieldNamesToColumns(fieldName).label

  /** Complete with equality comparing to new table (C) */
  inline def eq(inline selector: C => T): OnConditionChain2[A, B, C] =
    completeWith(JoinOperator.Eq, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with not equal comparing to new table (C) */
  inline def neq(inline selector: C => T): OnConditionChain2[A, B, C] =
    completeWith(JoinOperator.Neq, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with less than comparing to new table (C) */
  inline def lt(inline selector: C => T): OnConditionChain2[A, B, C] =
    completeWith(JoinOperator.Lt, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with less than or equal comparing to new table (C) */
  inline def lte(inline selector: C => T): OnConditionChain2[A, B, C] =
    completeWith(JoinOperator.Lte, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with greater than comparing to new table (C) */
  inline def gt(inline selector: C => T): OnConditionChain2[A, B, C] =
    completeWith(JoinOperator.Gt, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with greater than or equal comparing to new table (C) */
  inline def gte(inline selector: C => T): OnConditionChain2[A, B, C] =
    completeWith(JoinOperator.Gte, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with a custom operator comparing to new table (C) */
  inline def op(operator: JoinOperator)(inline selector: C => T): OnConditionChain2[A, B, C] =
    completeWith(operator, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete with IS NULL (unary) */
  def isNull(): OnConditionChain2[A, B, C] =
    val condition = UnaryCondition(fromAlias, fromColumn, JoinOperator.IsNull)
    chain.copy(conditions = chain.conditions :+ condition)

  /** Complete with IS NOT NULL (unary) */
  def isNotNull(): OnConditionChain2[A, B, C] =
    val condition = UnaryCondition(fromAlias, fromColumn, JoinOperator.IsNotNull)
    chain.copy(conditions = chain.conditions :+ condition)
end OnConditionBuilderAnd2

// ============================================================================
// JoinSpec3 - Three tables joined
// ============================================================================

final case class JoinSpec3[A <: Product: Table, B <: Product: Table, C <: Product: Table](
    t1: Instance[A],
    t2: Instance[B],
    t3: Instance[C],
    joins: Vector[JoinClause],
    wherePredicates: Vector[SqlFragment] = Vector.empty,
    sorts: Vector[Sort[?]] = Vector.empty,
    limitValue: Option[Int] = None,
    offsetValue: Option[Long] = None,
):
  /** Add a WHERE predicate */
  def where(predicate: SqlFragment): JoinSpec3[A, B, C] =
    copy(wherePredicates = wherePredicates :+ predicate)

  /** Add an ORDER BY clause */
  def orderBy(sort: Sort[?]): JoinSpec3[A, B, C] =
    copy(sorts = sorts :+ sort)

  /** Set LIMIT */
  def limit(n: Int): JoinSpec3[A, B, C] = copy(limitValue = Some(n))

  /** Set OFFSET */
  def offset(n: Long): JoinSpec3[A, B, C] = copy(offsetValue = Some(n))

  /** Start an INNER JOIN with a fourth table */
  def innerJoin[D <: Product: Table]: JoinBuilder3[A, B, C, D] =
    JoinBuilder3(this, JoinType.Inner)

  /** Start a LEFT JOIN with a fourth table */
  def leftJoin[D <: Product: Table]: JoinBuilder3[A, B, C, D] =
    JoinBuilder3(this, JoinType.Left)

  /** Start a RIGHT JOIN with a fourth table */
  def rightJoin[D <: Product: Table]: JoinBuilder3[A, B, C, D] =
    JoinBuilder3(this, JoinType.Right)

  /** Start a FULL JOIN with a fourth table */
  def fullJoin[D <: Product: Table]: JoinBuilder3[A, B, C, D] =
    JoinBuilder3(this, JoinType.Full)

  /** Build the SQL fragment for this query */
  def build: SqlFragment =
    var result = SqlFragment(s"select * from ${t1.sql}", Seq.empty)

    for join <- joins do
      val joinSql = s" ${join.joinType.toSql} ${join.tableName} as ${join.alias}"
      result = result :+ SqlFragment(joinSql, Seq.empty)
      if join.condition.sql.nonEmpty then result = result :+ SqlFragment(" on ", Seq.empty) :+ join.condition

    if wherePredicates.nonEmpty then
      val joined = Placeholder.join(wherePredicates, " and ")
      result = result :+ SqlFragment(" where ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    if sorts.nonEmpty then
      val sortFragments = sorts.map(_.toSqlFragment)
      val joined        = Placeholder.join(sortFragments, ", ")
      result = result :+ SqlFragment(" order by ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    limitValue.foreach(n => result = result :+ SqlFragment(s" limit $n", Seq.empty))
    offsetValue.foreach(n => result = result :+ SqlFragment(s" offset $n", Seq.empty))

    result
  end build

  inline def query[R <: Product: Table](using Trace): ScopedQuery[Seq[R]]       = build.query[R]
  inline def queryOne[R <: Product: Table](using Trace): ScopedQuery[Option[R]] = build.queryOne[R]

end JoinSpec3

// ============================================================================
// JoinBuilder3 - Building ON clause for third join
// ============================================================================

final case class JoinBuilder3[A <: Product: Table, B <: Product: Table, C <: Product: Table, D <: Product: Table](
    spec: JoinSpec3[A, B, C],
    joinType: JoinType,
):
  /** Start a type-safe ON condition from the first table (A) */
  inline def on[T](inline selector: A => T): OnConditionBuilder3[A, B, C, D, T, A] =
    val alias          = AliasGenerator.next()
    val dInstance      = AliasGenerator.aliasedInstance[D](alias)
    val leftFieldName  = Macros.extractFieldName[A, T](selector)
    val leftColumnName = spec.t1.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = spec.t1.alias.getOrElse(spec.t1.tableName)
    OnConditionBuilder3(spec, joinType, leftAlias, leftColumnName, alias, dInstance)

  /** Start a type-safe ON condition from the second table (B) */
  inline def onFrom[T](inline selector: B => T): OnConditionBuilder3[A, B, C, D, T, B] =
    val alias          = AliasGenerator.next()
    val dInstance      = AliasGenerator.aliasedInstance[D](alias)
    val leftFieldName  = Macros.extractFieldName[B, T](selector)
    val leftColumnName = spec.t2.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = spec.t2.alias.getOrElse(spec.t2.tableName)
    OnConditionBuilder3(spec, joinType, leftAlias, leftColumnName, alias, dInstance)

  /** Start a type-safe ON condition from the third table (C) - the "previous" table */
  inline def onPrev[T](inline selector: C => T): OnConditionBuilder3[A, B, C, D, T, C] =
    val alias          = AliasGenerator.next()
    val dInstance      = AliasGenerator.aliasedInstance[D](alias)
    val leftFieldName  = Macros.extractFieldName[C, T](selector)
    val leftColumnName = spec.t3.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = spec.t3.alias.getOrElse(spec.t3.tableName)
    OnConditionBuilder3(spec, joinType, leftAlias, leftColumnName, alias, dInstance)
end JoinBuilder3

// ============================================================================
// OnConditionBuilder3 - Building ON condition for third join
// ============================================================================

final case class OnConditionBuilder3[
    A <: Product: Table,
    B <: Product: Table,
    C <: Product: Table,
    D <: Product: Table,
    T,
    From <: Product,
](
    spec: JoinSpec3[A, B, C],
    joinType: JoinType,
    leftAlias: String,
    leftColumn: String,
    rightAlias: String,
    rightInstance: Instance[D],
):
  private def complete(operator: JoinOperator, rightColumnLabel: String): OnConditionChain3[A, B, C, D] =
    val condition = BinaryCondition(leftAlias, leftColumn, operator, rightAlias, rightColumnLabel)
    OnConditionChain3(spec, joinType, rightAlias, rightInstance, Vector(condition))

  private inline def getRightColumnLabel[T2](inline selector: D => T2): String =
    val fieldName = Macros.extractFieldName[D, T2](selector)
    rightInstance.fieldNamesToColumns(fieldName).label

  inline def eq(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    complete(JoinOperator.Eq, getRightColumnLabel(selector))

  inline def neq(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    complete(JoinOperator.Neq, getRightColumnLabel(selector))

  inline def lt(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    complete(JoinOperator.Lt, getRightColumnLabel(selector))

  inline def lte(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    complete(JoinOperator.Lte, getRightColumnLabel(selector))

  inline def gt(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    complete(JoinOperator.Gt, getRightColumnLabel(selector))

  inline def gte(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    complete(JoinOperator.Gte, getRightColumnLabel(selector))

  inline def op(operator: JoinOperator)(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    complete(operator, getRightColumnLabel(selector))

  def isNull(): OnConditionChain3[A, B, C, D] =
    val condition = UnaryCondition(leftAlias, leftColumn, JoinOperator.IsNull)
    OnConditionChain3(spec, joinType, rightAlias, rightInstance, Vector(condition))

  def isNotNull(): OnConditionChain3[A, B, C, D] =
    val condition = UnaryCondition(leftAlias, leftColumn, JoinOperator.IsNotNull)
    OnConditionChain3(spec, joinType, rightAlias, rightInstance, Vector(condition))
end OnConditionBuilder3

// ============================================================================
// OnConditionChain3 - Chaining additional ON conditions for third join
// ============================================================================

final case class OnConditionChain3[
    A <: Product: Table,
    B <: Product: Table,
    C <: Product: Table,
    D <: Product: Table,
](
    spec: JoinSpec3[A, B, C],
    joinType: JoinType,
    rightAlias: String,
    rightInstance: Instance[D],
    conditions: Vector[JoinCondition],
):
  inline def and[T2](inline selector: A => T2): OnConditionBuilderAnd3[A, B, C, D, T2, A] =
    val fieldName   = Macros.extractFieldName[A, T2](selector)
    val columnLabel = spec.t1.fieldNamesToColumns(fieldName).label
    val alias       = spec.t1.alias.getOrElse(spec.t1.tableName)
    OnConditionBuilderAnd3(this, alias, columnLabel)

  inline def andFrom[T2](inline selector: B => T2): OnConditionBuilderAnd3[A, B, C, D, T2, B] =
    val fieldName   = Macros.extractFieldName[B, T2](selector)
    val columnLabel = spec.t2.fieldNamesToColumns(fieldName).label
    val alias       = spec.t2.alias.getOrElse(spec.t2.tableName)
    OnConditionBuilderAnd3(this, alias, columnLabel)

  inline def andFromC[T2](inline selector: C => T2): OnConditionBuilderAnd3[A, B, C, D, T2, C] =
    val fieldName   = Macros.extractFieldName[C, T2](selector)
    val columnLabel = spec.t3.fieldNamesToColumns(fieldName).label
    val alias       = spec.t3.alias.getOrElse(spec.t3.tableName)
    OnConditionBuilderAnd3(this, alias, columnLabel)

  inline def andRight[T2](inline selector: D => T2): OnConditionBuilderAnd3[A, B, C, D, T2, D] =
    val fieldName   = Macros.extractFieldName[D, T2](selector)
    val columnLabel = rightInstance.fieldNamesToColumns(fieldName).label
    OnConditionBuilderAnd3(this, rightAlias, columnLabel)

  def done(using Dialect): JoinSpec4[A, B, C, D] =
    val dTable        = summon[Table[D]]
    val conditionFrag = JoinCondition.toSqlFragment(conditions)
    JoinSpec4(
      spec.t1,
      spec.t2,
      spec.t3,
      rightInstance,
      spec.joins :+ JoinClause(dTable.name, rightAlias, joinType, conditionFrag),
      spec.wherePredicates,
      spec.sorts,
      spec.limitValue,
      spec.offsetValue,
    )
  end done

  def build(using Dialect): SqlFragment                                          = done.build
  def orderBy(sort: Sort[?])(using Dialect): JoinSpec4[A, B, C, D]               = done.orderBy(sort)
  def limit(n: Int)(using Dialect): JoinSpec4[A, B, C, D]                        = done.limit(n)
  def offset(n: Long)(using Dialect): JoinSpec4[A, B, C, D]                      = done.offset(n)
  def where(predicate: SqlFragment)(using Dialect): JoinSpec4[A, B, C, D]        = done.where(predicate)
  def innerJoin[E <: Product: Table](using Dialect): JoinBuilder4[A, B, C, D, E] = done.innerJoin[E]
  def leftJoin[E <: Product: Table](using Dialect): JoinBuilder4[A, B, C, D, E]  = done.leftJoin[E]
  def rightJoin[E <: Product: Table](using Dialect): JoinBuilder4[A, B, C, D, E] = done.rightJoin[E]
  def fullJoin[E <: Product: Table](using Dialect): JoinBuilder4[A, B, C, D, E]  = done.fullJoin[E]
end OnConditionChain3

// ============================================================================
// OnConditionBuilderAnd3 - Building chained ON condition for third join
// ============================================================================

final case class OnConditionBuilderAnd3[
    A <: Product: Table,
    B <: Product: Table,
    C <: Product: Table,
    D <: Product: Table,
    T,
    From <: Product,
](
    chain: OnConditionChain3[A, B, C, D],
    fromAlias: String,
    fromColumn: String,
):
  private def completeWith(
      operator: JoinOperator,
      toAlias: String,
      toColumnLabel: String,
  ): OnConditionChain3[A, B, C, D] =
    val condition = BinaryCondition(fromAlias, fromColumn, operator, toAlias, toColumnLabel)
    chain.copy(conditions = chain.conditions :+ condition)

  private inline def getRightColumnLabel[T2](inline selector: D => T2): String =
    val fieldName = Macros.extractFieldName[D, T2](selector)
    chain.rightInstance.fieldNamesToColumns(fieldName).label

  inline def eq(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    completeWith(JoinOperator.Eq, chain.rightAlias, getRightColumnLabel(selector))

  inline def neq(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    completeWith(JoinOperator.Neq, chain.rightAlias, getRightColumnLabel(selector))

  inline def lt(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    completeWith(JoinOperator.Lt, chain.rightAlias, getRightColumnLabel(selector))

  inline def lte(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    completeWith(JoinOperator.Lte, chain.rightAlias, getRightColumnLabel(selector))

  inline def gt(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    completeWith(JoinOperator.Gt, chain.rightAlias, getRightColumnLabel(selector))

  inline def gte(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    completeWith(JoinOperator.Gte, chain.rightAlias, getRightColumnLabel(selector))

  inline def op(operator: JoinOperator)(inline selector: D => T): OnConditionChain3[A, B, C, D] =
    completeWith(operator, chain.rightAlias, getRightColumnLabel(selector))

  def isNull(): OnConditionChain3[A, B, C, D] =
    val condition = UnaryCondition(fromAlias, fromColumn, JoinOperator.IsNull)
    chain.copy(conditions = chain.conditions :+ condition)

  def isNotNull(): OnConditionChain3[A, B, C, D] =
    val condition = UnaryCondition(fromAlias, fromColumn, JoinOperator.IsNotNull)
    chain.copy(conditions = chain.conditions :+ condition)
end OnConditionBuilderAnd3

// ============================================================================
// JoinSpec4 - Four tables joined
// ============================================================================

final case class JoinSpec4[A <: Product: Table, B <: Product: Table, C <: Product: Table, D <: Product: Table](
    t1: Instance[A],
    t2: Instance[B],
    t3: Instance[C],
    t4: Instance[D],
    joins: Vector[JoinClause],
    wherePredicates: Vector[SqlFragment] = Vector.empty,
    sorts: Vector[Sort[?]] = Vector.empty,
    limitValue: Option[Int] = None,
    offsetValue: Option[Long] = None,
):
  def where(predicate: SqlFragment): JoinSpec4[A, B, C, D] =
    copy(wherePredicates = wherePredicates :+ predicate)

  def orderBy(sort: Sort[?]): JoinSpec4[A, B, C, D] =
    copy(sorts = sorts :+ sort)

  def limit(n: Int): JoinSpec4[A, B, C, D]   = copy(limitValue = Some(n))
  def offset(n: Long): JoinSpec4[A, B, C, D] = copy(offsetValue = Some(n))

  def innerJoin[E <: Product: Table]: JoinBuilder4[A, B, C, D, E] =
    JoinBuilder4(this, JoinType.Inner)

  def leftJoin[E <: Product: Table]: JoinBuilder4[A, B, C, D, E] =
    JoinBuilder4(this, JoinType.Left)

  def rightJoin[E <: Product: Table]: JoinBuilder4[A, B, C, D, E] =
    JoinBuilder4(this, JoinType.Right)

  def fullJoin[E <: Product: Table]: JoinBuilder4[A, B, C, D, E] =
    JoinBuilder4(this, JoinType.Full)

  def build: SqlFragment =
    var result = SqlFragment(s"select * from ${t1.sql}", Seq.empty)

    for join <- joins do
      val joinSql = s" ${join.joinType.toSql} ${join.tableName} as ${join.alias}"
      result = result :+ SqlFragment(joinSql, Seq.empty)
      if join.condition.sql.nonEmpty then result = result :+ SqlFragment(" on ", Seq.empty) :+ join.condition

    if wherePredicates.nonEmpty then
      val joined = Placeholder.join(wherePredicates, " and ")
      result = result :+ SqlFragment(" where ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    if sorts.nonEmpty then
      val sortFragments = sorts.map(_.toSqlFragment)
      val joined        = Placeholder.join(sortFragments, ", ")
      result = result :+ SqlFragment(" order by ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    limitValue.foreach(n => result = result :+ SqlFragment(s" limit $n", Seq.empty))
    offsetValue.foreach(n => result = result :+ SqlFragment(s" offset $n", Seq.empty))

    result
  end build

  inline def query[R <: Product: Table](using Trace): ScopedQuery[Seq[R]]       = build.query[R]
  inline def queryOne[R <: Product: Table](using Trace): ScopedQuery[Option[R]] = build.queryOne[R]

end JoinSpec4

// ============================================================================
// JoinBuilder4 - Building ON clause for fourth join
// ============================================================================

final case class JoinBuilder4[
    A <: Product: Table,
    B <: Product: Table,
    C <: Product: Table,
    D <: Product: Table,
    E <: Product: Table,
](
    spec: JoinSpec4[A, B, C, D],
    joinType: JoinType,
):
  /** Start a type-safe ON condition from the first table (A) */
  inline def on[T](inline selector: A => T): OnConditionBuilder4[A, B, C, D, E, T, A] =
    val alias          = AliasGenerator.next()
    val eInstance      = AliasGenerator.aliasedInstance[E](alias)
    val leftFieldName  = Macros.extractFieldName[A, T](selector)
    val leftColumnName = spec.t1.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = spec.t1.alias.getOrElse(spec.t1.tableName)
    OnConditionBuilder4(spec, joinType, leftAlias, leftColumnName, alias, eInstance)

  /** Start a type-safe ON condition from the second table (B) */
  inline def onFromB[T](inline selector: B => T): OnConditionBuilder4[A, B, C, D, E, T, B] =
    val alias          = AliasGenerator.next()
    val eInstance      = AliasGenerator.aliasedInstance[E](alias)
    val leftFieldName  = Macros.extractFieldName[B, T](selector)
    val leftColumnName = spec.t2.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = spec.t2.alias.getOrElse(spec.t2.tableName)
    OnConditionBuilder4(spec, joinType, leftAlias, leftColumnName, alias, eInstance)

  /** Start a type-safe ON condition from the third table (C) */
  inline def onFromC[T](inline selector: C => T): OnConditionBuilder4[A, B, C, D, E, T, C] =
    val alias          = AliasGenerator.next()
    val eInstance      = AliasGenerator.aliasedInstance[E](alias)
    val leftFieldName  = Macros.extractFieldName[C, T](selector)
    val leftColumnName = spec.t3.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = spec.t3.alias.getOrElse(spec.t3.tableName)
    OnConditionBuilder4(spec, joinType, leftAlias, leftColumnName, alias, eInstance)

  /** Start a type-safe ON condition from the fourth table (D) - the "previous" table */
  inline def onPrev[T](inline selector: D => T): OnConditionBuilder4[A, B, C, D, E, T, D] =
    val alias          = AliasGenerator.next()
    val eInstance      = AliasGenerator.aliasedInstance[E](alias)
    val leftFieldName  = Macros.extractFieldName[D, T](selector)
    val leftColumnName = spec.t4.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = spec.t4.alias.getOrElse(spec.t4.tableName)
    OnConditionBuilder4(spec, joinType, leftAlias, leftColumnName, alias, eInstance)
end JoinBuilder4

// ============================================================================
// OnConditionBuilder4 - Building ON condition for fourth join
// ============================================================================

final case class OnConditionBuilder4[
    A <: Product: Table,
    B <: Product: Table,
    C <: Product: Table,
    D <: Product: Table,
    E <: Product: Table,
    T,
    From <: Product,
](
    spec: JoinSpec4[A, B, C, D],
    joinType: JoinType,
    leftAlias: String,
    leftColumn: String,
    rightAlias: String,
    rightInstance: Instance[E],
):
  private def complete(operator: JoinOperator, rightColumnLabel: String): OnConditionChain4[A, B, C, D, E] =
    val condition = BinaryCondition(leftAlias, leftColumn, operator, rightAlias, rightColumnLabel)
    OnConditionChain4(spec, joinType, rightAlias, rightInstance, Vector(condition))

  private inline def getRightColumnLabel[T2](inline selector: E => T2): String =
    val fieldName = Macros.extractFieldName[E, T2](selector)
    rightInstance.fieldNamesToColumns(fieldName).label

  inline def eq(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    complete(JoinOperator.Eq, getRightColumnLabel(selector))

  inline def neq(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    complete(JoinOperator.Neq, getRightColumnLabel(selector))

  inline def lt(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    complete(JoinOperator.Lt, getRightColumnLabel(selector))

  inline def lte(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    complete(JoinOperator.Lte, getRightColumnLabel(selector))

  inline def gt(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    complete(JoinOperator.Gt, getRightColumnLabel(selector))

  inline def gte(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    complete(JoinOperator.Gte, getRightColumnLabel(selector))

  inline def op(operator: JoinOperator)(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    complete(operator, getRightColumnLabel(selector))

  def isNull(): OnConditionChain4[A, B, C, D, E] =
    val condition = UnaryCondition(leftAlias, leftColumn, JoinOperator.IsNull)
    OnConditionChain4(spec, joinType, rightAlias, rightInstance, Vector(condition))

  def isNotNull(): OnConditionChain4[A, B, C, D, E] =
    val condition = UnaryCondition(leftAlias, leftColumn, JoinOperator.IsNotNull)
    OnConditionChain4(spec, joinType, rightAlias, rightInstance, Vector(condition))
end OnConditionBuilder4

// ============================================================================
// OnConditionChain4 - Chaining additional ON conditions for fourth join
// ============================================================================

final case class OnConditionChain4[
    A <: Product: Table,
    B <: Product: Table,
    C <: Product: Table,
    D <: Product: Table,
    E <: Product: Table,
](
    spec: JoinSpec4[A, B, C, D],
    joinType: JoinType,
    rightAlias: String,
    rightInstance: Instance[E],
    conditions: Vector[JoinCondition],
):
  inline def and[T2](inline selector: A => T2): OnConditionBuilderAnd4[A, B, C, D, E, T2, A] =
    val fieldName   = Macros.extractFieldName[A, T2](selector)
    val columnLabel = spec.t1.fieldNamesToColumns(fieldName).label
    val alias       = spec.t1.alias.getOrElse(spec.t1.tableName)
    OnConditionBuilderAnd4(this, alias, columnLabel)

  inline def andFromB[T2](inline selector: B => T2): OnConditionBuilderAnd4[A, B, C, D, E, T2, B] =
    val fieldName   = Macros.extractFieldName[B, T2](selector)
    val columnLabel = spec.t2.fieldNamesToColumns(fieldName).label
    val alias       = spec.t2.alias.getOrElse(spec.t2.tableName)
    OnConditionBuilderAnd4(this, alias, columnLabel)

  inline def andFromC[T2](inline selector: C => T2): OnConditionBuilderAnd4[A, B, C, D, E, T2, C] =
    val fieldName   = Macros.extractFieldName[C, T2](selector)
    val columnLabel = spec.t3.fieldNamesToColumns(fieldName).label
    val alias       = spec.t3.alias.getOrElse(spec.t3.tableName)
    OnConditionBuilderAnd4(this, alias, columnLabel)

  inline def andFromD[T2](inline selector: D => T2): OnConditionBuilderAnd4[A, B, C, D, E, T2, D] =
    val fieldName   = Macros.extractFieldName[D, T2](selector)
    val columnLabel = spec.t4.fieldNamesToColumns(fieldName).label
    val alias       = spec.t4.alias.getOrElse(spec.t4.tableName)
    OnConditionBuilderAnd4(this, alias, columnLabel)

  inline def andRight[T2](inline selector: E => T2): OnConditionBuilderAnd4[A, B, C, D, E, T2, E] =
    val fieldName   = Macros.extractFieldName[E, T2](selector)
    val columnLabel = rightInstance.fieldNamesToColumns(fieldName).label
    OnConditionBuilderAnd4(this, rightAlias, columnLabel)

  def done(using Dialect): JoinSpec5[A, B, C, D, E] =
    val eTable        = summon[Table[E]]
    val conditionFrag = JoinCondition.toSqlFragment(conditions)
    JoinSpec5(
      spec.t1,
      spec.t2,
      spec.t3,
      spec.t4,
      rightInstance,
      spec.joins :+ JoinClause(eTable.name, rightAlias, joinType, conditionFrag),
      spec.wherePredicates,
      spec.sorts,
      spec.limitValue,
      spec.offsetValue,
    )
  end done

  def build(using Dialect): SqlFragment                                      = done.build
  def orderBy(sort: Sort[?])(using Dialect): JoinSpec5[A, B, C, D, E]        = done.orderBy(sort)
  def limit(n: Int)(using Dialect): JoinSpec5[A, B, C, D, E]                 = done.limit(n)
  def offset(n: Long)(using Dialect): JoinSpec5[A, B, C, D, E]               = done.offset(n)
  def where(predicate: SqlFragment)(using Dialect): JoinSpec5[A, B, C, D, E] = done.where(predicate)
end OnConditionChain4

// ============================================================================
// OnConditionBuilderAnd4 - Building chained ON condition for fourth join
// ============================================================================

final case class OnConditionBuilderAnd4[
    A <: Product: Table,
    B <: Product: Table,
    C <: Product: Table,
    D <: Product: Table,
    E <: Product: Table,
    T,
    From <: Product,
](
    chain: OnConditionChain4[A, B, C, D, E],
    fromAlias: String,
    fromColumn: String,
):
  private def completeWith(
      operator: JoinOperator,
      toAlias: String,
      toColumnLabel: String,
  ): OnConditionChain4[A, B, C, D, E] =
    val condition = BinaryCondition(fromAlias, fromColumn, operator, toAlias, toColumnLabel)
    chain.copy(conditions = chain.conditions :+ condition)

  private inline def getRightColumnLabel[T2](inline selector: E => T2): String =
    val fieldName = Macros.extractFieldName[E, T2](selector)
    chain.rightInstance.fieldNamesToColumns(fieldName).label

  inline def eq(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    completeWith(JoinOperator.Eq, chain.rightAlias, getRightColumnLabel(selector))

  inline def neq(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    completeWith(JoinOperator.Neq, chain.rightAlias, getRightColumnLabel(selector))

  inline def lt(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    completeWith(JoinOperator.Lt, chain.rightAlias, getRightColumnLabel(selector))

  inline def lte(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    completeWith(JoinOperator.Lte, chain.rightAlias, getRightColumnLabel(selector))

  inline def gt(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    completeWith(JoinOperator.Gt, chain.rightAlias, getRightColumnLabel(selector))

  inline def gte(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    completeWith(JoinOperator.Gte, chain.rightAlias, getRightColumnLabel(selector))

  inline def op(operator: JoinOperator)(inline selector: E => T): OnConditionChain4[A, B, C, D, E] =
    completeWith(operator, chain.rightAlias, getRightColumnLabel(selector))

  def isNull(): OnConditionChain4[A, B, C, D, E] =
    val condition = UnaryCondition(fromAlias, fromColumn, JoinOperator.IsNull)
    chain.copy(conditions = chain.conditions :+ condition)

  def isNotNull(): OnConditionChain4[A, B, C, D, E] =
    val condition = UnaryCondition(fromAlias, fromColumn, JoinOperator.IsNotNull)
    chain.copy(conditions = chain.conditions :+ condition)
end OnConditionBuilderAnd4

// ============================================================================
// JoinSpec5 - Five tables joined (maximum)
// ============================================================================

final case class JoinSpec5[
    A <: Product: Table,
    B <: Product: Table,
    C <: Product: Table,
    D <: Product: Table,
    E <: Product: Table,
](
    t1: Instance[A],
    t2: Instance[B],
    t3: Instance[C],
    t4: Instance[D],
    t5: Instance[E],
    joins: Vector[JoinClause],
    wherePredicates: Vector[SqlFragment] = Vector.empty,
    sorts: Vector[Sort[?]] = Vector.empty,
    limitValue: Option[Int] = None,
    offsetValue: Option[Long] = None,
):
  def where(predicate: SqlFragment): JoinSpec5[A, B, C, D, E] =
    copy(wherePredicates = wherePredicates :+ predicate)

  def orderBy(sort: Sort[?]): JoinSpec5[A, B, C, D, E] =
    copy(sorts = sorts :+ sort)

  def limit(n: Int): JoinSpec5[A, B, C, D, E]   = copy(limitValue = Some(n))
  def offset(n: Long): JoinSpec5[A, B, C, D, E] = copy(offsetValue = Some(n))

  def build: SqlFragment =
    var result = SqlFragment(s"select * from ${t1.sql}", Seq.empty)

    for join <- joins do
      val joinSql = s" ${join.joinType.toSql} ${join.tableName} as ${join.alias}"
      result = result :+ SqlFragment(joinSql, Seq.empty)
      if join.condition.sql.nonEmpty then result = result :+ SqlFragment(" on ", Seq.empty) :+ join.condition

    if wherePredicates.nonEmpty then
      val joined = Placeholder.join(wherePredicates, " and ")
      result = result :+ SqlFragment(" where ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    if sorts.nonEmpty then
      val sortFragments = sorts.map(_.toSqlFragment)
      val joined        = Placeholder.join(sortFragments, ", ")
      result = result :+ SqlFragment(" order by ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    limitValue.foreach(n => result = result :+ SqlFragment(s" limit $n", Seq.empty))
    offsetValue.foreach(n => result = result :+ SqlFragment(s" offset $n", Seq.empty))

    result
  end build

  inline def query[R <: Product: Table](using Trace): ScopedQuery[Seq[R]]       = build.query[R]
  inline def queryOne[R <: Product: Table](using Trace): ScopedQuery[Option[R]] = build.queryOne[R]

end JoinSpec5

// ============================================================================
// JoinSpec Companion - Entry Point
// ============================================================================

object JoinSpec:
  /** Create a JoinSpec starting with a single table.
    *
    * The table is automatically aliased.
    *
    * Usage:
    * {{{
    *   JoinSpec[User]
    *     .innerJoin[Order].on(_.id).eq(_.userId)
    *     .build
    * }}}
    */
  def apply[A <: Product: Table]: JoinSpec1[A] =
    val alias    = AliasGenerator.next()
    val instance = AliasGenerator.aliasedInstance[A](alias)
    JoinSpec1(instance)
end JoinSpec

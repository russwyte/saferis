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

/** Alias generator for auto-aliasing tables in queries */
private[saferis] object AliasGenerator:
  private val counter = AtomicInteger(0)

  def next(): String = s"t${counter.incrementAndGet()}"

  /** Reset counter (useful for testing) */
  def reset(): Unit = counter.set(0)

  /** Create an aliased Instance from a Table */
  def aliasedInstance[A <: Product](alias: String)(using table: Table[A]): Instance[A] =
    val columns = table.columns.map(_.withTableAlias(Some(alias)))
    Instance[A](table.name, columns, Some(alias), Vector.empty)
end AliasGenerator

// ============================================================================
// QueryBase - Base trait for all query types
// ============================================================================

/** Base trait for all query types (Query1 through Query5).
  *
  * This allows subqueries to be arbitrarily complex - a subquery can be a simple single-table query or a multi-table
  * join.
  */
trait QueryBase:
  /** Build the SQL fragment for this query */
  def build: SqlFragment

/** A query with a known result type.
  *
  * Created by calling `.select(_.column)` on a query. The type parameter `T` tracks the result type for compile-time
  * checking in IN subqueries.
  *
  * Usage:
  * {{{
  *   // Type T is inferred from the selected column
  *   val subquery: SelectQuery[Int] = Query[Order].select(_.userId)
  *
  *   // IN requires matching types - this compiles because _.id is Int
  *   Query[User].where(_.id).in(subquery)
  *
  *   // This would NOT compile: _.name is String, subquery returns Int
  *   // Query[User].where(_.name).in(subquery)
  * }}}
  */
final case class SelectQuery[T](query: QueryBase) extends QueryBase:
  def build: SqlFragment = query.build

// ============================================================================
// Query1Builder - Single table (entry point, not yet ready to execute)
// ============================================================================

/** Derived table source - subquery with user-provided alias */
final case class DerivedSource(subquery: SelectQuery[?], alias: String)

/** Query builder for a single table - the starting point for building queries.
  *
  * This builder does NOT have execution methods (.query, .queryOne, .build). You must call .where(), .limit(),
  * .seekAfter(), or .all to get a Query1Ready which can be executed.
  *
  * Usage:
  * {{{
  *   Query[User]
  *     .where(_.name).eq("Alice")  // Returns Query1Ready
  *     .orderBy(users.name.asc)
  *     .limit(20)
  *     .query[User]
  * }}}
  */
final case class Query1Builder[A <: Product: Table](
    baseInstance: Instance[A],
    sorts: Vector[Sort[?]] = Vector.empty,
    selectColumns: Vector[Column[?]] = Vector.empty,
    derivedSource: Option[DerivedSource] = None,
):
  // === ORDER BY Methods (stay on Builder) ===

  /** Add an ORDER BY clause */
  def orderBy(sort: Sort[?]): Query1Builder[A] =
    copy(sorts = sorts :+ sort)

  /** Add multiple ORDER BY clauses */
  def orderBy(sort: Sort[?], moreSorts: Sort[?]*): Query1Builder[A] =
    copy(sorts = sorts ++ (sort +: moreSorts))

  // === SELECT Methods (for subqueries, stay on Builder) ===

  /** Select a single column (for use in IN subqueries). */
  inline def select[T](inline selector: A => T): SelectQuery[T] =
    val fieldName = Macros.extractFieldName[A, T](selector)
    val column    = baseInstance.fieldNamesToColumns(fieldName)
    // Create a Ready version for the subquery (subqueries don't need safety constraints)
    SelectQuery[T](
      Query1Ready(baseInstance, Vector.empty, sorts, Vector.empty, None, None, Vector(column), derivedSource)
    )

  /** Select multiple columns. */
  def select(columns: Column[?]*): Query1Builder[A] =
    copy(selectColumns = columns.toVector)

  /** Select all columns with a specified result type (for use in derived tables). */
  def selectAll[R <: Product: Table]: SelectQuery[R] =
    SelectQuery[R](
      Query1Ready(baseInstance, Vector.empty, sorts, Vector.empty, None, None, selectColumns, derivedSource)
    )

  // === JOIN Methods (stay on Builder) ===

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

  // === WHERE Methods (transition to Ready) ===

  /** Add a WHERE predicate using SqlFragment - transitions to Ready */
  def where(predicate: SqlFragment): Query1Ready[A] =
    Query1Ready(baseInstance, Vector(predicate), sorts, Vector.empty, None, None, selectColumns, derivedSource)

  /** Start a type-safe WHERE condition - transitions to Ready via WhereBuilder1 */
  inline def where[T](inline selector: A => T): WhereBuilder1[A, T] =
    val fieldName   = Macros.extractFieldName[A, T](selector)
    val columnLabel = baseInstance.fieldNamesToColumns(fieldName).label
    val alias       = baseInstance.alias.getOrElse(baseInstance.tableName)
    WhereBuilder1(this, alias, columnLabel)

  /** EXISTS subquery */
  def whereExists(subquery: QueryBase): Query1Ready[A] =
    val subquerySql = subquery.build
    val existsSql   = s"EXISTS (${subquerySql.sql})"
    val whereFrag   = SqlFragment(existsSql, subquerySql.writes)
    Query1Ready(baseInstance, Vector(whereFrag), sorts, Vector.empty, None, None, selectColumns, derivedSource)

  /** NOT EXISTS subquery */
  def whereNotExists(subquery: QueryBase): Query1Ready[A] =
    val subquerySql  = subquery.build
    val notExistsSql = s"NOT EXISTS (${subquerySql.sql})"
    val whereFrag    = SqlFragment(notExistsSql, subquerySql.writes)
    Query1Ready(baseInstance, Vector(whereFrag), sorts, Vector.empty, None, None, selectColumns, derivedSource)

  // === LIMIT/SEEK Methods (transition to Ready) ===

  /** Set LIMIT - transitions to Ready (bounded query is safe) */
  def limit(n: Int): Query1Ready[A] =
    Query1Ready(baseInstance, Vector.empty, sorts, Vector.empty, Some(n), None, selectColumns, derivedSource)

  /** Add seek-based pagination cursor - transitions to Ready */
  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query1Ready[A] =
    Query1Ready(
      baseInstance,
      Vector.empty,
      sorts,
      Vector(Seek(column, direction, value, sortOrder, nullOrder)),
      None,
      None,
      selectColumns,
      derivedSource,
    )

  /** Convenience for forward seek (>). Gets rows AFTER the cursor value. */
  def seekAfter[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Asc): Query1Ready[A] =
    seek(column, SeekDir.Gt, value, sortOrder)

  /** Convenience for backward seek (<). Gets rows BEFORE the cursor value. */
  def seekBefore[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Desc): Query1Ready[A] =
    seek(column, SeekDir.Lt, value, sortOrder)

  // === ALL Method (explicit opt-in to fetch all rows) ===

  /** Explicitly mark this as fetching all rows.
    *
    * This is required to prevent accidental unbounded queries.
    */
  def all: Query1Ready[A] =
    Query1Ready(baseInstance, Vector.empty, sorts, Vector.empty, None, None, selectColumns, derivedSource)

end Query1Builder

// ============================================================================
// Query1Ready - Single table query ready to execute
// ============================================================================

/** Query for a single table that is ready to execute.
  *
  * This type has execution methods (.query, .queryOne, .build) because it has been constrained by WHERE, LIMIT,
  * pagination, or explicit .all.
  */
final case class Query1Ready[A <: Product: Table](
    baseInstance: Instance[A],
    wherePredicates: Vector[SqlFragment],
    sorts: Vector[Sort[?]],
    seeks: Vector[Seek[?]],
    limitValue: Option[Int],
    offsetValue: Option[Long],
    selectColumns: Vector[Column[?]],
    derivedSource: Option[DerivedSource],
) extends QueryBase:
  // === WHERE Methods (chain on Ready) ===

  /** Add a WHERE predicate using SqlFragment */
  def where(predicate: SqlFragment): Query1Ready[A] =
    copy(wherePredicates = wherePredicates :+ predicate)

  /** Start a type-safe WHERE condition */
  inline def where[T](inline selector: A => T): WhereBuilder1Ready[A, T] =
    val fieldName   = Macros.extractFieldName[A, T](selector)
    val columnLabel = baseInstance.fieldNamesToColumns(fieldName).label
    val alias       = baseInstance.alias.getOrElse(baseInstance.tableName)
    WhereBuilder1Ready(this, alias, columnLabel)

  /** EXISTS subquery */
  def whereExists(subquery: QueryBase): Query1Ready[A] =
    val subquerySql = subquery.build
    val existsSql   = s"EXISTS (${subquerySql.sql})"
    val whereFrag   = SqlFragment(existsSql, subquerySql.writes)
    copy(wherePredicates = wherePredicates :+ whereFrag)

  /** NOT EXISTS subquery */
  def whereNotExists(subquery: QueryBase): Query1Ready[A] =
    val subquerySql  = subquery.build
    val notExistsSql = s"NOT EXISTS (${subquerySql.sql})"
    val whereFrag    = SqlFragment(notExistsSql, subquerySql.writes)
    copy(wherePredicates = wherePredicates :+ whereFrag)

  // === ORDER BY Methods ===

  /** Add an ORDER BY clause */
  def orderBy(sort: Sort[?]): Query1Ready[A] =
    copy(sorts = sorts :+ sort)

  /** Add multiple ORDER BY clauses */
  def orderBy(sort: Sort[?], moreSorts: Sort[?]*): Query1Ready[A] =
    copy(sorts = sorts ++ (sort +: moreSorts))

  // === PAGINATION Methods ===

  /** Set LIMIT */
  def limit(n: Int): Query1Ready[A] = copy(limitValue = Some(n))

  /** Set OFFSET (safe because already Ready) */
  def offset(n: Long): Query1Ready[A] = copy(offsetValue = Some(n))

  // === SEEK Methods ===

  /** Add seek-based pagination cursor */
  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query1Ready[A] =
    copy(seeks = seeks :+ Seek(column, direction, value, sortOrder, nullOrder))

  /** Add a pre-built Seek specification */
  def seek(s: Seek[?]): Query1Ready[A] = copy(seeks = seeks :+ s)

  /** Convenience for forward seek (>). Gets rows AFTER the cursor value. */
  def seekAfter[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Asc): Query1Ready[A] =
    seek(column, SeekDir.Gt, value, sortOrder)

  /** Convenience for backward seek (<). Gets rows BEFORE the cursor value. */
  def seekBefore[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Desc): Query1Ready[A] =
    seek(column, SeekDir.Lt, value, sortOrder)

  // === SELECT Methods (for subqueries) ===

  /** Select a single column (for use in IN subqueries). */
  inline def select[T](inline selector: A => T): SelectQuery[T] =
    val fieldName = Macros.extractFieldName[A, T](selector)
    val column    = baseInstance.fieldNamesToColumns(fieldName)
    SelectQuery[T](copy(selectColumns = Vector(column)))

  /** Select multiple columns. */
  def select(columns: Column[?]*): Query1Ready[A] =
    copy(selectColumns = columns.toVector)

  /** Select all columns with a specified result type (for use in derived tables). */
  def selectAll[R <: Product: Table]: SelectQuery[R] =
    SelectQuery[R](this)

  // === BUILD Methods ===

  /** Build the SQL fragment for this query */
  def build: SqlFragment =
    val selectClause = if selectColumns.isEmpty then "*" else selectColumns.map(_.label).mkString(", ")

    // For derived tables, use (subquery) as alias; otherwise use table as alias
    val (fromSql, fromWrites) = derivedSource match
      case Some(derived) =>
        val subSql = derived.subquery.build
        (s"(${subSql.sql}) as ${derived.alias}", subSql.writes)
      case None =>
        (baseInstance.sql, Seq.empty)

    var result = SqlFragment(s"select $selectClause from $fromSql", fromWrites)

    // WHERE clause (user predicates + seek predicates)
    val seekPredicates = seeks.map(_.toWherePredicate)
    val allPredicates  = (wherePredicates ++ seekPredicates).filter(_.sql.trim.nonEmpty)
    if allPredicates.nonEmpty then
      val joined = Placeholder.join(allPredicates, " and ")
      result = result :+ SqlFragment(" where ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    // ORDER BY clause (explicit sorts + seek sorts)
    val seekSorts = seeks.map(_.toSort)
    val allSorts  = sorts ++ seekSorts
    if allSorts.nonEmpty then
      val sortFragments = allSorts.map(_.toSqlFragment)
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

end Query1Ready

// ============================================================================
// WhereBuilder1 - Type-safe WHERE condition builder (Builder -> Ready)
// ============================================================================

/** Builder for type-safe WHERE conditions on a single table.
  *
  * Returns Query1Ready since adding a WHERE clause makes the query safe.
  */
final case class WhereBuilder1[A <: Product: Table, T](
    builder: Query1Builder[A],
    fromAlias: String,
    fromColumn: String,
) extends WhereBuilderOps[Query1Ready[A], T]:
  protected def whereAlias: String                                   = fromAlias
  protected def whereColumn: String                                  = fromColumn
  protected def addPredicate(predicate: SqlFragment): Query1Ready[A] =
    Query1Ready(
      builder.baseInstance,
      Vector(predicate),
      builder.sorts,
      Vector.empty,
      None,
      None,
      builder.selectColumns,
      builder.derivedSource,
    )

end WhereBuilder1

// ============================================================================
// WhereBuilder1Ready - Type-safe WHERE for chaining on Ready
// ============================================================================

/** Builder for chaining additional WHERE conditions on Query1Ready. */
final case class WhereBuilder1Ready[A <: Product: Table, T](
    ready: Query1Ready[A],
    fromAlias: String,
    fromColumn: String,
) extends WhereBuilderOps[Query1Ready[A], T]:
  protected def whereAlias: String                                   = fromAlias
  protected def whereColumn: String                                  = fromColumn
  protected def addPredicate(predicate: SqlFragment): Query1Ready[A] =
    ready.copy(wherePredicates = ready.wherePredicates :+ predicate)

end WhereBuilder1Ready

// ============================================================================
// JoinBuilder1 - Building ON clause for first join
// ============================================================================

final case class JoinBuilder1[A <: Product: Table, B <: Product: Table](
    query: Query1Builder[A],
    joinType: JoinType,
):
  /** Start a type-safe ON condition by selecting a column from the left table (A). */
  inline def on[T](inline selector: A => T): OnBuilder1[A, B, T] =
    val alias          = AliasGenerator.next()
    val bInstance      = AliasGenerator.aliasedInstance[B](alias)
    val leftFieldName  = Macros.extractFieldName[A, T](selector)
    val leftColumnName = query.baseInstance.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = query.baseInstance.alias.getOrElse(query.baseInstance.tableName)
    OnBuilder1(query, joinType, leftAlias, leftColumnName, alias, bInstance)
end JoinBuilder1

// ============================================================================
// OnBuilder1 - Building ON condition for first join
// ============================================================================

/** Builder for the first ON condition - awaiting the right-hand side. */
final case class OnBuilder1[A <: Product: Table, B <: Product: Table, T](
    query: Query1Builder[A],
    joinType: JoinType,
    leftAlias: String,
    leftColumn: String,
    rightAlias: String,
    rightInstance: Instance[B],
):
  private def complete(operator: Operator, rightColumnLabel: String): OnChain1[A, B] =
    val condition = BinaryCondition(leftAlias, leftColumn, operator, rightAlias, rightColumnLabel)
    OnChain1(query, joinType, leftAlias, rightAlias, rightInstance, Vector(condition))

  private inline def getRightColumnLabel[T2](inline selector: B => T2): String =
    val fieldName = Macros.extractFieldName[B, T2](selector)
    rightInstance.fieldNamesToColumns(fieldName).label

  /** Complete with equality (=) */
  inline def eq(inline selector: B => T): OnChain1[A, B] =
    complete(Operator.Eq, getRightColumnLabel(selector))

  /** Complete with not equal (<>) */
  inline def neq(inline selector: B => T): OnChain1[A, B] =
    complete(Operator.Neq, getRightColumnLabel(selector))

  /** Complete with less than (<) */
  inline def lt(inline selector: B => T): OnChain1[A, B] =
    complete(Operator.Lt, getRightColumnLabel(selector))

  /** Complete with less than or equal (<=) */
  inline def lte(inline selector: B => T): OnChain1[A, B] =
    complete(Operator.Lte, getRightColumnLabel(selector))

  /** Complete with greater than (>) */
  inline def gt(inline selector: B => T): OnChain1[A, B] =
    complete(Operator.Gt, getRightColumnLabel(selector))

  /** Complete with greater than or equal (>=) */
  inline def gte(inline selector: B => T): OnChain1[A, B] =
    complete(Operator.Gte, getRightColumnLabel(selector))

  /** Complete with custom operator */
  inline def op(operator: Operator)(inline selector: B => T): OnChain1[A, B] =
    complete(operator, getRightColumnLabel(selector))

  /** Complete with IS NULL (unary) */
  def isNull(): OnChain1[A, B] =
    val condition = UnaryCondition(leftAlias, leftColumn, Operator.IsNull)
    OnChain1(query, joinType, leftAlias, rightAlias, rightInstance, Vector(condition))

  /** Complete with IS NOT NULL (unary) */
  def isNotNull(): OnChain1[A, B] =
    val condition = UnaryCondition(leftAlias, leftColumn, Operator.IsNotNull)
    OnChain1(query, joinType, leftAlias, rightAlias, rightInstance, Vector(condition))

end OnBuilder1

// ============================================================================
// OnChain1 - Chaining ON conditions and finalizing to Query2Builder
// ============================================================================

/** Chain state after first ON condition - allows .and() or finalization. */
final case class OnChain1[A <: Product: Table, B <: Product: Table](
    query: Query1Builder[A],
    joinType: JoinType,
    leftAlias: String,
    rightAlias: String,
    rightInstance: Instance[B],
    conditions: Vector[Condition],
):
  /** Add another ON condition from the left table (A) */
  inline def and[T2](inline selector: A => T2): OnAndBuilder1[A, B, T2, A] =
    val fieldName   = Macros.extractFieldName[A, T2](selector)
    val columnLabel = query.baseInstance.fieldNamesToColumns(fieldName).label
    OnAndBuilder1(this, leftAlias, columnLabel)

  /** Add another ON condition from the right table (B) */
  inline def andRight[T2](inline selector: B => T2): OnAndBuilder1[A, B, T2, B] =
    val fieldName   = Macros.extractFieldName[B, T2](selector)
    val columnLabel = rightInstance.fieldNamesToColumns(fieldName).label
    OnAndBuilder1(this, rightAlias, columnLabel)

  /** Finalize the JOIN and create Query2Builder */
  def endJoin(using Dialect): Query2Builder[A, B] =
    val bTable        = summon[Table[B]]
    val conditionFrag = Condition.toSqlFragment(conditions)
    Query2Builder(
      query.baseInstance,
      rightInstance,
      Vector(JoinClause(bTable.name, rightAlias, joinType, conditionFrag)),
      query.sorts,
      query.derivedSource,
    )
  end endJoin

  // Convenience methods that implicitly finalize

  def orderBy(sort: Sort[?])(using Dialect): Query2Builder[A, B]      = endJoin.orderBy(sort)
  def limit(n: Int)(using Dialect): Query2Ready[A, B]                 = endJoin.limit(n)
  def offset(n: Long)(using Dialect): Query2Builder[A, B]             = endJoin.offset(n)
  def where(predicate: SqlFragment)(using Dialect): Query2Ready[A, B] = endJoin.where(predicate)
  def all(using Dialect): Query2Ready[A, B]                           = endJoin.all

  inline def where[T](inline selector: A => T)(using Dialect): WhereBuilder2[A, B, T] =
    val alias       = query.baseInstance.alias.getOrElse(query.baseInstance.tableName)
    val fieldName   = Macros.extractFieldName[A, T](selector)
    val columnLabel = query.baseInstance.fieldNamesToColumns(fieldName).label
    WhereBuilder2(endJoin, alias, columnLabel)

  inline def whereFrom[T](inline selector: B => T)(using Dialect): WhereBuilder2[A, B, T] =
    val alias       = rightInstance.alias.getOrElse(rightInstance.tableName)
    val fieldName   = Macros.extractFieldName[B, T](selector)
    val columnLabel = rightInstance.fieldNamesToColumns(fieldName).label
    WhereBuilder2(endJoin, alias, columnLabel)

  def innerJoin[C <: Product: Table](using Dialect): JoinBuilder2[A, B, C] = endJoin.innerJoin[C]
  def leftJoin[C <: Product: Table](using Dialect): JoinBuilder2[A, B, C]  = endJoin.leftJoin[C]
  def rightJoin[C <: Product: Table](using Dialect): JoinBuilder2[A, B, C] = endJoin.rightJoin[C]
  def fullJoin[C <: Product: Table](using Dialect): JoinBuilder2[A, B, C]  = endJoin.fullJoin[C]

end OnChain1

// ============================================================================
// OnAndBuilder1 - Building chained ON condition
// ============================================================================

final case class OnAndBuilder1[A <: Product: Table, B <: Product: Table, T, From <: Product](
    chain: OnChain1[A, B],
    fromAlias: String,
    fromColumn: String,
):
  private def completeWith(operator: Operator, toAlias: String, toColumnLabel: String): OnChain1[A, B] =
    val condition = BinaryCondition(fromAlias, fromColumn, operator, toAlias, toColumnLabel)
    chain.copy(conditions = chain.conditions :+ condition)

  private inline def getRightColumnLabel[T2](inline selector: B => T2): String =
    val fieldName = Macros.extractFieldName[B, T2](selector)
    chain.rightInstance.fieldNamesToColumns(fieldName).label

  private inline def getLeftColumnLabel[T2](inline selector: A => T2): String =
    val fieldName = Macros.extractFieldName[A, T2](selector)
    chain.query.baseInstance.fieldNamesToColumns(fieldName).label

  /** Complete comparing to right table */
  inline def eq(inline selector: B => T): OnChain1[A, B] =
    completeWith(Operator.Eq, chain.rightAlias, getRightColumnLabel(selector))

  inline def neq(inline selector: B => T): OnChain1[A, B] =
    completeWith(Operator.Neq, chain.rightAlias, getRightColumnLabel(selector))

  inline def lt(inline selector: B => T): OnChain1[A, B] =
    completeWith(Operator.Lt, chain.rightAlias, getRightColumnLabel(selector))

  inline def lte(inline selector: B => T): OnChain1[A, B] =
    completeWith(Operator.Lte, chain.rightAlias, getRightColumnLabel(selector))

  inline def gt(inline selector: B => T): OnChain1[A, B] =
    completeWith(Operator.Gt, chain.rightAlias, getRightColumnLabel(selector))

  inline def gte(inline selector: B => T): OnChain1[A, B] =
    completeWith(Operator.Gte, chain.rightAlias, getRightColumnLabel(selector))

  /** Complete comparing to left table */
  inline def eqLeft(inline selector: A => T): OnChain1[A, B] =
    completeWith(Operator.Eq, chain.leftAlias, getLeftColumnLabel(selector))

  def isNull(): OnChain1[A, B] =
    val condition = UnaryCondition(fromAlias, fromColumn, Operator.IsNull)
    chain.copy(conditions = chain.conditions :+ condition)

  def isNotNull(): OnChain1[A, B] =
    val condition = UnaryCondition(fromAlias, fromColumn, Operator.IsNotNull)
    chain.copy(conditions = chain.conditions :+ condition)

end OnAndBuilder1

// ============================================================================
// Query2Builder - Two tables joined (not yet ready to execute)
// ============================================================================

final case class Query2Builder[A <: Product: Table, B <: Product: Table](
    t1: Instance[A],
    t2: Instance[B],
    joins: Vector[JoinClause],
    sorts: Vector[Sort[?]] = Vector.empty,
    derivedSource: Option[DerivedSource] = None,
):
  // === ORDER BY Methods ===

  def orderBy(sort: Sort[?]): Query2Builder[A, B] =
    copy(sorts = sorts :+ sort)

  def orderBy(sort: Sort[?], moreSorts: Sort[?]*): Query2Builder[A, B] =
    copy(sorts = sorts ++ (sort +: moreSorts))

  // === OFFSET (stays on Builder - still needs constraint) ===

  def offset(n: Long): Query2Builder[A, B] = copy() // Offset alone doesn't make safe

  // === JOIN Methods ===

  def innerJoin[C <: Product: Table]: JoinBuilder2[A, B, C] = JoinBuilder2(this, JoinType.Inner)
  def leftJoin[C <: Product: Table]: JoinBuilder2[A, B, C]  = JoinBuilder2(this, JoinType.Left)
  def rightJoin[C <: Product: Table]: JoinBuilder2[A, B, C] = JoinBuilder2(this, JoinType.Right)
  def fullJoin[C <: Product: Table]: JoinBuilder2[A, B, C]  = JoinBuilder2(this, JoinType.Full)

  // === WHERE Methods (transition to Ready) ===

  def where(predicate: SqlFragment): Query2Ready[A, B] =
    Query2Ready(t1, t2, joins, Vector(predicate), sorts, Vector.empty, None, None, derivedSource)

  inline def where[T](inline selector: A => T): WhereBuilder2[A, B, T] =
    val alias       = t1.alias.getOrElse(t1.tableName)
    val fieldName   = Macros.extractFieldName[A, T](selector)
    val columnLabel = t1.fieldNamesToColumns(fieldName).label
    WhereBuilder2(this, alias, columnLabel)

  inline def whereFrom[T](inline selector: B => T): WhereBuilder2[A, B, T] =
    val alias       = t2.alias.getOrElse(t2.tableName)
    val fieldName   = Macros.extractFieldName[B, T](selector)
    val columnLabel = t2.fieldNamesToColumns(fieldName).label
    WhereBuilder2(this, alias, columnLabel)

  // === LIMIT/SEEK Methods (transition to Ready) ===

  def limit(n: Int): Query2Ready[A, B] =
    Query2Ready(t1, t2, joins, Vector.empty, sorts, Vector.empty, Some(n), None, derivedSource)

  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query2Ready[A, B] =
    Query2Ready(
      t1,
      t2,
      joins,
      Vector.empty,
      sorts,
      Vector(Seek(column, direction, value, sortOrder, nullOrder)),
      None,
      None,
      derivedSource,
    )

  def seekAfter[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Asc): Query2Ready[A, B] =
    seek(column, SeekDir.Gt, value, sortOrder)

  def seekBefore[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Desc): Query2Ready[A, B] =
    seek(column, SeekDir.Lt, value, sortOrder)

  // === ALL Method ===

  def all: Query2Ready[A, B] =
    Query2Ready(t1, t2, joins, Vector.empty, sorts, Vector.empty, None, None, derivedSource)

end Query2Builder

// ============================================================================
// Query2Ready - Two tables joined, ready to execute
// ============================================================================

final case class Query2Ready[A <: Product: Table, B <: Product: Table](
    t1: Instance[A],
    t2: Instance[B],
    joins: Vector[JoinClause],
    wherePredicates: Vector[SqlFragment],
    sorts: Vector[Sort[?]],
    seeks: Vector[Seek[?]],
    limitValue: Option[Int],
    offsetValue: Option[Long],
    derivedSource: Option[DerivedSource],
) extends QueryBase:
  // === WHERE Methods ===

  def where(predicate: SqlFragment): Query2Ready[A, B] =
    copy(wherePredicates = wherePredicates :+ predicate)

  inline def where[T](inline selector: A => T): WhereBuilder2Ready[A, B, T] =
    val alias       = t1.alias.getOrElse(t1.tableName)
    val fieldName   = Macros.extractFieldName[A, T](selector)
    val columnLabel = t1.fieldNamesToColumns(fieldName).label
    WhereBuilder2Ready(this, alias, columnLabel)

  inline def whereFrom[T](inline selector: B => T): WhereBuilder2Ready[A, B, T] =
    val alias       = t2.alias.getOrElse(t2.tableName)
    val fieldName   = Macros.extractFieldName[B, T](selector)
    val columnLabel = t2.fieldNamesToColumns(fieldName).label
    WhereBuilder2Ready(this, alias, columnLabel)

  // === ORDER BY Methods ===

  def orderBy(sort: Sort[?]): Query2Ready[A, B] =
    copy(sorts = sorts :+ sort)

  def orderBy(sort: Sort[?], moreSorts: Sort[?]*): Query2Ready[A, B] =
    copy(sorts = sorts ++ (sort +: moreSorts))

  // === PAGINATION Methods ===

  def limit(n: Int): Query2Ready[A, B]   = copy(limitValue = Some(n))
  def offset(n: Long): Query2Ready[A, B] = copy(offsetValue = Some(n))

  // === SEEK Methods ===

  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query2Ready[A, B] =
    copy(seeks = seeks :+ Seek(column, direction, value, sortOrder, nullOrder))

  def seek(s: Seek[?]): Query2Ready[A, B] = copy(seeks = seeks :+ s)

  def seekAfter[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Asc): Query2Ready[A, B] =
    seek(column, SeekDir.Gt, value, sortOrder)

  def seekBefore[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Desc): Query2Ready[A, B] =
    seek(column, SeekDir.Lt, value, sortOrder)

  // === BUILD Methods ===

  def build: SqlFragment =
    val alias = t1.alias.getOrElse("derived")

    // For derived tables, use (subquery) as alias; otherwise use table as alias
    val (fromSql, fromWrites) = derivedSource match
      case Some(derived) =>
        val subSql = derived.subquery.build
        (s"(${subSql.sql}) as ${derived.alias}", subSql.writes)
      case None =>
        (t1.sql, Seq.empty)

    var result = SqlFragment(s"select * from $fromSql", fromWrites)

    // Add joins
    for join <- joins do
      val joinSql = s" ${join.joinType.toSql} ${join.tableName} as ${join.alias}"
      result = result :+ SqlFragment(joinSql, Seq.empty)
      if join.condition.sql.nonEmpty then result = result :+ SqlFragment(" on ", Seq.empty) :+ join.condition

    // WHERE clause
    val seekPredicates = seeks.map(_.toWherePredicate)
    val allPredicates  = (wherePredicates ++ seekPredicates).filter(_.sql.trim.nonEmpty)
    if allPredicates.nonEmpty then
      val joined = Placeholder.join(allPredicates, " and ")
      result = result :+ SqlFragment(" where ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    // ORDER BY clause
    val seekSorts = seeks.map(_.toSort)
    val allSorts  = sorts ++ seekSorts
    if allSorts.nonEmpty then
      val sortFragments = allSorts.map(_.toSqlFragment)
      val joined        = Placeholder.join(sortFragments, ", ")
      result = result :+ SqlFragment(" order by ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    // LIMIT/OFFSET
    limitValue.foreach(n => result = result :+ SqlFragment(s" limit $n", Seq.empty))
    offsetValue.foreach(n => result = result :+ SqlFragment(s" offset $n", Seq.empty))

    result
  end build

  inline def query[R <: Product: Table](using Trace): ScopedQuery[Seq[R]]       = build.query[R]
  inline def queryOne[R <: Product: Table](using Trace): ScopedQuery[Option[R]] = build.queryOne[R]

end Query2Ready

// ============================================================================
// WhereBuilder2 - Type-safe WHERE for two-table queries (Builder -> Ready)
// ============================================================================

final case class WhereBuilder2[A <: Product: Table, B <: Product: Table, T](
    builder: Query2Builder[A, B],
    fromAlias: String,
    fromColumn: String,
) extends WhereBuilderOps[Query2Ready[A, B], T]:
  protected def whereAlias: String                                      = fromAlias
  protected def whereColumn: String                                     = fromColumn
  protected def addPredicate(predicate: SqlFragment): Query2Ready[A, B] =
    Query2Ready(
      builder.t1,
      builder.t2,
      builder.joins,
      Vector(predicate),
      builder.sorts,
      Vector.empty,
      None,
      None,
      builder.derivedSource,
    )

  // Column comparisons (specific to join queries)
  private def completeColumn(operator: Operator, toAlias: String, toColumn: String): Query2Ready[A, B] =
    val condition = BinaryCondition(fromAlias, fromColumn, operator, toAlias, toColumn)
    val whereFrag = Condition.toSqlFragment(Vector(condition))(using saferis.postgres.PostgresDialect)
    addPredicate(whereFrag)

  private inline def getColumnLabel[X <: Product, T2](instance: Instance[X])(inline selector: X => T2): String =
    val fieldName = Macros.extractFieldName[X, T2](selector)
    instance.fieldNamesToColumns(fieldName).label

  inline def eqCol(inline selector: B => T): Query2Ready[A, B] =
    val toAlias = builder.t2.alias.getOrElse(builder.t2.tableName)
    val toCol   = getColumnLabel(builder.t2)(selector)
    completeColumn(Operator.Eq, toAlias, toCol)

  inline def neqCol(inline selector: B => T): Query2Ready[A, B] =
    val toAlias = builder.t2.alias.getOrElse(builder.t2.tableName)
    val toCol   = getColumnLabel(builder.t2)(selector)
    completeColumn(Operator.Neq, toAlias, toCol)

end WhereBuilder2

// ============================================================================
// WhereBuilder2Ready - Type-safe WHERE for chaining on Ready
// ============================================================================

final case class WhereBuilder2Ready[A <: Product: Table, B <: Product: Table, T](
    ready: Query2Ready[A, B],
    fromAlias: String,
    fromColumn: String,
) extends WhereBuilderOps[Query2Ready[A, B], T]:
  protected def whereAlias: String                                      = fromAlias
  protected def whereColumn: String                                     = fromColumn
  protected def addPredicate(predicate: SqlFragment): Query2Ready[A, B] =
    ready.copy(wherePredicates = ready.wherePredicates :+ predicate)

  // Column comparisons
  private def completeColumn(operator: Operator, toAlias: String, toColumn: String): Query2Ready[A, B] =
    val condition = BinaryCondition(fromAlias, fromColumn, operator, toAlias, toColumn)
    val whereFrag = Condition.toSqlFragment(Vector(condition))(using saferis.postgres.PostgresDialect)
    addPredicate(whereFrag)

  private inline def getColumnLabel[X <: Product, T2](instance: Instance[X])(inline selector: X => T2): String =
    val fieldName = Macros.extractFieldName[X, T2](selector)
    instance.fieldNamesToColumns(fieldName).label

  inline def eqCol(inline selector: B => T): Query2Ready[A, B] =
    val toAlias = ready.t2.alias.getOrElse(ready.t2.tableName)
    val toCol   = getColumnLabel(ready.t2)(selector)
    completeColumn(Operator.Eq, toAlias, toCol)

  inline def neqCol(inline selector: B => T): Query2Ready[A, B] =
    val toAlias = ready.t2.alias.getOrElse(ready.t2.tableName)
    val toCol   = getColumnLabel(ready.t2)(selector)
    completeColumn(Operator.Neq, toAlias, toCol)

end WhereBuilder2Ready

// ============================================================================
// JoinBuilder2 - Building ON clause for second join
// ============================================================================

final case class JoinBuilder2[A <: Product: Table, B <: Product: Table, C <: Product: Table](
    query: Query2Builder[A, B],
    joinType: JoinType,
):
  /** ON condition from first table (A) */
  inline def on[T](inline selector: A => T): OnBuilder2[A, B, C, T, A] =
    val alias          = AliasGenerator.next()
    val cInstance      = AliasGenerator.aliasedInstance[C](alias)
    val leftFieldName  = Macros.extractFieldName[A, T](selector)
    val leftColumnName = query.t1.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = query.t1.alias.getOrElse(query.t1.tableName)
    OnBuilder2(query, joinType, leftAlias, leftColumnName, alias, cInstance)

  /** ON condition from second table (B) - the "previous" table */
  inline def onPrev[T](inline selector: B => T): OnBuilder2[A, B, C, T, B] =
    val alias          = AliasGenerator.next()
    val cInstance      = AliasGenerator.aliasedInstance[C](alias)
    val leftFieldName  = Macros.extractFieldName[B, T](selector)
    val leftColumnName = query.t2.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = query.t2.alias.getOrElse(query.t2.tableName)
    OnBuilder2(query, joinType, leftAlias, leftColumnName, alias, cInstance)

end JoinBuilder2

// ============================================================================
// OnBuilder2 - Building ON condition for second join
// ============================================================================

final case class OnBuilder2[A <: Product: Table, B <: Product: Table, C <: Product: Table, T, From <: Product](
    query: Query2Builder[A, B],
    joinType: JoinType,
    leftAlias: String,
    leftColumn: String,
    rightAlias: String,
    rightInstance: Instance[C],
):
  private def complete(operator: Operator, rightColumnLabel: String): OnChain2[A, B, C] =
    val condition = BinaryCondition(leftAlias, leftColumn, operator, rightAlias, rightColumnLabel)
    OnChain2(query, joinType, rightAlias, rightInstance, Vector(condition))

  private inline def getRightColumnLabel[T2](inline selector: C => T2): String =
    val fieldName = Macros.extractFieldName[C, T2](selector)
    rightInstance.fieldNamesToColumns(fieldName).label

  inline def eq(inline selector: C => T): OnChain2[A, B, C]  = complete(Operator.Eq, getRightColumnLabel(selector))
  inline def neq(inline selector: C => T): OnChain2[A, B, C] = complete(Operator.Neq, getRightColumnLabel(selector))
  inline def lt(inline selector: C => T): OnChain2[A, B, C]  = complete(Operator.Lt, getRightColumnLabel(selector))
  inline def lte(inline selector: C => T): OnChain2[A, B, C] = complete(Operator.Lte, getRightColumnLabel(selector))
  inline def gt(inline selector: C => T): OnChain2[A, B, C]  = complete(Operator.Gt, getRightColumnLabel(selector))
  inline def gte(inline selector: C => T): OnChain2[A, B, C] = complete(Operator.Gte, getRightColumnLabel(selector))
  inline def op(operator: Operator)(inline selector: C => T): OnChain2[A, B, C] =
    complete(operator, getRightColumnLabel(selector))

  def isNull(): OnChain2[A, B, C] =
    val condition = UnaryCondition(leftAlias, leftColumn, Operator.IsNull)
    OnChain2(query, joinType, rightAlias, rightInstance, Vector(condition))

  def isNotNull(): OnChain2[A, B, C] =
    val condition = UnaryCondition(leftAlias, leftColumn, Operator.IsNotNull)
    OnChain2(query, joinType, rightAlias, rightInstance, Vector(condition))

end OnBuilder2

// ============================================================================
// OnChain2 - Chaining ON conditions for second join
// ============================================================================

final case class OnChain2[A <: Product: Table, B <: Product: Table, C <: Product: Table](
    query: Query2Builder[A, B],
    joinType: JoinType,
    rightAlias: String,
    rightInstance: Instance[C],
    conditions: Vector[Condition],
):
  def endJoin(using Dialect): Query3Builder[A, B, C] =
    val cTable        = summon[Table[C]]
    val conditionFrag = Condition.toSqlFragment(conditions)
    Query3Builder(
      query.t1,
      query.t2,
      rightInstance,
      query.joins :+ JoinClause(cTable.name, rightAlias, joinType, conditionFrag),
      query.sorts,
    )
  end endJoin

  def orderBy(sort: Sort[?])(using Dialect): Query3Builder[A, B, C]      = endJoin.orderBy(sort)
  def limit(n: Int)(using Dialect): Query3Ready[A, B, C]                 = endJoin.limit(n)
  def offset(n: Long)(using Dialect): Query3Builder[A, B, C]             = endJoin.offset(n)
  def where(predicate: SqlFragment)(using Dialect): Query3Ready[A, B, C] = endJoin.where(predicate)
  def all(using Dialect): Query3Ready[A, B, C]                           = endJoin.all

end OnChain2

// ============================================================================
// Query3Builder - Three tables joined (not yet ready to execute)
// ============================================================================

final case class Query3Builder[A <: Product: Table, B <: Product: Table, C <: Product: Table](
    t1: Instance[A],
    t2: Instance[B],
    t3: Instance[C],
    joins: Vector[JoinClause],
    sorts: Vector[Sort[?]] = Vector.empty,
):
  def where(predicate: SqlFragment): Query3Ready[A, B, C] =
    Query3Ready(t1, t2, t3, joins, Vector(predicate), sorts, Vector.empty, None, None)
  def orderBy(sort: Sort[?]): Query3Builder[A, B, C] = copy(sorts = sorts :+ sort)
  def offset(n: Long): Query3Builder[A, B, C]        = copy() // Offset alone doesn't make safe

  def limit(n: Int): Query3Ready[A, B, C] =
    Query3Ready(t1, t2, t3, joins, Vector.empty, sorts, Vector.empty, Some(n), None)

  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query3Ready[A, B, C] =
    Query3Ready(
      t1,
      t2,
      t3,
      joins,
      Vector.empty,
      sorts,
      Vector(Seek(column, direction, value, sortOrder, nullOrder)),
      None,
      None,
    )

  def seekAfter[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Asc): Query3Ready[A, B, C] =
    seek(column, SeekDir.Gt, value, sortOrder)

  def all: Query3Ready[A, B, C] =
    Query3Ready(t1, t2, t3, joins, Vector.empty, sorts, Vector.empty, None, None)

  // JOIN Methods
  def innerJoin[D <: Product: Table]: JoinBuilder3[A, B, C, D] = JoinBuilder3(this, JoinType.Inner)
  def leftJoin[D <: Product: Table]: JoinBuilder3[A, B, C, D]  = JoinBuilder3(this, JoinType.Left)
  def rightJoin[D <: Product: Table]: JoinBuilder3[A, B, C, D] = JoinBuilder3(this, JoinType.Right)
  def fullJoin[D <: Product: Table]: JoinBuilder3[A, B, C, D]  = JoinBuilder3(this, JoinType.Full)

end Query3Builder

// ============================================================================
// Query3Ready - Three tables joined, ready to execute
// ============================================================================

final case class Query3Ready[A <: Product: Table, B <: Product: Table, C <: Product: Table](
    t1: Instance[A],
    t2: Instance[B],
    t3: Instance[C],
    joins: Vector[JoinClause],
    wherePredicates: Vector[SqlFragment],
    sorts: Vector[Sort[?]],
    seeks: Vector[Seek[?]],
    limitValue: Option[Int],
    offsetValue: Option[Long],
) extends QueryBase:
  def where(predicate: SqlFragment): Query3Ready[A, B, C] = copy(wherePredicates = wherePredicates :+ predicate)
  def orderBy(sort: Sort[?]): Query3Ready[A, B, C]        = copy(sorts = sorts :+ sort)
  def limit(n: Int): Query3Ready[A, B, C]                 = copy(limitValue = Some(n))
  def offset(n: Long): Query3Ready[A, B, C]               = copy(offsetValue = Some(n))

  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query3Ready[A, B, C] =
    copy(seeks = seeks :+ Seek(column, direction, value, sortOrder, nullOrder))

  def seekAfter[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Asc): Query3Ready[A, B, C] =
    seek(column, SeekDir.Gt, value, sortOrder)

  def build: SqlFragment =
    var result = SqlFragment(s"select * from ${t1.sql}", Seq.empty)

    for join <- joins do
      val joinSql = s" ${join.joinType.toSql} ${join.tableName} as ${join.alias}"
      result = result :+ SqlFragment(joinSql, Seq.empty)
      if join.condition.sql.nonEmpty then result = result :+ SqlFragment(" on ", Seq.empty) :+ join.condition

    val seekPredicates = seeks.map(_.toWherePredicate)
    val allPredicates  = (wherePredicates ++ seekPredicates).filter(_.sql.trim.nonEmpty)
    if allPredicates.nonEmpty then
      val joined = Placeholder.join(allPredicates, " and ")
      result = result :+ SqlFragment(" where ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    val seekSorts = seeks.map(_.toSort)
    val allSorts  = sorts ++ seekSorts
    if allSorts.nonEmpty then
      val sortFragments = allSorts.map(_.toSqlFragment)
      val joined        = Placeholder.join(sortFragments, ", ")
      result = result :+ SqlFragment(" order by ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    limitValue.foreach(n => result = result :+ SqlFragment(s" limit $n", Seq.empty))
    offsetValue.foreach(n => result = result :+ SqlFragment(s" offset $n", Seq.empty))

    result
  end build

  inline def query[R <: Product: Table](using Trace): ScopedQuery[Seq[R]]       = build.query[R]
  inline def queryOne[R <: Product: Table](using Trace): ScopedQuery[Option[R]] = build.queryOne[R]

end Query3Ready

// ============================================================================
// JoinBuilder3 - Building ON clause for third join
// ============================================================================

final case class JoinBuilder3[A <: Product: Table, B <: Product: Table, C <: Product: Table, D <: Product: Table](
    query: Query3Builder[A, B, C],
    joinType: JoinType,
):
  inline def on[T](inline selector: A => T): OnBuilder3[A, B, C, D, T] =
    val alias          = AliasGenerator.next()
    val dInstance      = AliasGenerator.aliasedInstance[D](alias)
    val leftFieldName  = Macros.extractFieldName[A, T](selector)
    val leftColumnName = query.t1.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = query.t1.alias.getOrElse(query.t1.tableName)
    OnBuilder3(query, joinType, leftAlias, leftColumnName, alias, dInstance)

  inline def onPrev[T](inline selector: C => T): OnBuilder3[A, B, C, D, T] =
    val alias          = AliasGenerator.next()
    val dInstance      = AliasGenerator.aliasedInstance[D](alias)
    val leftFieldName  = Macros.extractFieldName[C, T](selector)
    val leftColumnName = query.t3.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = query.t3.alias.getOrElse(query.t3.tableName)
    OnBuilder3(query, joinType, leftAlias, leftColumnName, alias, dInstance)

end JoinBuilder3

final case class OnBuilder3[A <: Product: Table, B <: Product: Table, C <: Product: Table, D <: Product: Table, T](
    query: Query3Builder[A, B, C],
    joinType: JoinType,
    leftAlias: String,
    leftColumn: String,
    rightAlias: String,
    rightInstance: Instance[D],
):
  private def complete(operator: Operator, rightColumnLabel: String): OnChain3[A, B, C, D] =
    val condition = BinaryCondition(leftAlias, leftColumn, operator, rightAlias, rightColumnLabel)
    OnChain3(query, joinType, rightAlias, rightInstance, Vector(condition))

  private inline def getRightColumnLabel[T2](inline selector: D => T2): String =
    val fieldName = Macros.extractFieldName[D, T2](selector)
    rightInstance.fieldNamesToColumns(fieldName).label

  inline def eq(inline selector: D => T): OnChain3[A, B, C, D]  = complete(Operator.Eq, getRightColumnLabel(selector))
  inline def neq(inline selector: D => T): OnChain3[A, B, C, D] = complete(Operator.Neq, getRightColumnLabel(selector))
  inline def lt(inline selector: D => T): OnChain3[A, B, C, D]  = complete(Operator.Lt, getRightColumnLabel(selector))
  inline def lte(inline selector: D => T): OnChain3[A, B, C, D] = complete(Operator.Lte, getRightColumnLabel(selector))
  inline def gt(inline selector: D => T): OnChain3[A, B, C, D]  = complete(Operator.Gt, getRightColumnLabel(selector))
  inline def gte(inline selector: D => T): OnChain3[A, B, C, D] = complete(Operator.Gte, getRightColumnLabel(selector))

end OnBuilder3

final case class OnChain3[A <: Product: Table, B <: Product: Table, C <: Product: Table, D <: Product: Table](
    query: Query3Builder[A, B, C],
    joinType: JoinType,
    rightAlias: String,
    rightInstance: Instance[D],
    conditions: Vector[Condition],
):
  def endJoin(using Dialect): Query4Builder[A, B, C, D] =
    val dTable        = summon[Table[D]]
    val conditionFrag = Condition.toSqlFragment(conditions)
    Query4Builder(
      query.t1,
      query.t2,
      query.t3,
      rightInstance,
      query.joins :+ JoinClause(dTable.name, rightAlias, joinType, conditionFrag),
      query.sorts,
    )
  end endJoin

  def orderBy(sort: Sort[?])(using Dialect): Query4Builder[A, B, C, D]      = endJoin.orderBy(sort)
  def limit(n: Int)(using Dialect): Query4Ready[A, B, C, D]                 = endJoin.limit(n)
  def offset(n: Long)(using Dialect): Query4Builder[A, B, C, D]             = endJoin.offset(n)
  def where(predicate: SqlFragment)(using Dialect): Query4Ready[A, B, C, D] = endJoin.where(predicate)
  def all(using Dialect): Query4Ready[A, B, C, D]                           = endJoin.all

end OnChain3

// ============================================================================
// Query4Builder - Four tables joined (not yet ready to execute)
// ============================================================================

final case class Query4Builder[A <: Product: Table, B <: Product: Table, C <: Product: Table, D <: Product: Table](
    t1: Instance[A],
    t2: Instance[B],
    t3: Instance[C],
    t4: Instance[D],
    joins: Vector[JoinClause],
    sorts: Vector[Sort[?]] = Vector.empty,
):
  def where(predicate: SqlFragment): Query4Ready[A, B, C, D] =
    Query4Ready(t1, t2, t3, t4, joins, Vector(predicate), sorts, Vector.empty, None, None)
  def orderBy(sort: Sort[?]): Query4Builder[A, B, C, D] = copy(sorts = sorts :+ sort)
  def offset(n: Long): Query4Builder[A, B, C, D]        = copy()

  def limit(n: Int): Query4Ready[A, B, C, D] =
    Query4Ready(t1, t2, t3, t4, joins, Vector.empty, sorts, Vector.empty, Some(n), None)

  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query4Ready[A, B, C, D] =
    Query4Ready(
      t1,
      t2,
      t3,
      t4,
      joins,
      Vector.empty,
      sorts,
      Vector(Seek(column, direction, value, sortOrder, nullOrder)),
      None,
      None,
    )

  def seekAfter[T: Encoder](
      column: Column[T],
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
  ): Query4Ready[A, B, C, D] =
    seek(column, SeekDir.Gt, value, sortOrder)

  def all: Query4Ready[A, B, C, D] =
    Query4Ready(t1, t2, t3, t4, joins, Vector.empty, sorts, Vector.empty, None, None)

  def innerJoin[E <: Product: Table]: JoinBuilder4[A, B, C, D, E] = JoinBuilder4(this, JoinType.Inner)
  def leftJoin[E <: Product: Table]: JoinBuilder4[A, B, C, D, E]  = JoinBuilder4(this, JoinType.Left)
  def rightJoin[E <: Product: Table]: JoinBuilder4[A, B, C, D, E] = JoinBuilder4(this, JoinType.Right)
  def fullJoin[E <: Product: Table]: JoinBuilder4[A, B, C, D, E]  = JoinBuilder4(this, JoinType.Full)

end Query4Builder

// ============================================================================
// Query4Ready - Four tables joined, ready to execute
// ============================================================================

final case class Query4Ready[A <: Product: Table, B <: Product: Table, C <: Product: Table, D <: Product: Table](
    t1: Instance[A],
    t2: Instance[B],
    t3: Instance[C],
    t4: Instance[D],
    joins: Vector[JoinClause],
    wherePredicates: Vector[SqlFragment],
    sorts: Vector[Sort[?]],
    seeks: Vector[Seek[?]],
    limitValue: Option[Int],
    offsetValue: Option[Long],
) extends QueryBase:
  def where(predicate: SqlFragment): Query4Ready[A, B, C, D] = copy(wherePredicates = wherePredicates :+ predicate)
  def orderBy(sort: Sort[?]): Query4Ready[A, B, C, D]        = copy(sorts = sorts :+ sort)
  def limit(n: Int): Query4Ready[A, B, C, D]                 = copy(limitValue = Some(n))
  def offset(n: Long): Query4Ready[A, B, C, D]               = copy(offsetValue = Some(n))

  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query4Ready[A, B, C, D] =
    copy(seeks = seeks :+ Seek(column, direction, value, sortOrder, nullOrder))

  def seekAfter[T: Encoder](
      column: Column[T],
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
  ): Query4Ready[A, B, C, D] =
    seek(column, SeekDir.Gt, value, sortOrder)

  def build: SqlFragment =
    var result = SqlFragment(s"select * from ${t1.sql}", Seq.empty)

    for join <- joins do
      val joinSql = s" ${join.joinType.toSql} ${join.tableName} as ${join.alias}"
      result = result :+ SqlFragment(joinSql, Seq.empty)
      if join.condition.sql.nonEmpty then result = result :+ SqlFragment(" on ", Seq.empty) :+ join.condition

    val seekPredicates = seeks.map(_.toWherePredicate)
    val allPredicates  = (wherePredicates ++ seekPredicates).filter(_.sql.trim.nonEmpty)
    if allPredicates.nonEmpty then
      val joined = Placeholder.join(allPredicates, " and ")
      result = result :+ SqlFragment(" where ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    val seekSorts = seeks.map(_.toSort)
    val allSorts  = sorts ++ seekSorts
    if allSorts.nonEmpty then
      val sortFragments = allSorts.map(_.toSqlFragment)
      val joined        = Placeholder.join(sortFragments, ", ")
      result = result :+ SqlFragment(" order by ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    limitValue.foreach(n => result = result :+ SqlFragment(s" limit $n", Seq.empty))
    offsetValue.foreach(n => result = result :+ SqlFragment(s" offset $n", Seq.empty))

    result
  end build

  inline def query[R <: Product: Table](using Trace): ScopedQuery[Seq[R]]       = build.query[R]
  inline def queryOne[R <: Product: Table](using Trace): ScopedQuery[Option[R]] = build.queryOne[R]

end Query4Ready

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
    query: Query4Builder[A, B, C, D],
    joinType: JoinType,
):
  inline def on[T](inline selector: A => T): OnBuilder4[A, B, C, D, E, T] =
    val alias          = AliasGenerator.next()
    val eInstance      = AliasGenerator.aliasedInstance[E](alias)
    val leftFieldName  = Macros.extractFieldName[A, T](selector)
    val leftColumnName = query.t1.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = query.t1.alias.getOrElse(query.t1.tableName)
    OnBuilder4(query, joinType, leftAlias, leftColumnName, alias, eInstance)

  inline def onPrev[T](inline selector: D => T): OnBuilder4[A, B, C, D, E, T] =
    val alias          = AliasGenerator.next()
    val eInstance      = AliasGenerator.aliasedInstance[E](alias)
    val leftFieldName  = Macros.extractFieldName[D, T](selector)
    val leftColumnName = query.t4.fieldNamesToColumns(leftFieldName).label
    val leftAlias      = query.t4.alias.getOrElse(query.t4.tableName)
    OnBuilder4(query, joinType, leftAlias, leftColumnName, alias, eInstance)

end JoinBuilder4

final case class OnBuilder4[
    A <: Product: Table,
    B <: Product: Table,
    C <: Product: Table,
    D <: Product: Table,
    E <: Product: Table,
    T,
](
    query: Query4Builder[A, B, C, D],
    joinType: JoinType,
    leftAlias: String,
    leftColumn: String,
    rightAlias: String,
    rightInstance: Instance[E],
):
  private def complete(operator: Operator, rightColumnLabel: String): OnChain4[A, B, C, D, E] =
    val condition = BinaryCondition(leftAlias, leftColumn, operator, rightAlias, rightColumnLabel)
    OnChain4(query, joinType, rightAlias, rightInstance, Vector(condition))

  private inline def getRightColumnLabel[T2](inline selector: E => T2): String =
    val fieldName = Macros.extractFieldName[E, T2](selector)
    rightInstance.fieldNamesToColumns(fieldName).label

  inline def eq(inline selector: E => T): OnChain4[A, B, C, D, E] = complete(Operator.Eq, getRightColumnLabel(selector))
  inline def neq(inline selector: E => T): OnChain4[A, B, C, D, E] =
    complete(Operator.Neq, getRightColumnLabel(selector))
  inline def lt(inline selector: E => T): OnChain4[A, B, C, D, E] = complete(Operator.Lt, getRightColumnLabel(selector))
  inline def lte(inline selector: E => T): OnChain4[A, B, C, D, E] =
    complete(Operator.Lte, getRightColumnLabel(selector))
  inline def gt(inline selector: E => T): OnChain4[A, B, C, D, E] = complete(Operator.Gt, getRightColumnLabel(selector))
  inline def gte(inline selector: E => T): OnChain4[A, B, C, D, E] =
    complete(Operator.Gte, getRightColumnLabel(selector))

end OnBuilder4

final case class OnChain4[
    A <: Product: Table,
    B <: Product: Table,
    C <: Product: Table,
    D <: Product: Table,
    E <: Product: Table,
](
    query: Query4Builder[A, B, C, D],
    joinType: JoinType,
    rightAlias: String,
    rightInstance: Instance[E],
    conditions: Vector[Condition],
):
  def endJoin(using Dialect): Query5Builder[A, B, C, D, E] =
    val eTable        = summon[Table[E]]
    val conditionFrag = Condition.toSqlFragment(conditions)
    Query5Builder(
      query.t1,
      query.t2,
      query.t3,
      query.t4,
      rightInstance,
      query.joins :+ JoinClause(eTable.name, rightAlias, joinType, conditionFrag),
      query.sorts,
    )
  end endJoin

  def orderBy(sort: Sort[?])(using Dialect): Query5Builder[A, B, C, D, E]      = endJoin.orderBy(sort)
  def limit(n: Int)(using Dialect): Query5Ready[A, B, C, D, E]                 = endJoin.limit(n)
  def offset(n: Long)(using Dialect): Query5Builder[A, B, C, D, E]             = endJoin.offset(n)
  def where(predicate: SqlFragment)(using Dialect): Query5Ready[A, B, C, D, E] = endJoin.where(predicate)
  def all(using Dialect): Query5Ready[A, B, C, D, E]                           = endJoin.all

end OnChain4

// ============================================================================
// Query5Builder - Five tables joined (not yet ready to execute)
// ============================================================================

final case class Query5Builder[
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
    sorts: Vector[Sort[?]] = Vector.empty,
):
  def where(predicate: SqlFragment): Query5Ready[A, B, C, D, E] =
    Query5Ready(t1, t2, t3, t4, t5, joins, Vector(predicate), sorts, Vector.empty, None, None)
  def orderBy(sort: Sort[?]): Query5Builder[A, B, C, D, E] = copy(sorts = sorts :+ sort)
  def offset(n: Long): Query5Builder[A, B, C, D, E]        = copy()

  def limit(n: Int): Query5Ready[A, B, C, D, E] =
    Query5Ready(t1, t2, t3, t4, t5, joins, Vector.empty, sorts, Vector.empty, Some(n), None)

  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query5Ready[A, B, C, D, E] =
    Query5Ready(
      t1,
      t2,
      t3,
      t4,
      t5,
      joins,
      Vector.empty,
      sorts,
      Vector(Seek(column, direction, value, sortOrder, nullOrder)),
      None,
      None,
    )

  def seekAfter[T: Encoder](
      column: Column[T],
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
  ): Query5Ready[A, B, C, D, E] =
    seek(column, SeekDir.Gt, value, sortOrder)

  def all: Query5Ready[A, B, C, D, E] =
    Query5Ready(t1, t2, t3, t4, t5, joins, Vector.empty, sorts, Vector.empty, None, None)

end Query5Builder

// ============================================================================
// Query5Ready - Five tables joined, ready to execute
// ============================================================================

final case class Query5Ready[
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
    wherePredicates: Vector[SqlFragment],
    sorts: Vector[Sort[?]],
    seeks: Vector[Seek[?]],
    limitValue: Option[Int],
    offsetValue: Option[Long],
) extends QueryBase:
  def where(predicate: SqlFragment): Query5Ready[A, B, C, D, E] = copy(wherePredicates = wherePredicates :+ predicate)
  def orderBy(sort: Sort[?]): Query5Ready[A, B, C, D, E]        = copy(sorts = sorts :+ sort)
  def limit(n: Int): Query5Ready[A, B, C, D, E]                 = copy(limitValue = Some(n))
  def offset(n: Long): Query5Ready[A, B, C, D, E]               = copy(offsetValue = Some(n))

  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query5Ready[A, B, C, D, E] =
    copy(seeks = seeks :+ Seek(column, direction, value, sortOrder, nullOrder))

  def seekAfter[T: Encoder](
      column: Column[T],
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
  ): Query5Ready[A, B, C, D, E] =
    seek(column, SeekDir.Gt, value, sortOrder)

  def build: SqlFragment =
    var result = SqlFragment(s"select * from ${t1.sql}", Seq.empty)

    for join <- joins do
      val joinSql = s" ${join.joinType.toSql} ${join.tableName} as ${join.alias}"
      result = result :+ SqlFragment(joinSql, Seq.empty)
      if join.condition.sql.nonEmpty then result = result :+ SqlFragment(" on ", Seq.empty) :+ join.condition

    val seekPredicates = seeks.map(_.toWherePredicate)
    val allPredicates  = (wherePredicates ++ seekPredicates).filter(_.sql.trim.nonEmpty)
    if allPredicates.nonEmpty then
      val joined = Placeholder.join(allPredicates, " and ")
      result = result :+ SqlFragment(" where ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    val seekSorts = seeks.map(_.toSort)
    val allSorts  = sorts ++ seekSorts
    if allSorts.nonEmpty then
      val sortFragments = allSorts.map(_.toSqlFragment)
      val joined        = Placeholder.join(sortFragments, ", ")
      result = result :+ SqlFragment(" order by ", Seq.empty) :+ SqlFragment(joined.sql, joined.writes)

    limitValue.foreach(n => result = result :+ SqlFragment(s" limit $n", Seq.empty))
    offsetValue.foreach(n => result = result :+ SqlFragment(s" offset $n", Seq.empty))

    result
  end build

  inline def query[R <: Product: Table](using Trace): ScopedQuery[Seq[R]]       = build.query[R]
  inline def queryOne[R <: Product: Table](using Trace): ScopedQuery[Option[R]] = build.queryOne[R]

end Query5Ready

// ============================================================================
// Query Companion - Entry Point
// ============================================================================

object Query:
  /** Create a Query starting with a single table.
    *
    * This returns a Query1Builder which does NOT have execution methods (.query, .queryOne). You must constrain the
    * query by calling .where(), .limit(), .seekAfter(), or .all to get a Query1Ready which can be executed.
    *
    * Usage:
    * {{{
    *   Query[User]
    *     .where(_.name).eq("Alice")  // Now Query1Ready
    *     .innerJoin[Order].on(_.id).eq(_.userId)
    *     .query[UserWithOrder]
    * }}}
    */
  def apply[A <: Product: Table]: Query1Builder[A] =
    val alias    = AliasGenerator.next()
    val instance = AliasGenerator.aliasedInstance[A](alias)
    Query1Builder(instance)

  /** Create a Query from a derived table (subquery in FROM clause).
    *
    * The result type `A` of the SelectQuery must be a case class that `derives Table` and matches the shape of the
    * subquery result.
    *
    * Usage:
    * {{{
    *   // Define a virtual type for the subquery result
    *   case class PaidOrderSummary(userId: Int, total: BigDecimal) derives Table
    *
    *   // Create typed subquery
    *   val summary = Query[Order]
    *     .where(_.status).eq("paid")
    *     .selectAll[PaidOrderSummary]
    *
    *   // Use as table source with explicit alias
    *   Query.from(summary, "paid_summary")
    *     .innerJoin[User].on(_.userId).eq(_.id)
    *     .where(_.total).gt(100)
    *     .query[UserWithTotal]
    * }}}
    */
  def from[A <: Product: Table](subquery: SelectQuery[A], alias: String): Query1Builder[A] =
    val instance = AliasGenerator.aliasedInstance[A](alias)
    Query1Builder(instance, derivedSource = Some(DerivedSource(subquery, alias)))

end Query

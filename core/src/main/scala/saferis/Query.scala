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
// Query1 - Single table (entry point)
// ============================================================================

/** Query for a single table - the starting point for building queries.
  *
  * Usage:
  * {{{
  *   Query[User]
  *     .where(_.name).eq("Alice")
  *     .orderBy(users.name.asc)
  *     .seekAfter(users.id, lastId)
  *     .limit(20)
  *     .query[User]
  * }}}
  */
/** Derived table source - subquery with user-provided alias */
final case class DerivedSource(subquery: SelectQuery[?], alias: String)

final case class Query1[A <: Product: Table](
    baseInstance: Instance[A],
    wherePredicates: Vector[SqlFragment] = Vector.empty,
    sorts: Vector[Sort[?]] = Vector.empty,
    seeks: Vector[Seek[?]] = Vector.empty,
    limitValue: Option[Int] = None,
    offsetValue: Option[Long] = None,
    selectColumns: Vector[Column[?]] = Vector.empty, // Empty = select *
    derivedSource: Option[DerivedSource] = None,     // For derived tables (subquery in FROM)
) extends QueryBase:
  // === WHERE Methods ===

  /** Add a WHERE predicate using SqlFragment */
  def where(predicate: SqlFragment): Query1[A] =
    copy(wherePredicates = wherePredicates :+ predicate)

  /** Start a type-safe WHERE condition by selecting a column */
  inline def where[T](inline selector: A => T): WhereBuilder1[A, T] =
    val fieldName   = Macros.extractFieldName[A, T](selector)
    val columnLabel = baseInstance.fieldNamesToColumns(fieldName).label
    val alias       = baseInstance.alias.getOrElse(baseInstance.tableName)
    WhereBuilder1(this, alias, columnLabel)

  /** EXISTS subquery - check if subquery returns any rows.
    *
    * For correlated subqueries, use sql"..." with outer table references:
    * {{{
    *   val users = Table[User]
    *   Query[User]
    *     .whereExists(Query[Order].where(sql"userId = ${users.id}"))
    * }}}
    */
  def whereExists(subquery: QueryBase): Query1[A] =
    val subquerySql = subquery.build
    val existsSql   = s"EXISTS (${subquerySql.sql})"
    val whereFrag   = SqlFragment(existsSql, subquerySql.writes)
    copy(wherePredicates = wherePredicates :+ whereFrag)

  /** NOT EXISTS subquery */
  def whereNotExists(subquery: QueryBase): Query1[A] =
    val subquerySql  = subquery.build
    val notExistsSql = s"NOT EXISTS (${subquerySql.sql})"
    val whereFrag    = SqlFragment(notExistsSql, subquerySql.writes)
    copy(wherePredicates = wherePredicates :+ whereFrag)

  // === ORDER BY Methods ===

  /** Add an ORDER BY clause */
  def orderBy(sort: Sort[?]): Query1[A] =
    copy(sorts = sorts :+ sort)

  /** Add multiple ORDER BY clauses */
  def orderBy(sort: Sort[?], moreSorts: Sort[?]*): Query1[A] =
    copy(sorts = sorts ++ (sort +: moreSorts))

  // === PAGINATION Methods ===

  /** Set LIMIT */
  def limit(n: Int): Query1[A] = copy(limitValue = Some(n))

  /** Set OFFSET */
  def offset(n: Long): Query1[A] = copy(offsetValue = Some(n))

  // === SEEK Methods ===

  /** Add seek-based pagination cursor */
  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query1[A] =
    copy(seeks = seeks :+ Seek(column, direction, value, sortOrder, nullOrder))

  /** Add a pre-built Seek specification */
  def seek(s: Seek[?]): Query1[A] = copy(seeks = seeks :+ s)

  /** Convenience for forward seek (>). Gets rows AFTER the cursor value. */
  def seekAfter[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Asc): Query1[A] =
    seek(column, SeekDir.Gt, value, sortOrder)

  /** Convenience for backward seek (<). Gets rows BEFORE the cursor value. */
  def seekBefore[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Desc): Query1[A] =
    seek(column, SeekDir.Lt, value, sortOrder)

  // === JOIN Methods ===

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

  // === SELECT Methods (for subqueries) ===

  /** Select a single column (for use in IN subqueries).
    *
    * Returns a SelectQuery[T] where T is the column type, enabling compile-time type checking for IN subqueries.
    *
    * Usage:
    * {{{
    *   val subquery: SelectQuery[Int] = Query[Order].select(_.userId)
    *   Query[User].where(_.id).in(subquery)  // Compiles: both are Int
    * }}}
    */
  inline def select[T](inline selector: A => T): SelectQuery[T] =
    val fieldName = Macros.extractFieldName[A, T](selector)
    val column    = baseInstance.fieldNamesToColumns(fieldName)
    SelectQuery[T](copy(selectColumns = Vector(column)))

  /** Select multiple columns.
    *
    * Usage:
    * {{{
    *   Query[Order].select(_.userId, _.amount)
    * }}}
    */
  def select(columns: Column[?]*): Query1[A] =
    copy(selectColumns = columns.toVector)

  /** Select all columns with a specified result type (for use in derived tables).
    *
    * Returns a SelectQuery[R] where R is the result row type, enabling use as a derived table in FROM clause.
    *
    * Usage:
    * {{{
    *   case class OrderSummary(userId: Int, status: String) derives Table
    *
    *   val summary: SelectQuery[OrderSummary] = Query[Order]
    *     .where(_.amount).gt(100)
    *     .selectAll[OrderSummary]
    *
    *   Query.from(summary, "high_value_orders")
    *     .innerJoin[User].on(_.userId).eq(_.id)
    *     .query[UserWithOrders]
    * }}}
    */
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

end Query1

// ============================================================================
// WhereBuilder1 - Type-safe WHERE condition builder for single table
// ============================================================================

/** Builder for type-safe WHERE conditions on a single table.
  *
  * Supports literal value comparisons (bound as prepared statement parameters).
  */
final case class WhereBuilder1[A <: Product: Table, T](
    query: Query1[A],
    fromAlias: String,
    fromColumn: String,
):
  private def completeLiteral[V](operator: Operator, value: V)(using enc: Encoder[V]): Query1[A] =
    val write     = enc(value)
    val condition = LiteralCondition(fromAlias, fromColumn, operator, write)
    val whereFrag = Condition.toSqlFragment(Vector(condition))(using saferis.postgres.PostgresDialect)
    query.copy(wherePredicates = query.wherePredicates :+ whereFrag)

  /** Compare to literal value with equality */
  def eq(value: T)(using Encoder[T]): Query1[A] =
    completeLiteral(Operator.Eq, value)

  /** Compare to literal value with not equal */
  def neq(value: T)(using Encoder[T]): Query1[A] =
    completeLiteral(Operator.Neq, value)

  /** Compare to literal value with less than */
  def lt(value: T)(using Encoder[T]): Query1[A] =
    completeLiteral(Operator.Lt, value)

  /** Compare to literal value with less than or equal */
  def lte(value: T)(using Encoder[T]): Query1[A] =
    completeLiteral(Operator.Lte, value)

  /** Compare to literal value with greater than */
  def gt(value: T)(using Encoder[T]): Query1[A] =
    completeLiteral(Operator.Gt, value)

  /** Compare to literal value with greater than or equal */
  def gte(value: T)(using Encoder[T]): Query1[A] =
    completeLiteral(Operator.Gte, value)

  /** Compare with custom operator */
  def op(operator: Operator)(value: T)(using Encoder[T]): Query1[A] =
    completeLiteral(operator, value)

  /** IS NULL check */
  def isNull(): Query1[A] =
    val condition = UnaryCondition(fromAlias, fromColumn, Operator.IsNull)
    val whereFrag = Condition.toSqlFragment(Vector(condition))(using saferis.postgres.PostgresDialect)
    query.copy(wherePredicates = query.wherePredicates :+ whereFrag)

  /** IS NOT NULL check */
  def isNotNull(): Query1[A] =
    val condition = UnaryCondition(fromAlias, fromColumn, Operator.IsNotNull)
    val whereFrag = Condition.toSqlFragment(Vector(condition))(using saferis.postgres.PostgresDialect)
    query.copy(wherePredicates = query.wherePredicates :+ whereFrag)

  /** IN subquery - check if value is in the results of another query.
    *
    * Type-safe: the subquery must return the same type T as this column.
    *
    * Usage:
    * {{{
    *   Query[User]
    *     .where(_.id).in(Query[Order].select(_.userId))  // Both Int - compiles
    *   // Query[User].where(_.name).in(Query[Order].select(_.userId))  // String vs Int - won't compile
    * }}}
    */
  def in(subquery: SelectQuery[T]): Query1[A] =
    val subquerySql = subquery.build
    val inSql       = s""""$fromAlias".$fromColumn IN (${subquerySql.sql})"""
    val whereFrag   = SqlFragment(inSql, subquerySql.writes)
    query.copy(wherePredicates = query.wherePredicates :+ whereFrag)

  /** NOT IN subquery - type-safe variant */
  def notIn(subquery: SelectQuery[T]): Query1[A] =
    val subquerySql = subquery.build
    val notInSql    = s""""$fromAlias".$fromColumn NOT IN (${subquerySql.sql})"""
    val whereFrag   = SqlFragment(notInSql, subquerySql.writes)
    query.copy(wherePredicates = query.wherePredicates :+ whereFrag)

end WhereBuilder1

// ============================================================================
// JoinBuilder1 - Building ON clause for first join
// ============================================================================

final case class JoinBuilder1[A <: Product: Table, B <: Product: Table](
    query: Query1[A],
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
    query: Query1[A],
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
// OnChain1 - Chaining ON conditions and finalizing to Query2
// ============================================================================

/** Chain state after first ON condition - allows .and() or finalization. */
final case class OnChain1[A <: Product: Table, B <: Product: Table](
    query: Query1[A],
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

  /** Finalize and create Query2 */
  def done(using Dialect): Query2[A, B] =
    val bTable        = summon[Table[B]]
    val conditionFrag = Condition.toSqlFragment(conditions)
    Query2(
      query.baseInstance,
      rightInstance,
      Vector(JoinClause(bTable.name, rightAlias, joinType, conditionFrag)),
      query.wherePredicates,
      query.sorts,
      query.seeks,
      query.limitValue,
      query.offsetValue,
      query.derivedSource,
    )
  end done

  /** Build SQL fragment directly */
  def build(using Dialect): SqlFragment = done.build

  // Convenience methods that implicitly finalize

  def orderBy(sort: Sort[?])(using Dialect): Query2[A, B]        = done.orderBy(sort)
  def limit(n: Int)(using Dialect): Query2[A, B]                 = done.limit(n)
  def offset(n: Long)(using Dialect): Query2[A, B]               = done.offset(n)
  def where(predicate: SqlFragment)(using Dialect): Query2[A, B] = done.where(predicate)

  inline def where[T](inline selector: A => T)(using Dialect): WhereBuilder2[A, B, T] =
    val alias       = query.baseInstance.alias.getOrElse(query.baseInstance.tableName)
    val fieldName   = Macros.extractFieldName[A, T](selector)
    val columnLabel = query.baseInstance.fieldNamesToColumns(fieldName).label
    WhereBuilder2(done, alias, columnLabel)

  inline def whereFrom[T](inline selector: B => T)(using Dialect): WhereBuilder2[A, B, T] =
    val alias       = rightInstance.alias.getOrElse(rightInstance.tableName)
    val fieldName   = Macros.extractFieldName[B, T](selector)
    val columnLabel = rightInstance.fieldNamesToColumns(fieldName).label
    WhereBuilder2(done, alias, columnLabel)

  def innerJoin[C <: Product: Table](using Dialect): JoinBuilder2[A, B, C] = done.innerJoin[C]
  def leftJoin[C <: Product: Table](using Dialect): JoinBuilder2[A, B, C]  = done.leftJoin[C]
  def rightJoin[C <: Product: Table](using Dialect): JoinBuilder2[A, B, C] = done.rightJoin[C]
  def fullJoin[C <: Product: Table](using Dialect): JoinBuilder2[A, B, C]  = done.fullJoin[C]

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
// Query2 - Two tables joined
// ============================================================================

final case class Query2[A <: Product: Table, B <: Product: Table](
    t1: Instance[A],
    t2: Instance[B],
    joins: Vector[JoinClause],
    wherePredicates: Vector[SqlFragment] = Vector.empty,
    sorts: Vector[Sort[?]] = Vector.empty,
    seeks: Vector[Seek[?]] = Vector.empty,
    limitValue: Option[Int] = None,
    offsetValue: Option[Long] = None,
    derivedSource: Option[DerivedSource] = None,
) extends QueryBase:
  // === WHERE Methods ===

  def where(predicate: SqlFragment): Query2[A, B] =
    copy(wherePredicates = wherePredicates :+ predicate)

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

  // === ORDER BY Methods ===

  def orderBy(sort: Sort[?]): Query2[A, B] =
    copy(sorts = sorts :+ sort)

  def orderBy(sort: Sort[?], moreSorts: Sort[?]*): Query2[A, B] =
    copy(sorts = sorts ++ (sort +: moreSorts))

  // === PAGINATION Methods ===

  def limit(n: Int): Query2[A, B]   = copy(limitValue = Some(n))
  def offset(n: Long): Query2[A, B] = copy(offsetValue = Some(n))

  // === SEEK Methods ===

  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query2[A, B] =
    copy(seeks = seeks :+ Seek(column, direction, value, sortOrder, nullOrder))

  def seek(s: Seek[?]): Query2[A, B] = copy(seeks = seeks :+ s)

  def seekAfter[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Asc): Query2[A, B] =
    seek(column, SeekDir.Gt, value, sortOrder)

  def seekBefore[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Desc): Query2[A, B] =
    seek(column, SeekDir.Lt, value, sortOrder)

  // === JOIN Methods ===

  def innerJoin[C <: Product: Table]: JoinBuilder2[A, B, C] = JoinBuilder2(this, JoinType.Inner)
  def leftJoin[C <: Product: Table]: JoinBuilder2[A, B, C]  = JoinBuilder2(this, JoinType.Left)
  def rightJoin[C <: Product: Table]: JoinBuilder2[A, B, C] = JoinBuilder2(this, JoinType.Right)
  def fullJoin[C <: Product: Table]: JoinBuilder2[A, B, C]  = JoinBuilder2(this, JoinType.Full)

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

end Query2

// ============================================================================
// WhereBuilder2 - Type-safe WHERE for two-table queries
// ============================================================================

final case class WhereBuilder2[A <: Product: Table, B <: Product: Table, T](
    query: Query2[A, B],
    fromAlias: String,
    fromColumn: String,
):
  private def completeLiteral[V](operator: Operator, value: V)(using enc: Encoder[V]): Query2[A, B] =
    val write     = enc(value)
    val condition = LiteralCondition(fromAlias, fromColumn, operator, write)
    val whereFrag = Condition.toSqlFragment(Vector(condition))(using saferis.postgres.PostgresDialect)
    query.copy(wherePredicates = query.wherePredicates :+ whereFrag)

  private def completeColumn(operator: Operator, toAlias: String, toColumn: String): Query2[A, B] =
    val condition = BinaryCondition(fromAlias, fromColumn, operator, toAlias, toColumn)
    val whereFrag = Condition.toSqlFragment(Vector(condition))(using saferis.postgres.PostgresDialect)
    query.copy(wherePredicates = query.wherePredicates :+ whereFrag)

  // Literal comparisons
  def eq(value: T)(using Encoder[T]): Query2[A, B]                     = completeLiteral(Operator.Eq, value)
  def neq(value: T)(using Encoder[T]): Query2[A, B]                    = completeLiteral(Operator.Neq, value)
  def lt(value: T)(using Encoder[T]): Query2[A, B]                     = completeLiteral(Operator.Lt, value)
  def lte(value: T)(using Encoder[T]): Query2[A, B]                    = completeLiteral(Operator.Lte, value)
  def gt(value: T)(using Encoder[T]): Query2[A, B]                     = completeLiteral(Operator.Gt, value)
  def gte(value: T)(using Encoder[T]): Query2[A, B]                    = completeLiteral(Operator.Gte, value)
  def op(operator: Operator)(value: T)(using Encoder[T]): Query2[A, B] = completeLiteral(operator, value)

  // Column comparisons
  private inline def getColumnLabel[X <: Product, T2](instance: Instance[X])(inline selector: X => T2): String =
    val fieldName = Macros.extractFieldName[X, T2](selector)
    instance.fieldNamesToColumns(fieldName).label

  inline def eqCol(inline selector: B => T): Query2[A, B] =
    val toAlias = query.t2.alias.getOrElse(query.t2.tableName)
    val toCol   = getColumnLabel(query.t2)(selector)
    completeColumn(Operator.Eq, toAlias, toCol)

  inline def neqCol(inline selector: B => T): Query2[A, B] =
    val toAlias = query.t2.alias.getOrElse(query.t2.tableName)
    val toCol   = getColumnLabel(query.t2)(selector)
    completeColumn(Operator.Neq, toAlias, toCol)

  // Unary
  def isNull(): Query2[A, B] =
    val condition = UnaryCondition(fromAlias, fromColumn, Operator.IsNull)
    val whereFrag = Condition.toSqlFragment(Vector(condition))(using saferis.postgres.PostgresDialect)
    query.copy(wherePredicates = query.wherePredicates :+ whereFrag)

  def isNotNull(): Query2[A, B] =
    val condition = UnaryCondition(fromAlias, fromColumn, Operator.IsNotNull)
    val whereFrag = Condition.toSqlFragment(Vector(condition))(using saferis.postgres.PostgresDialect)
    query.copy(wherePredicates = query.wherePredicates :+ whereFrag)

end WhereBuilder2

// ============================================================================
// JoinBuilder2 - Building ON clause for second join
// ============================================================================

final case class JoinBuilder2[A <: Product: Table, B <: Product: Table, C <: Product: Table](
    query: Query2[A, B],
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
    query: Query2[A, B],
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
    query: Query2[A, B],
    joinType: JoinType,
    rightAlias: String,
    rightInstance: Instance[C],
    conditions: Vector[Condition],
):
  def done(using Dialect): Query3[A, B, C] =
    val cTable        = summon[Table[C]]
    val conditionFrag = Condition.toSqlFragment(conditions)
    Query3(
      query.t1,
      query.t2,
      rightInstance,
      query.joins :+ JoinClause(cTable.name, rightAlias, joinType, conditionFrag),
      query.wherePredicates,
      query.sorts,
      query.seeks,
      query.limitValue,
      query.offsetValue,
    )
  end done

  def build(using Dialect): SqlFragment                             = done.build
  def orderBy(sort: Sort[?])(using Dialect): Query3[A, B, C]        = done.orderBy(sort)
  def limit(n: Int)(using Dialect): Query3[A, B, C]                 = done.limit(n)
  def offset(n: Long)(using Dialect): Query3[A, B, C]               = done.offset(n)
  def where(predicate: SqlFragment)(using Dialect): Query3[A, B, C] = done.where(predicate)

end OnChain2

// ============================================================================
// Query3 - Three tables joined
// ============================================================================

final case class Query3[A <: Product: Table, B <: Product: Table, C <: Product: Table](
    t1: Instance[A],
    t2: Instance[B],
    t3: Instance[C],
    joins: Vector[JoinClause],
    wherePredicates: Vector[SqlFragment] = Vector.empty,
    sorts: Vector[Sort[?]] = Vector.empty,
    seeks: Vector[Seek[?]] = Vector.empty,
    limitValue: Option[Int] = None,
    offsetValue: Option[Long] = None,
) extends QueryBase:
  def where(predicate: SqlFragment): Query3[A, B, C] = copy(wherePredicates = wherePredicates :+ predicate)
  def orderBy(sort: Sort[?]): Query3[A, B, C]        = copy(sorts = sorts :+ sort)
  def limit(n: Int): Query3[A, B, C]                 = copy(limitValue = Some(n))
  def offset(n: Long): Query3[A, B, C]               = copy(offsetValue = Some(n))

  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query3[A, B, C] =
    copy(seeks = seeks :+ Seek(column, direction, value, sortOrder, nullOrder))

  def seekAfter[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Asc): Query3[A, B, C] =
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

  // JOIN Methods for Query3
  def innerJoin[D <: Product: Table]: JoinBuilder3[A, B, C, D] = JoinBuilder3(this, JoinType.Inner)
  def leftJoin[D <: Product: Table]: JoinBuilder3[A, B, C, D]  = JoinBuilder3(this, JoinType.Left)
  def rightJoin[D <: Product: Table]: JoinBuilder3[A, B, C, D] = JoinBuilder3(this, JoinType.Right)
  def fullJoin[D <: Product: Table]: JoinBuilder3[A, B, C, D]  = JoinBuilder3(this, JoinType.Full)

end Query3

// ============================================================================
// JoinBuilder3 - Building ON clause for third join
// ============================================================================

final case class JoinBuilder3[A <: Product: Table, B <: Product: Table, C <: Product: Table, D <: Product: Table](
    query: Query3[A, B, C],
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
    query: Query3[A, B, C],
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
    query: Query3[A, B, C],
    joinType: JoinType,
    rightAlias: String,
    rightInstance: Instance[D],
    conditions: Vector[Condition],
):
  def done(using Dialect): Query4[A, B, C, D] =
    val dTable        = summon[Table[D]]
    val conditionFrag = Condition.toSqlFragment(conditions)
    Query4(
      query.t1,
      query.t2,
      query.t3,
      rightInstance,
      query.joins :+ JoinClause(dTable.name, rightAlias, joinType, conditionFrag),
      query.wherePredicates,
      query.sorts,
      query.seeks,
      query.limitValue,
      query.offsetValue,
    )
  end done

  def build(using Dialect): SqlFragment                                = done.build
  def orderBy(sort: Sort[?])(using Dialect): Query4[A, B, C, D]        = done.orderBy(sort)
  def limit(n: Int)(using Dialect): Query4[A, B, C, D]                 = done.limit(n)
  def offset(n: Long)(using Dialect): Query4[A, B, C, D]               = done.offset(n)
  def where(predicate: SqlFragment)(using Dialect): Query4[A, B, C, D] = done.where(predicate)

end OnChain3

// ============================================================================
// Query4 - Four tables joined
// ============================================================================

final case class Query4[A <: Product: Table, B <: Product: Table, C <: Product: Table, D <: Product: Table](
    t1: Instance[A],
    t2: Instance[B],
    t3: Instance[C],
    t4: Instance[D],
    joins: Vector[JoinClause],
    wherePredicates: Vector[SqlFragment] = Vector.empty,
    sorts: Vector[Sort[?]] = Vector.empty,
    seeks: Vector[Seek[?]] = Vector.empty,
    limitValue: Option[Int] = None,
    offsetValue: Option[Long] = None,
) extends QueryBase:
  def where(predicate: SqlFragment): Query4[A, B, C, D] = copy(wherePredicates = wherePredicates :+ predicate)
  def orderBy(sort: Sort[?]): Query4[A, B, C, D]        = copy(sorts = sorts :+ sort)
  def limit(n: Int): Query4[A, B, C, D]                 = copy(limitValue = Some(n))
  def offset(n: Long): Query4[A, B, C, D]               = copy(offsetValue = Some(n))

  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query4[A, B, C, D] =
    copy(seeks = seeks :+ Seek(column, direction, value, sortOrder, nullOrder))

  def seekAfter[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Asc): Query4[A, B, C, D] =
    seek(column, SeekDir.Gt, value, sortOrder)

  def innerJoin[E <: Product: Table]: JoinBuilder4[A, B, C, D, E] = JoinBuilder4(this, JoinType.Inner)
  def leftJoin[E <: Product: Table]: JoinBuilder4[A, B, C, D, E]  = JoinBuilder4(this, JoinType.Left)
  def rightJoin[E <: Product: Table]: JoinBuilder4[A, B, C, D, E] = JoinBuilder4(this, JoinType.Right)
  def fullJoin[E <: Product: Table]: JoinBuilder4[A, B, C, D, E]  = JoinBuilder4(this, JoinType.Full)

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

end Query4

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
    query: Query4[A, B, C, D],
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
    query: Query4[A, B, C, D],
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
    query: Query4[A, B, C, D],
    joinType: JoinType,
    rightAlias: String,
    rightInstance: Instance[E],
    conditions: Vector[Condition],
):
  def done(using Dialect): Query5[A, B, C, D, E] =
    val eTable        = summon[Table[E]]
    val conditionFrag = Condition.toSqlFragment(conditions)
    Query5(
      query.t1,
      query.t2,
      query.t3,
      query.t4,
      rightInstance,
      query.joins :+ JoinClause(eTable.name, rightAlias, joinType, conditionFrag),
      query.wherePredicates,
      query.sorts,
      query.seeks,
      query.limitValue,
      query.offsetValue,
    )
  end done

  def build(using Dialect): SqlFragment                                   = done.build
  def orderBy(sort: Sort[?])(using Dialect): Query5[A, B, C, D, E]        = done.orderBy(sort)
  def limit(n: Int)(using Dialect): Query5[A, B, C, D, E]                 = done.limit(n)
  def offset(n: Long)(using Dialect): Query5[A, B, C, D, E]               = done.offset(n)
  def where(predicate: SqlFragment)(using Dialect): Query5[A, B, C, D, E] = done.where(predicate)

end OnChain4

// ============================================================================
// Query5 - Five tables joined (maximum)
// ============================================================================

final case class Query5[
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
    seeks: Vector[Seek[?]] = Vector.empty,
    limitValue: Option[Int] = None,
    offsetValue: Option[Long] = None,
) extends QueryBase:
  def where(predicate: SqlFragment): Query5[A, B, C, D, E] = copy(wherePredicates = wherePredicates :+ predicate)
  def orderBy(sort: Sort[?]): Query5[A, B, C, D, E]        = copy(sorts = sorts :+ sort)
  def limit(n: Int): Query5[A, B, C, D, E]                 = copy(limitValue = Some(n))
  def offset(n: Long): Query5[A, B, C, D, E]               = copy(offsetValue = Some(n))

  def seek[T: Encoder](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): Query5[A, B, C, D, E] =
    copy(seeks = seeks :+ Seek(column, direction, value, sortOrder, nullOrder))

  def seekAfter[T: Encoder](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Asc): Query5[A, B, C, D, E] =
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

end Query5

// ============================================================================
// Query Companion - Entry Point
// ============================================================================

object Query:
  /** Create a Query starting with a single table.
    *
    * Usage:
    * {{{
    *   Query[User]
    *     .where(_.name).eq("Alice")
    *     .innerJoin[Order].on(_.id).eq(_.userId)
    *     .build
    * }}}
    */
  def apply[A <: Product: Table]: Query1[A] =
    val alias    = AliasGenerator.next()
    val instance = AliasGenerator.aliasedInstance[A](alias)
    Query1(instance)

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
    *     .query[UserWithTotal]
    * }}}
    */
  def from[A <: Product: Table](subquery: SelectQuery[A], alias: String): Query1[A] =
    val instance = AliasGenerator.aliasedInstance[A](alias)
    Query1(instance, derivedSource = Some(DerivedSource(subquery, alias)))

end Query

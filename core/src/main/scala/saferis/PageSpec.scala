package saferis

import zio.Trace

/** Immutable specification builder for paginated queries.
  *
  * Supports both offset-based pagination (limit/offset) and cursor-based pagination (seek).
  *
  * @tparam E
  *   The entity type being queried (must have a Table instance)
  */
final case class PageSpec[E <: Product](
    private val tableName: String,
    private val wherePredicates: Vector[SqlFragment] = Vector.empty,
    private val sorts: Vector[Sort[?]] = Vector.empty,
    private val seeks: Vector[Seek[?]] = Vector.empty,
    private val limitValue: Option[Int] = None,
    private val offsetValue: Option[Long] = None,
):

  // === Filter Methods ===

  /** Add a WHERE predicate. Multiple predicates are ANDed together. */
  def where(predicate: SqlFragment): PageSpec[E] =
    copy(wherePredicates = wherePredicates :+ predicate)

  /** Add multiple WHERE predicates (ANDed together) */
  def where(predicates: SqlFragment*): PageSpec[E] =
    copy(wherePredicates = wherePredicates ++ predicates)

  // === Sort Methods ===

  /** Add a sort column with specified order and null handling */
  def orderBy[T](
      column: Column[T],
      order: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  ): PageSpec[E] =
    copy(sorts = sorts :+ Sort(column, order, nullOrder))

  /** Add a Sort specification directly */
  def orderBy(sort: Sort[?]): PageSpec[E] =
    copy(sorts = sorts :+ sort)

  // === Pagination Methods ===

  /** Set the maximum number of rows to return */
  def limit(n: Int): PageSpec[E] = copy(limitValue = Some(n))

  /** Set the number of rows to skip */
  def offset(n: Long): PageSpec[E] = copy(offsetValue = Some(n))

  // === Seek/Cursor Pagination ===

  /** Add seek-based pagination cursor.
    *
    * Seek pagination is more efficient than offset-based pagination for large datasets as it uses indexed lookups
    * instead of scanning and discarding rows.
    *
    * Type T is inferred from the value parameter.
    */
  def seek[T](
      column: Column[T],
      direction: SeekDir,
      value: T,
      sortOrder: SortOrder = SortOrder.Asc,
      nullOrder: NullOrder = NullOrder.Default,
  )(using Encoder[T]): PageSpec[E] =
    copy(seeks = seeks :+ Seek(column, direction, value, sortOrder, nullOrder))

  /** Convenience for forward seek (>). Gets rows AFTER the cursor value. */
  def seekAfter[T](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Asc)(using Encoder[T]): PageSpec[E] =
    seek(column, SeekDir.Gt, value, sortOrder)

  /** Convenience for backward seek (<). Gets rows BEFORE the cursor value. */
  def seekBefore[T](column: Column[T], value: T, sortOrder: SortOrder = SortOrder.Desc)(using Encoder[T]): PageSpec[E] =
    seek(column, SeekDir.Lt, value, sortOrder)

  /** Add a pre-built Seek specification directly (for use with column extensions like `column.gt(value)`) */
  def seek(s: Seek[?]): PageSpec[E] = copy(seeks = seeks :+ s)

  // === SQL Generation ===

  /** Build the complete SqlFragment for this specification */
  def build: SqlFragment =
    // Base query using stored table name
    var result = SqlFragment(s"select * from $tableName", Seq.empty)

    // WHERE clause (user predicates + seek predicates)
    val seekPredicates = seeks.map(_.toWherePredicate)
    val allPredicates  = (wherePredicates ++ seekPredicates).filter(p => p.sql.trim.nonEmpty)
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

  // === Query Execution Methods ===

  /** Execute the query and return all matching rows */
  inline def query[T <: E: Table](using Trace): ScopedQuery[Seq[T]] = build.query[T]

  /** Execute the query and return the first matching row */
  inline def queryOne[T <: E: Table](using Trace): ScopedQuery[Option[T]] = build.queryOne[T]

end PageSpec

object PageSpec:
  /** Create an empty PageSpec for an entity type */
  inline def apply[E <: Product: Table]: PageSpec[E] =
    new PageSpec[E](tableName = Macros.nameOf[E])

  /** Create a PageSpec with an initial filter */
  inline def where[E <: Product: Table](predicate: SqlFragment): PageSpec[E] =
    PageSpec[E].where(predicate)

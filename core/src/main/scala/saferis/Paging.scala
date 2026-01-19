package saferis

/** Sort direction for ORDER BY clauses */
enum SortOrder:
  case Asc, Desc

/** Null ordering preference in sorted results */
enum NullOrder:
  case First, Last, Default

/** Seek direction for cursor-based pagination */
enum SeekDir:
  /** Greater than (>) - forward pagination, get items AFTER cursor */
  case Gt

  /** Less than (<) - backward pagination, get items BEFORE cursor */
  case Lt

/** Type-safe sort specification using Column[T]
  *
  * @param column
  *   The column to sort by
  * @param order
  *   Sort direction (Asc or Desc)
  * @param nullOrder
  *   Where to place NULL values in results
  */
final case class Sort[T](
    column: Column[T],
    order: SortOrder = SortOrder.Asc,
    nullOrder: NullOrder = NullOrder.Default,
):
  /** Generate the SQL fragment for this sort specification */
  def toSqlFragment: SqlFragment =
    val orderStr = order match
      case SortOrder.Asc  => " asc"
      case SortOrder.Desc => " desc"
    val nullStr = nullOrder match
      case NullOrder.First   => " nulls first"
      case NullOrder.Last    => " nulls last"
      case NullOrder.Default => ""
    SqlFragment(s"${column.sql}$orderStr$nullStr", Seq.empty)
end Sort

/** Type-safe seek specification for cursor-based pagination
  *
  * @param column
  *   The column to seek on (usually the primary key or indexed column)
  * @param direction
  *   Forward (Gt) or backward (Lt)
  * @param value
  *   The cursor value to seek from
  * @param sortOrder
  *   How to sort the seek column
  * @param nullOrder
  *   Where to place NULL values
  */
final case class Seek[T: Encoder](
    column: Column[T],
    direction: SeekDir,
    value: T,
    sortOrder: SortOrder = SortOrder.Asc,
    nullOrder: NullOrder = NullOrder.Default,
):
  /** Generate the WHERE predicate for this seek specification */
  def toWherePredicate: SqlFragment =
    val op = direction match
      case SeekDir.Gt => ">"
      case SeekDir.Lt => "<"
    val encoder = summon[Encoder[T]]
    SqlFragment(s"${column.sql} $op ?", Seq(encoder(value)))

  /** Generate a Sort from this seek specification */
  def toSort: Sort[T] = Sort(column, sortOrder, nullOrder)
end Seek

// Column extensions for fluent sorting and seeking
extension [T](column: Column[T])
  // Sort extensions (combined style)
  def asc: Sort[T]            = Sort(column, SortOrder.Asc)
  def desc: Sort[T]           = Sort(column, SortOrder.Desc)
  def ascNullsFirst: Sort[T]  = Sort(column, SortOrder.Asc, NullOrder.First)
  def ascNullsLast: Sort[T]   = Sort(column, SortOrder.Asc, NullOrder.Last)
  def descNullsFirst: Sort[T] = Sort(column, SortOrder.Desc, NullOrder.First)
  def descNullsLast: Sort[T]  = Sort(column, SortOrder.Desc, NullOrder.Last)

  // Seek extensions
  def gt(value: T)(using Encoder[T]): Seek[T] = Seek(column, SeekDir.Gt, value)
  def lt(value: T)(using Encoder[T]): Seek[T] = Seek(column, SeekDir.Lt, value)
end extension

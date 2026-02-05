package saferis

import zio.Trace

/** Type-safe aggregate function DSL for SQL aggregate operations.
  *
  * Usage:
  * {{{
  *   // Get max sequence number with default
  *   Query[EventRow]
  *     .where(_.instanceId).eq(instanceId)
  *     .selectAggregate(_.sequenceNr.max.coalesce(0L))
  *     .queryValue[Long]
  *
  *   // Count all rows
  *   Query[User]
  *     .where(_.status).eq("active")
  *     .selectAggregate(countAll)
  *     .queryValue[Long]
  * }}}
  */

// ============================================================================
// Aggregate Function Enum
// ============================================================================

/** SQL aggregate functions */
enum AggregateFunction(val sql: String):
  case Max   extends AggregateFunction("max")
  case Min   extends AggregateFunction("min")
  case Sum   extends AggregateFunction("sum")
  case Avg   extends AggregateFunction("avg")
  case Count extends AggregateFunction("count")

// ============================================================================
// Aggregate Expression Types
// ============================================================================

/** Base trait for aggregate expressions */
sealed trait AggregateExpr[T]:
  def toSql: String
  def writes: Seq[Write[?]]

/** Aggregate function applied to a column */
final case class ColumnAggregate[T](function: AggregateFunction, column: Column[T]) extends AggregateExpr[T]:
  def toSql: String         = s"${function.sql}(${column.label})"
  def writes: Seq[Write[?]] = Seq.empty

  /** Wrap the aggregate in COALESCE with a default value */
  def coalesce(default: T)(using enc: Encoder[T]): CoalesceExpr[T] =
    CoalesceExpr(this, default, enc)

/** COALESCE wrapper for aggregate expressions */
final case class CoalesceExpr[T](expr: AggregateExpr[T], default: T, encoder: Encoder[T]) extends AggregateExpr[T]:
  def toSql: String         = s"coalesce(${expr.toSql}, ?)"
  def writes: Seq[Write[?]] = expr.writes :+ encoder(default)

/** COUNT(*) aggregate */
case object CountAll extends AggregateExpr[Long]:
  def toSql: String         = "count(*)"
  def writes: Seq[Write[?]] = Seq.empty

// ============================================================================
// Column Extensions for Aggregates
// ============================================================================

/** Extension methods to add aggregate functions to columns */
extension [T](column: Column[T])
  /** MAX aggregate function */
  def max: ColumnAggregate[T] = ColumnAggregate(AggregateFunction.Max, column)

  /** MIN aggregate function */
  def min: ColumnAggregate[T] = ColumnAggregate(AggregateFunction.Min, column)

  /** SUM aggregate function */
  def sum: ColumnAggregate[T] = ColumnAggregate(AggregateFunction.Sum, column)

  /** AVG aggregate function */
  def avg: ColumnAggregate[T] = ColumnAggregate(AggregateFunction.Avg, column)

  /** COUNT aggregate function (counts non-null values) */
  def count: ColumnAggregate[Long] = ColumnAggregate(AggregateFunction.Count, column.asInstanceOf[Column[Long]])
end extension

/** Convenience function for COUNT(*) */
def countAll: CountAll.type = CountAll

// ============================================================================
// AggregateQuery - Query returning aggregate value
// ============================================================================

/** A query that returns a single aggregate value.
  *
  * Created by calling `.selectAggregate(...)` on a Query1Ready.
  */
final case class AggregateQuery[A <: Product, T](
    tableName: String,
    tableAlias: Option[Alias],
    wherePredicates: Vector[SqlFragment],
    aggregate: AggregateExpr[T],
):
  /** Build the SELECT aggregate SQL */
  def build: SqlFragment =
    val fromClause = tableAlias.fold(tableName)(a => s"$tableName as ${a.value}")
    var sql        = s"select ${aggregate.toSql} from $fromClause"

    if wherePredicates.nonEmpty then
      val whereJoined = Placeholder.join(wherePredicates, " and ")
      sql = sql + s" where ${whereJoined.sql}"

    val allWrites = aggregate.writes ++ wherePredicates.flatMap(_.writes)
    SqlFragment(sql, allWrites)
  end build

  /** Execute and return the aggregate value */
  inline def queryValue[R](using dec: Decoder[R], trace: Trace): ScopedQuery[Option[R]] =
    build.queryValue[R]
end AggregateQuery

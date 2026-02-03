package saferis

/** Type-safe comparison operators for queries and where clauses.
  *
  * Standard operators work on all databases. Users can extend for custom operators by creating their own case objects
  * that extend `Operator`.
  *
  * Example:
  * {{{
  *   // Standard equality join
  *   Query[User]
  *     .innerJoin[Order].on(_.id).eq(_.userId)
  *     .build
  *
  *   // Using generic op() for custom operators
  *   Query[User]
  *     .innerJoin[Order].on(_.name).op(Operator.ILike)(_.pattern)
  *     .build
  * }}}
  */
sealed trait Operator:
  /** The SQL operator string */
  def sql: String

object Operator:
  // === Standard SQL operators (all dialects) ===

  /** Equality operator (=) */
  case object Eq extends Operator:
    def sql = "="

  /** Not equal operator (<>) */
  case object Neq extends Operator:
    def sql = "<>"

  /** Less than operator (<) */
  case object Lt extends Operator:
    def sql = "<"

  /** Less than or equal operator (<=) */
  case object Lte extends Operator:
    def sql = "<="

  /** Greater than operator (>) */
  case object Gt extends Operator:
    def sql = ">"

  /** Greater than or equal operator (>=) */
  case object Gte extends Operator:
    def sql = ">="

  /** LIKE operator for pattern matching */
  case object Like extends Operator:
    def sql = "LIKE"

  // === PostgreSQL-specific operators ===

  /** Case-insensitive LIKE (PostgreSQL only) */
  case object ILike extends Operator:
    def sql = "ILIKE"

  /** SQL SIMILAR TO pattern matching (PostgreSQL only) */
  case object SimilarTo extends Operator:
    def sql = "SIMILAR TO"

  /** POSIX regex match, case-sensitive (PostgreSQL only) */
  case object RegexMatch extends Operator:
    def sql = "~"

  /** POSIX regex match, case-insensitive (PostgreSQL only) */
  case object RegexMatchCI extends Operator:
    def sql = "~*"

  // === Unary operators (for IS NULL / IS NOT NULL) ===

  /** IS NULL - checks if column value is null */
  case object IsNull extends Operator:
    def sql = "IS NULL"

  /** IS NOT NULL - checks if column value is not null */
  case object IsNotNull extends Operator:
    def sql = "IS NOT NULL"
end Operator

package saferis

/** Type-safe comparison operators for joins and where clauses.
  *
  * Standard operators work on all databases. Users can extend for custom operators by creating their own case objects
  * that extend `JoinOperator`.
  *
  * Example:
  * {{{
  *   // Standard equality join
  *   JoinSpec[User]
  *     .innerJoin[Order].on(_.id).eq(_.userId)
  *     .build
  *
  *   // Using generic op() for custom operators
  *   JoinSpec[User]
  *     .innerJoin[Order].on(_.name).op(JoinOperator.ILike)(_.pattern)
  *     .build
  * }}}
  */
sealed trait JoinOperator:
  /** The SQL operator string */
  def sql: String

object JoinOperator:
  // === Standard SQL operators (all dialects) ===

  /** Equality operator (=) */
  case object Eq extends JoinOperator:
    def sql = "="

  /** Not equal operator (<>) */
  case object Neq extends JoinOperator:
    def sql = "<>"

  /** Less than operator (<) */
  case object Lt extends JoinOperator:
    def sql = "<"

  /** Less than or equal operator (<=) */
  case object Lte extends JoinOperator:
    def sql = "<="

  /** Greater than operator (>) */
  case object Gt extends JoinOperator:
    def sql = ">"

  /** Greater than or equal operator (>=) */
  case object Gte extends JoinOperator:
    def sql = ">="

  /** LIKE operator for pattern matching */
  case object Like extends JoinOperator:
    def sql = "LIKE"

  // === PostgreSQL-specific operators ===

  /** Case-insensitive LIKE (PostgreSQL only) */
  case object ILike extends JoinOperator:
    def sql = "ILIKE"

  /** SQL SIMILAR TO pattern matching (PostgreSQL only) */
  case object SimilarTo extends JoinOperator:
    def sql = "SIMILAR TO"

  /** POSIX regex match, case-sensitive (PostgreSQL only) */
  case object RegexMatch extends JoinOperator:
    def sql = "~"

  /** POSIX regex match, case-insensitive (PostgreSQL only) */
  case object RegexMatchCI extends JoinOperator:
    def sql = "~*"

  // === Unary operators (for IS NULL / IS NOT NULL) ===

  /** IS NULL - checks if column value is null */
  case object IsNull extends JoinOperator:
    def sql = "IS NULL"

  /** IS NOT NULL - checks if column value is not null */
  case object IsNotNull extends JoinOperator:
    def sql = "IS NOT NULL"
end JoinOperator

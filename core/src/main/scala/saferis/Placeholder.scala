package saferis

/** A placeholder in a SQL query. This is used to represent a value in a SQL query that will be replaced with a value
  * when the query is executed. It is used to build statements from fragments and values.
  */
trait Placeholder:
  private[saferis] def writes: Seq[Write[?]]
  def sql: String

  /** Validation issues accumulated during fragment construction. Empty for normal placeholders; non-empty when a helper
    * (e.g. an empty IN-list) couldn't produce valid SQL. Surfaces as [[SaferisError.InvalidStatement]] at execution.
    */
  private[saferis] def issues: List[FragmentIssue] = Nil

  /** Combine this placeholder with another placeholder */
  final def ++(other: Placeholder): Placeholder =
    Placeholder.Derived(writes ++ other.writes, sql + other.sql, issues ++ other.issues)
end Placeholder

object Placeholder:
  /** Create a placeholder from a value with an encoder */
  def apply[A](a: A)(using sw: Encoder[A]): Placeholder = Derived(Seq(sw(a)), sw.placeholder(a))

  /** Identity function for placeholders */
  def apply(p: Placeholder): Placeholder = p

  /** Create a placeholder from raw SQL (use with caution) */
  def raw(sql: String): Placeholder = RawSql(sql)

  /** Create a placeholder for a safely escaped identifier (table name, column name, etc.). This prevents SQL injection
    * by properly escaping quotes in the identifier.
    *
    * @param identifier
    *   The identifier to escape
    * @param dialect
    *   The database dialect to use for escaping
    * @return
    *   A Placeholder with the properly escaped identifier
    */
  def identifier(identifier: String)(using dialect: Dialect): Placeholder =
    RawSql(dialect.escapeIdentifier(identifier))

  /** Create a placeholder from multiple placeholders */
  def concat(placeholders: Placeholder*): Placeholder =
    Derived(
      placeholders.flatMap(_.writes),
      placeholders.map(_.sql).mkString,
      placeholders.toList.flatMap(_.issues),
    )

  /** Create a placeholder with a separator between elements */
  def join(placeholders: Seq[Placeholder], separator: String = ", "): Placeholder =
    if placeholders.isEmpty then Empty
    else if placeholders.size == 1 then placeholders.head
    else
      val sql    = placeholders.map(_.sql).mkString(separator)
      val writes = placeholders.flatMap(_.writes)
      val issues = placeholders.toList.flatMap(_.issues)
      Derived(writes, sql, issues)

  /** Create a comma-separated list of placeholders */
  def commaList(placeholders: Placeholder*): Placeholder =
    join(placeholders, ", ")

  /** Splice a collection of values as a comma-separated list of parameterized placeholders.
    *
    * Accepts any `Iterable[A]` (`Seq`, `List`, `Set`, `LinkedHashSet`, etc.). Duplicates are removed (set semantics;
    * matches typical IN-clause use). Each remaining element becomes one `?` bound via the implicit `Encoder[A]`. Useful
    * for VALUES rows and other comma-separated list contexts. For IN clauses prefer the top-level `in` helper, which
    * adds the surrounding parentheses.
    *
    * Order: follows the input iterator's order, preserving insertion order for ordered collections.
    *
    * On empty (or degenerate-empty-after-dedupe) input, returns a placeholder carrying a
    * [[FragmentIssue.EmptyCollection]]. The issue surfaces as [[SaferisError.InvalidStatement]] when the resulting
    * fragment is run — no throw at the call site.
    */
  def list[A](values: Iterable[A])(using Encoder[A]): Placeholder =
    listTagged(values, helper = "Placeholder.list", origin = captureOrigin())

  /** Varargs convenience overload — at least one element by construction. */
  def list[A](first: A, rest: A*)(using Encoder[A]): Placeholder =
    listTagged(first +: rest, helper = "Placeholder.list", origin = captureOrigin())

  /** Internal helper used by `list` and `Interpolator.in` so each can tag the issue with the helper name the user
    * actually called and pass an origin captured at the user-facing entry point.
    */
  private[saferis] def listTagged[A](values: Iterable[A], helper: String, origin: Option[StackTraceElement])(using
      sw: Encoder[A]
  ): Placeholder =
    val deduped = values.iterator.distinct.toVector
    if deduped.isEmpty then
      Derived(writes = Nil, sql = "", issues = List(FragmentIssue.EmptyCollection(helper, origin)))
    else join(deduped.map(Placeholder.apply[A]), ", ")

  /** Capture the user's call-site frame. Walks the stack trace and returns the first frame whose declaring class is
    * outside the `saferis` package, ignoring synthetic frames introduced by `export` aliases or inlined helpers.
    */
  private[saferis] def captureOrigin(): Option[StackTraceElement] =
    val st = new Throwable().getStackTrace
    st.find(f => !f.getClassName.startsWith("saferis."))

  /** Extract all writes from a sequence of placeholders */
  def allWrites(ps: Seq[Placeholder]): Seq[Write[?]] = ps.flatMap(_.writes)

  /** Extract all validation issues from a sequence of placeholders */
  private[saferis] def allIssues(ps: Seq[Placeholder]): List[FragmentIssue] = ps.toList.flatMap(_.issues)

  final private case class Derived(
      override private[saferis] val writes: Seq[Write[?]],
      sql: String,
      override private[saferis] val issues: List[FragmentIssue] = Nil,
  ) extends Placeholder

  given convertToPlaceholder[A](using sw: Encoder[A]): Conversion[A, Placeholder] with
    def apply(a: A): Placeholder =
      Derived(Vector(sw(a)), sw.placeholder(a))

  given convertSeqOfPlaceholdersToPlaceholder: Conversion[Seq[Placeholder], Placeholder] with
    def apply(as: Seq[Placeholder]): Placeholder =
      val sql    = as.map(_.sql).mkString("")
      val writes = as.flatMap(_.writes)
      val issues = as.toList.flatMap(_.issues)
      Derived(writes, sql, issues)

  /** A raw SQL query fragment. This should be used cautiously, as it if misused it could open up the possibility of SQL
    * injection attacks. Only use this when you are sure that the SQL fragment is safe - not derived from user input for
    * example.
    *
    * Use [[Placeholder.raw]] to create instances of this class.
    *
    * @param sql
    *   the raw SQL string
    */
  final private[saferis] class RawSql(val sql: String) extends Placeholder:
    override private[saferis] val writes = Seq.empty

    override def toString: String = s"RawSql($sql)"

  /** An empty placeholder that produces no SQL and no writes */
  object Empty extends Placeholder:
    val sql                              = ""
    override private[saferis] val writes = Seq.empty

    override def toString: String = "Placeholder.Empty"

    /** Check if a placeholder is empty */
    def isEmpty(p: Placeholder): Boolean = p match
      case Empty => true
      case other => other.sql.trim.isEmpty && other.writes.isEmpty
  end Empty
end Placeholder

package saferis

/** A placeholder in a SQL query. This is used to represent a value in a SQL query that will be replaced with a value
  * when the query is executed. It is used to build statements from fragments and values.
  */
trait Placeholder:
  private[saferis] def writes: Seq[Write[?]]
  def sql: String

  /** Combine this placeholder with another placeholder */
  final def ++(other: Placeholder): Placeholder =
    Placeholder.Derived(writes ++ other.writes, sql + other.sql)

object Placeholder:
  /** Create a placeholder from a value with an encoder */
  def apply[A: Encoder as sw](a: A): Placeholder = Derived(Seq(sw(a)), sw.placeholder(a))

  /** Identity function for placeholders */
  def apply(p: Placeholder): Placeholder = p

  /** Create a placeholder from raw SQL (use with caution) */
  def raw(sql: String): Placeholder = RawSql(sql)

  /** Create a placeholder from multiple placeholders */
  def concat(placeholders: Placeholder*): Placeholder =
    Derived(placeholders.flatMap(_.writes), placeholders.map(_.sql).mkString)

  /** Create a placeholder with a separator between elements */
  def join(placeholders: Seq[Placeholder], separator: String = ", "): Placeholder =
    if placeholders.isEmpty then Empty
    else if placeholders.size == 1 then placeholders.head
    else
      val sql    = placeholders.map(_.sql).mkString(separator)
      val writes = placeholders.flatMap(_.writes)
      Derived(writes, sql)

  /** Create a comma-separated list of placeholders */
  def commaList(placeholders: Placeholder*): Placeholder =
    join(placeholders, ", ")

  /** Extract all writes from a sequence of placeholders */
  def allWrites(ps: Seq[Placeholder]): Seq[Write[?]] = ps.flatMap(_.writes)

  final private case class Derived(override private[saferis] val writes: Seq[Write[?]], sql: String) extends Placeholder

  given convertToPlaceholder[A: Encoder as sw]: Conversion[A, Placeholder] with
    def apply(a: A): Placeholder =
      Derived(Vector(sw(a)), sw.placeholder(a))

  given convertSeqOfPlaceholdersToPlaceholder: Conversion[Seq[Placeholder], Placeholder] with
    def apply(as: Seq[Placeholder]): Placeholder =
      val sql    = as.map(_.sql).mkString("")
      val writes = as.flatMap(_.writes)
      Derived(writes, sql)

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
  end RawSql

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

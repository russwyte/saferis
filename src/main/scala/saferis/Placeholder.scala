package saferis

import zio.Tag

/** A placeholder in a SQL query. This is used to represent a value in a SQL query that will be replaced with a value
  * when the query is executed. It is used to build statements from fragments and values.
  */
trait Placeholder:
  private[saferis] def writes: Seq[Write[?]]
  def sql: String

object Placeholder:
  def apply[A: Encoder as sw](a: A): Placeholder = Derived(Seq(sw(a)), sw.placeholder(a))
  def apply[A](p: Placeholder)                   = p
  def allWrites(ps: Seq[Placeholder]): Seq[Write[?]] = ps.flatMap: p =>
    p.writes
  final private case class Derived(override private[saferis] val writes: Seq[Write[?]], sql: String) extends Placeholder

  given convertToPlaceholder[A: Encoder as sw: Tag]: Conversion[A, Placeholder] with
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
    * @param sql
    */
  final class RawSql(val sql: String) extends Placeholder:
    override private[saferis] val writes = Seq.empty

  object Empty extends Placeholder:
    val sql                              = ""
    override private[saferis] val writes = Seq.empty
end Placeholder

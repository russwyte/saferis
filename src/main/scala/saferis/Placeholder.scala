package saferis

trait Placeholder[A]:
  def write: Option[Write[A]] = None
  def sql: String

object Placeholder:
  def apply[A: StatementWriter as sw](a: A): Placeholder[A] = Derived(Some(sw(a)), sw.placeholder)
  def apply[A](p: Placeholder[A])                           = p
  def allWrites(ps: Seq[Placeholder[?]]): Seq[Write[?]]     = ps.flatMap(_.write)
  final case class Derived[A](override val write: Option[Write[A]], sql: String) extends Placeholder[A]

  given aToPlaceholder[A: StatementWriter as sw]: Conversion[A, Placeholder[A]] with
    def apply(a: A): Placeholder[A] = Derived(Some(sw(a)), sw.placeholder)
end Placeholder

class RawSql(val sql: String) extends Placeholder[Unit]

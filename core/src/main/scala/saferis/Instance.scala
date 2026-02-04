package saferis
//import saferis.Instance.*
import zio.Trace
import scala.annotation.unused

/** Provides DDL information about a table.
  *
  * This class is derived via a macro. It can be derived from a Table[A].
  *
  * @param tableName
  * @param fieldNamesToLabels
  * @param alias
  */
final case class Instance[A <: Product: Table as table](
    private[saferis] val tableName: String,
    private[saferis] val columns: Seq[Column[?]],
    private[saferis] val alias: Option[Alias],
    private[saferis] val foreignKeys: Vector[ForeignKeySpec[A, ?]] = Vector.empty,
    private[saferis] val indexes: Vector[IndexSpec[?]] = Vector.empty,
    private[saferis] val uniqueConstraints: Vector[UniqueConstraintSpec[?]] = Vector.empty,
) extends Selectable
    with Placeholder:
  private[saferis] val fieldNamesToColumns: Map[String, Column[?]] = columns.map(c => c.name -> c).toMap

  /** Exposes the Table[A] evidence for use in builder classes */
  private[saferis] def tableEvidence: Table[A] = table
  def foreignKeyConstraints: Seq[String]       = foreignKeys.map(_.toConstraintSql).toSeq
  def uniqueConstraintsSql: Seq[String]        = uniqueConstraints.map(_.toConstraintSql).toSeq

  def selectDynamic(name: String)                              = fieldNamesToColumns(name)
  def applyDynamic[A: Encoder](@unused name: String)(args: A*) =
    val cs: Seq[Column[?]] =
      columns.filter(_.isKey)
    val whereArgs   = cs.zip(args).toList
    val whereClause = whereArgs.headOption.fold(Placeholder.Empty):
      case (c, a) =>
        whereArgs.tail
          .foldLeft(sql"where $c = $a"):
            case (acc, (c, a)) =>
              acc :+ sql" and $c = $a"
    TypedFragment(sql"select * from $this $whereClause")
  end applyDynamic

  override def sql: String                            = alias.fold(tableName)(a => s"$tableName as ${a.value}")
  override private[saferis] def writes: Seq[Write[?]] = Seq.empty

  /** Set a user-provided alias on this instance. The alias will be escaped in SQL. */
  transparent inline def withAlias(alias: String) =
    val userAlias  = Alias.User(alias)
    val newColumns = columns.map(_.withTableAlias(Some(userAlias)))
    copy(
      alias = Some(userAlias),
      columns = newColumns,
      foreignKeys = foreignKeys,
      indexes = indexes,
      uniqueConstraints = uniqueConstraints,
    ).asInstanceOf[this.type]

  /** Internal method to set a generated alias on this instance. */
  private[saferis] transparent inline def withGeneratedAlias(alias: Alias.Generated) =
    val newColumns = columns.map(_.withTableAlias(Some(alias)))
    copy(
      alias = Some(alias),
      columns = newColumns,
      foreignKeys = foreignKeys,
      indexes = indexes,
      uniqueConstraints = uniqueConstraints,
    ).asInstanceOf[this.type]

  transparent inline def deAliased =
    val newColumns = columns.map(_.withTableAlias(None))
    copy(
      alias = None,
      columns = newColumns,
      foreignKeys = foreignKeys,
      indexes = indexes,
      uniqueConstraints = uniqueConstraints,
    ).asInstanceOf[this.type]

  /** Extract a typed column using a field selector.
    *
    * This is private[saferis] to avoid collision with user-defined fields named "column".
    * Used internally by Query.where and other builder methods.
    */
  private[saferis] inline def column[T](inline selector: A => T): Column[T] =
    Macros.extractColumn(this, selector)

  final private[saferis] class TypedFragment(val fragment: SqlFragment):
    def sql                                                  = fragment.sql
    inline def query(using Trace): ScopedQuery[Seq[A]]       = fragment.query[A]
    inline def queryOne(using Trace): ScopedQuery[Option[A]] = fragment.queryOne[A]
end Instance

object Instance:
  val getByKey = "getByKey"

package saferis
import Metadata.*
import zio.Trace

/** Provides DDL information about a table.
  *
  * This class is derived via a macro. It can be derived from any Table[A] instance.
  *
  * @param tableName
  * @param fieldNamesToLabels
  * @param alias
  */
final case class Metadata[E <: Product: Table](
    private val tableName: String,
    private val columns: Seq[Column[?]],
    private val alias: Option[String],
) extends Selectable
    with Placeholder:
  private[saferis] val fieldNamesToLabels: Map[String, Column[?]] = columns.map(c => c.name -> c).toMap
  def selectDynamic(name: String)                                 = fieldNamesToLabels(name)
  def applyDynamic[A: StatementWriter](name: String)(args: A*) =
    (name, args) match
      case (getByKey, as) =>
        val cs: Seq[Column[?]] =
          columns.filter(_.isKey)
        val whereArgs = cs.zip(as).toList
        val whereClause = whereArgs.headOption.fold(Placeholder.Empty):
          case (c, a) =>
            whereArgs.tail
              .foldLeft(sql"where $c = $a"):
                case (acc, (c, a)) =>
                  acc :+ sql" and $c = $a"
        TypedFragment(sql"select * from $this $whereClause")
  override def sql: String                            = alias.fold(tableName)(a => s"$tableName as $a")
  override private[saferis] def writes: Seq[Write[?]] = Seq.empty

  transparent inline def withAlias(alias: String) =
    val newColumns = columns.map(_.withTableAlias(Some(alias)))
    copy(alias = Some(alias), columns = newColumns).asInstanceOf[this.type]

  transparent inline def deAliased =
    val newColumns = columns.map(_.withTableAlias(None))
    copy(alias = None, columns = newColumns).asInstanceOf[this.type]

  final private[saferis] class TypedFragment(val fragment: SqlFragment):
    def sql                                                  = fragment.sql
    inline def query(using Trace): ScopedQuery[Seq[E]]       = fragment.query[E]
    inline def queryOne(using Trace): ScopedQuery[Option[E]] = fragment.queryOne[E]
end Metadata

object Metadata:
  val getByKey = "getByKey"
  transparent inline def apply[A <: Product] =
    Macros.metadataOf[A]

  transparent inline def apply[A <: Product](alias: String) =
    Macros.metadataOf[A](alias)

end Metadata

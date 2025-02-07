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
final case class Metadata[E <: Product: Table](tableName: String, columns: Seq[Column[?]], alias: Option[String])
    extends Selectable
    with Placeholder:
  val fieldNamesToLabels: Map[String, Column[?]] = columns.map(c => c.name -> c).toMap
  def selectDynamic(name: String)                = fieldNamesToLabels(name)
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
  override def sql: String           = alias.fold(tableName)(a => s"$tableName as $a")
  override def writes: Seq[Write[?]] = Seq.empty

  transparent inline def withAlias(alias: String) = copy(alias = Some(alias)).asInstanceOf[this.type]

  final private[saferis] class TypedFragment(val fragment: SqlFragment):
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

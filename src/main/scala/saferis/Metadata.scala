package saferis

/** Provides DDL information about a table.
  *
  * This class is derived via a macro. It can be derived from any Table[A] instance.
  *
  * @param tableName
  * @param fieldNamesToLabels
  * @param alias
  */
final case class Metadata(tableName: String, fieldNamesToLabels: Map[String, String], alias: Option[String])
    extends Selectable
    with Placeholder[Unit]:
  private def getLabel(fieldName: String): String =
    val raw = fieldNamesToLabels.getOrElse(fieldName, fieldName)
    alias.map(a => s"$a.$raw").getOrElse(raw)
  def selectDynamic(name: String)                 = RawSql(getLabel(name))
  def sql: String                                 = alias.fold(tableName)(a => s"$tableName as $a")
  transparent inline def withAlias(alias: String) = copy(alias = Some(alias)).asInstanceOf[this.type]
end Metadata

object Metadata:
  transparent inline def apply[A <: Product] =
    Macros.metadataOf[A]

  transparent inline def apply[A <: Product](alias: String) =
    Macros.metadataOf[A](alias)

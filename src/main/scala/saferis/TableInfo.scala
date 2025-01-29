package saferis

trait TableMetadata[A <: Product: Table as table] extends Selectable:
  def name: String            = table.name
  def columns: Seq[Column[?]] = table.columns
  def alias: Option[String]   = None

  def selectDynamic(name: String): String =
    alias.fold(name)(a => s"$a.$name")

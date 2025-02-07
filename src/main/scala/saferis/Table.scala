package saferis

import scala.annotation.StaticAnnotation

final case class tableName(name: String) extends StaticAnnotation

sealed trait Table[A <: Product]:
  private[saferis] def name: String
  def columns: Seq[Column[?]]
  override def toString(): String =
    s"Table($name, ${columns.mkString(", ")})"
  private[saferis] def columnMap = columns.map(c => c.name -> c.label).toMap

object Table:
  def apply[A <: Product: Table as table]: Table[A] = table

  final case class Derived[A <: Product](name: String, columns: Seq[Column[?]]) extends Table[A]

  inline def derived[A <: Product]: Table[A] =
    Derived[A](Macros.nameOf[A], Macros.columnsOf[A])

  extension [A <: Product](a: A)(using table: Table[A]) def toTable: Table[A] = table

  extension [A <: Product](a: Table[A])
    transparent inline def metadata                = Macros.metadataOf[A]
    transparent inline def metadata(alias: String) = Macros.metadataOf[A](alias)

end Table

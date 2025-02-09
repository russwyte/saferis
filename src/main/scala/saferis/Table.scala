package saferis

import scala.annotation.StaticAnnotation

export saferis.Table.values

final case class tableName(name: String) extends StaticAnnotation

sealed trait Table[A <: Product]:
  private[saferis] def name: String
  def columns: Seq[Column[?]]
  private[saferis] def columnMap = columns.map(c => c.name -> c.label).toMap

object Table:
  transparent inline def apply[A <: Product: Table]                = Macros.instanceOf[A](alias = None)
  transparent inline def apply(alias: String)[A <: Product: Table] = Macros.instanceOf[A](alias = Some(alias))

  final case class Derived[A <: Product](name: String, columns: Seq[Column[?]]) extends Table[A]

  inline def derived[A <: Product]: Table[A] =
    Derived[A](Macros.nameOf[A], Macros.columnsOf[A])
  extension [A <: Product: Table as table](a: A)
    inline def values =
      println(s"valuesOf: $a")
      Macros.valuesOf(a)

end Table

package saferis

import scala.annotation.StaticAnnotation

final case class tableName(name: String) extends StaticAnnotation

sealed trait Table[A <: Product]:
  private[saferis] def name: String
  def columns: Seq[Column[?]]
  private[saferis] def columnMap                               = columns.map(c => c.name -> c).toMap
  transparent inline def instance                              = Macros.instanceOf[A](alias = None)
  transparent inline def aliasedInstance(inline alias: String) =
    val _ = Alias(alias) // Compile-time validation that alias is a string literal
    Macros.instanceOf[A](alias = Some(alias))
  private[saferis] def insertColumnsSql: SqlFragment =
    SqlFragment(columns.filterNot(_.isGenerated).map(_.sql).mkString("(", ", ", ")"), Seq.empty)
  private[saferis] def returningColumnsSql: SqlFragment =
    SqlFragment(columns.map(_.sql).mkString(", "), Seq.empty)
  private[saferis] inline def insertPlaceholders(a: A): Seq[Placeholder] =
    Macros
      .columnPlaceholders(a)
      .filterNot: (name, _) =>
        columnMap(name).isGenerated
      .map: (_, p) =>
        p
  private[saferis] inline def insertPlaceholdersSql(a: A): SqlFragment =
    val placeholders = insertPlaceholders(a)
    SqlFragment(placeholders.map(_.sql).mkString("(", ", ", ")"), placeholders.flatMap(_.writes))

  private[saferis] inline def updateSetClause(a: A): SqlFragment =
    val placeholders = Macros
      .columnPlaceholders(a)
      .filterNot: (name, _) =>
        val col = columnMap(name)
        col.isGenerated || col.isKey
    val setClauses = placeholders.map: (name, placeholder) =>
      val column = columnMap(name)
      SqlFragment(s"${column.sql} = ${placeholder.sql}", placeholder.writes)
    SqlFragment(setClauses.map(_.sql).mkString(", "), setClauses.flatMap(_.writes))
  end updateSetClause

  private[saferis] inline def updateWhereClause(a: A): SqlFragment =
    val keyPlaceholders = Macros
      .columnPlaceholders(a)
      .filter: (name, _) =>
        columnMap(name).isKey
    val whereClauses = keyPlaceholders.map: (name, placeholder) =>
      val column = columnMap(name)
      SqlFragment(s"${column.sql} = ${placeholder.sql}", placeholder.writes)
    if whereClauses.nonEmpty then
      SqlFragment(s" where ${whereClauses.map(_.sql).mkString(" and ")}", whereClauses.flatMap(_.writes))
    else SqlFragment("", Seq.empty)
  end updateWhereClause

end Table

object Table:
  transparent inline def apply[A <: Product: Table](using table: Table[A])                       = table.instance
  transparent inline def apply[A <: Product: Table](inline alias: String)(using table: Table[A]) =
    table.aliasedInstance(alias)

  final case class Derived[A <: Product](name: String, columns: Seq[Column[?]]) extends Table[A]

  inline def derived[A <: Product]: Table[A] =
    Derived[A](Macros.nameOf[A], Macros.columnsOf[A])

end Table

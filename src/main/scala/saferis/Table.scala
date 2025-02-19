package saferis

import scala.annotation.StaticAnnotation
import zio.Scope
import zio.ZIO
import zio.Trace

final case class tableName(name: String) extends StaticAnnotation

sealed trait Table[A <: Product]:
  private[saferis] def name: String
  def columns: Seq[Column[?]]
  private[saferis] def columnMap                 = columns.map(c => c.name -> c).toMap
  transparent inline def instance                = Macros.instanceOf[A](alias = None)
  transparent inline def instance(alias: String) = Macros.instanceOf[A](alias = Some(alias))
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

end Table

object Table:
  transparent inline def apply[A <: Product: Table as table]                = table.instance
  transparent inline def apply(alias: String)[A <: Product: Table as table] = table.instance(alias)

  final case class Derived[A <: Product](name: String, columns: Seq[Column[?]]) extends Table[A]

  inline def derived[A <: Product]: Table[A] =
    Derived[A](Macros.nameOf[A], Macros.columnsOf[A])

end Table

inline def insert[A <: Product: Table as table](a: A)(using Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  (sql"insert into ${table.instance} values " :+ table.insertPlaceholdersSql(a)).insert
end insert

inline def insertReturning[A <: Product: Table as table](a: A)(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, A] =
  val sql = sql"insert into ${table.instance}${table.insertColumnsSql} values " :+ table.insertPlaceholdersSql(
    a
  ) :+ sql" returning ${table.returningColumnsSql}"
  for
    o <- sql.queryOne[A]
    a <- ZIO.fromOption(o).orElseFail(new java.sql.SQLException("insert returning failed"))
  yield a
end insertReturning

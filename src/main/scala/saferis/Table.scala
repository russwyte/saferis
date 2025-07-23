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
  def indexedColumns: Seq[Column[?]]             = columns.filter(_.isIndexed)
  def uniqueIndexColumns: Seq[Column[?]]         = columns.filter(_.isUniqueIndex)
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
  transparent inline def apply[A <: Product: Table as table]                = table.instance
  transparent inline def apply(alias: String)[A <: Product: Table as table] = table.instance(alias)

  final case class Derived[A <: Product](name: String, columns: Seq[Column[?]]) extends Table[A]

  inline def derived[A <: Product]: Table[A] =
    Derived[A](Macros.nameOf[A], Macros.columnsOf[A])

end Table

inline def insert[A <: Product: Table as table](a: A)(using Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  (sql"insert into ${table.instance}${table.insertColumnsSql} values " :+ table.insertPlaceholdersSql(a)).insert
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

inline def update[A <: Product: Table as table](a: A)(using Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  val sql = sql"update ${table.instance} set " :+ table.updateSetClause(a) :+ table.updateWhereClause(a)
  sql.update

inline def updateWhere[A <: Product: Table as table](a: A, whereClause: SqlFragment)(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  val sql = sql"update ${table.instance} set " :+ table.updateSetClause(a) :+ sql" where " :+ whereClause
  sql.update
end updateWhere

inline def updateReturning[A <: Product: Table as table](a: A)(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, A] =
  val sql = sql"update ${table.instance} set " :+ table.updateSetClause(a) :+ table.updateWhereClause(
    a
  ) :+ sql" returning ${table.returningColumnsSql}"
  for
    o <- sql.queryOne[A]
    a <- ZIO.fromOption(o).orElseFail(new java.sql.SQLException("update returning failed"))
  yield a
end updateReturning

inline def updateWhereReturning[A <: Product: Table as table](a: A, whereClause: SqlFragment)(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, A] =
  val sql = sql"update ${table.instance} set " :+ table.updateSetClause(
    a
  ) :+ sql" where " :+ whereClause :+ sql" returning ${table.returningColumnsSql}"
  for
    o <- sql.queryOne[A]
    a <- ZIO.fromOption(o).orElseFail(new java.sql.SQLException("update returning failed"))
  yield a
end updateWhereReturning

inline def delete[A <: Product: Table as table](a: A)(using Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  val sql = sql"delete from ${table.instance}" :+ table.updateWhereClause(a)
  sql.delete

inline def deleteWhere[A <: Product: Table as table](whereClause: SqlFragment)(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  val sql = sql"delete from ${table.instance} where " :+ whereClause
  sql.delete
end deleteWhere

inline def deleteReturning[A <: Product: Table as table](a: A)(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, A] =
  val sql = sql"delete from ${table.instance}" :+ table.updateWhereClause(
    a
  ) :+ sql" returning ${table.returningColumnsSql}"
  for
    o <- sql.queryOne[A]
    a <- ZIO.fromOption(o).orElseFail(new java.sql.SQLException("delete returning failed"))
  yield a
end deleteReturning

inline def deleteWhereReturning[A <: Product: Table as table](whereClause: SqlFragment)(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, Seq[A]] =
  val sql = sql"delete from ${table.instance} where " :+ whereClause :+ sql" returning ${table.returningColumnsSql}"
  sql.query[A]
end deleteWhereReturning

// DDL Operations

inline def createTable[A <: Product: Table as table](ifNotExists: Boolean = false)(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  val ifNotExistsClause = if ifNotExists then " if not exists" else ""
  val columnDefs = table.columns.map { col =>
    val baseType = sqlTypeFromColumn(col)
    val constraints =
      if col.isKey && col.isGenerated then " generated always as identity primary key"
      else if col.isKey then " primary key"
      else ""
    s"${col.label} $baseType$constraints"
  }
  val tableName = table.name
  val sql       = SqlFragment(s"create table$ifNotExistsClause $tableName (${columnDefs.mkString(", ")})", Seq.empty)
  sql.dml
end createTable

private def sqlTypeFromColumn[R](col: Column[R]): String = col.postgresType

inline def dropTable[A <: Product: Table as table](ifExists: Boolean = false)(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  val ifExistsClause = if ifExists then " if exists" else ""
  val tableName      = table.name
  val sql            = SqlFragment(s"drop table$ifExistsClause $tableName", Seq.empty)
  sql.dml

inline def truncateTable[A <: Product: Table as table]()(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  val tableName = table.name
  val sql       = SqlFragment(s"truncate table $tableName", Seq.empty)
  sql.dml

inline def addColumn[A <: Product: Table as table](columnName: String, columnType: String)(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  val tableName = table.name
  val sql       = SqlFragment(s"alter table $tableName add column $columnName $columnType", Seq.empty)
  sql.dml

inline def dropColumn[A <: Product: Table as table](columnName: String)(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  val tableName = table.name
  val sql       = SqlFragment(s"alter table $tableName drop column $columnName", Seq.empty)
  sql.dml

inline def createIndex[A <: Product: Table as table](
    indexName: String,
    columnNames: Seq[String],
    unique: Boolean = false,
)(using Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  val uniqueClause = if unique then "unique " else ""
  val tableName    = table.name
  val sql =
    SqlFragment(s"create ${uniqueClause}index $indexName on $tableName (${columnNames.mkString(", ")})", Seq.empty)
  sql.dml
end createIndex

inline def createIndexesSql[A <: Product: Table as table](): String =
  val tableName = table.name

  // Create regular indexes for @indexed fields
  val indexedIndexes = table.indexedColumns.map { col =>
    val indexName = s"idx_${tableName}_${col.label}"
    s"CREATE INDEX $indexName ON $tableName (${col.label})"
  }

  // Create unique indexes for @uniqueIndex fields
  val uniqueIndexes = table.uniqueIndexColumns.map { col =>
    val indexName = s"idx_${tableName}_${col.label}"
    s"CREATE UNIQUE INDEX $indexName ON $tableName (${col.label})"
  }

  (indexedIndexes ++ uniqueIndexes).mkString("\n")
end createIndexesSql

inline def createIndexes[A <: Product: Table as table]()(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, Seq[Int]] =
  val tableName = table.name

  // Create regular indexes for @indexed fields
  val indexedIndexes = table.indexedColumns.map { col =>
    val indexName = s"idx_${tableName}_${col.label}"
    val sql       = SqlFragment(s"create index $indexName on $tableName (${col.label})", Seq.empty)
    sql.dml
  }

  // Create unique indexes for @uniqueIndex fields
  val uniqueIndexes = table.uniqueIndexColumns.map { col =>
    val indexName = s"idx_${tableName}_${col.label}"
    val sql       = SqlFragment(s"create unique index $indexName on $tableName (${col.label})", Seq.empty)
    sql.dml
  }

  ZIO.collectAll(indexedIndexes ++ uniqueIndexes)
end createIndexes

inline def dropIndex(indexName: String)(using
    Trace
): ZIO[ConnectionProvider & Scope, Throwable, Int] =
  val sql = SqlFragment(s"drop index $indexName", Seq.empty)
  sql.dml

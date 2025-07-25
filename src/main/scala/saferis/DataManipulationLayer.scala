package saferis

import zio.Scope
import zio.ZIO
import zio.Trace

val dml = DataManipulationLayer

object DataManipulationLayer:

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

end DataManipulationLayer

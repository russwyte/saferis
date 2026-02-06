package saferis

import zio.Scope
import zio.Trace
import zio.ZIO

val dml = DataManipulationLayer

object DataManipulationLayer:
  inline def insert[A <: Product](a: A)(using
      table: Table[A]
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Int] =
    (sql"insert into ${table.instance} ${table.insertColumnsSql} values " :+ table.insertPlaceholdersSql(a)).insert
  end insert

  inline def insertReturning[A <: Product](a: A)(using
      table: Table[A]
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, A] =
    val sql = sql"insert into ${table.instance}${table.insertColumnsSql} values " :+ table.insertPlaceholdersSql(
      a
    ) :+ sql" returning ${table.returningColumnsSql}"
    for
      o <- sql.queryOne[A]
      a <- ZIO.fromOption(o).orElseFail(SaferisError.ReturningOperationFailed("insert", table.name))
    yield a
  end insertReturning

  inline def update[A <: Product](a: A)(using
      table: Table[A]
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Int] =
    val sql = sql"update ${table.instance} set " :+ table.updateSetClause(a) :+ table.updateWhereClause(a)
    sql.update

  inline def updateWhere[A <: Product](a: A, whereClause: SqlFragment)(using
      table: Table[A]
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Int] =
    val sql = sql"update ${table.instance} set " :+ table.updateSetClause(a) :+ sql" where " :+ whereClause
    sql.update

  inline def updateReturning[A <: Product](a: A)(using
      table: Table[A]
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, A] =
    val sql = sql"update ${table.instance} set " :+ table.updateSetClause(a) :+ table.updateWhereClause(
      a
    ) :+ sql" returning ${table.returningColumnsSql}"
    for
      o <- sql.queryOne[A]
      a <- ZIO.fromOption(o).orElseFail(SaferisError.ReturningOperationFailed("update", table.name))
    yield a
  end updateReturning

  inline def updateWhereReturning[A <: Product](a: A, whereClause: SqlFragment)(using
      table: Table[A]
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, A] =
    val sql = sql"update ${table.instance} set " :+ table.updateSetClause(
      a
    ) :+ sql" where " :+ whereClause :+ sql" returning ${table.returningColumnsSql}"
    for
      o <- sql.queryOne[A]
      a <- ZIO.fromOption(o).orElseFail(SaferisError.ReturningOperationFailed("update", table.name))
    yield a
  end updateWhereReturning

  inline def delete[A <: Product](a: A)(using
      table: Table[A]
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Int] =
    val sql = sql"delete from ${table.instance}" :+ table.updateWhereClause(a)
    sql.delete

  inline def deleteWhere[A <: Product](whereClause: SqlFragment)(using
      table: Table[A]
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Int] =
    val sql = sql"delete from ${table.instance} where " :+ whereClause
    sql.delete

  inline def deleteReturning[A <: Product](a: A)(using
      table: Table[A]
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, A] =
    val sql = sql"delete from ${table.instance}" :+ table.updateWhereClause(
      a
    ) :+ sql" returning ${table.returningColumnsSql}"
    for
      o <- sql.queryOne[A]
      a <- ZIO.fromOption(o).orElseFail(SaferisError.ReturningOperationFailed("delete", table.name))
    yield a
  end deleteReturning

  inline def deleteWhereReturning[A <: Product](whereClause: SqlFragment)(using
      table: Table[A]
  )(using trace: Trace): ZIO[ConnectionProvider & Scope, SaferisError, Seq[A]] =
    val sql = sql"delete from ${table.instance} where " :+ whereClause :+ sql" returning ${table.returningColumnsSql}"
    sql.query[A]

end DataManipulationLayer

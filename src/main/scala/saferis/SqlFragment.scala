package saferis

import zio.*

import java.sql.PreparedStatement
import java.sql.ResultSet
import scala.collection.mutable as m

type ScopedQuery[E] = ZIO[ConnectionProvider & Scope, Throwable, E]

final case class SqlFragment(
    sql: String,
    override private[saferis] val writes: Seq[Write[?]],
) extends Placeholder:

  inline private def make[E <: Product: Table as table](rs: ResultSet)(using Trace): Task[E] =
    for cs <- ZIO.foreach(table.columns)(c => c.read(rs))
    yield (Macros.make[E](cs))
  end make

  private def doWrites(statement: PreparedStatement)(using Trace) = ZIO.foreach(writes.zipWithIndex): (write, idx) =>
    write.write(statement, idx + 1)

  inline def query[E <: Product: Table](using Trace): ScopedQuery[Seq[E]] =
    for
      connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
      statement  <- ZIO.attempt(connection.prepareStatement(sql))
      _          <- doWrites(statement)
      rs         <- ZIO.attempt(statement.executeQuery())
      results <-
        def loop(acc: m.Builder[E, Vector[E]]): ZIO[Any, Throwable, Vector[E]] =
          ZIO.attempt(rs.next()).flatMap { hasNext =>
            if hasNext then make[E](rs).flatMap(e => loop(acc.addOne(e)))
            else ZIO.succeed(acc.result)
          }
        loop(Vector.newBuilder[E])
    yield results
    end for
  end query

  inline def queryOne[E <: Product: Table](using Trace): ScopedQuery[Option[E]] =
    for
      connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
      statement  <- ZIO.attempt(connection.prepareStatement(sql))
      _          <- doWrites(statement)
      rs         <- ZIO.attempt(statement.executeQuery())
      result     <- if rs.next() then make[E](rs).map(Some(_)) else ZIO.succeed(None)
    yield result
  end queryOne

  /** alias for [[Statement.dml]]
    *
    * Executes the statement which must be an SQL Data Manipulation Language (DML) statement, such as INSERT, UPDATE or
    * DELETE or an SQL statement that returns nothing, such as a DDL statement.
    * @return
    */
  def update(using Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] = dml

  /** alias for [[Statement.dml]]
    *
    * Executes the statement which must be an SQL Data Manipulation Language (DML) statement, such as INSERT, UPDATE or
    * DELETE or an SQL statement that returns nothing, such as a DDL statement.
    * @return
    */
  def delete(using Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] = dml

  /** alias for [[Statement.dml]]
    *
    * Executes the statement which must be an SQL Data Manipulation Language (DML) statement, such as INSERT, UPDATE or
    * DELETE or an SQL statement that returns nothing, such as a DDL statement.
    * @return
    */
  def insert(using Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] = dml

  /** Executes the statement which must be an SQL Data Manipulation Language (DML) statement, such as INSERT, UPDATE or
    * DELETE or an SQL statement that returns nothing, such as a DDL statement.
    * @return
    */
  inline def dml(using Trace): ZIO[ConnectionProvider & Scope, Throwable, Int] =
    for
      connection <- ZIO.serviceWithZIO[ConnectionProvider](_.getConnection)
      statement  <- ZIO.attempt(connection.prepareStatement(sql))
      _ <- ZIO.foreach(writes.zipWithIndex): (write, idx) =>
        write.write(statement, idx + 1)
      result <- ZIO.attempt(statement.executeUpdate())
    yield result

  def stripMargin(marginChar: Char) = copy(sql = sql.stripMargin(marginChar))
  def stripMargin                   = copy(sql = sql.stripMargin)

  def append(other: SqlFragment) = copy(sql = sql + other.sql, writes = writes ++ other.writes)
  def :+(other: SqlFragment)     = append(other)

end SqlFragment

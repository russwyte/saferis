package saferis
import zio.*

import java.sql.PreparedStatement

trait StatementWriter[A]:
  /** Placeholder for the value in the SQL query
    *
    * for product types, this should be a comma separated list of placeholders
    *
    * e.g. for a case class with 2 fields, the placeholder should be `?, ?` or `(?, ?)` depending on the implementation
    *
    * @return
    */
  def placeholder: String = "?"

  /** Write the value to the prepared statement at the given index
    *
    * @param a
    * @param stmt
    * @param idx
    * @return
    */
  def write(a: A, stmt: PreparedStatement, idx: Int): Task[Unit]

  /** Construct a Write instance for the given value
    *
    * @param a
    * @return
    */
  def apply(a: A): Write[A] = Write(a)(using this)
end StatementWriter

object StatementWriter:
  given StatementWriter[String] with
    def write(a: String, stmt: PreparedStatement, idx: Int): Task[Unit] =
      ZIO.attempt(stmt.setString(idx, a))
  given StatementWriter[Short] with
    def write(a: Short, stmt: PreparedStatement, idx: Int): Task[Unit] =
      ZIO.attempt(stmt.setShort(idx, a))
  given StatementWriter[Int] with
    def write(a: Int, stmt: PreparedStatement, idx: Int): Task[Unit] =
      ZIO.attempt(stmt.setInt(idx, a))
  given StatementWriter[Long] with
    def write(a: Long, stmt: PreparedStatement, idx: Int): Task[Unit] =
      ZIO.attempt(stmt.setLong(idx, a))
  given StatementWriter[Boolean] with
    def write(a: Boolean, stmt: PreparedStatement, idx: Int): Task[Unit] =
      ZIO.attempt(stmt.setBoolean(idx, a))
  given StatementWriter[Float] with
    def write(a: Float, stmt: PreparedStatement, idx: Int): Task[Unit] =
      ZIO.attempt(stmt.setFloat(idx, a))
  given StatementWriter[Double] with
    def write(a: Double, stmt: PreparedStatement, idx: Int): Task[Unit] =
      ZIO.attempt(stmt.setDouble(idx, a))
  given StatementWriter[java.sql.Date] with
    def write(a: java.sql.Date, stmt: PreparedStatement, idx: Int): Task[Unit] =
      ZIO.attempt(stmt.setDate(idx, a))
end StatementWriter

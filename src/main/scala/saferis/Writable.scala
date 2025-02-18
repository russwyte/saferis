package saferis
import zio.*

import java.sql.PreparedStatement

trait Writable[A]:
  self =>

  /** Placeholder for the value in the SQL query
    *
    * for product types, this should be a comma separated list of placeholders
    *
    * e.g. for a case class with 2 fields, the placeholder should be `?, ?` or `(?, ?)` depending on the implementation
    *
    * @return
    */
  def placeholder(a: A): String = "?"

  /** Write the value to the prepared statement at the given index
    *
    * @param a
    * @param stmt
    * @param idx
    * @return
    */
  def write(a: A, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit]

  /** Construct a Write instance for the given value
    *
    * @param a
    * @return
    */
  def apply(a: A): Write[A] = Write(a)(using this)
  def transform[B](f: B => Task[A]): Writable[B] =
    new Writable[B]:
      def write(b: B, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
        f(b).flatMap(a => self.write(a, stmt, idx))
  def sqlType: Int = java.sql.Types.JAVA_OBJECT
end Writable

object Writable:
  given optionWriter[A: Writable as writer]: Writable[Option[A]] with
    def write(a: Option[A], stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      // todo: verify that this is correct
      a.fold(ZIO.attempt(stmt.setNull(idx, java.sql.Types.NULL))): a =>
        writer.write(a, stmt, idx)
  given Writable[String] with
    def write(a: String, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setString(idx, a)
  given Writable[Short] with
    def write(a: Short, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setShort(idx, a)
  given Writable[Int] with
    def write(a: Int, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setInt(idx, a)
  given Writable[Long] with
    def write(a: Long, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setLong(idx, a)
  given Writable[Boolean] with
    def write(a: Boolean, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setBoolean(idx, a)
  given Writable[Float] with
    def write(a: Float, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setFloat(idx, a)
  given Writable[Double] with
    def write(a: Double, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setDouble(idx, a)
  given Writable[java.sql.Date] with
    def write(a: java.sql.Date, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setDate(idx, a)
end Writable

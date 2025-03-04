package saferis
import zio.*

import java.sql.PreparedStatement

trait Encoder[A]:
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

  /** Encode the value to the prepared statement at the given index
    *
    * @param a
    * @param stmt
    * @param idx
    * @return
    */
  def encode(a: A, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit]

  /** Construct a Write instance for the given value
    *
    * @param a
    * @return
    */
  def apply(a: A): Write[A] = Write(a)(using this)

  /** transform am Encoder[A] to Encoder[B] by mapping the value of A to Task[B]
    *
    * @param f
    * @return
    */
  def transform[B](f: B => Task[A]): Encoder[B] =
    new Encoder[B]:
      def encode(b: B, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
        f(b).flatMap(a => self.encode(a, stmt, idx))
end Encoder

object Encoder:
  given option[A: Encoder as encoder]: Encoder[Option[A]] with
    def encode(a: Option[A], stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      // todo: verify that this is correct
      a.fold(ZIO.attempt(stmt.setNull(idx, java.sql.Types.NULL))): a =>
        encoder.encode(a, stmt, idx)
  given string: Encoder[String] with
    def encode(a: String, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setString(idx, a)
  given short: Encoder[Short] with
    def encode(a: Short, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setShort(idx, a)
  given int: Encoder[Int] with
    def encode(a: Int, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setInt(idx, a)
  given long: Encoder[Long] with
    def encode(a: Long, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setLong(idx, a)
  given boolean: Encoder[Boolean] with
    def encode(a: Boolean, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setBoolean(idx, a)
  given float: Encoder[Float] with
    def encode(a: Float, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setFloat(idx, a)
  given double: Encoder[Double] with
    def encode(a: Double, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setDouble(idx, a)
  given date: Encoder[java.sql.Date] with
    def encode(a: java.sql.Date, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setDate(idx, a)
  given bigDecimal: Encoder[BigDecimal] with
    def encode(a: BigDecimal, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setBigDecimal(idx, a.bigDecimal)
  given bigInt: Encoder[BigInt] with
    def encode(a: BigInt, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setBigDecimal(idx, BigDecimal(a).bigDecimal)
  given time: Encoder[java.sql.Time] with
    def encode(a: java.sql.Time, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setTime(idx, a)
  given timestamp: Encoder[java.sql.Timestamp] with
    def encode(a: java.sql.Timestamp, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setTimestamp(idx, a)
  given url: Encoder[java.net.URL] with
    def encode(a: java.net.URL, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setURL(idx, a)
end Encoder

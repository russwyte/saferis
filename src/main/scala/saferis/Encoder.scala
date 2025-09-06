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
  def apply(a: A): Write[A] = Write(a)(using self)

  /** transform am Encoder[A] to Encoder[B] by mapping the value of A to Task[B]
    *
    * @param f
    * @return
    */
  def transform[B](f: B => Task[A]): Encoder[B] =
    new Encoder[B]:
      def encode(b: B, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
        f(b).flatMap(a => self.encode(a, stmt, idx))
      val jdbcType: Int = self.jdbcType
  def jdbcType: Int
  def columnType(using dialect: Dialect = postgres.PostgresDialect): String =
    dialect.columnType(self)
  def literal(a: A): String =
    def escapedString(a: A): String =
      a.toString.replaceAll("'", "''")
    end escapedString
    import java.sql.Types
    jdbcType match
      case Types.VARCHAR | Types.LONGVARCHAR | Types.CHAR | Types.NVARCHAR | Types.LONGNVARCHAR | Types.NCHAR =>
        s"'${escapedString(a)}'"
      case Types.INTEGER | Types.BIGINT | Types.SMALLINT | Types.TINYINT | Types.FLOAT | Types.DOUBLE | Types.REAL |
          Types.NUMERIC | Types.DECIMAL =>
        a.toString
      case Types.BOOLEAN | Types.BIT =>
        a.toString.toLowerCase
      case Types.DATE =>
        s"DATE '${a.toString}'"
      case Types.TIME =>
        s"TIME '${escapedString(a)}'"
      case Types.TIMESTAMP =>
        s"TIMESTAMP '${escapedString(a)}'"
      case Types.DATALINK =>
        s"'${escapedString(a)}'"
      case _ =>
        s"'${escapedString(a)}'" // fallback for unknown types
    end match
  end literal

end Encoder

object Encoder:
  given option[A: Encoder as encoder]: Encoder[Option[A]] with
    def encode(a: Option[A], stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      a.fold(ZIO.attempt(stmt.setObject(idx, null, jdbcType))): a =>
        encoder.encode(a, stmt, idx)
    def jdbcType: Int = encoder.jdbcType
  given string: Encoder[String] with
    def encode(a: String, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setString(idx, a)
    val jdbcType: Int = java.sql.Types.VARCHAR
  given short: Encoder[Short] with
    def encode(a: Short, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setShort(idx, a)
    val jdbcType: Int = java.sql.Types.SMALLINT
  given int: Encoder[Int] with
    def encode(a: Int, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setInt(idx, a)
    val jdbcType: Int = java.sql.Types.INTEGER
  given long: Encoder[Long] with
    def encode(a: Long, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setLong(idx, a)
    val jdbcType: Int = java.sql.Types.BIGINT
  given boolean: Encoder[Boolean] with
    def encode(a: Boolean, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setBoolean(idx, a)
    val jdbcType: Int = java.sql.Types.BOOLEAN
  given float: Encoder[Float] with
    def encode(a: Float, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setFloat(idx, a)
    val jdbcType: Int = java.sql.Types.FLOAT
  given double: Encoder[Double] with
    def encode(a: Double, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setDouble(idx, a)
    val jdbcType: Int = java.sql.Types.DOUBLE
  given date: Encoder[java.sql.Date] with
    def encode(a: java.sql.Date, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setDate(idx, a)
    val jdbcType: Int = java.sql.Types.DATE
  given bigDecimal: Encoder[BigDecimal] with
    def encode(a: BigDecimal, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setBigDecimal(idx, a.bigDecimal)
    val jdbcType: Int = java.sql.Types.DECIMAL
  given bigInt: Encoder[BigInt] with
    def encode(a: BigInt, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setBigDecimal(idx, BigDecimal(a).bigDecimal)
    val jdbcType: Int = java.sql.Types.DECIMAL
  given time: Encoder[java.sql.Time] with
    def encode(a: java.sql.Time, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setTime(idx, a)
    val jdbcType: Int = java.sql.Types.TIME
  given timestamp: Encoder[java.sql.Timestamp] with
    def encode(a: java.sql.Timestamp, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setTimestamp(idx, a)
    val jdbcType: Int = java.sql.Types.TIMESTAMP
  given url: Encoder[java.net.URL] with
    def encode(a: java.net.URL, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        stmt.setURL(idx, a)
    val jdbcType: Int = java.sql.Types.DATALINK
end Encoder

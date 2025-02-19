package saferis

import zio.*

import java.sql.ResultSet

trait Decoder[A]:
  self =>

  /** map the value A to Task[B] after decoding
    * @param f
    * @return
    */
  def mapOrFail[B](f: A => Task[B]): Decoder[B] =
    new Decoder[B]:
      def decode(rs: ResultSet, name: String)(using Trace): Task[B] =
        self.decode(rs, name).flatMap(f)

  /** Decode a value from the result set
    *
    * @param rs
    * @param name
    * @return
    */
  def decode(rs: ResultSet, name: String)(using Trace): Task[A]
end Decoder

object Decoder:
  given option[A: Decoder as decoder]: Decoder[Option[A]] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Option[A]] =
      ZIO
        .attempt(rs.getObject(name))
        .flatMap: a =>
          if a == null then ZIO.succeed(None)
          else decoder.decode(rs, name).map(Some(_))
  given string: Decoder[String] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[String] =
      ZIO.attempt(rs.getString(name))
  given short: Decoder[Short] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Short] =
      ZIO.attempt(rs.getShort(name))
  given int: Decoder[Int] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Int] =
      ZIO.attempt(rs.getInt(name))
  given long: Decoder[Long] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Long] =
      ZIO.attempt(rs.getLong(name))
  given boolean: Decoder[Boolean] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Boolean] =
      ZIO.attempt(rs.getBoolean(name))
  given float: Decoder[Float] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Float] =
      ZIO.attempt(rs.getFloat(name))
  given double: Decoder[Double] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Double] =
      ZIO.attempt(rs.getDouble(name))
  given date: Decoder[java.sql.Date] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[java.sql.Date] =
      ZIO.attempt(rs.getDate(name))
end Decoder

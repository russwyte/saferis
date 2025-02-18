package saferis

import zio.*

import java.sql.ResultSet

trait Reader[A]:
  self =>
  def transform[B](f: A => Task[B]): Reader[B] =
    new Reader[B]:
      def read(rs: ResultSet, name: String)(using Trace): Task[B] =
        self.read(rs, name).flatMap(f)
  def read(rs: ResultSet, name: String)(using Trace): Task[A]

object Reader:
  given option[A: Reader as reader]: Reader[Option[A]] with
    def read(rs: ResultSet, name: String)(using Trace): Task[Option[A]] =
      ZIO
        .attempt(rs.getObject(name))
        .flatMap: a =>
          if a == null then ZIO.succeed(None)
          else reader.read(rs, name).map(Some(_))
  given string: Reader[String] with
    def read(rs: ResultSet, name: String)(using Trace): Task[String] =
      ZIO.attempt(rs.getString(name))
  given short: Reader[Short] with
    def read(rs: ResultSet, name: String)(using Trace): Task[Short] =
      ZIO.attempt(rs.getShort(name))
  given int: Reader[Int] with
    def read(rs: ResultSet, name: String)(using Trace): Task[Int] =
      ZIO.attempt(rs.getInt(name))
  given long: Reader[Long] with
    def read(rs: ResultSet, name: String)(using Trace): Task[Long] =
      ZIO.attempt(rs.getLong(name))
  given boolean: Reader[Boolean] with
    def read(rs: ResultSet, name: String)(using Trace): Task[Boolean] =
      ZIO.attempt(rs.getBoolean(name))
  given float: Reader[Float] with
    def read(rs: ResultSet, name: String)(using Trace): Task[Float] =
      ZIO.attempt(rs.getFloat(name))
  given double: Reader[Double] with
    def read(rs: ResultSet, name: String)(using Trace): Task[Double] =
      ZIO.attempt(rs.getDouble(name))
  given date: Reader[java.sql.Date] with
    def read(rs: ResultSet, name: String)(using Trace): Task[java.sql.Date] =
      ZIO.attempt(rs.getDate(name))
end Reader

package saferis
import zio.*

trait Codec[A]:
  self =>
  def encode(a: A, stmt: java.sql.PreparedStatement, idx: Int)(using Trace): zio.Task[Unit]
  def decode(rs: java.sql.ResultSet, name: String)(using Trace): zio.Task[A]
  def transform[B](map: A => Task[B])(contramap: B => Task[A]): Codec[B] =
    new Codec[B]:
      def encode(b: B, stmt: java.sql.PreparedStatement, idx: Int)(using Trace): zio.Task[Unit] =
        contramap(b).flatMap(a => self.encode(a, stmt, idx))
      def decode(rs: java.sql.ResultSet, name: String)(using Trace): zio.Task[B] =
        self.decode(rs, name).flatMap(map)
end Codec
object Codec:
  def apply[A: Encoder as encoder: Decoder as decoder]: Codec[A] = codec[A]
  val string                                                     = Codec[String]
  val short                                                      = Codec[Short]
  val int                                                        = Codec[Int]
  val long                                                       = Codec[Long]
  val boolean                                                    = Codec[Boolean]
  val float                                                      = Codec[Float]
  val double                                                     = Codec[Double]
  val date                                                       = Codec[java.sql.Date]
  given codec[A: Encoder as encoder: Decoder as decoder]: Codec[A] with
    def encode(a: A, stmt: java.sql.PreparedStatement, idx: Int)(using Trace): zio.Task[Unit] =
      encoder.encode(a, stmt, idx)
    def decode(rs: java.sql.ResultSet, name: String)(using Trace): zio.Task[A] =
      decoder.decode(rs, name)
  given option[A: Codec as codec]: Codec[Option[A]] with
    def encode(a: Option[A], stmt: java.sql.PreparedStatement, idx: Int)(using Trace): zio.Task[Unit] =
      a.fold(zio.ZIO.attempt(stmt.setNull(idx, java.sql.Types.NULL))): a =>
        codec.encode(a, stmt, idx)
    def decode(rs: java.sql.ResultSet, name: String)(using Trace): zio.Task[Option[A]] =
      ZIO
        .attempt(rs.getObject(name))
        .flatMap: a =>
          if a == null then ZIO.succeed(None)
          else codec.decode(rs, name).map(Some(_))
  end option
end Codec

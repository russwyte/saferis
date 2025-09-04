package saferis
import zio.*

trait Codec[A] extends Encoder[A], Decoder[A]:
  self =>
  val encoder: Encoder[A]
  val decoder: Decoder[A]
  def encode(a: A, stmt: java.sql.PreparedStatement, idx: Int)(using Trace): zio.Task[Unit] =
    encoder.encode(a, stmt, idx)
  def decode(rs: java.sql.ResultSet, name: String)(using Trace): zio.Task[A] =
    decoder.decode(rs, name)
  def transform[B](map: A => Task[B])(contramap: B => Task[A]): Codec[B] =
    new Codec[B]:
      val encoder: Encoder[B] = self.encoder.transform(contramap)
      val decoder: Decoder[B] = self.decoder.transform(map)
      val jdbcType: Int       = self.jdbcType
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
  val bigDecimal                                                 = Codec[BigDecimal]
  val bigInt                                                     = Codec[BigInt]
  val time                                                       = Codec[java.sql.Time]
  val timestamp                                                  = Codec[java.sql.Timestamp]
  val url                                                        = Codec[java.net.URL]

  // given encoder[A: Codec as codec]: Encoder[A] = codec.encoder
  // given decoder[A: Codec as codec]: Decoder[A] = codec.decoder

  given codec[A: Encoder as enc: Decoder as dec]: Codec[A] with
    val encoder: Encoder[A] = enc
    val decoder: Decoder[A] = dec
    val jdbcType: Int       = enc.jdbcType

  given option[A: Codec as codec]: Codec[Option[A]] with
    val encoder: Encoder[Option[A]] = Encoder.option[A]
    val decoder: Decoder[Option[A]] = Decoder.option[A]
    val jdbcType: Int               = codec.jdbcType
end Codec

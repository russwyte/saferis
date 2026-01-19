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
      val encoder: Encoder[B]    = self.encoder.transform(contramap)
      val decoder: Decoder[B]    = self.decoder.transform(map)
      override val jdbcType: Int = self.jdbcType
end Codec
object Codec:
  def apply[A: Encoder as encoder: Decoder as decoder]: Codec[A] = codec[A]
  given string: Codec[String]                                    = Codec[String]
  given short: Codec[Short]                                      = Codec[Short]
  given int: Codec[Int]                                          = Codec[Int]
  given long: Codec[Long]                                        = Codec[Long]
  given boolean: Codec[Boolean]                                  = Codec[Boolean]
  given float: Codec[Float]                                      = Codec[Float]
  given double: Codec[Double]                                    = Codec[Double]
  given date: Codec[java.sql.Date]                               = Codec[java.sql.Date]
  given bigDecimal: Codec[BigDecimal]                            = Codec[BigDecimal]
  given bigInt: Codec[BigInt]                                    = Codec[BigInt]
  given time: Codec[java.sql.Time]                               = Codec[java.sql.Time]
  given timestamp: Codec[java.sql.Timestamp]                     = Codec[java.sql.Timestamp]
  given url: Codec[java.net.URL]                                 = Codec[java.net.URL]

  // Java time API codecs
  given instant: Codec[java.time.Instant]               = Codec[java.time.Instant]
  given localDateTime: Codec[java.time.LocalDateTime]   = Codec[java.time.LocalDateTime]
  given localDate: Codec[java.time.LocalDate]           = Codec[java.time.LocalDate]
  given localTime: Codec[java.time.LocalTime]           = Codec[java.time.LocalTime]
  given zonedDateTime: Codec[java.time.ZonedDateTime]   = Codec[java.time.ZonedDateTime]
  given offsetDateTime: Codec[java.time.OffsetDateTime] = Codec[java.time.OffsetDateTime]

  // given encoder[A: Codec as codec]: Encoder[A] = codec.encoder
  // given decoder[A: Codec as codec]: Decoder[A] = codec.decoder

  given codec[A: Encoder as enc: Decoder as dec]: Codec[A] with
    val encoder: Encoder[A]    = enc
    val decoder: Decoder[A]    = dec
    override val jdbcType: Int = enc.jdbcType

  given option[A: Codec as codec]: Codec[Option[A]] with
    val encoder: Encoder[Option[A]] = Encoder.option[A]
    val decoder: Decoder[Option[A]] = Decoder.option[A]
    override val jdbcType: Int      = codec.jdbcType

  /** Default UUID codec from PostgreSQL dialect - provided as a low priority given. This allows users to work with
    * UUIDs out of the box with just `import saferis.*` Users can override this by importing dialect-specific codecs
    * (e.g., `import saferis.mysql.{given}`)
    */
  given defaultUuidCodec: Codec[java.util.UUID] =
    codec[java.util.UUID](using Encoder.defaultUuidEncoder, Decoder.defaultUuidDecoder)

end Codec

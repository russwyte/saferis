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
  private def make[A](enc: Encoder[A], dec: Decoder[A]): Codec[A] = new Codec[A]:
    val encoder: Encoder[A]    = enc
    val decoder: Decoder[A]    = dec
    override val jdbcType: Int = enc.jdbcType

  def apply[A](using encoder: Encoder[A], decoder: Decoder[A]): Codec[A] = make(encoder, decoder)

  given string: Codec[String]                = make(Encoder.string, Decoder.string)
  given short: Codec[Short]                  = make(Encoder.short, Decoder.short)
  given int: Codec[Int]                      = make(Encoder.int, Decoder.int)
  given long: Codec[Long]                    = make(Encoder.long, Decoder.long)
  given boolean: Codec[Boolean]              = make(Encoder.boolean, Decoder.boolean)
  given float: Codec[Float]                  = make(Encoder.float, Decoder.float)
  given double: Codec[Double]                = make(Encoder.double, Decoder.double)
  given date: Codec[java.sql.Date]           = make(Encoder.date, Decoder.date)
  given bigDecimal: Codec[BigDecimal]        = make(Encoder.bigDecimal, Decoder.bigDecimal)
  given bigInt: Codec[BigInt]                = make(Encoder.bigInt, Decoder.bigInt)
  given time: Codec[java.sql.Time]           = make(Encoder.time, Decoder.time)
  given timestamp: Codec[java.sql.Timestamp] = make(Encoder.timestamp, Decoder.timestamp)
  given url: Codec[java.net.URL]             = make(Encoder.url, Decoder.url)

  // Java time API codecs
  given instant: Codec[java.time.Instant]               = make(Encoder.instant, Decoder.instant)
  given localDateTime: Codec[java.time.LocalDateTime]   = make(Encoder.localDateTime, Decoder.localDateTime)
  given localDate: Codec[java.time.LocalDate]           = make(Encoder.localDate, Decoder.localDate)
  given localTime: Codec[java.time.LocalTime]           = make(Encoder.localTime, Decoder.localTime)
  given zonedDateTime: Codec[java.time.ZonedDateTime]   = make(Encoder.zonedDateTime, Decoder.zonedDateTime)
  given offsetDateTime: Codec[java.time.OffsetDateTime] = make(Encoder.offsetDateTime, Decoder.offsetDateTime)

  given codec[A](using enc: Encoder[A], dec: Decoder[A]): Codec[A] with
    val encoder: Encoder[A]    = enc
    val decoder: Decoder[A]    = dec
    override val jdbcType: Int = enc.jdbcType

  given option[A](using c: Codec[A]): Codec[Option[A]] with
    val encoder: Encoder[Option[A]] = Encoder.option[A](using c.encoder)
    val decoder: Decoder[Option[A]] = Decoder.option[A](using c.decoder)
    override val jdbcType: Int      = c.jdbcType

  /** Default UUID codec from PostgreSQL dialect - provided as a low priority given. This allows users to work with
    * UUIDs out of the box with just `import saferis.*` Users can override this by importing dialect-specific codecs
    * (e.g., `import saferis.mysql.{given}`)
    */
  given defaultUuidCodec: Codec[java.util.UUID] =
    make(Encoder.defaultUuidEncoder, Decoder.defaultUuidDecoder)

end Codec

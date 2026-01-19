package saferis

import zio.*
import java.sql.{PreparedStatement, ResultSet}

/** An opaque type for unbounded text columns (maps to TEXT in PostgreSQL, LONGTEXT in MySQL, etc.)
  *
  * Use this instead of String when you need TEXT column type rather than VARCHAR(255).
  *
  * Example:
  * {{{
  *   case class Article(
  *     @key id: Int,
  *     title: String,          // VARCHAR(255)
  *     content: Text           // TEXT
  *   ) derives Table
  * }}}
  */
opaque type Text = String

object Text:
  /** Create a Text value from a String */
  def apply(value: String): Text = value

  /** Extension method to get the underlying String value */
  extension (t: Text) def value: String = t

  /** Encoder for Text type - uses LONGVARCHAR which maps to TEXT */
  given encoder: Encoder[Text] with
    def encode(a: Text, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt(stmt.setString(idx, a))
    override val jdbcType: Int = java.sql.Types.LONGVARCHAR

  /** Decoder for Text type */
  given decoder: Decoder[Text] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Text] =
      ZIO.attempt(rs.getString(name))

  /** Codec for Text type */
  given codec: Codec[Text] = Codec[Text]

  /** Option encoder for Text */
  given optionEncoder: Encoder[Option[Text]] = Encoder.option[Text]

  /** Option decoder for Text */
  given optionDecoder: Decoder[Option[Text]] = Decoder.option[Text]

  /** Option codec for Text */
  given optionCodec: Codec[Option[Text]] = Codec.option[Text]
end Text

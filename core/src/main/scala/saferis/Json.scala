package saferis

import zio.*
import zio.json.*

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/** A wrapper type for storing values as JSON in the database (JSONB in PostgreSQL, JSON in MySQL, etc.)
  *
  * Use this to wrap any type that has a JsonCodec to store it as JSON.
  *
  * Example:
  * {{{
  *   case class Metadata(tags: List[String], version: Int) derives JsonCodec
  *
  *   case class Event(
  *     @key id: Int,
  *     name: String,
  *     metadata: Json[Metadata]  // Stored as JSONB in PostgreSQL
  *   ) derives Table
  * }}}
  */
opaque type Json[A] = A

object Json:
  /** Wrap a value to be stored as JSON */
  def apply[A](value: A)(using @scala.annotation.unused codec: JsonCodec[A]): Json[A] = value

  /** Extension method to get the underlying value */
  extension [A](json: Json[A]) def value(using @scala.annotation.unused codec: JsonCodec[A]): A = json

  /** Encoder for Json[A] - uses Types.OTHER which maps to jsonb in PostgreSQL */
  given encoder[A: JsonCodec]: Encoder[Json[A]] with
    override val jdbcType: Int                                                         = java.sql.Types.OTHER
    def encode(a: Json[A], stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
      ZIO.attempt:
        val jsonString = summon[JsonCodec[A]].encoder.encodeJson(a, None).toString
        stmt.setObject(idx, jsonString, jdbcType)

  /** Decoder for Json[A] - reads JSON string and decodes using JsonCodec */
  given decoder[A: JsonCodec]: Decoder[Json[A]] with
    def decode(rs: ResultSet, name: String)(using Trace): Task[Json[A]] =
      ZIO
        .attempt(rs.getString(name))
        .flatMap { jsonString =>
          if jsonString == null then ZIO.fail(new SQLException(s"Column $name is null"))
          else
            ZIO
              .fromEither(summon[JsonCodec[A]].decoder.decodeJson(jsonString))
              .mapError(e => new SQLException(s"Failed to decode JSON for column $name: $e"))
        }
  end decoder

  /** Codec for Json[A] */
  given codec[A: JsonCodec]: Codec[Json[A]] = Codec[Json[A]]

  /** Option encoder for Json[A] */
  given optionEncoder[A: JsonCodec]: Encoder[Option[Json[A]]] = Encoder.option[Json[A]]

  /** Option decoder for Json[A] */
  given optionDecoder[A: JsonCodec]: Decoder[Option[Json[A]]] = Decoder.option[Json[A]]

  /** Option codec for Json[A] */
  given optionCodec[A: JsonCodec]: Codec[Option[Json[A]]] = Codec.option[Json[A]]
end Json

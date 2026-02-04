package saferis.mysql

import saferis.*
import zio.*

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/** MySQL-specific codecs for types that require different handling than PostgreSQL.
  *
  * Import these with `import saferis.mysql.{given}` to override the default PostgreSQL codecs.
  */

/** UUID encoder for MySQL - stores UUIDs as CHAR(36) strings.
  *
  * MySQL doesn't have a native UUID type, so we store them as fixed-length CHAR(36) strings in the standard UUID format
  * (e.g., "550e8400-e29b-41d4-a716-446655440000").
  */
given uuidEncoder: Encoder[UUID] with
  override val jdbcType = java.sql.Types.CHAR

  def encode(uuid: UUID, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
    ZIO.attempt(stmt.setString(idx, uuid.toString))

  override def columnType(using dialect: Dialect): String = "char(36)"

  override def literal(uuid: UUID): String =
    s"'${uuid.toString}'"
end uuidEncoder

/** UUID decoder for MySQL - reads UUIDs from CHAR(36) string columns.
  */
given uuidDecoder: Decoder[UUID] with
  def decode(rs: ResultSet, name: String)(using Trace): Task[UUID] =
    ZIO.attempt:
      val str = rs.getString(name)
      if str == null then null
      else UUID.fromString(str)

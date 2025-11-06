package saferis.postgres

import saferis.*
import zio.*
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/** PostgreSQL-specific codecs for types that have native database support.
  *
  * These are automatically available when using `import saferis.*` since PostgreSQL is the default dialect.
  */

/** UUID encoder for PostgreSQL - uses native UUID type.
  *
  * PostgreSQL has native UUID support, so we can pass the UUID object directly and let the JDBC driver handle the
  * conversion.
  */
given uuidEncoder: Encoder[UUID] with
  override val jdbcType: Int = java.sql.Types.OTHER

  def encode(uuid: UUID, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
    ZIO.attempt:
      // Pass jdbcType hint so PostgreSQL JDBC driver knows the target SQL type
      stmt.setObject(idx, uuid, jdbcType)

  override def columnType(using dialect: Dialect): String = "uuid"
end uuidEncoder

/** UUID decoder for PostgreSQL - reads from native UUID columns.
  */
given uuidDecoder: Decoder[UUID] with
  def decode(rs: ResultSet, name: String)(using Trace): Task[UUID] =
    ZIO.attempt:
      rs.getObject(name, classOf[UUID])

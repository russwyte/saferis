package saferis

import zio.*

import java.sql.PreparedStatement

/** A class that can write a value to a prepared statement.
  *
  * @param a
  * @param writer
  */
final class Write[A: Encoder as encoder](a: A):

  /** Writes the value to the prepared statement using the encoder
    *
    * @param stmt
    * @param idx
    * @return
    */
  def write(stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
    encoder.encode(a, stmt, idx)
end Write

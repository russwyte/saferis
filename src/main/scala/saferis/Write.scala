package saferis

import zio.*

import java.sql.PreparedStatement

/** A class that can write a value to a prepared statement It is used as a wrapper around a value to write it to a
  * prepared statement The sql interpolation will generate a `Write` instance for each value
  *
  * @param a
  * @param writer
  */
final case class Write[A: StatementWriter as writer](a: A):
  /** convenience method to get the placeholder from the writer so that the writer does not need to be summoned
    *
    * @return
    */
  def placeholder: String = writer.placeholder

  /** convenience method to write the value to the prepared statement at the given index so that the writer does not
    * need to be summoned
    *
    * @param stmt
    * @param idx
    * @return
    */
  def write(stmt: PreparedStatement, idx: Int): Task[Unit] =
    writer.write(a, stmt, idx)
end Write

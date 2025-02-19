package saferis

import zio.*

import java.sql.PreparedStatement

/** A class that can write a value to a prepared statement It is used as a wrapper around a value to write it to a
  * prepared statement The sql interpolation will generate a `Write` instance for each value
  *
  * @param a
  * @param writer
  */
final class Write[A: Encoder as writer](a: A):
  /** convenience method to get the placeholder from the writer so that the writer does not need to be summoned
    *
    * @return
    */
  def placeholder: String = writer.placeholder(a)

  /** convenience method to write the value to the prepared statement at the given index so that the writer does not
    * need to be summoned
    *
    * @param stmt
    * @param idx
    * @return
    */
  def write(stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] =
    writer.encode(a, stmt, idx)
  override def toString(): String = s"Write($a:${a.getClass().getName()})"
end Write

package saferis

import zio.*

import java.sql.ResultSet
import scala.annotation.StaticAnnotation

/** Represents a label for a column in a result set. Fields in a case class can be annotated with this to specify the
  * column name/label Example:
  * {{{
  *   case class User(
  *     @label("user_id") id: Int,
  *     @label("user_name") name: String
  *   )
  * }}}
  *
  * @param name
  */
final case class label(name: String) extends StaticAnnotation

/** denotes fields of a case class that are generated by the database
  */
class generated extends key

/** denotes fields of a case class that are the primary key
  */
class key extends StaticAnnotation

/** Represents a column/field in result set
  *
  * @param name
  *   the scala field name in the case class
  * @param label
  *   the column name/label in the result set
  * @param reader
  */
final case class Column[R: Reader as reader](
    name: String,
    label: String,
    isKey: Boolean,
    isGenerated: Boolean,
) extends Placeholder:
  type ColumnType = R
  val writes = Seq.empty
  val sql    = label

  private[saferis] def read(rs: ResultSet)(using Trace): Task[(String, R)] =
    reader.read(rs, label).map(v => name -> v)
end Column

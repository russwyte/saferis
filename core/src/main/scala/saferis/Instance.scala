package saferis
//import saferis.Instance.*
import zio.Trace
import scala.annotation.unused

/** Provides table column access and DDL information.
  *
  * This class is derived via a macro from a Table[A] typeclass.
  *
  * Instance exposes ONLY column access via `selectDynamic` - all other methods are private[saferis].
  * This ensures no method names can collide with user-defined field names like "sql", "withAlias", etc.
  *
  * To get the SQL representation, use: `toSql(instance)`
  * To alias a table, use: `instance as "alias"` or `aliased(instance, "alias")`
  *
  * ==Reserved Field Names==
  * The following Scala field names cannot be used because they conflict with the Selectable trait:
  *   - `selectDynamic`
  *   - `applyDynamic`
  *
  * If your database column has one of these names, use a different Scala field name with `@label`:
  * {{{
  *   @label("selectDynamic") selectDyn: String
  * }}}
  *
  * @tparam A
  *   The case class type representing the table
  */
final case class Instance[A <: Product: Table as table](
    private[saferis] val tableName: String,
    private[saferis] val columns: Seq[Column[?]],
    private[saferis] val alias: Option[Alias],
    private[saferis] val foreignKeys: Vector[ForeignKeySpec[A, ?]] = Vector.empty,
    private[saferis] val indexes: Vector[IndexSpec[?]] = Vector.empty,
    private[saferis] val uniqueConstraints: Vector[UniqueConstraintSpec[?]] = Vector.empty,
) extends Selectable:
  private[saferis] val fieldNamesToColumns: Map[String, Column[?]] = columns.map(c => c.name -> c).toMap

  /** Exposes the Table[A] evidence for use in builder classes */
  private[saferis] def tableEvidence: Table[A] = table

  /** Get foreign key constraint SQL - use via `foreignKeyConstraints(instance)` */
  private[saferis] def foreignKeyConstraints: Seq[String] = foreignKeys.map(_.toConstraintSql).toSeq

  /** Get unique constraint SQL - use via `uniqueConstraints(instance)` */
  private[saferis] def uniqueConstraintsSql: Seq[String] = uniqueConstraints.map(_.toConstraintSql).toSeq

  /** Column access via field name - the ONLY public method besides applyDynamic */
  def selectDynamic(name: String) = fieldNamesToColumns(name)

  /** getByKey lookup - stays public for refined type compatibility */
  def applyDynamic[A: Encoder](@unused name: String)(args: A*) =
    val cs: Seq[Column[?]] =
      columns.filter(_.isKey)
    val whereArgs   = cs.zip(args).toList
    val whereClause = whereArgs.headOption.fold(Placeholder.Empty):
      case (c, a) =>
        whereArgs.tail
          .foldLeft(sql"where $c = $a"):
            case (acc, (c, a)) =>
              acc :+ sql" and $c = $a"
    TypedFragment(sql"select * from $this $whereClause")
  end applyDynamic

  /** Set a user-provided alias on this instance - use via `instance as "alias"` */
  private[saferis] transparent inline def withAlias(alias: String) =
    val userAlias  = Alias.User(alias)
    val newColumns = columns.map(_.withTableAlias(Some(userAlias)))
    copy(
      alias = Some(userAlias),
      columns = newColumns,
      foreignKeys = foreignKeys,
      indexes = indexes,
      uniqueConstraints = uniqueConstraints,
    ).asInstanceOf[this.type]

  /** Internal method to set a generated alias on this instance. */
  private[saferis] transparent inline def withGeneratedAlias(alias: Alias.Generated) =
    val newColumns = columns.map(_.withTableAlias(Some(alias)))
    copy(
      alias = Some(alias),
      columns = newColumns,
      foreignKeys = foreignKeys,
      indexes = indexes,
      uniqueConstraints = uniqueConstraints,
    ).asInstanceOf[this.type]

  /** Remove alias from this instance - use via `unaliased(instance)` */
  private[saferis] transparent inline def deAliased =
    val newColumns = columns.map(_.withTableAlias(None))
    copy(
      alias = None,
      columns = newColumns,
      foreignKeys = foreignKeys,
      indexes = indexes,
      uniqueConstraints = uniqueConstraints,
    ).asInstanceOf[this.type]

  /** Internal SQL representation - for use by Query builders and interpolator */
  private[saferis] def toSqlString: String = alias.fold(tableName)(a => s"$tableName as ${a.value}")

  /** Extract a typed column using a field selector.
    *
    * This is private[saferis] to avoid collision with user-defined fields named "column".
    * Used internally by Query.where and other builder methods.
    */
  private[saferis] inline def column[T](inline selector: A => T): Column[T] =
    Macros.extractColumn(this, selector)

  final private[saferis] class TypedFragment(val fragment: SqlFragment):
    def sql                                                  = fragment.sql
    inline def query(using Trace): ScopedQuery[Seq[A]]       = fragment.query[A]
    inline def queryOne(using Trace): ScopedQuery[Option[A]] = fragment.queryOne[A]
end Instance

object Instance:
  val getByKey = "getByKey"

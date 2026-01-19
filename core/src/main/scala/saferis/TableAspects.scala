package saferis

import scala.quoted.*

/** TableAspects provides type-safe builders for table schema aspects like foreign keys.
  *
  * Usage:
  * {{{
  *   import saferis.TableAspects.*
  *
  *   case class User(@generated @key id: Int, name: String) derives Table
  *   case class Order(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table
  *
  *   val orders = Table[Order]
  *     @@ foreignKey[Order, Int](_.userId).references[User](_.id).onDelete(Cascade)
  *
  *   // Use with DDL
  *   ddl.createTable(orders)
  * }}}
  */
object TableAspects:

  /** Start building a foreign key constraint for a single column.
    *
    * @tparam A
    *   The source table type
    * @tparam T
    *   The column type
    * @param selector
    *   A selector function like `_.userId` that identifies the FK column
    * @return
    *   A ForeignKeyBuilder to continue building the constraint
    */
  inline def foreignKey[A <: Product: Table, T](
      inline selector: A => T
  ): ForeignKeyBuilder[A, T] =
    ${ foreignKeyImpl[A, T]('selector) }

  /** Start building a compound foreign key constraint for two columns.
    *
    * @tparam A
    *   The source table type
    * @param selector1
    *   First column selector
    * @param selector2
    *   Second column selector
    * @return
    *   A ForeignKeyBuilder for compound FK
    */
  inline def foreignKey[A <: Product: Table, T1, T2](
      inline selector1: A => T1,
      inline selector2: A => T2,
  ): ForeignKeyBuilder2[A, T1, T2] =
    ${ foreignKey2Impl[A, T1, T2]('selector1, 'selector2) }

  // === Extension methods for .references() - must be here for macro accessibility ===

  extension [From <: Product, T](builder: ForeignKeyBuilder[From, T])
    /** Specify the referenced table and column.
      *
      * @tparam To
      *   The referenced table type
      * @param selector
      *   A selector for the referenced column like `_.id`
      * @return
      *   A ForeignKeyConfigBuilder for setting ON DELETE/UPDATE actions
      */
    inline def references[To <: Product: Table](
        inline selector: To => T
    ): ForeignKeyConfigBuilder[From, To] =
      ${ buildReferencesImpl[From, To, T]('builder, 'selector) }
  end extension

  extension [From <: Product, T1, T2](builder: ForeignKeyBuilder2[From, T1, T2])
    /** Specify the referenced table and columns for a compound FK.
      *
      * @tparam To
      *   The referenced table type
      * @param selector1
      *   First referenced column selector
      * @param selector2
      *   Second referenced column selector
      */
    inline def references[To <: Product: Table](
        inline selector1: To => T1,
        inline selector2: To => T2,
    ): ForeignKeyConfigBuilder[From, To] =
      ${ buildReferences2Impl[From, To, T1, T2]('builder, 'selector1, 'selector2) }
  end extension

  // === Macro implementations ===

  private def foreignKeyImpl[A <: Product: Type, T: Type](
      selector: Expr[A => T]
  )(using Quotes): Expr[ForeignKeyBuilder[A, T]] =
    import quotes.reflect.*
    val columnName = extractFieldName(selector)
    val table      = Expr
      .summon[Table[A]]
      .getOrElse(
        report.errorAndAbort(s"Could not find Table instance for ${Type.show[A]}")
      )
    '{ ForeignKeyBuilder[A, T]($table, Seq(${ Expr(columnName) })) }
  end foreignKeyImpl

  private def foreignKey2Impl[A <: Product: Type, T1: Type, T2: Type](
      selector1: Expr[A => T1],
      selector2: Expr[A => T2],
  )(using Quotes): Expr[ForeignKeyBuilder2[A, T1, T2]] =
    import quotes.reflect.*
    val col1  = extractFieldName(selector1)
    val col2  = extractFieldName(selector2)
    val table = Expr
      .summon[Table[A]]
      .getOrElse(
        report.errorAndAbort(s"Could not find Table instance for ${Type.show[A]}")
      )
    '{ ForeignKeyBuilder2[A, T1, T2]($table, Seq(${ Expr(col1) }, ${ Expr(col2) })) }
  end foreignKey2Impl

  private def buildReferencesImpl[From <: Product: Type, To <: Product: Type, T: Type](
      builder: Expr[ForeignKeyBuilder[From, T]],
      selector: Expr[To => T],
  )(using Quotes): Expr[ForeignKeyConfigBuilder[From, To]] =
    import quotes.reflect.*
    val toColumnName = extractFieldName(selector)
    val toTable      = Expr
      .summon[Table[To]]
      .getOrElse(
        report.errorAndAbort(s"Could not find Table instance for ${Type.show[To]}")
      )
    '{
      val b               = $builder
      val toTableInstance = $toTable
      ForeignKeyConfigBuilder[From, To](
        b.fromColumns,
        toTableInstance.name,
        Seq(${ Expr(toColumnName) }),
      )
    }
  end buildReferencesImpl

  private def buildReferences2Impl[From <: Product: Type, To <: Product: Type, T1: Type, T2: Type](
      builder: Expr[ForeignKeyBuilder2[From, T1, T2]],
      selector1: Expr[To => T1],
      selector2: Expr[To => T2],
  )(using Quotes): Expr[ForeignKeyConfigBuilder[From, To]] =
    import quotes.reflect.*
    val toCol1  = extractFieldName(selector1)
    val toCol2  = extractFieldName(selector2)
    val toTable = Expr
      .summon[Table[To]]
      .getOrElse(
        report.errorAndAbort(s"Could not find Table instance for ${Type.show[To]}")
      )
    '{
      val b               = $builder
      val toTableInstance = $toTable
      ForeignKeyConfigBuilder[From, To](
        b.fromColumns,
        toTableInstance.name,
        Seq(${ Expr(toCol1) }, ${ Expr(toCol2) }),
      )
    }
  end buildReferences2Impl

  /** Extract field name from a selector function like `_.fieldName` */
  private def extractFieldName[A: Type, T: Type](selector: Expr[A => T])(using Quotes): String =
    import quotes.reflect.*
    selector.asTerm match
      case Inlined(_, _, Block(List(DefDef(_, _, _, Some(body))), _)) =>
        extractFromBody(body)
      case Inlined(_, _, Lambda(_, body)) =>
        extractFromBody(body)
      case Lambda(_, body) =>
        extractFromBody(body)
      case other =>
        report.errorAndAbort(
          s"Expected a simple field selector like _.fieldName, got: ${other.show}"
        )
    end match
  end extractFieldName

  private def extractFromBody(using Quotes)(body: quotes.reflect.Term): String =
    import quotes.reflect.*
    body match
      case Select(_, fieldName) => fieldName
      case Inlined(_, _, inner) => extractFromBody(inner)
      case other                =>
        report.errorAndAbort(
          s"Expected a simple field selector like _.fieldName, got: ${other.show}"
        )

end TableAspects

/** Builder for single-column foreign key - awaiting `references` call */
final case class ForeignKeyBuilder[From <: Product, T](
    fromTable: Table[From],
    fromColumns: Seq[String],
)

/** Builder for two-column compound foreign key */
final case class ForeignKeyBuilder2[From <: Product, T1, T2](
    fromTable: Table[From],
    fromColumns: Seq[String],
)

/** Builder for configuring FK actions (ON DELETE, ON UPDATE) */
final case class ForeignKeyConfigBuilder[From <: Product, To <: Product](
    fromColumns: Seq[String],
    toTable: String,
    toColumns: Seq[String],
    onDeleteAction: ForeignKeyAction = ForeignKeyAction.NoAction,
    onUpdateAction: ForeignKeyAction = ForeignKeyAction.NoAction,
    name: Option[String] = None,
):
  /** Set the ON DELETE action */
  def onDelete(action: ForeignKeyAction): ForeignKeyConfigBuilder[From, To] =
    copy(onDeleteAction = action)

  /** Set the ON UPDATE action */
  def onUpdate(action: ForeignKeyAction): ForeignKeyConfigBuilder[From, To] =
    copy(onUpdateAction = action)

  /** Set a custom constraint name */
  def named(constraintName: String): ForeignKeyConfigBuilder[From, To] =
    copy(name = Some(constraintName))

  /** Build the ForeignKeySpec - implicitly called when using @@ operator */
  def build: ForeignKeySpec[From, To] =
    ForeignKeySpec(
      fromColumns = fromColumns,
      toTable = toTable,
      toColumns = toColumns,
      onDelete = onDeleteAction,
      onUpdate = onUpdateAction,
      constraintName = name,
    )
end ForeignKeyConfigBuilder

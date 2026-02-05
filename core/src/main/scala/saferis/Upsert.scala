package saferis

/** Type-safe UPSERT DSL for conditional insert-or-update operations.
  *
  * Usage:
  * {{{
  *   given dialect: Dialect & UpsertSupport & ReturningSupport = PostgresDialect
  *
  *   // Basic upsert
  *   Upsert[Lock]
  *     .values(Lock(instanceId, nodeId, now, expiresAt))
  *     .onConflict(_.instanceId)
  *     .doUpdateAll
  *     .build
  *     .execute
  *
  *   // Conditional upsert with WHERE on conflict
  *   Upsert[Lock]
  *     .values(Lock(instanceId, nodeId, now, expiresAt))
  *     .onConflict(_.instanceId)
  *     .doUpdateAll
  *     .where(_.expiresAt).lt(now)
  *     .or(_.nodeId).eqExcluded  // Only update if expired OR same node
  *     .returning
  *     .queryOne[Lock]
  * }}}
  */
object Upsert:
  /** Create an Upsert builder for a table type */
  inline def apply[A <: Product: Table]: UpsertBuilder[A] =
    val table = summon[Table[A]]
    UpsertBuilder(table.name, table.columnMap, table.columns.toVector)

// ============================================================================
// UpsertBuilder - Entry point (needs .values())
// ============================================================================

/** Initial upsert builder - needs entity values. */
final case class UpsertBuilder[A <: Product: Table](
    private[saferis] val tableName: String,
    private[saferis] val fieldNamesToColumns: Map[String, Column[?]],
    private[saferis] val allColumns: Vector[Column[?]],
):
  /** Provide the entity to insert/update */
  def values(entity: A): UpsertValuesReady[A] =
    UpsertValuesReady(tableName, fieldNamesToColumns, allColumns, entity)

// ============================================================================
// UpsertValuesReady - Has entity, needs .onConflict()
// ============================================================================

/** Upsert builder with entity values - needs conflict columns. */
final case class UpsertValuesReady[A <: Product: Table](
    private[saferis] val tableName: String,
    private[saferis] val fieldNamesToColumns: Map[String, Column[?]],
    private[saferis] val allColumns: Vector[Column[?]],
    private[saferis] val entity: A,
):
  /** Specify the conflict column(s) for ON CONFLICT */
  transparent inline def onConflict[T](inline selector: A => T): UpsertConflictReady[A] =
    val fieldName = Macros.extractFieldName[A, T](selector)
    val col       = fieldNamesToColumns(fieldName)
    UpsertConflictReady(tableName, fieldNamesToColumns, allColumns, entity, Vector(col.label))
end UpsertValuesReady

// ============================================================================
// UpsertConflictReady - Has conflict columns, needs action (doUpdateAll/doNothing)
// ============================================================================

/** Upsert builder with conflict columns - needs update action. */
final case class UpsertConflictReady[A <: Product: Table](
    private[saferis] val tableName: String,
    private[saferis] val fieldNamesToColumns: Map[String, Column[?]],
    private[saferis] val allColumns: Vector[Column[?]],
    private[saferis] val entity: A,
    private[saferis] val conflictColumns: Vector[String],
):
  /** Add another conflict column */
  transparent inline def and[T](inline selector: A => T): UpsertConflictReady[A] =
    val fieldName = Macros.extractFieldName[A, T](selector)
    val col       = fieldNamesToColumns(fieldName)
    copy(conflictColumns = conflictColumns :+ col.label)

  /** DO UPDATE SET all non-key, non-generated columns */
  transparent inline def doUpdateAll: UpsertActionReady[A] =
    val table         = summon[Table[A]]
    val updateColumns = table.updateSetClause(entity)
    UpsertActionReady(
      tableName,
      fieldNamesToColumns,
      allColumns,
      entity,
      conflictColumns,
      updateColumns.sql,
      updateColumns.writes,
      doNothing = false,
    )
  end doUpdateAll

  /** DO NOTHING - only insert if no conflict */
  def doNothing: UpsertDoNothingReady[A] =
    UpsertDoNothingReady(tableName, fieldNamesToColumns, allColumns, entity, conflictColumns)
end UpsertConflictReady

// ============================================================================
// UpsertDoNothingReady - DO NOTHING variant, ready to build
// ============================================================================

/** Upsert with DO NOTHING - ready to build. */
final case class UpsertDoNothingReady[A <: Product: Table](
    private[saferis] val tableName: String,
    private[saferis] val fieldNamesToColumns: Map[String, Column[?]],
    private[saferis] val allColumns: Vector[Column[?]],
    private[saferis] val entity: A,
    private[saferis] val conflictColumns: Vector[String],
):
  /** Build the INSERT ... ON CONFLICT DO NOTHING SQL */
  transparent inline def build(using dialect: Dialect & UpsertSupport): SqlFragment =
    val table        = summon[Table[A]]
    val insertCols   = table.insertColumnsSql.sql
    val insertValues = table.insertPlaceholdersSql(entity)
    val insertClause = s"$insertCols values ${insertValues.sql}"
    val sql          = dialect.upsertDoNothingSql(tableName, insertClause, conflictColumns)
    SqlFragment(sql, insertValues.writes)
end UpsertDoNothingReady

// ============================================================================
// UpsertActionReady - Has DO UPDATE, can add WHERE or build
// ============================================================================

/** Upsert with DO UPDATE - can add WHERE clause or build directly. */
final case class UpsertActionReady[A <: Product: Table](
    private[saferis] val tableName: String,
    private[saferis] val fieldNamesToColumns: Map[String, Column[?]],
    private[saferis] val allColumns: Vector[Column[?]],
    private[saferis] val entity: A,
    private[saferis] val conflictColumns: Vector[String],
    private[saferis] val updateColumnsSql: String,
    private[saferis] val updateWrites: Seq[Write[?]],
    private[saferis] val doNothing: Boolean,
):
  /** Add a WHERE clause for conditional update (starts the condition) */
  transparent inline def where[T](inline selector: A => T): UpsertWhereBuilder[A, T] =
    val fieldName = Macros.extractFieldName[A, T](selector)
    val col       = fieldNamesToColumns(fieldName).asInstanceOf[Column[T]]
    UpsertWhereBuilder(this, Alias.unsafe(tableName), col)

  /** Build without WHERE clause */
  transparent inline def build(using dialect: Dialect & UpsertSupport): SqlFragment =
    val table        = summon[Table[A]]
    val insertCols   = table.insertColumnsSql.sql
    val insertValues = table.insertPlaceholdersSql(entity)
    val insertClause = s"$insertCols values ${insertValues.sql}"
    val sql          = dialect.upsertSql(tableName, insertClause, conflictColumns, updateColumnsSql)
    SqlFragment(sql, insertValues.writes ++ updateWrites)

  /** Build with RETURNING clause (no WHERE) */
  transparent inline def returning(using dialect: Dialect & UpsertSupport & ReturningSupport): ReturningQuery[A] =
    ReturningQuery(build :+ SqlFragment(" returning *", Seq.empty))
end UpsertActionReady

// ============================================================================
// UpsertWhereBuilder - Building WHERE condition for conflict update
// ============================================================================

/** Builder for WHERE conditions on upsert conflict update. */
final case class UpsertWhereBuilder[A <: Product: Table, T](
    action: UpsertActionReady[A],
    alias: Alias,
    column: Column[T],
):
  private def complete(operator: Operator, write: Write[?]): UpsertWhereReady[A] =
    val condition = LiteralCondition(alias, column, operator, write)
    val fragment  = Condition.toSqlFragment(Vector(condition))
    UpsertWhereReady(action, Vector(fragment))

  private def completeUnary(operator: Operator): UpsertWhereReady[A] =
    val condition = UnaryCondition(alias, column, operator)
    val fragment  = Condition.toSqlFragment(Vector(condition))
    UpsertWhereReady(action, Vector(fragment))

  /** Equals comparison */
  def eq(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Eq, enc(value))

  /** Not equals comparison */
  def neq(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Neq, enc(value))

  /** Less than comparison */
  def lt(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Lt, enc(value))

  /** Less than or equal comparison */
  def lte(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Lte, enc(value))

  /** Greater than comparison */
  def gt(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Gt, enc(value))

  /** Greater than or equal comparison */
  def gte(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Gte, enc(value))

  /** IS NULL check */
  def isNull: UpsertWhereReady[A] =
    completeUnary(Operator.IsNull)

  /** IS NOT NULL check */
  def isNotNull: UpsertWhereReady[A] =
    completeUnary(Operator.IsNotNull)

  /** Compare to EXCLUDED pseudo-table value (same column) */
  def eqExcluded: UpsertWhereReady[A] =
    val condition = ExcludedCondition(alias, column, Operator.Eq, column)
    val fragment  = SqlFragment(condition.toSql, Seq.empty)
    UpsertWhereReady(action, Vector(fragment))

  /** Compare to EXCLUDED pseudo-table with not equal */
  def neqExcluded: UpsertWhereReady[A] =
    val condition = ExcludedCondition(alias, column, Operator.Neq, column)
    val fragment  = SqlFragment(condition.toSql, Seq.empty)
    UpsertWhereReady(action, Vector(fragment))

end UpsertWhereBuilder

// ============================================================================
// UpsertWhereReady - Has WHERE, can add more conditions or build
// ============================================================================

/** Upsert with WHERE clause - can chain more conditions or build. */
final case class UpsertWhereReady[A <: Product: Table](
    private[saferis] val action: UpsertActionReady[A],
    private[saferis] val wherePredicates: Vector[SqlFragment],
):
  /** Chain with OR */
  transparent inline def or[T](inline selector: A => T): UpsertOrBuilder[A, T] =
    UpsertWhereReady.chainOr(this, selector)

  /** Chain with AND */
  transparent inline def and[T](inline selector: A => T): UpsertAndBuilder[A, T] =
    UpsertWhereReady.chainAnd(this, selector)

  /** Build the complete upsert SQL */
  transparent inline def build(using dialect: Dialect & UpsertSupport): SqlFragment =
    val table        = summon[Table[A]]
    val insertCols   = table.insertColumnsSql.sql
    val insertValues = table.insertPlaceholdersSql(action.entity)
    val insertClause = s"$insertCols values ${insertValues.sql}"

    // Build WHERE clause from predicates
    val whereJoined = if wherePredicates.nonEmpty then
      val joined = Placeholder.join(wherePredicates, " OR ")
      Some(joined.sql)
    else None

    val sql = dialect.upsertWithWhereSql(
      action.tableName,
      insertClause,
      action.conflictColumns,
      action.updateColumnsSql,
      whereJoined,
    )

    val allWrites = insertValues.writes ++ action.updateWrites ++ wherePredicates.flatMap(_.writes)
    SqlFragment(sql, allWrites)
  end build

  /** Build with RETURNING clause */
  transparent inline def returning(using dialect: Dialect & UpsertSupport & ReturningSupport): ReturningQuery[A] =
    ReturningQuery(build :+ SqlFragment(" returning *", Seq.empty))

end UpsertWhereReady

object UpsertWhereReady:
  inline def chainOr[A <: Product: Table, T](
      ready: UpsertWhereReady[A],
      inline selector: A => T,
  ): UpsertOrBuilder[A, T] =
    val fieldName = Macros.extractFieldName[A, T](selector)
    val col       = ready.action.fieldNamesToColumns(fieldName).asInstanceOf[Column[T]]
    UpsertOrBuilder(ready, Alias.unsafe(ready.action.tableName), col)

  inline def chainAnd[A <: Product: Table, T](
      ready: UpsertWhereReady[A],
      inline selector: A => T,
  ): UpsertAndBuilder[A, T] =
    val fieldName = Macros.extractFieldName[A, T](selector)
    val col       = ready.action.fieldNamesToColumns(fieldName).asInstanceOf[Column[T]]
    UpsertAndBuilder(ready, Alias.unsafe(ready.action.tableName), col)
end UpsertWhereReady

// ============================================================================
// UpsertOrBuilder - Building OR condition
// ============================================================================

/** Builder for OR conditions in upsert WHERE clause. */
final case class UpsertOrBuilder[A <: Product: Table, T](
    ready: UpsertWhereReady[A],
    alias: Alias,
    column: Column[T],
):
  private def complete(operator: Operator, write: Write[?]): UpsertWhereReady[A] =
    val condition = LiteralCondition(alias, column, operator, write)
    val fragment  = Condition.toSqlFragment(Vector(condition))
    ready.copy(wherePredicates = ready.wherePredicates :+ fragment)

  private def completeUnary(operator: Operator): UpsertWhereReady[A] =
    val condition = UnaryCondition(alias, column, operator)
    val fragment  = Condition.toSqlFragment(Vector(condition))
    ready.copy(wherePredicates = ready.wherePredicates :+ fragment)

  /** Equals comparison */
  def eq(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Eq, enc(value))

  /** Not equals comparison */
  def neq(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Neq, enc(value))

  /** Less than comparison */
  def lt(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Lt, enc(value))

  /** Less than or equal comparison */
  def lte(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Lte, enc(value))

  /** Greater than comparison */
  def gt(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Gt, enc(value))

  /** Greater than or equal comparison */
  def gte(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Gte, enc(value))

  /** IS NULL check */
  def isNull: UpsertWhereReady[A] =
    completeUnary(Operator.IsNull)

  /** IS NOT NULL check */
  def isNotNull: UpsertWhereReady[A] =
    completeUnary(Operator.IsNotNull)

  /** Compare to EXCLUDED pseudo-table value (same column) */
  def eqExcluded: UpsertWhereReady[A] =
    val condition = ExcludedCondition(alias, column, Operator.Eq, column)
    val fragment  = SqlFragment(condition.toSql, Seq.empty)
    ready.copy(wherePredicates = ready.wherePredicates :+ fragment)

  /** Compare to EXCLUDED pseudo-table with not equal */
  def neqExcluded: UpsertWhereReady[A] =
    val condition = ExcludedCondition(alias, column, Operator.Neq, column)
    val fragment  = SqlFragment(condition.toSql, Seq.empty)
    ready.copy(wherePredicates = ready.wherePredicates :+ fragment)

end UpsertOrBuilder

// ============================================================================
// UpsertAndBuilder - Building AND condition
// ============================================================================

/** Builder for AND conditions in upsert WHERE clause. */
final case class UpsertAndBuilder[A <: Product: Table, T](
    ready: UpsertWhereReady[A],
    alias: Alias,
    column: Column[T],
):
  private def complete(operator: Operator, write: Write[?]): UpsertWhereReady[A] =
    // For AND, we need to group the current predicates and add a new one
    // This is simplified - full implementation would track AND/OR grouping
    val condition = LiteralCondition(alias, column, operator, write)
    val fragment  = Condition.toSqlFragment(Vector(condition))
    ready.copy(wherePredicates = ready.wherePredicates :+ fragment)

  private def completeUnary(operator: Operator): UpsertWhereReady[A] =
    val condition = UnaryCondition(alias, column, operator)
    val fragment  = Condition.toSqlFragment(Vector(condition))
    ready.copy(wherePredicates = ready.wherePredicates :+ fragment)

  /** Equals comparison */
  def eq(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Eq, enc(value))

  /** Not equals comparison */
  def neq(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Neq, enc(value))

  /** Less than comparison */
  def lt(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Lt, enc(value))

  /** Less than or equal comparison */
  def lte(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Lte, enc(value))

  /** Greater than comparison */
  def gt(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Gt, enc(value))

  /** Greater than or equal comparison */
  def gte(value: T)(using enc: Encoder[T]): UpsertWhereReady[A] =
    complete(Operator.Gte, enc(value))

  /** IS NULL check */
  def isNull: UpsertWhereReady[A] =
    completeUnary(Operator.IsNull)

  /** IS NOT NULL check */
  def isNotNull: UpsertWhereReady[A] =
    completeUnary(Operator.IsNotNull)

  /** Compare to EXCLUDED pseudo-table value (same column) */
  def eqExcluded: UpsertWhereReady[A] =
    val condition = ExcludedCondition(alias, column, Operator.Eq, column)
    val fragment  = SqlFragment(condition.toSql, Seq.empty)
    ready.copy(wherePredicates = ready.wherePredicates :+ fragment)

end UpsertAndBuilder

# Index Aspects API

## Overview

Add `@@` aspect support for indexes, similar to the foreign key API. This provides a more flexible and composable way to define indexes compared to annotations.

## Current Annotation Approach

```scala
case class User(
  @generated @key id: Int,
  @indexed name: String,
  @indexed("idx_status_created", "status = 'active'") status: String,
  @uniqueIndex email: String
) derives Table
```

**Limitations:**
- Compile-time only - can't build indexes programmatically
- Clunky syntax for partial indexes with string parameters
- Can't add indexes conditionally or dynamically

## Proposed `@@` Aspect Approach

```scala
import saferis.TableAspects.*

val users = Table[User]
  @@ index(_.name)
  @@ index(_.status).where("status = 'active'").named("idx_active_status")
  @@ uniqueIndex(_.email).where("deleted_at IS NULL")
  @@ index(_.lastName, _.firstName).named("idx_full_name")  // compound
```

**Benefits:**
- More readable, especially for partial indexes
- Composable - different index configs for different environments
- Type-safe column references with `_.columnName` pattern
- Runtime flexibility - programmatically add indexes

## Design

### 1. IndexSpec Case Class

```scala
// In a new file or TableAspects.scala
final case class IndexSpec(
    columns: Seq[String],
    name: Option[String] = None,
    unique: Boolean = false,
    where: Option[String] = None,
):
  def toCreateSql(tableName: String)(using dialect: Dialect): String =
    val indexName = name.getOrElse(s"idx_${tableName}_${columns.mkString("_")}")
    if unique then
      dialect.createUniqueIndexSql(indexName, tableName, columns, false, where)
    else
      dialect.createIndexSql(indexName, tableName, columns, false, where)
```

### 2. IndexBuilder with Fluent API

```scala
final case class IndexBuilder[A <: Product](
    columns: Seq[String],
    unique: Boolean = false,
):
  def where(condition: String): IndexConfigBuilder =
    IndexConfigBuilder(columns, unique, Some(condition), None)

  def named(indexName: String): IndexConfigBuilder =
    IndexConfigBuilder(columns, unique, None, Some(indexName))

  def build: IndexSpec = IndexSpec(columns, None, unique, None)

final case class IndexConfigBuilder(
    columns: Seq[String],
    unique: Boolean,
    whereCondition: Option[String],
    name: Option[String],
):
  def where(condition: String): IndexConfigBuilder =
    copy(whereCondition = Some(condition))

  def named(indexName: String): IndexConfigBuilder =
    copy(name = Some(indexName))

  def build: IndexSpec = IndexSpec(columns, name, unique, whereCondition)
```

### 3. Macro Functions in TableAspects

```scala
object TableAspects:
  // Single column index
  inline def index[A <: Product: Table, T](
      inline selector: A => T
  ): IndexBuilder[A] =
    ${ indexImpl[A, T]('selector, false) }

  // Compound index (2 columns)
  inline def index[A <: Product: Table, T1, T2](
      inline selector1: A => T1,
      inline selector2: A => T2,
  ): IndexBuilder[A] =
    ${ index2Impl[A, T1, T2]('selector1, 'selector2, false) }

  // Single column unique index
  inline def uniqueIndex[A <: Product: Table, T](
      inline selector: A => T
  ): IndexBuilder[A] =
    ${ indexImpl[A, T]('selector, true) }

  // ... similar for compound unique indexes
```

### 4. Update Instance

```scala
final case class Instance[A <: Product: Table](
    private[saferis] val tableName: String,
    private[saferis] val columns: Seq[Column[?]],
    private[saferis] val alias: Option[String],
    private[saferis] val foreignKeys: Vector[ForeignKeySpec[A, ?]] = Vector.empty,
    private[saferis] val indexes: Vector[IndexSpec] = Vector.empty,  // NEW
) extends Selectable with Placeholder:

  // Existing FK methods...

  /** Add an index to this Instance */
  transparent inline def @@(indexBuilder: IndexBuilder[?]): this.type =
    Instance[A](tableName, columns, alias, foreignKeys,
      indexes :+ indexBuilder.build).asInstanceOf[this.type]

  transparent inline def @@(indexConfig: IndexConfigBuilder): this.type =
    Instance[A](tableName, columns, alias, foreignKeys,
      indexes :+ indexConfig.build).asInstanceOf[this.type]
```

### 5. Update DDL

Modify `createIndexesFromInstance` to merge annotation-based and aspect-based indexes:

```scala
private def createIndexesFromInstance[A <: Product](instance: Instance[A])(using
    dialect: Dialect,
    trace: Trace,
): ZIO[ConnectionProvider & Scope, Throwable, Seq[Int]] =
  // Existing annotation-based index creation...
  val annotationIndexes = // ... existing logic

  // NEW: Aspect-based indexes from instance.indexes
  val aspectIndexes = instance.indexes.map { spec =>
    val sql = SqlFragment(spec.toCreateSql(instance.tableName), Seq.empty)
    sql.dml
  }

  ZIO.collectAll(annotationIndexes ++ aspectIndexes)
```

## Files to Modify

1. **TableAspects.scala** - Add `index`, `uniqueIndex` macros and builders
2. **Instance.scala** - Add `indexes` field and `@@` overloads
3. **Macros.scala** - Update `instanceImpl` to include `indexes = Vector.empty`
4. **DataDefinitionLayer.scala** - Update `createIndexesFromInstance` to include aspect indexes
5. **New test file** - IndexAspectSpecs.scala

## Example Usage

```scala
import saferis.*
import saferis.TableAspects.*

@tableName("users")
case class User(
  @generated @key id: Int,
  name: String,
  email: String,
  status: String,
  deletedAt: Option[java.time.Instant]
) derives Table

// Flexible index configuration
val users = Table[User]
  @@ index(_.name)
  @@ uniqueIndex(_.email).where("deleted_at IS NULL")  // Partial unique
  @@ index(_.status).where("status = 'active'").named("idx_active_users")

// Create table with all indexes
ddl.createTable(users)

// Or use annotations for simple cases, aspects for complex ones
@tableName("products")
case class Product(
  @generated @key id: Int,
  @indexed name: String,  // Simple index via annotation
  sku: String,
  status: String
) derives Table

val products = Table[Product]
  @@ uniqueIndex(_.sku).where("status <> 'deleted'")  // Complex index via aspect
```

## Implementation Order

1. Add `IndexSpec` case class
2. Add `IndexBuilder` and `IndexConfigBuilder`
3. Add macros in `TableAspects`
4. Add `indexes` field to `Instance`
5. Add `@@` overloads for index builders
6. Update DDL to create aspect-based indexes
7. Write tests
8. Update documentation

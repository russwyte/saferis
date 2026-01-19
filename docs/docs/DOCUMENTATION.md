# Saferis Documentation

A comprehensive guide to Saferis - the type-safe, resource-safe SQL client library for Scala 3 and ZIO.

## Table of Contents

- [Getting Started](#getting-started)
- [Core Concepts](#core-concepts)
- [Dialect System](#dialect-system)
- [Data Definition Layer (DDL)](#data-definition-layer-ddl)
- [Foreign Key Support](#foreign-key-support)
- [Data Manipulation Layer (DML)](#data-manipulation-layer-dml)
- [Type-Safe Joins](#type-safe-joins)
- [Pagination](#pagination)
- [Type-Safe Capabilities](#type-safe-capabilities)

---

## Getting Started

### Installation

Add Saferis to your `build.sbt`:

```scala
libraryDependencies += "io.github.russwyte" %% "saferis" % "0.1.2"
```

Saferis requires ZIO as a provided dependency:

```scala
libraryDependencies += "dev.zio" %% "zio" % "2.1.24"
```

### Quick Example

```scala
import saferis.*
import saferis.docs.DocTestContainer
import saferis.docs.DocTestContainer.{run, transactor as xa}

// Define a table
@tableName("quick_users")
case class QuickUser(@generated @key id: Int, name: String, email: String) derives Table
```

```scala
// Create table, insert data, and query
run {
  xa.run(for
    _ <- ddl.createTable[QuickUser]()
    _ <- dml.insert(QuickUser(-1, "Alice", "alice@example.com"))
    _ <- dml.insert(QuickUser(-1, "Bob", "bob@example.com"))
    users <- sql"SELECT * FROM ${Table[QuickUser]}".query[QuickUser]
  yield users)
}
// res0: Seq[QuickUser] = Vector(
//   QuickUser(id = 1, name = "Alice", email = "alice@example.com"),
//   QuickUser(id = 2, name = "Bob", email = "bob@example.com")
// )
```

---

## Core Concepts

### Table Definitions

Define tables using case classes with the `Table` typeclass:

```scala
import saferis.*

@tableName("products")
case class Product(
  @generated @key id: Long,      // Auto-generated primary key
  @indexed name: String,          // Single-column index
  @uniqueIndex sku: String,       // Unique index
  price: Double,
  inStock: Boolean = true,        // Has default value
  description: Option[String]     // Nullable
) derives Table
```

#### Annotations

| Annotation | Purpose |
|------------|---------|
| `@tableName("name")` | Specifies the SQL table name |
| `@key` | Marks a primary key column |
| `@generated` | Marks an auto-generated column (identity/auto-increment) |
| `@indexed` | Creates a single-column index |
| `@indexed("name")` | Creates a named index (same name = compound index) |
| `@indexed("name", "condition")` | Creates a partial index with WHERE clause |
| `@uniqueIndex` | Creates a unique index |
| `@uniqueIndex("name", "condition")` | Creates a partial unique index with WHERE clause |
| `@unique("name")` | Creates a named unique constraint (same name = compound) |
| `@label("column_name")` | Maps field to a different column name |

#### Automatic Column Properties

Saferis infers column properties from your Scala types:

| Scala Type | SQL Property |
|------------|--------------|
| `T` (non-Option) | `NOT NULL` |
| `Option[T]` | Nullable |
| Field with default value | `DEFAULT <value>` |

```scala
import saferis.*

@tableName("products")
case class Product(
  @generated @key id: Long,
  @label("product_name") name: String,  // Column is "product_name", NOT NULL
  quantity: Int = 0,                     // NOT NULL, DEFAULT 0
  price: Double,                         // NOT NULL
  notes: Option[String]                  // Nullable (no NOT NULL constraint)
) derives Table
```

```scala
// See the generated SQL with all column properties
ddl.createTableSql[Product]()
// res3: String = """create table if not exists products (
//   id bigint not null generated always as identity primary key,
//   product_name varchar(255) not null,
//   quantity integer not null default 0,
//   price double precision not null,
//   notes varchar(255)
// )"""
```

#### Compound Primary Keys

Use multiple `@key` annotations to create a composite primary key:

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("order_items")
case class OrderItem(
  @key orderId: Long,
  @key productId: Long,
  quantity: Int
) derives Table
```

```scala
// Show the generated CREATE TABLE SQL with compound primary key
ddl.createTableSql[OrderItem]()
// res5: String = """create table if not exists order_items (
//   orderId bigint not null,
//   productId bigint not null,
//   quantity integer not null,
//   primary key (orderId, productId)
// )"""
```

```scala
// Create and use the table
run {
  xa.run(for
    _ <- ddl.createTable[OrderItem]()
    _ <- dml.insert(OrderItem(1, 100, 2))
    _ <- dml.insert(OrderItem(1, 101, 1))  // Same order, different product - OK
    _ <- dml.insert(OrderItem(2, 100, 3))  // Different order, same product - OK
    items <- sql"SELECT * FROM ${Table[OrderItem]}".query[OrderItem]
  yield items)
}
// res6: Seq[OrderItem] = Vector(
//   OrderItem(orderId = 1L, productId = 100L, quantity = 2),
//   OrderItem(orderId = 1L, productId = 101L, quantity = 1),
//   OrderItem(orderId = 2L, productId = 100L, quantity = 3)
// )
```

#### Compound Indexes and Constraints

Use the same name to group columns:

```scala
import saferis.*

@tableName("events")
case class Event(
  @generated @key id: Long,
  @indexed("tenant_user_idx") tenantId: String,   // Part of compound index
  @indexed("tenant_user_idx") userId: Int,        // Part of compound index
  @unique("natural_key") source: String,          // Part of compound unique
  @unique("natural_key") externalId: String,      // Part of compound unique
  createdAt: java.time.Instant
) derives Table
```

### SQL Interpolation

The `sql"..."` interpolator provides SQL injection protection:

```scala
import saferis.*

@tableName("products")
case class Product(
  @generated @key id: Long,
  @indexed name: String,
  @uniqueIndex sku: String,
  price: Double,
  inStock: Boolean = true,
  description: Option[String]
) derives Table

val products = Table[Product]
```

```scala
// Values are safely parameterized
val minPrice = 10.0
// minPrice: Double = 10.0
val query = sql"SELECT * FROM $products WHERE ${products.price} > $minPrice"
// query: SqlFragment = SqlFragment(
//   sql = "SELECT * FROM products WHERE price > ?",
//   writes = Vector(saferis.Write@17ce8173)
// )
query.show
// res9: String = "SELECT * FROM products WHERE price > 10.0"
```

```scala
// Table and column references are properly escaped
sql"SELECT ${products.name}, ${products.price} FROM $products WHERE ${products.inStock} = ${true}".show
// res10: String = "SELECT name, price FROM products WHERE inStock = true"
```

### The Transactor

The `Transactor` wraps a `ConnectionProvider` and executes SQL operations:

```scala
import saferis.*
import zio.*
import javax.sql.DataSource

// Assuming you have a DataSource
val dataSource: DataSource = ???

@tableName("users")
case class User(@generated @key id: Int, name: String) derives Table

// From a ConnectionProvider
val provider = ConnectionProvider.FromDataSource(dataSource)
val xa = Transactor(provider, _ => (), None)

// Execute operations
val result = xa.run(
  sql"SELECT * FROM ${Table[User]}".query[User]
)
```

---

## Dialect System

Saferis supports multiple databases with compile-time type safety. Each dialect provides database-specific SQL generation and type mappings.

### Available Dialects

```scala
import saferis.*

// PostgreSQL (default) - full feature support
// No additional import needed - it's the default

// MySQL
import saferis.mysql.{given}

// SQLite
import saferis.sqlite.{given}

// Spark SQL
import saferis.spark.{given}
```

### Feature Comparison

| Feature | PostgreSQL | MySQL | SQLite | Spark |
|---------|------------|-------|--------|-------|
| RETURNING clause | Yes | No | Yes | No |
| JSON operations | Yes | Yes | No | No |
| Array types | Yes | No | No | No |
| UPSERT | Yes | No | No | No |
| IF NOT EXISTS (indexes) | Yes | No | Yes | Yes |
| Window functions | Yes | Yes | Yes | Yes |
| CTEs | Yes | Yes | Yes | Yes |

### Type Mappings

| JDBC Type | PostgreSQL | MySQL | SQLite |
|-----------|------------|-------|--------|
| VARCHAR | varchar(255) | varchar(255) | text |
| INTEGER | integer | int | integer |
| BIGINT | bigint | bigint | integer |
| DOUBLE | double precision | double | real |
| BOOLEAN | boolean | boolean | integer |
| TIMESTAMP | timestamp | timestamp | text |

### Auto-Increment Syntax

| Database | Syntax |
|----------|--------|
| PostgreSQL | `GENERATED ALWAYS AS IDENTITY` |
| MySQL | `AUTO_INCREMENT` |
| SQLite | `AUTOINCREMENT` |

---

## Data Definition Layer (DDL)

The DDL layer provides type-safe schema management operations.

### Creating Tables

```scala
import saferis.*

@tableName("customers")
case class Customer(
  @generated @key id: Long,
  @indexed name: String,
  @uniqueIndex email: String,
  status: String = "active",
  notes: Option[String]
) derives Table
```

```scala
// Show index creation SQL
ddl.createIndexesSql[Customer]()
// res14: String = """create index if not exists idx_customers_name on customers (name)
// create unique index if not exists idx_customers_email on customers (email)"""
```

### Running DDL Operations

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("customers")
case class Customer(
  @generated @key id: Long,
  @indexed name: String,
  @uniqueIndex email: String,
  status: String = "active",
  notes: Option[String]
) derives Table
```

```scala
// Actually create the table
run { xa.run(ddl.createTable[Customer]()) }
// res16: Int = 0
```

### Other DDL Operations

```scala
import saferis.*

@tableName("customers")
case class Customer(
  @generated @key id: Long,
  @indexed name: String,
  @uniqueIndex email: String,
  status: String = "active",
  notes: Option[String]
) derives Table

// Drop table
ddl.dropTable[Customer](ifExists = true)

// Truncate table
ddl.truncateTable[Customer]()

// Add column
ddl.addColumn[Customer, String]("new_column")

// Drop column
ddl.dropColumn[Customer]("old_column")

// Drop index
ddl.dropIndex("idx_name", ifExists = true)
```

### createTable Options

The `createTable` function accepts optional parameters:

```scala
import saferis.*

@tableName("my_table")
case class MyTable(@key id: Int, name: String) derives Table

// Default: creates table and all indexes
ddl.createTable[MyTable]()

// Skip table creation if it already exists
ddl.createTable[MyTable](ifNotExists = true)

// Create table without indexes (create them separately later)
ddl.createTable[MyTable](createIndexes = false)

// Then create indexes separately
ddl.createIndexes[MyTable]()
```

### Partial Indexes

Partial indexes only index rows that match a condition, making them smaller and faster for filtered queries.

#### Via Annotation

Use `@indexed("name", "condition")` or `@uniqueIndex("name", "condition")`:

```scala
import saferis.*

@tableName("tasks")
case class TaskWithIndex(
  @generated @key id: Int,
  status: String,
  @indexed("idx_pending_tasks", "status = 'pending'") priority: Int
) derives Table
```

```scala
// Show the generated index SQL with WHERE clause
ddl.createIndexesSql[TaskWithIndex]()
// res20: String = "create index if not exists idx_pending_tasks on tasks (priority) where status = 'pending'"
```

#### Via Runtime API

Create partial indexes programmatically:

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("jobs")
case class Job(@generated @key id: Int, status: String, retryAt: Option[java.time.Instant]) derives Table
```

```scala
run {
  xa.run(for
    _ <- ddl.createTable[Job](createIndexes = false)
    // Create a partial index for pending jobs with retry times
    _ <- ddl.createIndex[Job](
      "idx_pending_retry",
      Seq("retryat"),
      where = Some("status = 'pending'")
    )
    _ <- dml.insert(Job(-1, "pending", Some(java.time.Instant.now())))
    _ <- dml.insert(Job(-1, "completed", None))
    jobs <- sql"SELECT * FROM ${Table[Job]}".query[Job]
  yield jobs)
}
// res22: Seq[Job] = Vector(
//   Job(id = 1, status = "pending", retryAt = Some(2026-01-19T22:06:16.026170Z)),
//   Job(id = 2, status = "completed", retryAt = None)
// )
```

#### Partial Unique Indexes

Enforce uniqueness only for specific rows:

```scala
import saferis.*

@tableName("users")
case class UserWithPartialUnique(
  @generated @key id: Int,
  @uniqueIndex("uidx_active_email", "active = true") email: String,
  active: Boolean
) derives Table
```

```scala
// The unique constraint only applies when active = true
// Multiple inactive users can have the same email
ddl.createIndexesSql[UserWithPartialUnique]()
// res24: String = "create unique index if not exists uidx_active_email on users (email) where active = true"
```

---

## Foreign Key Support

Saferis provides a type-safe builder API for defining foreign key constraints. The builder uses Scala 3 macros to extract column names at compile time, ensuring type safety and catching errors early.

### Basic Foreign Keys

Define a foreign key using `foreignKey(_.column).references[Table](_.column)`:

```scala
import saferis.*
import saferis.TableAspects.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

// Parent table
@tableName("fk_users")
case class FkUser(@generated @key id: Int, name: String) derives Table

// Child table with foreign key column
@tableName("fk_orders")
case class FkOrder(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table
```

```scala
// Define the foreign key relationship
val orders = Table[FkOrder]
  @@ foreignKey[FkOrder, Int](_.userId).references[FkUser](_.id)
// orders: [A >: Nothing <: Product] =>> Instance[A] {
  val id: Column[Int]
  val userId: Column[Int]
  val amount: Column[BigDecimal]
  def getByKey(id: Int): TypedFragment
} = Instance(
//   tableName = "fk_orders",
//   columns = ArraySeq(
//     Column(
//       name = "id",
//       label = "id",
//       isKey = true,
//       isGenerated = true,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "userId",
//       label = "userId",
//       isKey = false,
//       isGenerated = false,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "amount",
//       label = "amount",
//       isKey = false,
//       isGenerated = false,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
// ...

// See the generated SQL
ddl.createTableSql(orders)
// res26: String = """create table if not exists fk_orders (
//   id integer not null generated always as identity primary key,
//   userId integer not null,
//   amount numeric not null,
//   foreign key (userId) references fk_users (id)
// )"""
```

```scala
// Create tables with foreign key constraint
run {
  xa.run(for
    _ <- ddl.createTable[FkUser]()
    _ <- ddl.createTable(orders)
    _ <- dml.insert(FkUser(-1, "Alice"))
    _ <- dml.insert(FkOrder(-1, 1, BigDecimal(99.99)))
    result <- sql"SELECT * FROM ${Table[FkOrder]}".query[FkOrder]
  yield result)
}
// res27: Seq[FkOrder] = Vector(FkOrder(id = 1, userId = 1, amount = 99.99))
```

### ON DELETE and ON UPDATE Actions

Specify what happens when a referenced row is deleted or updated:

```scala
import saferis.*
import saferis.TableAspects.*

@tableName("action_users")
case class ActionUser(@generated @key id: Int, name: String) derives Table

@tableName("action_orders")
case class ActionOrder(@generated @key id: Int, userId: Int) derives Table
```

```scala
// CASCADE: Deleting a user deletes their orders
val cascadeOrders = Table[ActionOrder]
  @@ foreignKey[ActionOrder, Int](_.userId).references[ActionUser](_.id)
      .onDelete(ForeignKeyAction.Cascade)
// cascadeOrders: [A >: Nothing <: Product] =>> Instance[A] {
  val id: Column[Int]
  val userId: Column[Int]
  def getByKey(id: Int): TypedFragment
} = Instance(
//   tableName = "action_orders",
//   columns = ArraySeq(
//     Column(
//       name = "id",
//       label = "id",
//       isKey = true,
//       isGenerated = true,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "userId",
//       label = "userId",
//       isKey = false,
//       isGenerated = false,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     )
//   ),
//   alias = None,
//   foreignKeys = Vector(
//     ForeignKeySpec(
//       fromColumns = List("userId"),
//       toTable = "action_users",
//       toColumns = List("id"),
//       onDelete = Cascade,
//       onUpdate = NoAction,
//       constraintName = None
//     )
//   )
// )

ddl.createTableSql(cascadeOrders)
// res29: String = """create table if not exists action_orders (
//   id integer not null generated always as identity primary key,
//   userId integer not null,
//   foreign key (userId) references action_users (id) on delete cascade
// )"""
```

```scala
// SET NULL: Sets FK column to NULL when parent is deleted
// Note: The FK column should be nullable (Option[T]) for SET NULL to work properly at runtime
val setNullOrders = Table[ActionOrder]
  @@ foreignKey[ActionOrder, Int](_.userId).references[ActionUser](_.id)
      .onDelete(ForeignKeyAction.SetNull)
// setNullOrders: [A >: Nothing <: Product] =>> Instance[A] {
  val id: Column[Int]
  val userId: Column[Int]
  def getByKey(id: Int): TypedFragment
} = Instance(
//   tableName = "action_orders",
//   columns = ArraySeq(
//     Column(
//       name = "id",
//       label = "id",
//       isKey = true,
//       isGenerated = true,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "userId",
//       label = "userId",
//       isKey = false,
//       isGenerated = false,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     )
//   ),
//   alias = None,
//   foreignKeys = Vector(
//     ForeignKeySpec(
//       fromColumns = List("userId"),
//       toTable = "action_users",
//       toColumns = List("id"),
//       onDelete = SetNull,
//       onUpdate = NoAction,
//       constraintName = None
//     )
//   )
// )

ddl.createTableSql(setNullOrders)
// res30: String = """create table if not exists action_orders (
//   id integer not null generated always as identity primary key,
//   userId integer not null,
//   foreign key (userId) references action_users (id) on delete set null
// )"""
```

Available actions:

| Action | Description |
|--------|-------------|
| `ForeignKeyAction.NoAction` | Fail if referenced row is deleted/updated (default) |
| `ForeignKeyAction.Cascade` | Delete/update child rows when parent is deleted/updated |
| `ForeignKeyAction.SetNull` | Set the FK column to NULL |
| `ForeignKeyAction.SetDefault` | Set the FK column to its default value |
| `ForeignKeyAction.Restrict` | Fail immediately (same as NoAction but checked immediately) |

### Named Constraints

Give your foreign key constraint a custom name:

```scala
import saferis.*
import saferis.TableAspects.*

@tableName("named_users")
case class NamedUser(@generated @key id: Int, name: String) derives Table

@tableName("named_orders")
case class NamedOrder(@generated @key id: Int, userId: Int) derives Table
```

```scala
val namedOrders = Table[NamedOrder]
  @@ foreignKey[NamedOrder, Int](_.userId).references[NamedUser](_.id)
      .onDelete(ForeignKeyAction.Cascade)
      .named("fk_order_user")
// namedOrders: [A >: Nothing <: Product] =>> Instance[A] {
  val id: Column[Int]
  val userId: Column[Int]
  def getByKey(id: Int): TypedFragment
} = Instance(
//   tableName = "named_orders",
//   columns = ArraySeq(
//     Column(
//       name = "id",
//       label = "id",
//       isKey = true,
//       isGenerated = true,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "userId",
//       label = "userId",
//       isKey = false,
//       isGenerated = false,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     )
//   ),
//   alias = None,
//   foreignKeys = Vector(
//     ForeignKeySpec(
//       fromColumns = List("userId"),
//       toTable = "named_users",
//       toColumns = List("id"),
//       onDelete = Cascade,
//       onUpdate = NoAction,
//       constraintName = Some("fk_order_user")
//     )
//   )
// )

ddl.createTableSql(namedOrders)
// res32: String = """create table if not exists named_orders (
//   id integer not null generated always as identity primary key,
//   userId integer not null,
//   constraint fk_order_user foreign key (userId) references named_users (id) on delete cascade
// )"""
```

### Compound Foreign Keys

Reference a composite primary key with multiple columns:

```scala
import saferis.*
import saferis.TableAspects.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

// Parent with compound primary key
@tableName("compound_products")
case class CompoundProduct(@key tenantId: String, @key sku: String, name: String) derives Table

// Child referencing the compound key
@tableName("compound_inventory")
case class CompoundInventory(
  @generated @key id: Int,
  tenantId: String,
  productSku: String,
  quantity: Int
) derives Table
```

```scala
// Reference multiple columns
val inventory = Table[CompoundInventory]
  @@ foreignKey[CompoundInventory, String, String](_.tenantId, _.productSku)
      .references[CompoundProduct](_.tenantId, _.sku)
      .onDelete(ForeignKeyAction.Cascade)
// inventory: [A >: Nothing <: Product] =>> Instance[A] {
  val id: Column[Int]
  val tenantId: Column[String]
  val productSku: Column[String]
  val quantity: Column[Int]
  def getByKey(id: Int): TypedFragment
} = Instance(
//   tableName = "compound_inventory",
//   columns = ArraySeq(
//     Column(
//       name = "id",
//       label = "id",
//       isKey = true,
//       isGenerated = true,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "tenantId",
//       label = "tenantId",
//       isKey = false,
//       isGenerated = false,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "productSku",
//       label = "productSku",
//       isKey = false,
//       isGenerated = false,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
// ...

ddl.createTableSql(inventory)
// res34: String = """create table if not exists compound_inventory (
//   id integer not null generated always as identity primary key,
//   tenantId varchar(255) not null,
//   productSku varchar(255) not null,
//   quantity integer not null,
//   foreign key (tenantId, productSku) references compound_products (tenantId, sku) on delete cascade
// )"""
```

```scala
// Create and use tables with compound FK
run {
  xa.run(for
    _ <- ddl.createTable[CompoundProduct]()
    _ <- ddl.createTable(inventory)
    _ <- dml.insert(CompoundProduct("tenant1", "SKU-001", "Widget"))
    _ <- dml.insert(CompoundInventory(-1, "tenant1", "SKU-001", 100))
    result <- sql"SELECT * FROM ${Table[CompoundInventory]}".query[CompoundInventory]
  yield result)
}
// res35: Seq[CompoundInventory] = Vector(
//   CompoundInventory(
//     id = 1,
//     tenantId = "tenant1",
//     productSku = "SKU-001",
//     quantity = 100
//   )
// )
```

### Multiple Foreign Keys

Chain multiple foreign keys using the `@@` operator:

```scala
import saferis.*
import saferis.TableAspects.*

@tableName("multi_users")
case class MultiUser(@generated @key id: Int, name: String) derives Table

@tableName("multi_products")
case class MultiProduct(@generated @key id: Int, name: String) derives Table

@tableName("multi_order_items")
case class MultiOrderItem(
  @generated @key id: Int,
  userId: Int,
  productId: Int,
  quantity: Int
) derives Table
```

```scala
// Multiple foreign keys on one table
val orderItems = Table[MultiOrderItem]
  @@ foreignKey[MultiOrderItem, Int](_.userId).references[MultiUser](_.id).onDelete(ForeignKeyAction.Cascade)
  @@ foreignKey[MultiOrderItem, Int](_.productId).references[MultiProduct](_.id).onDelete(ForeignKeyAction.Restrict)
// orderItems: [A >: Nothing <: Product] =>> Instance[A] {
  val id: Column[Int]
  val userId: Column[Int]
  val productId: Column[Int]
  val quantity: Column[Int]
  def getByKey(id: Int): TypedFragment
} = Instance(
//   tableName = "multi_order_items",
//   columns = ArraySeq(
//     Column(
//       name = "id",
//       label = "id",
//       isKey = true,
//       isGenerated = true,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "userId",
//       label = "userId",
//       isKey = false,
//       isGenerated = false,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "productId",
//       label = "productId",
//       isKey = false,
//       isGenerated = false,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
// ...

ddl.createTableSql(orderItems)
// res37: String = """create table if not exists multi_order_items (
//   id integer not null generated always as identity primary key,
//   userId integer not null,
//   productId integer not null,
//   quantity integer not null,
//   foreign key (userId) references multi_users (id) on delete cascade,
//   foreign key (productId) references multi_products (id) on delete restrict
// )"""
```

### Type Safety

The foreign key builder provides compile-time type safety. The column types must match between the source and referenced columns:

```scala
import saferis.*
import saferis.TableAspects.*

@tableName("type_users")
case class TypeUser(@generated @key id: Int, name: String) derives Table

@tableName("type_orders")
case class TypeOrder(@generated @key id: Int, userId: Int, userName: String) derives Table

// This compiles - Int matches Int
val valid = foreignKey[TypeOrder, Int](_.userId).references[TypeUser](_.id)

// This would NOT compile - String doesn't match Int
// val invalid = foreignKey[TypeOrder, String](_.userName).references[TypeUser](_.id)
```

---

## Data Manipulation Layer (DML)

The DML layer provides CRUD operations for your tables.

```scala
import saferis.*

@tableName("tasks")
case class Task(@generated @key id: Int, title: String, done: Boolean) derives Table

val tasks = Table[Task]
```

### Basic CRUD Operations

```scala
// SELECT query
sql"SELECT * FROM $tasks WHERE ${tasks.done} = ${false}".show
// res40: String = "SELECT * FROM tasks WHERE done = false"
```

```scala
// SELECT with multiple conditions
sql"SELECT * FROM $tasks WHERE ${tasks.title} LIKE ${"Learn%"} AND ${tasks.done} = ${false}".show
// res41: String = "SELECT * FROM tasks WHERE title LIKE 'Learn%' AND done = false"
```

### Running DML Operations

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("tasks")
case class Task(@generated @key id: Int, title: String, done: Boolean) derives Table

val tasks = Table[Task]
```

```scala
// Full workflow with actual database
run {
  xa.run(for
    _ <- ddl.createTable[Task]()
    _ <- dml.insert(Task(-1, "Task 1", false))
    _ <- dml.insert(Task(-1, "Task 2", false))
    _ <- dml.insert(Task(-1, "Task 3", true))
    all <- sql"SELECT * FROM $tasks".query[Task]
    done <- sql"SELECT * FROM $tasks WHERE ${tasks.done} = ${true}".query[Task]
  yield (all, done))
}
// res43: Tuple2[Seq[Task], Seq[Task]] = (
//   Vector(
//     Task(id = 1, title = "Task 1", done = false),
//     Task(id = 2, title = "Task 2", done = false),
//     Task(id = 3, title = "Task 3", done = true)
//   ),
//   Vector(Task(id = 3, title = "Task 3", done = true))
// )
```

### Insert with RETURNING

For databases that support it (PostgreSQL, SQLite), get the inserted row back:

```scala
run { xa.run(dml.insertReturning(Task(-1, "New Task", false))) }
// res44: Task = Task(id = 4, title = "New Task", done = false)
```

### Custom Queries

Use the `sql` interpolator for any query:

```scala
// Query with ordering
run { xa.run(sql"SELECT * FROM $tasks ORDER BY ${tasks.title}".query[Task]) }
// res45: Seq[Task] = Vector(
//   Task(id = 4, title = "New Task", done = false),
//   Task(id = 1, title = "Task 1", done = false),
//   Task(id = 2, title = "Task 2", done = false),
//   Task(id = 3, title = "Task 3", done = true)
// )
```

### Update Operations

Update records by primary key or with custom conditions:

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("items")
case class Item(@generated @key id: Int, name: String, quantity: Int) derives Table
val items = Table[Item]
```

```scala
run {
  xa.run(for
    _ <- ddl.createTable[Item]()
    inserted <- dml.insertReturning(Item(-1, "Widget", 10))

    // Update by primary key
    _ <- dml.update(inserted.copy(quantity = 15))

    // Update with RETURNING (get the updated row back)
    updated <- dml.updateReturning(inserted.copy(name = "Super Widget", quantity = 20))

    // Verify the update
    result <- sql"SELECT * FROM $items WHERE ${items.id} = ${inserted.id}".queryOne[Item]
  yield (updated, result))
}
// res47: Tuple2[Item, Option[Item]] = (
//   Item(id = 1, name = "Super Widget", quantity = 20),
//   Some(Item(id = 1, name = "Super Widget", quantity = 20))
// )
```

Update multiple rows with a WHERE clause:

```scala
run {
  xa.run(for
    _ <- dml.insert(Item(-1, "Gadget A", 5))
    _ <- dml.insert(Item(-1, "Gadget B", 3))

    // Update all items with quantity < 10
    rowsUpdated <- dml.updateWhere(
      Item(-1, "Low Stock Item", 0),  // Values to set (id ignored)
      sql"${items.quantity} < 10"
    )

    all <- sql"SELECT * FROM $items".query[Item]
  yield (rowsUpdated, all))
}
// res48: Tuple2[Int, Seq[Item]] = (
//   2,
//   Vector(
//     Item(id = 1, name = "Super Widget", quantity = 20),
//     Item(id = 2, name = "Low Stock Item", quantity = 0),
//     Item(id = 3, name = "Low Stock Item", quantity = 0)
//   )
// )
```

### Delete Operations

Delete records by primary key or with custom conditions:

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("logs")
case class LogEntry(@generated @key id: Int, level: String, message: String) derives Table
val logs = Table[LogEntry]
```

```scala
run {
  xa.run(for
    _ <- ddl.createTable[LogEntry]()
    entry1 <- dml.insertReturning(LogEntry(-1, "INFO", "Application started"))
    entry2 <- dml.insertReturning(LogEntry(-1, "DEBUG", "Processing request"))
    _ <- dml.insertReturning(LogEntry(-1, "ERROR", "Something failed"))

    // Delete by primary key
    _ <- dml.delete(entry2)

    // Delete with RETURNING (get the deleted row back)
    deleted <- dml.deleteReturning(entry1)

    remaining <- sql"SELECT * FROM $logs".query[LogEntry]
  yield (deleted, remaining))
}
// res50: Tuple2[LogEntry, Seq[LogEntry]] = (
//   LogEntry(id = 1, level = "INFO", message = "Application started"),
//   Vector(LogEntry(id = 3, level = "ERROR", message = "Something failed"))
// )
```

Delete multiple rows with a WHERE clause:

```scala
run {
  xa.run(for
    _ <- dml.insert(LogEntry(-1, "DEBUG", "Debug 1"))
    _ <- dml.insert(LogEntry(-1, "DEBUG", "Debug 2"))
    _ <- dml.insert(LogEntry(-1, "INFO", "Important info"))

    // Delete all DEBUG entries
    rowsDeleted <- dml.deleteWhere[LogEntry](sql"${logs.level} = ${"DEBUG"}")

    // Delete with WHERE and RETURNING (get all deleted rows)
    deletedEntries <- dml.deleteWhereReturning[LogEntry](sql"${logs.level} = ${"ERROR"}")

    remaining <- sql"SELECT * FROM $logs".query[LogEntry]
  yield (rowsDeleted, deletedEntries, remaining))
}
// res51: Tuple3[Int, Seq[LogEntry], Seq[LogEntry]] = (
//   2,
//   Vector(LogEntry(id = 3, level = "ERROR", message = "Something failed")),
//   Vector(LogEntry(id = 6, level = "INFO", message = "Important info"))
// )
```

---

## Type-Safe Joins

Saferis provides a fluent, type-safe API for building SQL JOIN queries. The API uses compile-time macros to extract column names from selectors like `_.userId`, ensuring type safety and catching errors at compile time rather than runtime.

### Basic Inner Join

The simplest join connects two tables with an equality condition:

```scala
import saferis.*
import saferis.postgres.given
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("join_demo_users")
case class JoinUser(@generated @key id: Int, name: String, email: String) derives Table

@tableName("join_demo_orders")
case class JoinOrder(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table
```

```scala
// Build a simple inner join
val joinQuery = JoinSpec[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .build
// joinQuery: SqlFragment = SqlFragment(
//   sql = "select * from join_demo_users as t1 inner join join_demo_orders as t2 on \"t1\".id = \"t2\".userId",
//   writes = List()
// )

joinQuery.sql
// res53: String = "select * from join_demo_users as t1 inner join join_demo_orders as t2 on \"t1\".id = \"t2\".userId"
```

```scala
// Execute the join
run {
  xa.run(for
    _ <- ddl.createTable[JoinUser]()
    _ <- ddl.createTable[JoinOrder]()
    _ <- dml.insert(JoinUser(-1, "Alice", "alice@test.com"))
    _ <- dml.insert(JoinUser(-1, "Bob", "bob@test.com"))
    _ <- dml.insert(JoinOrder(-1, 1, BigDecimal(100)))
    _ <- dml.insert(JoinOrder(-1, 1, BigDecimal(200)))
    _ <- dml.insert(JoinOrder(-1, 2, BigDecimal(150)))
    result <- sql"${JoinSpec[JoinUser].innerJoin[JoinOrder].on(_.id).eq(_.userId).build}".query[JoinUser]
  yield result)
}
// res54: Seq[JoinUser] = Vector(
//   JoinUser(id = 1, name = "Alice", email = "alice@test.com"),
//   JoinUser(id = 1, name = "Alice", email = "alice@test.com"),
//   JoinUser(id = 2, name = "Bob", email = "bob@test.com")
// )
```

### Join Types

All standard SQL join types are supported:

```scala
import saferis.*
import saferis.postgres.given

@tableName("jt_users")
case class JtUser(@generated @key id: Int, name: String) derives Table

@tableName("jt_orders")
case class JtOrder(@generated @key id: Int, userId: Int) derives Table
```

```scala
// Inner Join - only matching rows
JoinSpec[JtUser].innerJoin[JtOrder].on(_.id).eq(_.userId).build.sql
// res56: String = "select * from jt_users as t7 inner join jt_orders as t8 on \"t7\".id = \"t8\".userId"
```

```scala
// Left Join - all from left, matching from right
JoinSpec[JtUser].leftJoin[JtOrder].on(_.id).eq(_.userId).build.sql
// res57: String = "select * from jt_users as t9 left join jt_orders as t10 on \"t9\".id = \"t10\".userId"
```

```scala
// Right Join - all from right, matching from left
JoinSpec[JtUser].rightJoin[JtOrder].on(_.id).eq(_.userId).build.sql
// res58: String = "select * from jt_users as t11 right join jt_orders as t12 on \"t11\".id = \"t12\".userId"
```

```scala
// Full Join - all rows from both tables
JoinSpec[JtUser].fullJoin[JtOrder].on(_.id).eq(_.userId).build.sql
// res59: String = "select * from jt_users as t13 full join jt_orders as t14 on \"t13\".id = \"t14\".userId"
```

### Comparison Operators in ON Clause

Beyond equality, you can use various comparison operators:

```scala
import saferis.*
import saferis.postgres.given

@tableName("op_users")
case class OpUser(@generated @key id: Int, maxBudget: Int) derives Table

@tableName("op_orders")
case class OpOrder(@generated @key id: Int, userId: Int, amount: Int) derives Table
```

```scala
// Not equal
JoinSpec[OpUser].innerJoin[OpOrder].on(_.id).neq(_.userId).build.sql
// res61: String = "select * from op_users as t15 inner join op_orders as t16 on \"t15\".id <> \"t16\".userId"
```

```scala
// Less than
JoinSpec[OpUser].innerJoin[OpOrder].on(_.maxBudget).lt(_.amount).build.sql
// res62: String = "select * from op_users as t17 inner join op_orders as t18 on \"t17\".maxBudget < \"t18\".amount"
```

```scala
// Less than or equal
JoinSpec[OpUser].innerJoin[OpOrder].on(_.maxBudget).lte(_.amount).build.sql
// res63: String = "select * from op_users as t19 inner join op_orders as t20 on \"t19\".maxBudget <= \"t20\".amount"
```

```scala
// Greater than
JoinSpec[OpUser].innerJoin[OpOrder].on(_.maxBudget).gt(_.amount).build.sql
// res64: String = "select * from op_users as t21 inner join op_orders as t22 on \"t21\".maxBudget > \"t22\".amount"
```

```scala
// Greater than or equal
JoinSpec[OpUser].innerJoin[OpOrder].on(_.maxBudget).gte(_.amount).build.sql
// res65: String = "select * from op_users as t23 inner join op_orders as t24 on \"t23\".maxBudget >= \"t24\".amount"
```

```scala
// Custom operator
JoinSpec[OpUser].innerJoin[OpOrder].on(_.id).op(JoinOperator.Gte)(_.userId).build.sql
// res66: String = "select * from op_users as t25 inner join op_orders as t26 on \"t25\".id >= \"t26\".userId"
```

### IS NULL and IS NOT NULL in ON Clause

Check for null values in the ON condition:

```scala
import saferis.*
import saferis.postgres.given

@tableName("null_users")
case class NullUser(@generated @key id: Int, name: String) derives Table

@tableName("null_orders")
case class NullOrder(@generated @key id: Int, userId: Int, deletedAt: Option[java.time.Instant]) derives Table
```

```scala
// IS NULL check
JoinSpec[NullUser]
  .innerJoin[NullOrder].on(_.id).eq(_.userId)
  .andRight(_.deletedAt).isNull()
  .build.sql
// res68: String = "select * from null_users as t27 inner join null_orders as t28 on \"t27\".id = \"t28\".userId AND \"t28\".deletedAt IS NULL"
```

```scala
// IS NOT NULL check
JoinSpec[NullUser]
  .innerJoin[NullOrder].on(_.id).eq(_.userId)
  .andRight(_.deletedAt).isNotNull()
  .build.sql
// res69: String = "select * from null_users as t29 inner join null_orders as t30 on \"t29\".id = \"t30\".userId AND \"t30\".deletedAt IS NOT NULL"
```

### Chaining ON Conditions

Add multiple conditions to the ON clause with `and()`:

```scala
import saferis.*
import saferis.postgres.given

@tableName("chain_users")
case class ChainUser(@generated @key id: Int, tenantId: String, name: String) derives Table

@tableName("chain_orders")
case class ChainOrder(@generated @key id: Int, userId: Int, tenantId: String) derives Table
```

```scala
// Multiple ON conditions
JoinSpec[ChainUser]
  .innerJoin[ChainOrder].on(_.id).eq(_.userId)
  .and(_.tenantId).eq(_.tenantId)
  .build.sql
// res71: String = "select * from chain_users as t31 inner join chain_orders as t32 on \"t31\".id = \"t32\".userId AND \"t31\".tenantId = \"t32\".tenantId"
```

Use `andRight()` to add conditions starting from the right (joined) table:

```scala
JoinSpec[ChainUser]
  .innerJoin[ChainOrder].on(_.id).eq(_.userId)
  .andRight(_.tenantId).eq(_.tenantId)
  .build.sql
// res72: String = "select * from chain_users as t33 inner join chain_orders as t34 on \"t33\".id = \"t34\".userId AND \"t34\".tenantId = \"t34\".tenantId"
```

### Type-Safe WHERE Clause

Add WHERE conditions with type-safe column references and parameterized values:

```scala
import saferis.*
import saferis.postgres.given
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("where_users")
case class WhereUser(@generated @key id: Int, name: String, email: String) derives Table

@tableName("where_orders")
case class WhereOrder(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table
```

```scala
// WHERE with literal value (bound as prepared statement parameter)
val whereQuery = JoinSpec[WhereUser]
  .innerJoin[WhereOrder].on(_.id).eq(_.userId)
  .where(_.name).eq("Alice")
  .build
// whereQuery: SqlFragment = SqlFragment(
//   sql = "select * from where_users as t35 inner join where_orders as t36 on \"t35\".id = \"t36\".userId where \"t35\".name = ?",
//   writes = List(saferis.Write@691884f)
// )

// SQL uses ? placeholder - value is safely bound, not interpolated
whereQuery.sql
// res74: String = "select * from where_users as t35 inner join where_orders as t36 on \"t35\".id = \"t36\".userId where \"t35\".name = ?"
```

```scala
// WHERE on the joined table
JoinSpec[WhereUser]
  .innerJoin[WhereOrder].on(_.id).eq(_.userId)
  .whereFrom(_.amount).gte(BigDecimal(100))
  .build.sql
// res75: String = "select * from where_users as t37 inner join where_orders as t38 on \"t37\".id = \"t38\".userId where \"t38\".amount >= ?"
```

### WHERE with IS NULL Pattern

A common pattern with LEFT JOIN is to find rows without matches:

```scala
import saferis.*
import saferis.postgres.given
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("isnull_users")
case class IsNullUser(@generated @key id: Int, name: String) derives Table

@tableName("isnull_orders")
case class IsNullOrder(@generated @key id: Int, userId: Int) derives Table
```

```scala
// Find users without any orders
JoinSpec[IsNullUser]
  .leftJoin[IsNullOrder].on(_.id).eq(_.userId)
  .whereIsNullFrom(_.id)  // Order.id IS NULL means no matching order
  .build.sql
// res77: String = "select * from isnull_users as t39 left join isnull_orders as t40 on \"t39\".id = \"t40\".userId where \"t40\".id IS NULL"
```

```scala
// Execute to find users without orders
run {
  xa.run(for
    _ <- ddl.createTable[IsNullUser]()
    _ <- ddl.createTable[IsNullOrder]()
    _ <- dml.insert(IsNullUser(-1, "Alice"))  // Has order
    _ <- dml.insert(IsNullUser(-1, "Bob"))    // Has order
    _ <- dml.insert(IsNullUser(-1, "Charlie")) // No orders
    _ <- dml.insert(IsNullOrder(-1, 1))
    _ <- dml.insert(IsNullOrder(-1, 2))
    usersWithoutOrders <- sql"${JoinSpec[IsNullUser].leftJoin[IsNullOrder].on(_.id).eq(_.userId).whereIsNullFrom(_.id).build}".query[IsNullUser]
  yield usersWithoutOrders)
}
// res78: Seq[IsNullUser] = Vector(IsNullUser(id = 3, name = "Charlie"))
```

### Chaining WHERE Conditions

Combine multiple WHERE conditions:

```scala
import saferis.*
import saferis.postgres.given

@tableName("chainwhere_users")
case class ChainWhereUser(@generated @key id: Int, name: String, status: String) derives Table

@tableName("chainwhere_orders")
case class ChainWhereOrder(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table
```

```scala
// Multiple WHERE conditions
JoinSpec[ChainWhereUser]
  .innerJoin[ChainWhereOrder].on(_.id).eq(_.userId)
  .where(_.status).eq("active")
  .and(_.name).isNotNull()
  .andFrom(_.amount).gt(BigDecimal(50))
  .build.sql
// res80: String = "select * from chainwhere_users as t45 inner join chainwhere_orders as t46 on \"t45\".id = \"t46\".userId where \"t45\".status = ? and \"t45\".name IS NOT NULL and \"t46\".amount > ?"
```

### Complete Query with ORDER BY, LIMIT, OFFSET

Build complete queries with sorting and pagination:

```scala
import saferis.*
import saferis.postgres.given
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("complete_users")
case class CompleteUser(@generated @key id: Int, name: String, email: String) derives Table

@tableName("complete_orders")
case class CompleteOrder(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table
```

```scala
// Full query with all clauses
val users = Table[CompleteUser]
// users: [A >: Nothing <: Product] =>> Instance[A] {
  val id: Column[Int]
  val name: Column[String]
  val email: Column[String]
  def getByKey(id: Int): TypedFragment
} = Instance(
//   tableName = "complete_users",
//   columns = ArraySeq(
//     Column(
//       name = "id",
//       label = "id",
//       isKey = true,
//       isGenerated = true,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "name",
//       label = "name",
//       isKey = false,
//       isGenerated = false,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
//       indexCondition = None,
//       uniqueIndexCondition = None,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "email",
//       label = "email",
//       isKey = false,
//       isGenerated = false,
//       isIndexed = false,
//       isUniqueIndex = false,
//       isUnique = false,
//       isNullable = false,
//       uniqueGroup = None,
//       indexGroup = None,
//       uniqueIndexGroup = None,
// ...
JoinSpec[CompleteUser]
  .innerJoin[CompleteOrder].on(_.id).eq(_.userId)
  .where(_.name).eq("Alice")
  .andFrom(_.amount).gt(BigDecimal(100))
  .orderBy(users.name.asc)
  .limit(10)
  .offset(20)
  .build.sql
// res82: String = "select * from complete_users as t47 inner join complete_orders as t48 on \"t47\".id = \"t48\".userId where \"t47\".name = ? and \"t48\".amount > ? order by name asc limit 10 offset 20"
```

```scala
// Execute a complete query
run {
  xa.run(for
    _ <- ddl.createTable[CompleteUser]()
    _ <- ddl.createTable[CompleteOrder]()
    _ <- dml.insert(CompleteUser(-1, "Alice", "alice@test.com"))
    _ <- dml.insert(CompleteUser(-1, "Bob", "bob@test.com"))
    _ <- dml.insert(CompleteOrder(-1, 1, BigDecimal(150)))
    _ <- dml.insert(CompleteOrder(-1, 1, BigDecimal(200)))
    _ <- dml.insert(CompleteOrder(-1, 2, BigDecimal(50)))
    result <- sql"${JoinSpec[CompleteUser].innerJoin[CompleteOrder].on(_.id).eq(_.userId).whereFrom(_.amount).gte(BigDecimal(100)).limit(5).build}".query[CompleteUser]
  yield result)
}
// res83: Seq[CompleteUser] = Vector(
//   CompleteUser(id = 1, name = "Alice", email = "alice@test.com"),
//   CompleteUser(id = 1, name = "Alice", email = "alice@test.com")
// )
```

### Available Operators

| Method | SQL Operator | Description |
|--------|-------------|-------------|
| `eq()` | `=` | Equality |
| `neq()` | `<>` | Not equal |
| `lt()` | `<` | Less than |
| `lte()` | `<=` | Less than or equal |
| `gt()` | `>` | Greater than |
| `gte()` | `>=` | Greater than or equal |
| `op(JoinOperator.X)` | Custom | Any JoinOperator |
| `isNull()` | `IS NULL` | Check for NULL |
| `isNotNull()` | `IS NOT NULL` | Check for non-NULL |

### JoinOperator Reference

All available operators in `JoinOperator`:

| Operator | SQL | Notes |
|----------|-----|-------|
| `Eq` | `=` | Standard equality |
| `Neq` | `<>` | Standard inequality |
| `Lt` | `<` | Less than |
| `Lte` | `<=` | Less than or equal |
| `Gt` | `>` | Greater than |
| `Gte` | `>=` | Greater than or equal |
| `Like` | `LIKE` | Pattern matching |
| `ILike` | `ILIKE` | Case-insensitive LIKE (PostgreSQL) |
| `SimilarTo` | `SIMILAR TO` | Regex pattern (PostgreSQL) |
| `RegexMatch` | `~` | Regex match (PostgreSQL) |
| `RegexMatchCI` | `~*` | Case-insensitive regex (PostgreSQL) |
| `IsNull` | `IS NULL` | Null check |
| `IsNotNull` | `IS NOT NULL` | Non-null check |

---

## Pagination

Saferis provides type-safe pagination through `PageSpec`, supporting both offset-based and cursor-based (seek) pagination.

```scala
import saferis.*

@tableName("articles")
case class Article(@generated @key id: Long, title: String, views: Int, published: Boolean) derives Table

val articles = Table[Article]
```

### Offset Pagination

Traditional pagination with LIMIT and OFFSET:

```scala
// Page 3 with 10 items per page
PageSpec[Article]
  .where(sql"${articles.published} = ${true}")
  .orderBy(articles.views, SortOrder.Desc)
  .limit(10)
  .offset(20)
  .build
  .show
// res85: String = "select * from articles where published = true order by views desc limit 10 offset 20"
```

### Cursor/Seek Pagination

More efficient for large datasets - uses indexed lookups instead of scanning:

```scala
// Get next page after a known ID
PageSpec[Article]
  .seekAfter(articles.id, 100L)
  .limit(10)
  .build
  .show
// res86: String = "select * from articles where id > 100 order by id asc limit 10"
```

```scala
// Get previous page before a known ID
PageSpec[Article]
  .seekBefore(articles.id, 50L)
  .limit(10)
  .build
  .show
// res87: String = "select * from articles where id < 50 order by id desc limit 10"
```

### Combined Filters and Sorting

```scala
PageSpec[Article]
  .where(sql"${articles.published} = ${true}")
  .where(sql"${articles.views} > ${100}")
  .orderBy(articles.views, SortOrder.Desc)
  .orderBy(articles.id, SortOrder.Asc)
  .limit(20)
  .build
  .show
// res88: String = "select * from articles where published = true and views > 100 order by views desc, id asc limit 20"
```

### Running Paginated Queries

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("articles")
case class Article(@generated @key id: Long, title: String, views: Int, published: Boolean) derives Table

val articles = Table[Article]
```

```scala
// Setup test data and run pagination
run {
  xa.run(for
    _ <- ddl.createTable[Article]()
    _ <- dml.insert(Article(-1, "First Post", 100, true))
    _ <- dml.insert(Article(-1, "Second Post", 250, true))
    _ <- dml.insert(Article(-1, "Draft", 0, false))
    _ <- dml.insert(Article(-1, "Popular", 1000, true))
    page <- PageSpec[Article]
      .where(sql"${articles.published} = ${true}")
      .orderBy(articles.views, SortOrder.Desc)
      .limit(2)
      .query[Article]
  yield page)
}
// res90: Seq[Article] = Vector(
//   Article(id = 4L, title = "Popular", views = 1000, published = true),
//   Article(id = 2L, title = "Second Post", views = 250, published = true)
// )
```

### Null Handling in Sort Order

Control how NULL values are sorted using `NullOrder`:

```scala
// NullOrder.First - NULLs appear first
PageSpec[Article]
  .orderBy(articles.views, SortOrder.Asc, NullOrder.First)
  .build
  .show
// res91: String = "select * from articles order by views asc nulls first"
```

```scala
// NullOrder.Last - NULLs appear last
PageSpec[Article]
  .orderBy(articles.views, SortOrder.Desc, NullOrder.Last)
  .build
  .show
// res92: String = "select * from articles order by views desc nulls last"
```

```scala
// NullOrder.Default - database default behavior (no NULLS clause)
PageSpec[Article]
  .orderBy(articles.views, SortOrder.Asc, NullOrder.Default)
  .build
  .show
// res93: String = "select * from articles order by views asc"
```

### Column Extensions

For more concise syntax, use column extension methods:

#### Sorting Extensions

```scala
// .asc and .desc for simple sorting
PageSpec[Article]
  .orderBy(articles.views.desc)
  .orderBy(articles.title.asc)
  .build
  .show
// res94: String = "select * from articles order by views desc, title asc"
```

```scala
// Combined with null handling
PageSpec[Article]
  .orderBy(articles.views.descNullsLast)
  .orderBy(articles.title.ascNullsFirst)
  .build
  .show
// res95: String = "select * from articles order by views desc nulls last, title asc nulls first"
```

Available sorting extensions:
- `.asc` - ascending order
- `.desc` - descending order
- `.ascNullsFirst` - ascending, NULLs first
- `.ascNullsLast` - ascending, NULLs last
- `.descNullsFirst` - descending, NULLs first
- `.descNullsLast` - descending, NULLs last

#### Comparison Extensions for Seek Pagination

```scala
// Use .gt (greater than) for seek-after
PageSpec[Article]
  .seek(articles.id.gt(100L))
  .limit(10)
  .build
  .show
// res96: String = "select * from articles where id > 100 order by id asc limit 10"
```

```scala
// Use .lt (less than) for seek-before
PageSpec[Article]
  .seek(articles.id.lt(50L))
  .limit(10)
  .build
  .show
// res97: String = "select * from articles where id < 50 order by id asc limit 10"
```

Available comparison extensions:
- `.gt(value)` - greater than (for forward pagination)
- `.lt(value)` - less than (for backward pagination)

---

## Type-Safe Capabilities

Saferis uses Scala 3's type system to ensure operations are only available when the database supports them.

### Capability Traits

Each dialect mixes in capability traits that enable specific operations:

| Trait | Operations Enabled |
|-------|-------------------|
| `ReturningSupport` | `insertReturning`, `updateReturning`, `deleteReturning` |
| `JsonSupport` | `jsonExtract`, JSON type mappings |
| `ArraySupport` | `arrayContains`, array type mappings |
| `UpsertSupport` | `upsert` |
| `IndexIfNotExistsSupport` | Conditional index creation |

### Using SpecializedDML

The `SpecializedDML` object provides type-safe operations that only compile when the dialect supports them:

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("specialized_items")
case class SpecializedItem(@generated @key id: Int, name: String, category: String) derives Table
```

```scala
// Create table and use insertReturning (PostgreSQL supports RETURNING)
run {
  xa.run(for
    _ <- ddl.createTable[SpecializedItem]()
    inserted <- dml.insertReturning(SpecializedItem(-1, "Widget", "hardware"))
    _ <- dml.insert(SpecializedItem(-1, "Gadget", "electronics"))
    all <- sql"SELECT * FROM ${Table[SpecializedItem]}".query[SpecializedItem]
  yield (inserted, all))
}
// res99: Tuple2[SpecializedItem, Seq[SpecializedItem]] = (
//   SpecializedItem(id = 1, name = "Widget", category = "hardware"),
//   Vector(
//     SpecializedItem(id = 1, name = "Widget", category = "hardware"),
//     SpecializedItem(id = 2, name = "Gadget", category = "electronics")
//   )
// )
```

### Compile-Time Safety

The `SpecializedDML` object provides operations that require specific dialect capabilities. If your dialect doesn't support a capability, the code won't compile:

```scala
// Query the items we inserted
run {
  xa.run(sql"SELECT * FROM ${Table[SpecializedItem]} ORDER BY ${Table[SpecializedItem].id}".query[SpecializedItem])
}
// res100: Seq[SpecializedItem] = Vector(
//   SpecializedItem(id = 1, name = "Widget", category = "hardware"),
//   SpecializedItem(id = 2, name = "Gadget", category = "electronics")
// )
```

Available capability-constrained operations in `SpecializedDML`:

| Operation | Required Capability |
|-----------|-------------------|
| `insertReturning` | `ReturningSupport` |
| `updateReturning` | `ReturningSupport` |
| `deleteReturning` | `ReturningSupport` |
| `upsert` | `UpsertSupport` |
| `jsonExtract` | `JsonSupport` |
| `arrayContains` | `ArraySupport` |

### Generic Functions with Capability Constraints

Write functions that require specific capabilities using intersection types:

```scala
// Functions can require specific dialect capabilities via intersection types.
// This pattern ensures compile-time safety - code won't compile if
// the dialect doesn't support the required capability.

// Example: a function that only works with RETURNING-capable dialects
// def createAndReturn[A <: Product: Table](entity: A)(using
//   Dialect & ReturningSupport,  // <-- Constraint here
//   zio.Trace
// ): ZIO[ConnectionProvider & Scope, Throwable, A] = dml.insertReturning(entity)

// We've already seen this in action - insertReturning works because
// PostgreSQL provides ReturningSupport:
run {
  xa.run(dml.insertReturning(SpecializedItem(-1, "Capability Demo", "demo")))
}
// res101: SpecializedItem = SpecializedItem(
//   id = 3,
//   name = "Capability Demo",
//   category = "demo"
// )
```

---

## Type Support

Saferis provides built-in support for common Scala and Java types.

### java.time Types

All `java.time` types are supported with automatic SQL type mapping:

| Scala Type | PostgreSQL Type | JDBC Type |
|------------|-----------------|-----------|
| `java.time.Instant` | `timestamptz` | TIMESTAMP_WITH_TIMEZONE |
| `java.time.LocalDateTime` | `timestamp` | TIMESTAMP |
| `java.time.LocalDate` | `date` | DATE |
| `java.time.LocalTime` | `time` | TIME |
| `java.time.ZonedDateTime` | `timestamptz` | TIMESTAMP_WITH_TIMEZONE |
| `java.time.OffsetDateTime` | `timestamptz` | TIMESTAMP_WITH_TIMEZONE |

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}
import java.time.*

@tableName("events")
case class Event(
  @generated @key id: Int,
  name: String,
  occurredAt: Instant,
  scheduledFor: Option[LocalDateTime],
  eventDate: LocalDate
) derives Table

val events = Table[Event]
```

```scala
run {
  xa.run(for
    _ <- ddl.createTable[Event]()
    _ <- dml.insert(Event(-1, "Conference", Instant.now(), Some(LocalDateTime.now().plusDays(7)), LocalDate.now()))
    _ <- dml.insert(Event(-1, "Meeting", Instant.now(), None, LocalDate.now().plusDays(1)))
    all <- sql"SELECT * FROM $events".query[Event]
  yield all)
}
// res103: Seq[Event] = Vector(
//   Event(
//     id = 1,
//     name = "Conference",
//     occurredAt = 2026-01-19T22:06:16.464884Z,
//     scheduledFor = Some(2026-01-26T16:06:16.464937),
//     eventDate = 2026-01-19
//   ),
//   Event(
//     id = 2,
//     name = "Meeting",
//     occurredAt = 2026-01-19T22:06:16.469834Z,
//     scheduledFor = None,
//     eventDate = 2026-01-20
//   )
// )
```

### UUID Support

UUIDs can be used as primary keys:

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}
import java.util.UUID

@tableName("entities")
case class Entity(@key id: UUID, name: String) derives Table
val entities = Table[Entity]
```

```scala
run {
  xa.run(for
    _ <- ddl.createTable[Entity]()
    id1 = UUID.randomUUID()
    id2 = UUID.randomUUID()
    _ <- dml.insert(Entity(id1, "First Entity"))
    _ <- dml.insert(Entity(id2, "Second Entity"))
    found <- sql"SELECT * FROM $entities WHERE ${entities.id} = $id1".queryOne[Entity]
  yield found)
}
// res105: Option[Entity] = Some(
//   Entity(id = 037e368e-c2b9-421d-8e28-3d481165fbac, name = "First Entity")
// )
```

### Other Supported Types

| Scala Type | PostgreSQL Type |
|------------|-----------------|
| `String` | `varchar(255)` |
| `Int` | `integer` |
| `Long` | `bigint` |
| `Double` | `double precision` |
| `Float` | `real` |
| `Boolean` | `boolean` |
| `BigDecimal` | `numeric` |
| `Option[T]` | Same as `T`, nullable |

---

## Additional Resources

- [Source Code](https://github.com/russwyte/saferis)
- [Issue Tracker](https://github.com/russwyte/saferis/issues)

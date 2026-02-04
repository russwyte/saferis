# Saferis Documentation

A comprehensive guide to Saferis - the type-safe, resource-safe SQL client library for Scala 3 and ZIO.

## Table of Contents

- [Getting Started](#getting-started)
- [Core Concepts](#core-concepts)
- [Dialect System](#dialect-system)
- [Data Definition Layer (DDL)](#data-definition-layer-ddl)
- [Foreign Key Support](#foreign-key-support)
- [Data Manipulation Layer (DML)](#data-manipulation-layer-dml)
- [Query Builder](#query-builder)
- [Subqueries](#subqueries)
- [Type-Safe Capabilities](#type-safe-capabilities)

---

## Getting Started

### Installation

Add Saferis to your `build.sbt`:

```scala
libraryDependencies += "io.github.russwyte" %% "saferis" % "0.3.0+1-4c080842+20260204-1546"
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
  name: String,
  sku: String,
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
| `@label("column_name")` | Maps field to a different column name |

For indexes, unique constraints, and foreign keys, use the [Schema DSL](#schema-dsl-for-indexes-and-constraints).

#### Automatic Column Properties

Saferis infers column properties from your Scala types:

| Scala Type | SQL Property |
|------------|--------------|
| `T` (non-Option) | `NOT NULL` |
| `Option[T]` | Nullable |
| Field with default value | `DEFAULT <value>` |

```scala
import saferis.*
import saferis.Schema.*

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
Schema[Product].ddl().sql
// res3: String = "create table if not exists products (id bigint generated always as identity primary key not null, product_name varchar(255) not null, quantity integer not null default 0, price double precision not null, notes varchar(255))"
```

#### Compound Primary Keys

Use multiple `@key` annotations to create a composite primary key:

```scala
import saferis.*
import saferis.Schema.*
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
Schema[OrderItem].ddl().sql
// res5: String = """create table if not exists order_items (orderId bigint not null, productId bigint not null, quantity integer not null, primary key (orderId, productId));
// create index if not exists idx_order_items_compound_key on order_items (orderId, productId)"""
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

### SQL Interpolation

The `sql"..."` interpolator provides SQL injection protection:

```scala
import saferis.*

@tableName("products")
case class Product(
  @generated @key id: Long,
  name: String,
  sku: String,
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
//   writes = Vector(saferis.Write@59ae3bc2)
// )
query.show
// res8: String = "SELECT * FROM products WHERE price > 10.0"
```

```scala
// Table and column references are properly escaped
sql"SELECT ${products.name}, ${products.price} FROM $products WHERE ${products.inStock} = ${true}".show
// res9: String = "SELECT name, price FROM products WHERE inStock = true"
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
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("customers")
case class Customer(
  @generated @key id: Long,
  name: String,
  email: String,
  status: String = "active",
  notes: Option[String]
) derives Table
```

```scala
// Actually create the table
run { xa.run(ddl.createTable[Customer]()) }
// res13: Int = 0
```

### Other DDL Operations

```scala
import saferis.*

@tableName("customers")
case class Customer(
  @generated @key id: Long,
  name: String,
  email: String,
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

// Default: creates table and indexes for compound primary keys
ddl.createTable[MyTable]()

// Skip table creation if it already exists
ddl.createTable[MyTable](ifNotExists = true)

// Create table without indexes (create them separately later)
ddl.createTable[MyTable](createIndexes = false)
```

### Schema DSL for Indexes and Constraints

Use the `Schema` DSL to define indexes, unique constraints, and foreign keys with full DDL generation:

```scala
import saferis.*
import saferis.Schema.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("schema_users")
case class SchemaUser(
  @generated @key id: Int,
  name: String,
  email: String,
  status: String
) derives Table
```

```scala
// Simple index
Schema[SchemaUser]
  .withIndex(_.name)
  .ddl().sql
// res17: String = """create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null);
// create index "idx_schema_users_name" on "schema_users" ("name")"""
```

```scala
// Unique index
Schema[SchemaUser]
  .withUniqueIndex(_.email)
  .ddl().sql
// res18: String = """create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null);
// create unique index "idx_schema_users_email" on "schema_users" ("email")"""
```

```scala
// Compound index on multiple columns
Schema[SchemaUser]
  .withIndex(_.name).and(_.status).named("idx_name_status")
  .ddl().sql
// res19: String = """create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null);
// create index "idx_name_status" on "schema_users" ("name", "status")"""
```

```scala
// Partial index with WHERE clause
Schema[SchemaUser]
  .withIndex(_.name).where(_.status).eql("active")
  .ddl().sql
// res20: String = """create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null);
// create index "idx_schema_users_name" on "schema_users" ("name") where status = 'active'"""
```

```scala
// Partial unique index - uniqueness only for active users
Schema[SchemaUser]
  .withUniqueIndex(_.email).where(_.status).eql("active")
  .ddl().sql
// res21: String = """create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null);
// create unique index "idx_schema_users_email" on "schema_users" ("email") where status = 'active'"""
```

```scala
// Multiple indexes chained together
Schema[SchemaUser]
  .withIndex(_.name)
  .withUniqueIndex(_.email)
  .ddl().sql
// res22: String = """create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null);
// create index "idx_schema_users_name" on "schema_users" ("name");
// create unique index "idx_schema_users_email" on "schema_users" ("email")"""
```

```scala
// Compound unique constraint
Schema[SchemaUser]
  .withUniqueConstraint(_.name).and(_.status)
  .ddl().sql
// res23: String = "create table if not exists schema_users (id integer generated always as identity primary key not null, name varchar(255) not null, email varchar(255) not null, status varchar(255) not null, constraint uq_name_status unique (name, status))"
```

### Creating Tables with Schema

Use `.build` to get an Instance for `ddl.createTable`:

```scala
// Build schema with indexes and create table
val schemaUsers = Schema[SchemaUser]
  .withIndex(_.name)
  .withUniqueIndex(_.email)
  .build
// schemaUsers: Instance[SchemaUser] = Instance(
//   tableName = "schema_users",
//   columns = ArraySeq(
//     Column(
//       name = "id",
//       label = "id",
//       isKey = true,
//       isGenerated = true,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "name",
//       label = "name",
//       isKey = false,
//       isGenerated = false,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "email",
//       label = "email",
//       isKey = false,
//       isGenerated = false,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "status",
//       label = "status",
//       isKey = false,
//       isGenerated = false,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     )
//   ),
//   alias = None,
//   foreignKeys = Vector(),
//   indexes = Vector(
//     IndexSpec(columns = List("name"), name = None, unique = false, where = None),
//     IndexSpec(columns = List("email"), name = None, unique = true, where = None)
//   ),
//   uniqueConstraints = Vector()
// )

run { xa.run(ddl.createTable(schemaUsers)) }
// res24: Int = 0
```

### Partial Indexes via Runtime API

Create partial indexes programmatically using `ddl.createIndex`:

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
// res26: Seq[Job] = Vector(
//   Job(id = 1, status = "pending", retryAt = Some(2026-02-04T21:47:09.272875Z)),
//   Job(id = 2, status = "completed", retryAt = None)
// )
```

---

## Foreign Key Support

Saferis provides a type-safe `Schema` builder for defining foreign key constraints. The builder uses Scala 3 macros to extract column names at compile time, ensuring type safety and catching errors early.

### Basic Foreign Keys

Define a foreign key using `Schema[A].withForeignKey(_.column).references[Table](_.column)`:

```scala
import saferis.*
import saferis.Schema.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

// Parent table
@tableName("fk_users")
case class FkUser(@generated @key id: Int, name: String) derives Table

// Child table with foreign key column
@tableName("fk_orders")
case class FkOrder(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table
```

```scala
// Define the foreign key relationship and get DDL
Schema[FkOrder]
  .withForeignKey(_.userId).references[FkUser](_.id)
  .ddl().sql
// res28: String = "create table if not exists fk_orders (id integer generated always as identity primary key not null, userId integer not null, amount numeric not null, foreign key (userId) references fk_users (id))"
```

```scala
// Build the instance for use with ddl.createTable
val orders = Schema[FkOrder]
  .withForeignKey(_.userId).references[FkUser](_.id)
  .build
// orders: Instance[FkOrder] = Instance(
//   tableName = "fk_orders",
//   columns = ArraySeq(
//     Column(
//       name = "id",
//       label = "id",
//       isKey = true,
//       isGenerated = true,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "userId",
//       label = "userId",
//       isKey = false,
//       isGenerated = false,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "amount",
//       label = "amount",
//       isKey = false,
//       isGenerated = false,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     )
//   ),
//   alias = None,
//   foreignKeys = Vector(
//     ForeignKeySpec(
//       fromColumns = List("userId"),
//       toTable = "fk_users",
//       toColumns = List("id"),
//       onDelete = NoAction,
//       onUpdate = NoAction,
//       constraintName = None
//     )
//   ),
//   indexes = Vector(),
//   uniqueConstraints = Vector()
// )

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
// res29: Seq[FkOrder] = Vector(FkOrder(id = 1, userId = 1, amount = 99.99))
```

### ON DELETE and ON UPDATE Actions

Specify what happens when a referenced row is deleted or updated:

```scala
import saferis.*
import saferis.Schema.*

@tableName("action_users")
case class ActionUser(@generated @key id: Int, name: String) derives Table

@tableName("action_orders")
case class ActionOrder(@generated @key id: Int, userId: Int) derives Table
```

```scala
// CASCADE: Deleting a user deletes their orders
Schema[ActionOrder]
  .withForeignKey(_.userId).references[ActionUser](_.id)
  .onDelete(Cascade)
  .ddl().sql
// res31: String = "create table if not exists action_orders (id integer generated always as identity primary key not null, userId integer not null, foreign key (userId) references action_users (id) on delete cascade)"
```

```scala
// SET NULL: Sets FK column to NULL when parent is deleted
// Note: The FK column should be nullable (Option[T]) for SET NULL to work properly at runtime
Schema[ActionOrder]
  .withForeignKey(_.userId).references[ActionUser](_.id)
  .onDelete(SetNull)
  .ddl().sql
// res32: String = "create table if not exists action_orders (id integer generated always as identity primary key not null, userId integer not null, foreign key (userId) references action_users (id) on delete set null)"
```

Available actions (import `saferis.Schema.*` to use short names):

| Action | Description |
|--------|-------------|
| `NoAction` | Fail if referenced row is deleted/updated (default) |
| `Cascade` | Delete/update child rows when parent is deleted/updated |
| `SetNull` | Set the FK column to NULL |
| `SetDefault` | Set the FK column to its default value |
| `Restrict` | Fail immediately (same as NoAction but checked immediately) |

### Named Constraints

Give your foreign key constraint a custom name:

```scala
import saferis.*
import saferis.Schema.*

@tableName("named_users")
case class NamedUser(@generated @key id: Int, name: String) derives Table

@tableName("named_orders")
case class NamedOrder(@generated @key id: Int, userId: Int) derives Table
```

```scala
Schema[NamedOrder]
  .withForeignKey(_.userId).references[NamedUser](_.id)
  .onDelete(Cascade)
  .named("fk_order_user")
  .ddl().sql
// res34: String = "create table if not exists named_orders (id integer generated always as identity primary key not null, userId integer not null, constraint fk_order_user foreign key (userId) references named_users (id) on delete cascade)"
```

### Compound Foreign Keys

Reference a composite primary key with multiple columns using `.and()`:

```scala
import saferis.*
import saferis.Schema.*
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
// Reference multiple columns using .and()
Schema[CompoundInventory]
  .withForeignKey(_.tenantId).and(_.productSku)
  .references[CompoundProduct](_.tenantId).and(_.sku)
  .onDelete(Cascade)
  .ddl().sql
// res36: String = "create table if not exists compound_inventory (id integer generated always as identity primary key not null, tenantId varchar(255) not null, productSku varchar(255) not null, quantity integer not null, foreign key (tenantId, productSku) references compound_products (tenantId, sku) on delete cascade)"
```

```scala
// Build and create tables with compound FK
val inventory = Schema[CompoundInventory]
  .withForeignKey(_.tenantId).and(_.productSku)
  .references[CompoundProduct](_.tenantId).and(_.sku)
  .onDelete(Cascade)
  .build
// inventory: Instance[CompoundInventory] = Instance(
//   tableName = "compound_inventory",
//   columns = ArraySeq(
//     Column(
//       name = "id",
//       label = "id",
//       isKey = true,
//       isGenerated = true,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "tenantId",
//       label = "tenantId",
//       isKey = false,
//       isGenerated = false,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "productSku",
//       label = "productSku",
//       isKey = false,
//       isGenerated = false,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "quantity",
//       label = "quantity",
//       isKey = false,
//       isGenerated = false,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     )
//   ),
//   alias = None,
//   foreignKeys = Vector(
//     ForeignKeySpec(
//       fromColumns = List("tenantId", "productSku"),
//       toTable = "compound_products",
//       toColumns = List("tenantId", "sku"),
//       onDelete = Cascade,
//       onUpdate = NoAction,
//       constraintName = None
// ...

run {
  xa.run(for
    _ <- ddl.createTable[CompoundProduct]()
    _ <- ddl.createTable(inventory)
    _ <- dml.insert(CompoundProduct("tenant1", "SKU-001", "Widget"))
    _ <- dml.insert(CompoundInventory(-1, "tenant1", "SKU-001", 100))
    result <- sql"SELECT * FROM ${Table[CompoundInventory]}".query[CompoundInventory]
  yield result)
}
// res37: Seq[CompoundInventory] = Vector(
//   CompoundInventory(
//     id = 1,
//     tenantId = "tenant1",
//     productSku = "SKU-001",
//     quantity = 100
//   )
// )
```

### Multiple Foreign Keys

Chain multiple foreign keys using `.withForeignKey()`:

```scala
import saferis.*
import saferis.Schema.*

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
Schema[MultiOrderItem]
  .withForeignKey(_.userId).references[MultiUser](_.id).onDelete(Cascade)
  .withForeignKey(_.productId).references[MultiProduct](_.id).onDelete(Restrict)
  .ddl().sql
// res39: String = "create table if not exists multi_order_items (id integer generated always as identity primary key not null, userId integer not null, productId integer not null, quantity integer not null, foreign key (userId) references multi_users (id) on delete cascade, foreign key (productId) references multi_products (id) on delete restrict)"
```

### Type Safety

The foreign key builder provides compile-time type safety. The column types must match between the source and referenced columns:

```scala
import saferis.*
import saferis.Schema.*

@tableName("type_users")
case class TypeUser(@generated @key id: Int, name: String) derives Table

@tableName("type_orders")
case class TypeOrder(@generated @key id: Int, userId: Int, userName: String) derives Table

// This compiles - Int matches Int
val valid = Schema[TypeOrder]
  .withForeignKey(_.userId).references[TypeUser](_.id)

// This would NOT compile - String doesn't match Int
// val invalid = Schema[TypeOrder]
//   .withForeignKey(_.userName).references[TypeUser](_.id)
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
// res42: String = "SELECT * FROM tasks WHERE done = false"
```

```scala
// SELECT with multiple conditions
sql"SELECT * FROM $tasks WHERE ${tasks.title} LIKE ${"Learn%"} AND ${tasks.done} = ${false}".show
// res43: String = "SELECT * FROM tasks WHERE title LIKE 'Learn%' AND done = false"
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
// res45: Tuple2[Seq[Task], Seq[Task]] = (
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
// res46: Task = Task(id = 4, title = "New Task", done = false)
```

### Custom Queries

Use the `sql` interpolator for any query:

```scala
// Query with ordering
run { xa.run(sql"SELECT * FROM $tasks ORDER BY ${tasks.title}".query[Task]) }
// res47: Seq[Task] = Vector(
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
// res49: Tuple2[Item, Option[Item]] = (
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
// res50: Tuple2[Int, Seq[Item]] = (
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
// res52: Tuple2[LogEntry, Seq[LogEntry]] = (
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
// res53: Tuple3[Int, Seq[LogEntry], Seq[LogEntry]] = (
//   2,
//   Vector(LogEntry(id = 3, level = "ERROR", message = "Something failed")),
//   Vector(LogEntry(id = 6, level = "INFO", message = "Important info"))
// )
```

### Type-Safe Mutation Builders

Saferis provides type-safe builders for INSERT, UPDATE, and DELETE operations. These builders use Scala 3 macros to extract column names at compile time.

#### Insert Builder

Build INSERT statements with type-safe column selectors:

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("builder_users")
case class BuilderUser(@generated @key id: Int, name: String, email: String, age: Int) derives Table
```

```scala
// Type-safe INSERT builder
Insert[BuilderUser]
  .value(_.name, "Alice")
  .value(_.email, "alice@example.com")
  .value(_.age, 30)
  .build.sql
// res55: String = "insert into builder_users (name, email, age) values (?, ?, ?)"
```

```scala
// INSERT with RETURNING clause
Insert[BuilderUser]
  .value(_.name, "Bob")
  .value(_.email, "bob@example.com")
  .value(_.age, 25)
  .returning.sql
// res56: String = "insert into builder_users (name, email, age) values (?, ?, ?) returning *"
```

#### Update Builder (Builder/Ready Pattern)

The Update builder uses a **Builder/Ready pattern** to prevent accidental updates of all rows. You must either:
- Call `.where(...)` to specify which rows to update
- Call `.all` to explicitly update all rows

```scala
run {
  xa.run(for
    _ <- ddl.createTable[BuilderUser]()
    _ <- dml.insert(BuilderUser(-1, "Alice", "alice@example.com", 30))
    _ <- dml.insert(BuilderUser(-1, "Bob", "bob@example.com", 25))
    users <- sql"SELECT * FROM ${Table[BuilderUser]}".query[BuilderUser]
  yield users)
}
// res57: Seq[BuilderUser] = Vector(
//   BuilderUser(id = 1, name = "Alice", email = "alice@example.com", age = 30),
//   BuilderUser(id = 2, name = "Bob", email = "bob@example.com", age = 25)
// )
```

```scala
// Update with type-safe WHERE clause
Update[BuilderUser]
  .set(_.name, "Alice Updated")
  .set(_.age, 31)
  .where(_.id).eq(1)
  .build.sql
// res58: String = "update builder_users set name = ?, age = ? where \"builder_users\".id = ?"
```

```scala
// Chain multiple WHERE conditions
Update[BuilderUser]
  .set(_.email, "new@example.com")
  .where(_.name).eq("Bob")
  .where(_.age).gt(20)
  .build.sql
// res59: String = "update builder_users set email = ? where \"builder_users\".name = ? and \"builder_users\".age > ?"
```

```scala
// Update with RETURNING clause
Update[BuilderUser]
  .set(_.age, 35)
  .where(_.id).eq(1)
  .returning.sql
// res60: String = "update builder_users set age = ? where \"builder_users\".id = ? returning *"
```

```scala
// Explicitly update all rows (requires .all)
Update[BuilderUser]
  .set(_.age, 0)
  .all  // Required - prevents accidental "UPDATE ... SET" without WHERE
  .build.sql
// res61: String = "update builder_users set age = ?"
```

#### Delete Builder (Builder/Ready Pattern)

Like Update, the Delete builder requires either `.where(...)` or `.all`:

```scala
// Delete with type-safe WHERE clause
Delete[BuilderUser]
  .where(_.id).eq(1)
  .build.sql
// res62: String = "delete from builder_users where \"builder_users\".id = ?"
```

```scala
// Chain multiple WHERE conditions
Delete[BuilderUser]
  .where(_.age).lt(18)
  .where(_.name).neq("Admin")
  .build.sql
// res63: String = "delete from builder_users where \"builder_users\".age < ? and \"builder_users\".name <> ?"
```

```scala
// Delete with RETURNING clause
Delete[BuilderUser]
  .where(_.email).eq("old@example.com")
  .returning.sql
// res64: String = "delete from builder_users where \"builder_users\".email = ? returning *"
```

```scala
// Explicitly delete all rows (requires .all)
Delete[BuilderUser]
  .all  // Required - prevents accidental "DELETE FROM ..."
  .build.sql
// res65: String = "delete from builder_users"
```

#### Available WHERE Operators

All mutation builders support these operators in WHERE clauses:

| Method | SQL | Description |
|--------|-----|-------------|
| `.eq(value)` | `= ?` | Equality |
| `.neq(value)` | `<> ?` | Not equal |
| `.lt(value)` | `< ?` | Less than |
| `.lte(value)` | `<= ?` | Less than or equal |
| `.gt(value)` | `> ?` | Greater than |
| `.gte(value)` | `>= ?` | Greater than or equal |
| `.isNull()` | `IS NULL` | Null check |
| `.isNotNull()` | `IS NOT NULL` | Non-null check |

You can also use raw `SqlFragment` for complex conditions:

```scala
// Using SqlFragment for complex WHERE
val users = Table[BuilderUser]
// users: Instance[BuilderUser] {
  val id: Column[Int]
  val name: Column[String]
  val email: Column[String]
  val age: Column[Int]
  def getByKey(id: Int): TypedFragment
} = Instance(
//   tableName = "builder_users",
//   columns = ArraySeq(
//     Column(
//       name = "id",
//       label = "id",
//       isKey = true,
//       isGenerated = true,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "name",
//       label = "name",
//       isKey = false,
//       isGenerated = false,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "email",
//       label = "email",
//       isKey = false,
//       isGenerated = false,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "age",
//       label = "age",
//       isKey = false,
//       isGenerated = false,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     )
//   ),
//   alias = None,
//   foreignKeys = Vector(),
//   indexes = Vector(),
//   uniqueConstraints = Vector()
// )
Update[BuilderUser]
  .set(_.age, 25)
  .where(sql"${users.name} LIKE ${"A%"}")
  .build.sql
// res66: String = "update builder_users set age = ? where name LIKE ?"
```

---

## Query Builder

Saferis provides a unified, type-safe `Query` builder for constructing SQL queries. It supports single-table queries, multi-table joins (up to 5 tables), WHERE clauses, pagination, and subqueries - all with compile-time type safety.

### Query Safety (Builder/Ready Pattern)

To prevent accidental unbounded queries that could fetch millions of rows, Saferis uses a **Builder/Ready pattern**. A query must have at least one safety constraint before it can be executed:

| Safety Constraint | Description |
|------------------|-------------|
| `.where(...)` | Filter results with a WHERE clause |
| `.limit(n)` | Limit the number of rows returned |
| `.seekAfter(...)` / `.seekBefore(...)` | Cursor-based pagination |
| `.all` | Explicit opt-in to fetch all rows |

```scala
import saferis.*
import saferis.postgres.given

@tableName("safety_users")
case class SafetyUser(@generated @key id: Int, name: String) derives Table

// These compile - they have safety constraints:
Query[SafetyUser].where(_.name).eq("Alice")     // Has WHERE
Query[SafetyUser].limit(100)                     // Has LIMIT
Query[SafetyUser].all                            // Explicit opt-in

// This would NOT compile - no safety constraint:
// Query[SafetyUser].build  // Error: .build not available on Builder
```

The pattern ensures you consciously choose to query all rows with `.all` rather than accidentally doing so.

### Basic Queries

Start with `Query[A]` for single-table queries:

```scala
import saferis.*
import saferis.postgres.given
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("query_users")
case class QueryUser(@generated @key id: Int, name: String, email: String, age: Int) derives Table

val users = Table[QueryUser]
```

```scala
// Simple query with type-safe WHERE
Query[QueryUser]
  .where(_.name).eq("Alice")
  .build.sql
// res69: String = "select * from query_users as query_users_ref_1 where query_users_ref_1.name = ?"
```

```scala
// Query with ordering and pagination
Query[QueryUser]
  .where(_.age).gt(18)
  .orderBy(users.name.asc)
  .limit(10)
  .offset(20)
  .build.sql
// res70: String = "select * from query_users as query_users_ref_1 where query_users_ref_1.age > ? order by name asc limit 10 offset 20"
```

### Type-Safe WHERE Clauses

Use selector syntax for type-safe column references:

```scala
// Equality
Query[QueryUser].where(_.name).eq("Alice").build.sql
// res71: String = "select * from query_users as query_users_ref_1 where query_users_ref_1.name = ?"
```

```scala
// Comparison operators
Query[QueryUser].where(_.age).gt(21).build.sql
// res72: String = "select * from query_users as query_users_ref_1 where query_users_ref_1.age > ?"
```

```scala
// IS NULL / IS NOT NULL
Query[QueryUser].where(_.email).isNotNull().build.sql
// res73: String = "select * from query_users as query_users_ref_1 where query_users_ref_1.email IS NOT NULL"
```

You can also use raw `SqlFragment` for complex conditions:

```scala
// Raw SQL fragment
Query[QueryUser]
  .where(sql"${users.age} BETWEEN 18 AND 65")
  .build.sql
// res74: String = "select * from query_users as query_users_ref_1 where age BETWEEN 18 AND 65"
```

### Joins

Chain joins with the fluent API. The `on()` method uses type-safe selectors:

```scala
import saferis.*
import saferis.postgres.given
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("join_users")
case class JoinUser(@generated @key id: Int, name: String) derives Table

@tableName("join_orders")
case class JoinOrder(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table

@tableName("join_items")
case class JoinItem(@key orderId: Int, @key productId: Int, quantity: Int) derives Table
```

```scala
// Inner join (using .all to explicitly fetch all rows)
Query[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .all
  .build.sql
// res76: String = "select * from join_users as join_users_ref_1 inner join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId"
```

```scala
// Left join
Query[JoinUser]
  .leftJoin[JoinOrder].on(_.id).eq(_.userId)
  .all
  .build.sql
// res77: String = "select * from join_users as join_users_ref_1 left join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId"
```

```scala
// Right join
Query[JoinUser]
  .rightJoin[JoinOrder].on(_.id).eq(_.userId)
  .all
  .build.sql
// res78: String = "select * from join_users as join_users_ref_1 right join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId"
```

```scala
// Full join
Query[JoinUser]
  .fullJoin[JoinOrder].on(_.id).eq(_.userId)
  .all
  .build.sql
// res79: String = "select * from join_users as join_users_ref_1 full join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId"
```

### Finalizing Joins with `.endJoin`

After specifying the ON clause, you can either:
1. Use **convenience methods** like `.where()`, `.limit()`, `.all` directly on the join chain
2. Call **`.endJoin`** explicitly to finalize the join and return to the query builder

```scala
// Using convenience method (implicitly calls endJoin)
Query[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .where(_.name).eq("Alice")  // Convenience method
  .build.sql
// res80: String = "select * from join_users as join_users_ref_1 inner join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId where join_users_ref_1.name = ?"
```

```scala
// Using explicit .endJoin for more control
Query[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .endJoin                    // Explicitly finalize join
  .orderBy(Table[JoinUser].name.asc)
  .all
  .build.sql
// res81: String = "select * from join_users as join_users_ref_1 inner join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId order by name asc"
```

The `.endJoin` method is useful when you want to add operations like `.orderBy()` that aren't available as convenience methods on the join chain.

### Multi-Table Joins

Chain up to 5 tables. Use `onPrev()` to reference the previously joined table:

```scala
// Three-table join
Query[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .innerJoin[JoinItem].onPrev(_.id).eq(_.orderId)
  .all
  .build.sql
// res82: String = "select * from join_users as join_users_ref_1 inner join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId inner join join_items as join_items_ref_1 on join_orders_ref_1.id = join_items_ref_1.orderId"
```

### WHERE on Joined Queries

After joining, use `where()` for the first table or `whereFrom()` for joined tables:

```scala
// WHERE on first table
Query[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .where(_.name).eq("Alice")
  .build.sql
// res83: String = "select * from join_users as join_users_ref_1 inner join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId where join_users_ref_1.name = ?"
```

```scala
// WHERE on joined table
Query[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .whereFrom(_.amount).gt(BigDecimal(100))
  .build.sql
// res84: String = "select * from join_users as join_users_ref_1 inner join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId where join_orders_ref_1.amount > ?"
```

### ON Clause Operators

All comparison operators are available in the ON clause:

| Method | SQL | Description |
|--------|-----|-------------|
| `eq()` | `=` | Equality |
| `neq()` | `<>` | Not equal |
| `lt()` | `<` | Less than |
| `lte()` | `<=` | Less than or equal |
| `gt()` | `>` | Greater than |
| `gte()` | `>=` | Greater than or equal |
| `isNull()` | `IS NULL` | Null check |
| `isNotNull()` | `IS NOT NULL` | Non-null check |
| `op(Operator.X)` | Custom | Any operator |

### Pagination

#### Offset-Based Pagination

Traditional LIMIT/OFFSET pagination:

```scala
import saferis.*
import saferis.postgres.given

@tableName("page_articles")
case class Article(@generated @key id: Long, title: String, views: Int, published: Boolean) derives Table

val articles = Table[Article]
```

```scala
// Page 3 with 10 items per page
Query[Article]
  .where(_.published).eq(true)
  .orderBy(articles.views.desc)
  .limit(10)
  .offset(20)
  .build.sql
// res86: String = "select * from page_articles as page_articles_ref_1 where page_articles_ref_1.published = ? order by views desc limit 10 offset 20"
```

#### Cursor/Seek Pagination

More efficient for large datasets - uses indexed lookups:

```scala
// Get next page after ID 100
Query[Article]
  .seekAfter(articles.id, 100L)
  .limit(10)
  .build.sql
// res87: String = "select * from page_articles as page_articles_ref_1 where id > ? order by id asc limit 10"
```

```scala
// Get previous page before ID 50
Query[Article]
  .seekBefore(articles.id, 50L)
  .limit(10)
  .build.sql
// res88: String = "select * from page_articles as page_articles_ref_1 where id < ? order by id desc limit 10"
```

### Sorting

Use column extensions for concise sorting:

```scala
Query[Article]
  .orderBy(articles.views.desc)
  .orderBy(articles.title.asc)
  .all
  .build.sql
// res89: String = "select * from page_articles as page_articles_ref_1 order by views desc, title asc"
```

Control NULL ordering:

```scala
Query[Article]
  .orderBy(articles.views.descNullsLast)
  .all
  .build.sql
// res90: String = "select * from page_articles as page_articles_ref_1 order by views desc nulls last"
```

Available sorting extensions:
- `.asc` / `.desc` - basic ordering
- `.ascNullsFirst` / `.ascNullsLast`
- `.descNullsFirst` / `.descNullsLast`

### Executing Queries

Use `.query[R]` to execute and decode results:

```scala
import saferis.*
import saferis.postgres.given
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("exec_users")
case class ExecUser(@generated @key id: Int, name: String) derives Table

@tableName("exec_orders")
case class ExecOrder(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table

val users = Table[ExecUser]
```

```scala
run {
  xa.run(for
    _ <- ddl.createTable[ExecUser]()
    _ <- ddl.createTable[ExecOrder]()
    _ <- dml.insert(ExecUser(-1, "Alice"))
    _ <- dml.insert(ExecUser(-1, "Bob"))
    _ <- dml.insert(ExecOrder(-1, 1, BigDecimal(100)))
    _ <- dml.insert(ExecOrder(-1, 1, BigDecimal(200)))
    result <- Query[ExecUser]
      .innerJoin[ExecOrder].on(_.id).eq(_.userId)
      .where(_.name).eq("Alice")
      .limit(10)
      .query[ExecUser]
  yield result)
}
// res92: Seq[ExecUser] = Vector(
//   ExecUser(id = 1, name = "Alice"),
//   ExecUser(id = 1, name = "Alice")
// )
```

---

## Subqueries

The Query builder supports type-safe subqueries for IN, NOT IN, EXISTS, and derived tables.

### IN Subqueries

Use `.select(_.column)` to create a typed subquery, then pass it to `.in()`:

```scala
import saferis.*
import saferis.postgres.given
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("sub_users")
case class SubUser(@generated @key id: Int, name: String) derives Table

@tableName("sub_orders")
case class SubOrder(@generated @key id: Int, userId: Int, status: String) derives Table
```

```scala
// Type-safe IN subquery - column types must match
val activeUserIds = Query[SubOrder]
  .where(_.status).eq("active")
  .select(_.userId)  // Returns SelectQuery[Int]
// activeUserIds: SelectQuery[Int] = SelectQuery(
//   Query1Ready(
//     baseInstance = Instance(
//       tableName = "sub_orders",
//       columns = ArraySeq(
//         Column(
//           name = "id",
//           label = "id",
//           isKey = true,
//           isGenerated = true,
//           isNullable = false,
//           defaultValue = None,
//           tableAlias = Some(Generated("sub_orders_ref_1"))
//         ),
//         Column(
//           name = "userId",
//           label = "userId",
//           isKey = false,
//           isGenerated = false,
//           isNullable = false,
//           defaultValue = None,
//           tableAlias = Some(Generated("sub_orders_ref_1"))
//         ),
//         Column(
//           name = "status",
//           label = "status",
//           isKey = false,
//           isGenerated = false,
//           isNullable = false,
//           defaultValue = None,
//           tableAlias = Some(Generated("sub_orders_ref_1"))
//         )
//       ),
//       alias = Some(Generated("sub_orders_ref_1")),
//       foreignKeys = Vector(),
//       indexes = Vector(),
//       uniqueConstraints = Vector()
//     ),
//     wherePredicates = Vector(
//       SqlFragment(
//         sql = "sub_orders_ref_1.status = ?",
//         writes = Vector(saferis.Write@2a9dcf83)
//       )
//     ),
//     sorts = Vector(),
//     seeks = Vector(),
//     limitValue = None,
//     offsetValue = None,
//     selectColumns = Vector(
// ...

Query[SubUser]
  .where(_.id).in(activeUserIds)  // Compiles: both are Int
  .build.sql
// res94: String = "select * from sub_users as sub_users_ref_1 where sub_users_ref_1.id IN (select userId from sub_orders as sub_orders_ref_1 where sub_orders_ref_1.status = ?)"
```

```scala
// NOT IN subquery
Query[SubUser]
  .where(_.id).notIn(activeUserIds)
  .build.sql
// res95: String = "select * from sub_users as sub_users_ref_1 where sub_users_ref_1.id NOT IN (select userId from sub_orders as sub_orders_ref_1 where sub_orders_ref_1.status = ?)"
```

The type safety is enforced at compile time - if the column types don't match, it won't compile.

### EXISTS Subqueries

Use `whereExists()` or `whereNotExists()`:

```scala
// EXISTS - find users who have orders
Query[SubUser]
  .whereExists(Query[SubOrder].all)
  .build.sql
// res96: String = "select * from sub_users as sub_users_ref_1 where EXISTS (select * from sub_orders as sub_orders_ref_1)"
```

```scala
// NOT EXISTS - find users without orders
Query[SubUser]
  .whereNotExists(Query[SubOrder].where(_.status).eq("cancelled"))
  .build.sql
// res97: String = "select * from sub_users as sub_users_ref_1 where NOT EXISTS (select * from sub_orders as sub_orders_ref_1 where sub_orders_ref_1.status = ?)"
```

### Correlated Subqueries

For correlated subqueries, use `sql"..."` to reference outer table columns:

```scala
val users = Table[SubUser]
// users: Instance[SubUser] {
  val id: Column[Int]
  val name: Column[String]
  def getByKey(id: Int): TypedFragment
} = Instance(
//   tableName = "sub_users",
//   columns = ArraySeq(
//     Column(
//       name = "id",
//       label = "id",
//       isKey = true,
//       isGenerated = true,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     ),
//     Column(
//       name = "name",
//       label = "name",
//       isKey = false,
//       isGenerated = false,
//       isNullable = false,
//       defaultValue = None,
//       tableAlias = None
//     )
//   ),
//   alias = None,
//   foreignKeys = Vector(),
//   indexes = Vector(),
//   uniqueConstraints = Vector()
// )

// Correlated EXISTS - find users who have at least one order
Query[SubUser]
  .whereExists(
    Query[SubOrder].where(sql"userId = ${users.id}")
  )
  .build.sql
// res98: String = "select * from sub_users as sub_users_ref_1 where EXISTS (select * from sub_orders as sub_orders_ref_1 where userId = id)"
```

### Derived Tables

Use subqueries in the FROM clause with `Query.from()`:

```scala
import saferis.*
import saferis.postgres.given
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("derived_orders")
case class DerivedOrder(@generated @key id: Int, userId: Int, amount: BigDecimal, status: String) derives Table

@tableName("derived_users")
case class DerivedUser(@generated @key id: Int, name: String) derives Table

// Virtual type for the subquery result
@tableName("order_summary")
case class OrderSummary(userId: Int, amount: BigDecimal) derives Table
```

```scala
// Create a typed subquery
val highValueOrders = Query[DerivedOrder]
  .where(_.amount).gt(BigDecimal(100))
  .selectAll[OrderSummary]  // Returns SelectQuery[OrderSummary]
// highValueOrders: SelectQuery[OrderSummary] = SelectQuery(
//   Query1Ready(
//     baseInstance = Instance(
//       tableName = "derived_orders",
//       columns = ArraySeq(
//         Column(
//           name = "id",
//           label = "id",
//           isKey = true,
//           isGenerated = true,
//           isNullable = false,
//           defaultValue = None,
//           tableAlias = Some(Generated("derived_orders_ref_1"))
//         ),
//         Column(
//           name = "userId",
//           label = "userId",
//           isKey = false,
//           isGenerated = false,
//           isNullable = false,
//           defaultValue = None,
//           tableAlias = Some(Generated("derived_orders_ref_1"))
//         ),
//         Column(
//           name = "amount",
//           label = "amount",
//           isKey = false,
//           isGenerated = false,
//           isNullable = false,
//           defaultValue = None,
//           tableAlias = Some(Generated("derived_orders_ref_1"))
//         ),
//         Column(
//           name = "status",
//           label = "status",
//           isKey = false,
//           isGenerated = false,
//           isNullable = false,
//           defaultValue = None,
//           tableAlias = Some(Generated("derived_orders_ref_1"))
//         )
//       ),
//       alias = Some(Generated("derived_orders_ref_1")),
//       foreignKeys = Vector(),
//       indexes = Vector(),
//       uniqueConstraints = Vector()
//     ),
//     wherePredicates = Vector(
//       SqlFragment(
// ...

// Use as derived table with explicit alias
Query.from(highValueOrders, "high_value")
  .where(_.userId).gt(0)
  .build.sql
// res100: String = "select * from (select * from derived_orders as derived_orders_ref_1 where derived_orders_ref_1.amount > ?) as high_value where \"high_value\".userId > ?"
```

```scala
// Derived table with join
Query.from(highValueOrders, "summary")
  .innerJoin[DerivedUser].on(_.userId).eq(_.id)
  .all
  .build.sql
// res101: String = "select * from (select * from derived_orders as derived_orders_ref_1 where derived_orders_ref_1.amount > ?) as summary inner join derived_users as derived_users_ref_1 on \"summary\".userId = derived_users_ref_1.id"
```

### Complex Nested Subqueries

Subqueries can be arbitrarily complex - they support joins, nested subqueries, and all Query features:

```scala
import saferis.*
import saferis.postgres.given

@tableName("complex_users")
case class ComplexUser(@generated @key id: Int, name: String) derives Table

@tableName("complex_orders")
case class ComplexOrder(@generated @key id: Int, userId: Int, productId: Int) derives Table

@tableName("complex_products")
case class ComplexProduct(@generated @key id: Int, category: String) derives Table
```

```scala
// Nested subquery: find users who ordered electronics
val electronicProductIds = Query[ComplexProduct]
  .where(_.category).eq("electronics")
  .select(_.id)
// electronicProductIds: SelectQuery[Int] = SelectQuery(
//   Query1Ready(
//     baseInstance = Instance(
//       tableName = "complex_products",
//       columns = ArraySeq(
//         Column(
//           name = "id",
//           label = "id",
//           isKey = true,
//           isGenerated = true,
//           isNullable = false,
//           defaultValue = None,
//           tableAlias = Some(Generated("complex_products_ref_1"))
//         ),
//         Column(
//           name = "category",
//           label = "category",
//           isKey = false,
//           isGenerated = false,
//           isNullable = false,
//           defaultValue = None,
//           tableAlias = Some(Generated("complex_products_ref_1"))
//         )
//       ),
//       alias = Some(Generated("complex_products_ref_1")),
//       foreignKeys = Vector(),
//       indexes = Vector(),
//       uniqueConstraints = Vector()
//     ),
//     wherePredicates = Vector(
//       SqlFragment(
//         sql = "complex_products_ref_1.category = ?",
//         writes = Vector(saferis.Write@1a01f34d)
//       )
//     ),
//     sorts = Vector(),
//     seeks = Vector(),
//     limitValue = None,
//     offsetValue = None,
//     selectColumns = Vector(
//       Column(
//         name = "id",
//         label = "id",
//         isKey = true,
//         isGenerated = true,
//         isNullable = false,
//         defaultValue = None,
//         tableAlias = Some(Generated("complex_products_ref_1"))
//       )
// ...

val usersWithElectronics = Query[ComplexOrder]
  .where(_.productId).in(electronicProductIds)
  .select(_.userId)
// usersWithElectronics: SelectQuery[Int] = SelectQuery(
//   Query1Ready(
//     baseInstance = Instance(
//       tableName = "complex_orders",
//       columns = ArraySeq(
//         Column(
//           name = "id",
//           label = "id",
//           isKey = true,
//           isGenerated = true,
//           isNullable = false,
//           defaultValue = None,
//           tableAlias = Some(Generated("complex_orders_ref_1"))
//         ),
//         Column(
//           name = "userId",
//           label = "userId",
//           isKey = false,
//           isGenerated = false,
//           isNullable = false,
//           defaultValue = None,
//           tableAlias = Some(Generated("complex_orders_ref_1"))
//         ),
//         Column(
//           name = "productId",
//           label = "productId",
//           isKey = false,
//           isGenerated = false,
//           isNullable = false,
//           defaultValue = None,
//           tableAlias = Some(Generated("complex_orders_ref_1"))
//         )
//       ),
//       alias = Some(Generated("complex_orders_ref_1")),
//       foreignKeys = Vector(),
//       indexes = Vector(),
//       uniqueConstraints = Vector()
//     ),
//     wherePredicates = Vector(
//       SqlFragment(
//         sql = "complex_orders_ref_1.productId IN (select id from complex_products as complex_products_ref_1 where complex_products_ref_1.category = ?)",
//         writes = List(saferis.Write@1a01f34d)
//       )
//     ),
//     sorts = Vector(),
//     seeks = Vector(),
//     limitValue = None,
//     offsetValue = None,
// ...

Query[ComplexUser]
  .where(_.id).in(usersWithElectronics)
  .build.sql
// res103: String = "select * from complex_users as complex_users_ref_1 where complex_users_ref_1.id IN (select userId from complex_orders as complex_orders_ref_1 where complex_orders_ref_1.productId IN (select id from complex_products as complex_products_ref_1 where complex_products_ref_1.category = ?))"
```

### Operator Reference

All available operators in `Operator`:

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
// res105: Tuple2[SpecializedItem, Seq[SpecializedItem]] = (
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
// res106: Seq[SpecializedItem] = Vector(
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
// res107: SpecializedItem = SpecializedItem(
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
// res109: Seq[Event] = Vector(
//   Event(
//     id = 1,
//     name = "Conference",
//     occurredAt = 2026-02-04T21:47:09.598519Z,
//     scheduledFor = Some(2026-02-11T15:47:09.598561),
//     eventDate = 2026-02-04
//   ),
//   Event(
//     id = 2,
//     name = "Meeting",
//     occurredAt = 2026-02-04T21:47:09.602841Z,
//     scheduledFor = None,
//     eventDate = 2026-02-05
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
// res111: Option[Entity] = Some(
//   Entity(id = dc91b744-fe9e-4429-ba19-56282c69745e, name = "First Entity")
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

### JSON/JSONB Support

Saferis provides `Json[A]` for storing arbitrary types as JSON in the database. This maps to `JSONB` in PostgreSQL and `JSON` in MySQL.

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}
import zio.json.*

// Define a type to store as JSON - needs JsonCodec
case class Metadata(tags: List[String], version: Int) derives JsonCodec

// Use Json[A] wrapper in your table definition
@tableName("json_events")
case class JsonEvent(
  @generated @key id: Int,
  name: String,
  metadata: Json[Metadata]  // Stored as JSONB in PostgreSQL
) derives Table

val events = Table[JsonEvent]
```

```scala
// Create table and insert with JSON data
run {
  xa.run(for
    _ <- ddl.createTable[JsonEvent]()
    _ <- dml.insert(JsonEvent(-1, "Deploy", Json(Metadata(List("prod", "release"), 1))))
    _ <- dml.insert(JsonEvent(-1, "Rollback", Json(Metadata(List("prod", "hotfix"), 2))))
    all <- sql"SELECT * FROM $events".query[JsonEvent]
  yield all)
}
// res113: Seq[JsonEvent] = Vector(
//   JsonEvent(
//     id = 1,
//     name = "Deploy",
//     metadata = Metadata(tags = List("prod", "release"), version = 1)
//   ),
//   JsonEvent(
//     id = 2,
//     name = "Rollback",
//     metadata = Metadata(tags = List("prod", "hotfix"), version = 2)
//   )
// )
```

The `Json[A]` wrapper:
- Requires a `zio.json.JsonCodec[A]` instance for the wrapped type
- Uses `Types.OTHER` JDBC type which maps to `jsonb` in PostgreSQL
- Provides `.value` extension to unwrap: `event.metadata.value` returns `Metadata`

---

## Query Execution Methods

SqlFragment provides several methods for executing queries:

| Method | Returns | Description |
|--------|---------|-------------|
| `.query[T]` | `Seq[T]` | Execute query, return all matching rows |
| `.queryOne[T]` | `Option[T]` | Execute query, return first row if exists |
| `.queryValue[T]` | `Option[T]` | Execute query, return single value from first column |
| `.execute` / `.dml` | `Int` | Execute DML statement, return affected row count |

### queryValue for Single Values

Use `.queryValue[T]` for queries that return a single value (aggregates, counts, etc.):

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("value_items")
case class ValueItem(@generated @key id: Int, name: String, price: Double) derives Table
val items = Table[ValueItem]
```

```scala
run {
  xa.run(for
    _ <- ddl.createTable[ValueItem]()
    _ <- dml.insert(ValueItem(-1, "Widget", 10.0))
    _ <- dml.insert(ValueItem(-1, "Gadget", 25.0))
    _ <- dml.insert(ValueItem(-1, "Gizmo", 15.0))
    count <- sql"SELECT COUNT(*) FROM $items".queryValue[Int]
    maxPrice <- sql"SELECT MAX(${items.price}) FROM $items".queryValue[Double]
    avgPrice <- sql"SELECT AVG(${items.price}) FROM $items".queryValue[Double]
  yield (count, maxPrice, avgPrice))
}
// res115: Tuple3[Option[Int], Option[Double], Option[Double]] = (
//   Some(3),
//   Some(25.0),
//   Some(16.666666666666668)
// )
```

### execute for Mutation Builders

The mutation builders (Insert, Update, Delete) support `.build.execute` to run the statement:

```scala
import saferis.*
import saferis.docs.DocTestContainer.{run, transactor as xa}

@tableName("exec_items")
case class ExecItem(@generated @key id: Int, name: String, quantity: Int) derives Table
```

```scala
run {
  xa.run(for
    _ <- ddl.createTable[ExecItem]()
    // Insert using builder and execute
    insertCount <- Insert[ExecItem]
      .value(_.name, "Widget")
      .value(_.quantity, 10)
      .build
      .execute
    // Update using builder and execute
    updateCount <- Update[ExecItem]
      .set(_.quantity, 20)
      .where(_.name).eq("Widget")
      .build
      .execute
    // Verify
    result <- sql"SELECT * FROM ${Table[ExecItem]}".query[ExecItem]
  yield (insertCount, updateCount, result))
}
// res117: Tuple3[Int, Int, Seq[ExecItem]] = (
//   1,
//   1,
//   Vector(ExecItem(id = 1, name = "Widget", quantity = 20))
// )
```

---

## Additional Resources

- [Source Code](https://github.com/russwyte/saferis)
- [Issue Tracker](https://github.com/russwyte/saferis/issues)

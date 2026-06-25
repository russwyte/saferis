# Core Concepts

[← Back to index](index.md)

## Table Definitions

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

### Annotations

| Annotation | Purpose |
|------------|---------|
| `@tableName("name")` | Specifies the SQL table name |
| `@key` | Marks a primary key column |
| `@generated` | Marks an auto-generated column (identity/auto-increment) |
| `@label("column_name")` | Maps field to a different column name |

For indexes, unique constraints, and foreign keys, use the [Schema DSL](ddl.md#schema-dsl-for-indexes-and-constraints).

### Automatic Column Properties

Saferis infers column properties from your Scala types:

| Scala Type | SQL Property |
|------------|--------------|
| `T` (non-Option) | `NOT NULL` |
| `Option[T]` | Nullable |
| Field with default value | `DEFAULT <value>` |

```scala
import saferis.*
import saferis.Schema.*
import zio.*

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
println(Schema[Product].ddl().sql)
```
```
// Scala 3.3.8
create table if not exists products (id bigint generated always as identity primary key not null, product_name varchar(255) not null, quantity integer not null default 0, price double precision not null, notes varchar(255))
```

### Compound Primary Keys

Use multiple `@key` annotations to create a composite primary key:

```scala
import saferis.*
import saferis.Schema.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("order_items")
case class OrderItem(
  @key orderId: Long,
  @key productId: Long,
  quantity: Int
) derives Table
```

```scala
// Show the generated CREATE TABLE SQL with compound primary key
println(Schema[OrderItem].ddl().sql)
```
```
// Scala 3.3.8
create table if not exists order_items (orderId bigint not null, productId bigint not null, quantity integer not null, primary key (orderId, productId));
create index if not exists idx_order_items_compound_key on order_items (orderId, productId)
```

```scala
// Create and use the table
xa.run(for
  _     <- ddl.createTable[OrderItem](ifNotExists = true)
  _     <- dml.insert(OrderItem(1, 100, 2))
  _     <- dml.insert(OrderItem(1, 101, 1))  // Same order, different product - OK
  _     <- dml.insert(OrderItem(2, 100, 3))  // Different order, same product - OK
  items <- sql"SELECT * FROM ${Table[OrderItem]}".query[OrderItem]
yield items).debug("items")
```

## SQL Interpolation

The `sql"..."` interpolator is Saferis's primary defense against SQL injection. It automatically distinguishes between different types of interpolated values:

```scala
import saferis.*
import zio.*

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
// Values are safely parameterized using prepared statement placeholders.
// `.sql` shows the statement sent to the database: the value is a `?`.
val minPrice = 10.0
val query = sql"SELECT * FROM $products WHERE ${products.price} > $minPrice"
println(query.sql)
```
```
// Scala 3.3.8
SELECT * FROM products WHERE price > ?
```

```scala
// Table and column references become SQL identifiers; scalar values become `?`.
println(sql"SELECT ${products.name}, ${products.price} FROM $products WHERE ${products.inStock} = ${true}".sql)
```
```
// Scala 3.3.8
SELECT name, price FROM products WHERE inStock = ?
```

The interpolator handles each type differently:

| Interpolated Type | Treatment | Example |
|-------------------|-----------|---------|
| Table instance | SQL identifier | `$products` → `products` |
| Column reference | SQL identifier | `${products.name}` → `name` |
| Scalar values | Prepared statement `?` | `$minPrice` → `?` with bound value |
| `SqlFragment` | Embedded SQL | Nested fragments are composed |

See [SQL Injection Prevention](sql-injection-prevention.md) for the complete security model.

## The Transactor

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

### Concurrency Limiting

The `Transactor.layer` method accepts an optional `maxConcurrency` parameter that limits concurrent database operations using a ZIO Semaphore:

```scala
import saferis.*

// Default: no concurrency limit (recommended for connection pools)
val defaultLayer = Transactor.layer()

// With concurrency limit (for SQLite or direct JDBC without pooling)
val limitedLayer = Transactor.layer(maxConcurrency = 1L)
```

`Transactor.layer` also accepts an optional `defaultTimeout` that applies a JDBC statement timeout to every query run through the Transactor — see [Statement Timeouts](statement-timeouts.md).

**When to use `maxConcurrency`:**
- SQLite or other embedded databases without connection pooling
- Direct JDBC connections without a pool
- When you need concurrency limits below pool size for backpressure

**When NOT to use `maxConcurrency`:**
- With HikariCP or similar connection pools. The pool handles queuing more efficiently and HikariCP specifically recommends letting threads wait on the pool rather than limiting concurrency externally. Using a semaphore with a pool creates double-queuing and adds overhead in high-contention scenarios.

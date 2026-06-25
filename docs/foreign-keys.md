# Foreign Key Support

[← Back to index](index.md)

Saferis provides a type-safe `Schema` builder for defining foreign key constraints. The builder uses Scala 3 macros to extract column names at compile time, ensuring type safety and catching errors early.

## Basic Foreign Keys

Define a foreign key using `Schema[A].withForeignKey(_.column).references[Table](_.column)`:

```scala
import saferis.*
import saferis.Schema.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

// Parent table
@tableName("fk_users")
case class FkUser(@generated @key id: Int, name: String) derives Table

// Child table with foreign key column
@tableName("fk_orders")
case class FkOrder(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table
```

```scala
// Define the foreign key relationship and get DDL
println(Schema[FkOrder]
  .withForeignKey(_.userId).references[FkUser](_.id)
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists fk_orders (id integer generated always as identity primary key not null, userId integer not null, amount numeric not null, foreign key (userId) references fk_users (id))
```

```scala
// Build the instance for use with ddl.createTable
val orders = Schema[FkOrder]
  .withForeignKey(_.userId).references[FkUser](_.id)
  .build

// Create tables with foreign key constraint
xa.run(for
  _      <- ddl.createTable[FkUser](ifNotExists = true)
  _      <- ddl.createTable(orders)
  _      <- dml.insert(FkUser(-1, "Alice"))
  _      <- dml.insert(FkOrder(-1, 1, BigDecimal(99.99)))
  result <- sql"SELECT * FROM ${Table[FkOrder]}".query[FkOrder]
yield result).debug("orders")
```

## ON DELETE and ON UPDATE Actions

Specify what happens when a referenced row is deleted or updated:

```scala
import saferis.*
import saferis.Schema.*
import zio.*

@tableName("action_users")
case class ActionUser(@generated @key id: Int, name: String) derives Table

@tableName("action_orders")
case class ActionOrder(@generated @key id: Int, userId: Int) derives Table
```

```scala
// CASCADE: Deleting a user deletes their orders
println(Schema[ActionOrder]
  .withForeignKey(_.userId).references[ActionUser](_.id)
  .onDelete(Cascade)
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists action_orders (id integer generated always as identity primary key not null, userId integer not null, foreign key (userId) references action_users (id) on delete cascade)
```

```scala
// SET NULL: Sets FK column to NULL when parent is deleted
// Note: The FK column should be nullable (Option[T]) for SET NULL to work properly at runtime
println(Schema[ActionOrder]
  .withForeignKey(_.userId).references[ActionUser](_.id)
  .onDelete(SetNull)
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists action_orders (id integer generated always as identity primary key not null, userId integer not null, foreign key (userId) references action_users (id) on delete set null)
```

Available actions (import `saferis.Schema.*` to use short names):

| Action | Description |
|--------|-------------|
| `NoAction` | Fail if referenced row is deleted/updated (default) |
| `Cascade` | Delete/update child rows when parent is deleted/updated |
| `SetNull` | Set the FK column to NULL |
| `SetDefault` | Set the FK column to its default value |
| `Restrict` | Fail immediately (same as NoAction but checked immediately) |

## Named Constraints

Give your foreign key constraint a custom name:

```scala
import saferis.*
import saferis.Schema.*
import zio.*

@tableName("named_users")
case class NamedUser(@generated @key id: Int, name: String) derives Table

@tableName("named_orders")
case class NamedOrder(@generated @key id: Int, userId: Int) derives Table
```

```scala
println(Schema[NamedOrder]
  .withForeignKey(_.userId).references[NamedUser](_.id)
  .onDelete(Cascade)
  .named("fk_order_user")
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists named_orders (id integer generated always as identity primary key not null, userId integer not null, constraint fk_order_user foreign key (userId) references named_users (id) on delete cascade)
```

## Compound Foreign Keys

Reference a composite primary key with multiple columns using `.and()`:

```scala
import saferis.*
import saferis.Schema.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

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
println(Schema[CompoundInventory]
  .withForeignKey(_.tenantId).and(_.productSku)
  .references[CompoundProduct](_.tenantId).and(_.sku)
  .onDelete(Cascade)
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists compound_inventory (id integer generated always as identity primary key not null, tenantId varchar(255) not null, productSku varchar(255) not null, quantity integer not null, foreign key (tenantId, productSku) references compound_products (tenantId, sku) on delete cascade)
```

```scala
// Build and create tables with compound FK
val inventory = Schema[CompoundInventory]
  .withForeignKey(_.tenantId).and(_.productSku)
  .references[CompoundProduct](_.tenantId).and(_.sku)
  .onDelete(Cascade)
  .build

xa.run(for
  _      <- ddl.createTable[CompoundProduct](ifNotExists = true)
  _      <- ddl.createTable(inventory)
  _      <- dml.insert(CompoundProduct("tenant1", "SKU-001", "Widget"))
  _      <- dml.insert(CompoundInventory(-1, "tenant1", "SKU-001", 100))
  result <- sql"SELECT * FROM ${Table[CompoundInventory]}".query[CompoundInventory]
yield result).debug("inventory")
```

## Multiple Foreign Keys

Chain multiple foreign keys using `.withForeignKey()`:

```scala
import saferis.*
import saferis.Schema.*
import zio.*

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
println(Schema[MultiOrderItem]
  .withForeignKey(_.userId).references[MultiUser](_.id).onDelete(Cascade)
  .withForeignKey(_.productId).references[MultiProduct](_.id).onDelete(Restrict)
  .ddl().sql)
```
```
// Scala 3.3.8
create table if not exists multi_order_items (id integer generated always as identity primary key not null, userId integer not null, productId integer not null, quantity integer not null, foreign key (userId) references multi_users (id) on delete cascade, foreign key (productId) references multi_products (id) on delete restrict)
```

## Compile-Time Column Safety

The foreign key builder extracts column names from selectors at compile time, so
the source and target columns must actually exist on their tables:

```scala
import saferis.*
import saferis.Schema.*

@tableName("type_users")
case class TypeUser(@generated @key id: Int, name: String) derives Table

@tableName("type_orders")
case class TypeOrder(@generated @key id: Int, userId: Int, userName: String) derives Table

// This compiles - userId and id are both real columns
val valid = Schema[TypeOrder]
  .withForeignKey(_.userId).references[TypeUser](_.id)
```

Referencing a column that doesn't exist on the source table is a compile error.
The snippet below does not compile; the actual compiler diagnostic is shown
beneath it:

```scala
import saferis.*
import saferis.Schema.*

@tableName("type_users")
case class TypeUser(@generated @key id: Int, name: String) derives Table

@tableName("type_orders")
case class TypeOrder(@generated @key id: Int, userId: Int) derives Table

// `nope` is not a field of TypeOrder — compile error.
val invalid = Schema[TypeOrder]
  .withForeignKey(_.nope).references[TypeUser](_.id)
```
```
// Scala 3.3.8
// error: value nope is not a member of TypeOrder
```

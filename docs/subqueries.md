# Subqueries

[← Back to index](index.md)

The Query builder supports type-safe subqueries for IN, NOT IN, EXISTS, and derived tables.

## IN Subqueries

Use `.select(_.column)` to create a typed subquery, then pass it to `.in()`:

```scala
import saferis.*
import saferis.postgres.given
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

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

println(Query[SubUser]
  .where(_.id).inSubquery(activeUserIds)  // Compiles: both are Int
  .build.sql)
```
```
// Scala 3.3.8
select * from sub_users as sub_users_ref_1 where sub_users_ref_1.id in (select userId from sub_orders as sub_orders_ref_1 where sub_orders_ref_1.status = ?)
```

```scala
val activeUserIds = Query[SubOrder]
  .where(_.status).eq("active")
  .select(_.userId)

// NOT IN subquery
println(Query[SubUser]
  .where(_.id).notInSubquery(activeUserIds)
  .build.sql)
```
```
// Scala 3.3.8
select * from sub_users as sub_users_ref_1 where sub_users_ref_1.id not in (select userId from sub_orders as sub_orders_ref_1 where sub_orders_ref_1.status = ?)
```

The type safety is enforced at compile time — if the column types don't match, it
won't compile. The snippet below tries to match an `Int` column against a
`SelectQuery[String]`; the real compiler error is shown beneath it:

```scala
import saferis.*
import saferis.postgres.given

@tableName("sub_users")
case class SubUser(@generated @key id: Int, name: String) derives Table

@tableName("sub_orders")
case class SubOrder(@generated @key id: Int, userId: Int, status: String) derives Table

// .select(_.status) is SelectQuery[String]; _.id is an Int column — mismatch.
val statuses = Query[SubOrder].where(_.userId).gt(0).select(_.status)
Query[SubUser].where(_.id).inSubquery(statuses).build.sql
```
```
// Scala 3.3.8
// error: Found:    (statuses : saferis.SelectQuery[String])
Required: saferis.SelectQuery[Int]
```

## IN Literal Collections

For IN-clauses over runtime values (not subqueries), use `in` (varargs) for inline literals or `inList` for any
`Iterable[T]`. Both work in the typed Query DSL and via the top-level `in(...)` helper inside `sql"..."` interpolation.

```scala
// Varargs form — natural for inline literals
println(Query[SubUser]
  .where(_.name).in("Alice", "Bob")
  .build.sql)
```
```
// Scala 3.3.8
select * from sub_users as sub_users_ref_1 where sub_users_ref_1.name in (?, ?)
```

```scala
// Iterable form — for runtime collections (List, Set, Vector, LinkedHashSet, ranges, ...)
val ids = List(1, 2, 3, 3)  // duplicates are removed automatically
println(Query[SubUser]
  .where(_.id).inList(ids)
  .build.sql)
```
```
// Scala 3.3.8
select * from sub_users as sub_users_ref_1 where sub_users_ref_1.id in (?, ?, ?)
```

```scala
// Same helpers in raw sql"..." interpolation:
val ids = List(1, 2, 3)
println(sql"select * from sub_users where id in ${in(ids)}".sql)
println(sql"select * from sub_users where name in ${in("Alice", "Bob")}".sql)
```
```
// Scala 3.3.8
select * from sub_users where id in (?, ?, ?)
select * from sub_users where name in (?, ?)
```

`notIn` / `notInList` are symmetric. `inSubquery` / `notInSubquery` (renamed from `in`/`notIn` on `SelectQuery`) cover
the subquery case shown above.

### Empty collections fail at construction, not in the database

An empty (or all-duplicates-collapse-to-empty) input would produce invalid SQL (`IN ()`) on every supported dialect.
Saferis does **not** throw at the call site — instead the resulting fragment carries one
`FragmentIssue.EmptyCollection` per offending helper. When the fragment is run, execution fails with
`SaferisError.InvalidStatement(issues)` *before* any JDBC call.

The recommended recovery pattern catches `InvalidStatement` and substitutes an empty result — no DB round-trip:

```scala
// Recovery pattern for possibly-empty collections — no DB round-trip on failure:
val ids2 = List.empty[Int]
val recovered =
  xa.run(Query[SubUser].where(_.id).inList(ids2).query[SubUser])
    .catchSome { case _: SaferisError.InvalidStatement => ZIO.succeed(Chunk.empty[SubUser]) }

recovered.debug("recovered")
```

Without that recovery, running the empty-collection query fails with
`SaferisError.InvalidStatement` — and crucially it fails *before* any JDBC call.
The example below inspects the failure to confirm both facts (the output is shown
beneath it):

```scala
// Empty collection → IN () would be invalid SQL → InvalidStatement at run time.
val outcome =
  xa.run(Query[SubUser].where(_.id).inList(List.empty[Int]).query[SubUser]).either

outcome.flatMap(result =>
  Console.printLine(result match
    case Left(_: SaferisError.InvalidStatement) => "Failed with InvalidStatement (no JDBC call made)"
    case Left(other)                            => s"Failed with ${other.getClass.getSimpleName}"
    case Right(rows)                            => s"Unexpectedly succeeded with ${rows.size} rows"
  )
)
```

If you want to surface issues independently of execution, call `fragment.validate` on any `SqlFragment` — it succeeds
with the fragment if there are no issues, fails with `InvalidStatement(issues)` otherwise. Multiple offending splices
in a single statement accumulate into the same `InvalidStatement`, so you fix them all at once.

## EXISTS Subqueries

Use `whereExists()` or `whereNotExists()`:

```scala
// EXISTS - find users who have orders
println(Query[SubUser]
  .whereExists(Query[SubOrder].all)
  .build.sql)
```
```
// Scala 3.3.8
select * from sub_users as sub_users_ref_1 where exists (select * from sub_orders as sub_orders_ref_1)
```

```scala
// NOT EXISTS - find users without orders
println(Query[SubUser]
  .whereNotExists(Query[SubOrder].where(_.status).eq("cancelled"))
  .build.sql)
```
```
// Scala 3.3.8
select * from sub_users as sub_users_ref_1 where not exists (select * from sub_orders as sub_orders_ref_1 where sub_orders_ref_1.status = ?)
```

## Correlated Subqueries

For correlated subqueries, use `sql"..."` to reference outer table columns:

```scala
val users = Table[SubUser]

// Correlated EXISTS - find users who have at least one order
println(Query[SubUser]
  .whereExists(
    Query[SubOrder].where(sql"userId = ${users.id}")
  )
  .build.sql)
```
```
// Scala 3.3.8
select * from sub_users as sub_users_ref_1 where exists (select * from sub_orders as sub_orders_ref_1 where userId = id)
```

## Derived Tables

Use subqueries in the FROM clause with `Query.from()`:

```scala
import saferis.*
import saferis.postgres.given

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

// Use as derived table with explicit alias
println(Query.from(highValueOrders, "high_value")
  .where(_.userId).gt(0)
  .build.sql)
```
```
// Scala 3.3.8
select * from (select * from derived_orders as derived_orders_ref_1 where derived_orders_ref_1.amount > ?) as high_value where high_value.userId > ?
```

```scala
val highValueOrders = Query[DerivedOrder]
  .where(_.amount).gt(BigDecimal(100))
  .selectAll[OrderSummary]

// Derived table with join
println(Query.from(highValueOrders, "summary")
  .innerJoin[DerivedUser].on(_.userId).eq(_.id)
  .all
  .build.sql)
```
```
// Scala 3.3.8
select * from (select * from derived_orders as derived_orders_ref_1 where derived_orders_ref_1.amount > ?) as summary inner join derived_users as derived_users_ref_1 on summary.userId = derived_users_ref_1.id
```

## Complex Nested Subqueries

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

val usersWithElectronics = Query[ComplexOrder]
  .where(_.productId).inSubquery(electronicProductIds)
  .select(_.userId)

println(Query[ComplexUser]
  .where(_.id).inSubquery(usersWithElectronics)
  .build.sql)
```
```
// Scala 3.3.8
select * from complex_users as complex_users_ref_1 where complex_users_ref_1.id in (select userId from complex_orders as complex_orders_ref_1 where complex_orders_ref_1.productId in (select id from complex_products as complex_products_ref_1 where complex_products_ref_1.category = ?))
```

## Operator Reference

All available operators in `Operator`:

| Operator | SQL | Notes |
|----------|-----|-------|
| `Eq` | `=` | Standard equality |
| `Neq` | `<>` | Standard inequality |
| `Lt` | `<` | Less than |
| `Lte` | `<=` | Less than or equal |
| `Gt` | `>` | Greater than |
| `Gte` | `>=` | Greater than or equal |
| `Like` | `like` | Pattern matching |
| `ILike` | `ilike` | Case-insensitive LIKE (PostgreSQL) |
| `SimilarTo` | `similar to` | Regex pattern (PostgreSQL) |
| `RegexMatch` | `~` | Regex match (PostgreSQL) |
| `RegexMatchCI` | `~*` | Case-insensitive regex (PostgreSQL) |
| `IsNull` | `is null` | Null check |
| `IsNotNull` | `is not null` | Non-null check |

## Complex WHERE with OR and Grouping

For queries with complex OR logic, use `andWhere` with a lambda:

```scala
import saferis.*
import saferis.postgres.given

@tableName("timeout_rows")
case class TimeoutRow(
  @generated @key id: Int,
  deadline: java.time.Instant,
  claimedBy: Option[String],
  claimedUntil: Option[java.time.Instant]
) derives Table
```

```scala
// Find rows that are due AND either unclaimed or with expired claims
val now = java.time.Instant.now()
println(Query[TimeoutRow]
  .where(_.deadline).lte(now)
  .andWhere(w => w(_.claimedBy).isNull.or(_.claimedUntil).lt(Some(now)))
  .build.sql)
```
```
// Scala 3.3.8
select * from timeout_rows as timeout_rows_ref_1 where timeout_rows_ref_1.deadline <= ? and (timeout_rows_ref_1.claimedBy is null or timeout_rows_ref_1.claimedUntil < ?)
```

This generates: `select ... where deadline <= ? and (claimed_by is null or claimed_until < ?)`

The parentheses are automatically added around the grouped conditions.

### Building Complex Conditions

The `andWhere` lambda provides a fluent builder:

```scala
// Multiple OR conditions
val now = java.time.Instant.now()
println(Query[TimeoutRow]
  .where(_.id).gt(0)
  .andWhere(w =>
    w(_.claimedBy).isNull
      .or(_.claimedUntil).lt(Some(now))
      .or(_.deadline).gt(now)
  )
  .build.sql)
```
```
// Scala 3.3.8
select * from timeout_rows as timeout_rows_ref_1 where timeout_rows_ref_1.id > ? and (timeout_rows_ref_1.claimedBy is null or timeout_rows_ref_1.claimedUntil < ? or timeout_rows_ref_1.deadline > ?)
```

```scala
// AND within the group
val now = java.time.Instant.now()
println(Query[TimeoutRow]
  .where(_.id).gt(0)
  .andWhere(w =>
    w(_.claimedBy).isNotNull
      .and(_.claimedUntil).gt(Some(now))
  )
  .build.sql)
```
```
// Scala 3.3.8
select * from timeout_rows as timeout_rows_ref_1 where timeout_rows_ref_1.id > ? and (timeout_rows_ref_1.claimedBy is not null and timeout_rows_ref_1.claimedUntil > ?)
```

Available operations in the `andWhere` builder:

| Method | Description |
|--------|-------------|
| `w(_.column)` | Start condition on a column |
| `.eq(value)` | Equality check |
| `.neq(value)` | Not equal |
| `.lt(value)` / `.lte(value)` | Less than (or equal) |
| `.gt(value)` / `.gte(value)` | Greater than (or equal) |
| `.isNull` / `.isNotNull` | Null checks |
| `.or(_.column)` | Chain with OR |
| `.and(_.column)` | Chain with AND |

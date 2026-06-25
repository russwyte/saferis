# Query Builder

[← Back to index](index.md)

Saferis provides a unified, type-safe `Query` builder for constructing SQL queries. It supports single-table queries, multi-table joins (up to 5 tables), WHERE clauses, pagination, and subqueries - all with compile-time type safety.

## Query Safety (Builder/Ready Pattern)

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
```

A query with no safety constraint cannot be built — `.build` simply doesn't exist
on a bare `Builder`, so the snippet below does not compile (the compiler error is
shown beneath it):

```scala
import saferis.*
import saferis.postgres.given

@tableName("safety_users")
case class SafetyUser(@generated @key id: Int, name: String) derives Table

// No WHERE / LIMIT / .all — .build is not available on a Builder.
Query[SafetyUser].build
```
```
// Scala 3.3.8
// error: value build is not a member of saferis.Query1Builder[SafetyUser]
```

The pattern ensures you consciously choose to query all rows with `.all` rather than accidentally doing so.

## Basic Queries

Start with `Query[A]` for single-table queries:

```scala
import saferis.*
import saferis.postgres.given
import zio.*

@tableName("query_users")
case class QueryUser(@generated @key id: Int, name: String, email: String, age: Int) derives Table

val users = Table[QueryUser]
```

```scala
// Simple query with type-safe WHERE
println(Query[QueryUser]
  .where(_.name).eq("Alice")
  .build.sql)
```
```
// Scala 3.3.8
select * from query_users as query_users_ref_1 where query_users_ref_1.name = ?
```

```scala
// Query with ordering and pagination
println(Query[QueryUser]
  .where(_.age).gt(18)
  .orderBy(users.name.asc)
  .limit(10)
  .offset(20)
  .build.sql)
```
```
// Scala 3.3.8
select * from query_users as query_users_ref_1 where query_users_ref_1.age > ? order by name asc limit 10 offset 20
```

## Type-Safe WHERE Clauses

Use selector syntax for type-safe column references:

```scala
// Equality
println(Query[QueryUser].where(_.name).eq("Alice").build.sql)
```
```
// Scala 3.3.8
select * from query_users as query_users_ref_1 where query_users_ref_1.name = ?
```

```scala
// Comparison operators
println(Query[QueryUser].where(_.age).gt(21).build.sql)
```
```
// Scala 3.3.8
select * from query_users as query_users_ref_1 where query_users_ref_1.age > ?
```

```scala
// IS NULL / IS NOT NULL
println(Query[QueryUser].where(_.email).isNotNull().build.sql)
```
```
// Scala 3.3.8
select * from query_users as query_users_ref_1 where query_users_ref_1.email is not null
```

You can also use raw `SqlFragment` for complex conditions:

```scala
// Raw SQL fragment
println(Query[QueryUser]
  .where(sql"${users.age} BETWEEN 18 AND 65")
  .build.sql)
```
```
// Scala 3.3.8
select * from query_users as query_users_ref_1 where age BETWEEN 18 AND 65
```

## Joins

Chain joins with the fluent API. The `on()` method uses type-safe selectors:

```scala
import saferis.*
import saferis.postgres.given
import zio.*

@tableName("join_users")
case class JoinUser(@generated @key id: Int, name: String) derives Table

@tableName("join_orders")
case class JoinOrder(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table

@tableName("join_items")
case class JoinItem(@key orderId: Int, @key productId: Int, quantity: Int) derives Table
```

```scala
// Inner join (using .all to explicitly fetch all rows)
println(Query[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .all
  .build.sql)
```
```
// Scala 3.3.8
select * from join_users as join_users_ref_1 inner join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId
```

```scala
// Left join
println(Query[JoinUser]
  .leftJoin[JoinOrder].on(_.id).eq(_.userId)
  .all
  .build.sql)
```
```
// Scala 3.3.8
select * from join_users as join_users_ref_1 left join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId
```

```scala
// Right join
println(Query[JoinUser]
  .rightJoin[JoinOrder].on(_.id).eq(_.userId)
  .all
  .build.sql)
```
```
// Scala 3.3.8
select * from join_users as join_users_ref_1 right join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId
```

```scala
// Full join
println(Query[JoinUser]
  .fullJoin[JoinOrder].on(_.id).eq(_.userId)
  .all
  .build.sql)
```
```
// Scala 3.3.8
select * from join_users as join_users_ref_1 full join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId
```

## Finalizing Joins with `.endJoin`

After specifying the ON clause, you can either:
1. Use **convenience methods** like `.where()`, `.limit()`, `.all` directly on the join chain
2. Call **`.endJoin`** explicitly to finalize the join and return to the query builder

```scala
// Using convenience method (implicitly calls endJoin)
println(Query[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .where(_.name).eq("Alice")  // Convenience method
  .build.sql)
```
```
// Scala 3.3.8
select * from join_users as join_users_ref_1 inner join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId where join_users_ref_1.name = ?
```

```scala
// Using explicit .endJoin for more control
println(Query[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .endJoin                    // Explicitly finalize join
  .orderBy(Table[JoinUser].name.asc)
  .all
  .build.sql)
```
```
// Scala 3.3.8
select * from join_users as join_users_ref_1 inner join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId order by name asc
```

The `.endJoin` method is useful when you want to add operations like `.orderBy()` that aren't available as convenience methods on the join chain.

## Multi-Table Joins

Chain up to 5 tables. Use `onPrev()` to reference the previously joined table:

```scala
// Three-table join
println(Query[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .innerJoin[JoinItem].onPrev(_.id).eq(_.orderId)
  .all
  .build.sql)
```
```
// Scala 3.3.8
select * from join_users as join_users_ref_1 inner join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId inner join join_items as join_items_ref_1 on join_orders_ref_1.id = join_items_ref_1.orderId
```

## WHERE on Joined Queries

After joining, use `where()` for the first table or `whereFrom()` for joined tables:

```scala
// WHERE on first table
println(Query[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .where(_.name).eq("Alice")
  .build.sql)
```
```
// Scala 3.3.8
select * from join_users as join_users_ref_1 inner join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId where join_users_ref_1.name = ?
```

```scala
// WHERE on joined table
println(Query[JoinUser]
  .innerJoin[JoinOrder].on(_.id).eq(_.userId)
  .whereFrom(_.amount).gt(BigDecimal(100))
  .build.sql)
```
```
// Scala 3.3.8
select * from join_users as join_users_ref_1 inner join join_orders as join_orders_ref_1 on join_users_ref_1.id = join_orders_ref_1.userId where join_orders_ref_1.amount > ?
```

## ON Clause Operators

All comparison operators are available in the ON clause:

| Method | SQL | Description |
|--------|-----|-------------|
| `eq()` | `=` | Equality |
| `neq()` | `<>` | Not equal |
| `lt()` | `<` | Less than |
| `lte()` | `<=` | Less than or equal |
| `gt()` | `>` | Greater than |
| `gte()` | `>=` | Greater than or equal |
| `isNull()` | `is null` | Null check |
| `isNotNull()` | `is not null` | Non-null check |
| `op(Operator.X)` | Custom | Any operator |

## Pagination

### Offset-Based Pagination

Traditional LIMIT/OFFSET pagination:

```scala
import saferis.*
import saferis.postgres.given
import zio.*

@tableName("page_articles")
case class Article(@generated @key id: Long, title: String, views: Int, published: Boolean) derives Table

val articles = Table[Article]
```

```scala
// Page 3 with 10 items per page
println(Query[Article]
  .where(_.published).eq(true)
  .orderBy(articles.views.desc)
  .limit(10)
  .offset(20)
  .build.sql)
```
```
// Scala 3.3.8
select * from page_articles as page_articles_ref_1 where page_articles_ref_1.published = ? order by views desc limit 10 offset 20
```

### Cursor/Seek Pagination

More efficient for large datasets - uses indexed lookups:

```scala
// Get next page after ID 100
println(Query[Article]
  .seekAfter(articles.id, 100L)
  .limit(10)
  .build.sql)
```
```
// Scala 3.3.8
select * from page_articles as page_articles_ref_1 where id > ? order by id asc limit 10
```

```scala
// Get previous page before ID 50
println(Query[Article]
  .seekBefore(articles.id, 50L)
  .limit(10)
  .build.sql)
```
```
// Scala 3.3.8
select * from page_articles as page_articles_ref_1 where id < ? order by id desc limit 10
```

## Sorting

Use column extensions for concise sorting:

```scala
println(Query[Article]
  .orderBy(articles.views.desc)
  .orderBy(articles.title.asc)
  .all
  .build.sql)
```
```
// Scala 3.3.8
select * from page_articles as page_articles_ref_1 order by views desc, title asc
```

Control NULL ordering:

```scala
println(Query[Article]
  .orderBy(articles.views.descNullsLast)
  .all
  .build.sql)
```
```
// Scala 3.3.8
select * from page_articles as page_articles_ref_1 order by views desc nulls last
```

Available sorting extensions:
- `.asc` / `.desc` - basic ordering
- `.ascNullsFirst` / `.ascNullsLast`
- `.descNullsFirst` / `.descNullsLast`

## Executing Queries

Use `.query[R]` to execute and decode results:

```scala
import saferis.*
import saferis.postgres.given
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("exec_users")
case class ExecUser(@generated @key id: Int, name: String) derives Table

@tableName("exec_orders")
case class ExecOrder(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table

val users = Table[ExecUser]
```

```scala
xa.run(for
  _ <- ddl.createTable[ExecUser](ifNotExists = true)
  _ <- ddl.createTable[ExecOrder](ifNotExists = true)
  _ <- dml.insert(ExecUser(-1, "Alice"))
  _ <- dml.insert(ExecUser(-1, "Bob"))
  _ <- dml.insert(ExecOrder(-1, 1, BigDecimal(100)))
  _ <- dml.insert(ExecOrder(-1, 1, BigDecimal(200)))
  result <- Query[ExecUser]
    .innerJoin[ExecOrder].on(_.id).eq(_.userId)
    .where(_.name).eq("Alice")
    .limit(10)
    .query[ExecUser]
yield result).debug("result")
```

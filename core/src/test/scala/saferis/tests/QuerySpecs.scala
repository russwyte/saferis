package saferis.tests

import saferis.*
import saferis.postgres.given
import zio.*
import zio.test.*

object QuerySpecs extends ZIOSpecDefault:

  // Test tables
  @tableName("users")
  case class User(@generated @key id: Int, name: String, email: String, age: Int) derives Table

  @tableName("orders")
  case class Order(@generated @key id: Int, userId: Int, amount: BigDecimal, status: String) derives Table

  @tableName("products")
  case class Product(@generated @key id: Int, name: String, price: BigDecimal) derives Table

  @tableName("order_items")
  case class OrderItem(@key orderId: Int, @key productId: Int, quantity: Int) derives Table

  @tableName("categories")
  case class Category(@generated @key id: Int, name: String) derives Table

  val spec = suite("Unified Query API")(
    suite("Query1 - Single table")(
      test("Query[A] creates aliased table query") {
        AliasGenerator.reset()
        val q   = Query[User].all
        val sql = q.build.sql
        assertTrue(
          sql.contains("select * from users as t1")
        )
      },
      test("where with SqlFragment") {
        AliasGenerator.reset()
        val q   = Query[User].where(sql"name = 'Alice'")
        val sql = q.build.sql
        assertTrue(
          sql.contains("where name = 'Alice'")
        )
      },
      test("type-safe where with eq") {
        AliasGenerator.reset()
        val q   = Query[User].where(_.name).eq("Alice")
        val sql = q.build.sql
        assertTrue(
          sql.contains("where"),
          sql.contains("name ="),
        )
      },
      test("orderBy adds sort clause") {
        AliasGenerator.reset()
        val users = Table[User]
        val q     = Query[User].orderBy(users.name.asc).all
        val sql   = q.build.sql
        assertTrue(
          sql.contains("order by name asc")
        )
      },
      test("limit and offset") {
        AliasGenerator.reset()
        val q   = Query[User].limit(10).offset(20)
        val sql = q.build.sql
        assertTrue(
          sql.contains("limit 10"),
          sql.contains("offset 20"),
        )
      },
      test("seekAfter generates where and order by") {
        AliasGenerator.reset()
        val users = Table[User]
        val q     = Query[User].seekAfter(users.id, 100)
        val sql   = q.build.sql
        assertTrue(
          sql.contains("where id > ?"),
          sql.contains("order by id asc"),
        )
      },
    ),
    suite("Query2 - Two tables joined")(
      test("innerJoin generates correct SQL") {
        AliasGenerator.reset()
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("select * from users as t1"),
          sql.contains("inner join orders as t2"),
          sql.contains("on t1.id = t2.userId"),
        )
      },
      test("leftJoin generates correct SQL") {
        AliasGenerator.reset()
        val q = Query[User]
          .leftJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("left join orders as t2")
        )
      },
      test("rightJoin generates correct SQL") {
        AliasGenerator.reset()
        val q = Query[User]
          .rightJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("right join orders as t2")
        )
      },
      test("fullJoin generates correct SQL") {
        AliasGenerator.reset()
        val q = Query[User]
          .fullJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("full join orders as t2")
        )
      },
      test("join with where clause") {
        AliasGenerator.reset()
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .where(_.name)
          .eq("Alice")
        val sql = q.build.sql
        assertTrue(
          sql.contains("inner join orders as t2"),
          sql.contains("where"),
        )
      },
      test("join with limit and offset") {
        AliasGenerator.reset()
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .limit(20)
          .offset(40)
        val sql = q.build.sql
        assertTrue(
          sql.contains("limit 20"),
          sql.contains("offset 40"),
        )
      },
      test("join with seekAfter") {
        AliasGenerator.reset()
        val users = Table[User]
        val q     = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .endJoin
          .seekAfter(users.id, 100)
        val sql = q.build.sql
        assertTrue(
          sql.contains("where id > ?"),
          sql.contains("order by id asc"),
        )
      },
    ),
    suite("Query3 - Three tables joined")(
      test("chained joins generate correct SQL") {
        AliasGenerator.reset()
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .innerJoin[OrderItem]
          .onPrev(_.id)
          .eq(_.orderId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("inner join orders as t2"),
          sql.contains("inner join order_items as t3"),
          sql.contains("on t1.id = t2.userId"),
          sql.contains("on t2.id = t3.orderId"),
        )
      }
    ),
    suite("ON clause operators")(
      test("neq generates <>") {
        AliasGenerator.reset()
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .neq(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("on t1.id <> t2.userId")
        )
      },
      test("lt generates <") {
        AliasGenerator.reset()
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .lt(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("on t1.id < t2.userId")
        )
      },
      test("lte generates <=") {
        AliasGenerator.reset()
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .lte(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("on t1.id <= t2.userId")
        )
      },
      test("gt generates >") {
        AliasGenerator.reset()
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .gt(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("on t1.id > t2.userId")
        )
      },
      test("gte generates >=") {
        AliasGenerator.reset()
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .gte(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("on t1.id >= t2.userId")
        )
      },
    ),
    suite("WHERE clause")(
      test("type-safe where with neq") {
        AliasGenerator.reset()
        val q   = Query[User].where(_.name).neq("Bob")
        val sql = q.build.sql
        assertTrue(sql.contains("where"), sql.contains("<>"))
      },
      test("type-safe where with lt") {
        AliasGenerator.reset()
        val q   = Query[User].where(_.age).lt(30)
        val sql = q.build.sql
        assertTrue(sql.contains("where"), sql.contains("<"))
      },
      test("type-safe where with gt") {
        AliasGenerator.reset()
        val q   = Query[User].where(_.age).gt(18)
        val sql = q.build.sql
        assertTrue(sql.contains("where"), sql.contains(">"))
      },
      test("type-safe where with isNull") {
        AliasGenerator.reset()
        val q   = Query[User].where(_.email).isNull()
        val sql = q.build.sql
        assertTrue(sql.contains("IS NULL"))
      },
      test("type-safe where with isNotNull") {
        AliasGenerator.reset()
        val q   = Query[User].where(_.email).isNotNull()
        val sql = q.build.sql
        assertTrue(sql.contains("IS NOT NULL"))
      },
    ),
    suite("IN Subqueries")(
      test("simple IN subquery") {
        AliasGenerator.reset()
        val subquery = Query[Order].select(_.userId)
        val q        = Query[User].where(_.id).in(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("IN (select userId from orders as t1)")
        )
      },
      test("IN subquery with where clause") {
        AliasGenerator.reset()
        val subquery = Query[Order].where(_.status).eq("paid").select(_.userId)
        val q        = Query[User].where(_.id).in(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("IN (select userId from orders as t1 where"),
          sql.contains("status ="),
        )
      },
      test("NOT IN subquery") {
        AliasGenerator.reset()
        val subquery = Query[Order].select(_.userId)
        val q        = Query[User].where(_.id).notIn(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("NOT IN (select userId from orders as t1)")
        )
      },
      test("select modifies query to select specific column") {
        AliasGenerator.reset()
        val q   = Query[User].select(_.id)
        val sql = q.build.sql
        assertTrue(
          sql.startsWith("select id from users")
        )
      },
    ),
    suite("EXISTS Subqueries")(
      test("simple EXISTS subquery") {
        AliasGenerator.reset()
        val subquery = Query[Order].all
        val q        = Query[User].whereExists(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("EXISTS (select * from orders as t1)")
        )
      },
      test("EXISTS subquery with where clause") {
        AliasGenerator.reset()
        val subquery = Query[Order].where(_.status).eq("paid")
        val q        = Query[User].whereExists(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("EXISTS (select * from orders as t1 where")
        )
      },
      test("NOT EXISTS subquery") {
        AliasGenerator.reset()
        val subquery = Query[Order].all
        val q        = Query[User].whereNotExists(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("NOT EXISTS (select * from orders as t1)")
        )
      },
      test("correlated EXISTS using sql interpolation") {
        AliasGenerator.reset()
        val users    = Table[User]
        val subquery = Query[Order].where(sql"userId = ${users.id}")
        val q        = Query[User].whereExists(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("EXISTS"),
          sql.contains("userId = id"),
        )
      },
    ),
    suite("Derived Tables")(
      test("selectAll creates typed query for derived table") {
        AliasGenerator.reset()
        // Virtual type matching subquery result shape
        @tableName("order_summary")
        case class OrderSummary(userId: Int, status: String) derives Table

        val subquery = Query[Order]
          .where(_.amount)
          .gt(BigDecimal(100))
          .selectAll[OrderSummary]

        // SelectQuery wraps the original query
        val sql = subquery.build.sql
        assertTrue(
          sql.contains("select * from orders as t1"),
          sql.contains("where"),
        )
      },
      test("Query.from creates derived table query") {
        AliasGenerator.reset()
        @tableName("paid_orders")
        case class PaidOrder(userId: Int, amount: BigDecimal, status: String) derives Table

        val subquery = Query[Order]
          .where(_.status)
          .eq("paid")
          .selectAll[PaidOrder]

        val q   = Query.from(subquery, "paid_summary").all
        val sql = q.build.sql
        assertTrue(
          sql.contains("select * from (select * from orders as t1 where"),
          sql.contains(") as paid_summary"),
        )
      },
      test("derived table with where clause") {
        AliasGenerator.reset()
        @tableName("high_value")
        case class HighValueOrder(userId: Int, amount: BigDecimal) derives Table

        val subquery = Query[Order]
          .where(_.amount)
          .gt(BigDecimal(1000))
          .selectAll[HighValueOrder]

        val q = Query
          .from(subquery, "high_value")
          .where(_.userId)
          .gt(0)

        val sql = q.build.sql
        assertTrue(
          sql.contains("(select * from orders as t1 where"),
          sql.contains(") as high_value"),
          sql.contains("\"high_value\".userId >"),
        )
      },
      test("derived table with join") {
        AliasGenerator.reset()
        @tableName("order_totals")
        case class OrderTotal(userId: Int, amount: BigDecimal) derives Table

        val subquery = Query[Order]
          .where(_.status)
          .eq("completed")
          .selectAll[OrderTotal]

        val q = Query
          .from(subquery, "totals")
          .innerJoin[User]
          .on(_.userId)
          .eq(_.id)
          .endJoin
          .all

        val sql = q.build.sql
        assertTrue(
          sql.contains("(select * from orders as t1 where"),
          sql.contains(") as totals"),
          sql.contains("inner join users as t2"),
          sql.contains(""""totals".userId = t2.id"""),
        )
      },
    ),
  ) @@ TestAspect.sequential
end QuerySpecs

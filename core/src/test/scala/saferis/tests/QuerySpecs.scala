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
        val q   = Query[User].all
        val sql = q.build.sql
        assertTrue(
          sql == "select * from users as users_ref_1"
        )
      },
      test("where with SqlFragment") {
        val q   = Query[User].where(sql"name = 'Alice'")
        val sql = q.build.sql
        assertTrue(
          sql.contains("where name = 'Alice'")
        )
      },
      test("type-safe where with eq") {
        val q   = Query[User].where(_.name).eq("Alice")
        val sql = q.build.sql
        assertTrue(
          sql.contains("where"),
          sql.contains("name ="),
        )
      },
      test("orderBy adds sort clause") {
        val users = Table[User]
        val q     = Query[User].orderBy(users.name.asc).all
        val sql   = q.build.sql
        assertTrue(
          sql.contains("order by name asc")
        )
      },
      test("limit and offset") {
        val q   = Query[User].limit(10).offset(20)
        val sql = q.build.sql
        assertTrue(
          sql.contains("limit 10"),
          sql.contains("offset 20"),
        )
      },
      test("seekAfter generates where and order by") {
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
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("select * from users as users_ref_1"),
          sql.contains("inner join orders as orders_ref_1"),
          sql.contains("on users_ref_1.id = orders_ref_1.userId"),
        )
      },
      test("leftJoin generates correct SQL") {
        val q = Query[User]
          .leftJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("left join orders as orders_ref_1")
        )
      },
      test("rightJoin generates correct SQL") {
        val q = Query[User]
          .rightJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("right join orders as orders_ref_1")
        )
      },
      test("fullJoin generates correct SQL") {
        val q = Query[User]
          .fullJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("full join orders as orders_ref_1")
        )
      },
      test("join with where clause") {
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .where(_.name)
          .eq("Alice")
        val sql = q.build.sql
        assertTrue(
          sql.contains("inner join orders as orders_ref_1"),
          sql.contains("where"),
        )
      },
      test("join with limit and offset") {
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
          sql.contains("inner join orders as orders_ref_1"),
          sql.contains("inner join order_items as order_items_ref_1"),
          sql.contains("on users_ref_1.id = orders_ref_1.userId"),
          sql.contains("on orders_ref_1.id = order_items_ref_1.orderId"),
        )
      }
    ),
    suite("ON clause operators")(
      test("neq generates <>") {
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .neq(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("on users_ref_1.id <> orders_ref_1.userId")
        )
      },
      test("lt generates <") {
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .lt(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("on users_ref_1.id < orders_ref_1.userId")
        )
      },
      test("lte generates <=") {
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .lte(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("on users_ref_1.id <= orders_ref_1.userId")
        )
      },
      test("gt generates >") {
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .gt(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("on users_ref_1.id > orders_ref_1.userId")
        )
      },
      test("gte generates >=") {
        val q = Query[User]
          .innerJoin[Order]
          .on(_.id)
          .gte(_.userId)
          .endJoin
          .all
        val sql = q.build.sql
        assertTrue(
          sql.contains("on users_ref_1.id >= orders_ref_1.userId")
        )
      },
    ),
    suite("WHERE clause")(
      test("type-safe where with neq") {
        val q   = Query[User].where(_.name).neq("Bob")
        val sql = q.build.sql
        assertTrue(sql.contains("where"), sql.contains("<>"))
      },
      test("type-safe where with lt") {
        val q   = Query[User].where(_.age).lt(30)
        val sql = q.build.sql
        assertTrue(sql.contains("where"), sql.contains("<"))
      },
      test("type-safe where with gt") {
        val q   = Query[User].where(_.age).gt(18)
        val sql = q.build.sql
        assertTrue(sql.contains("where"), sql.contains(">"))
      },
      test("type-safe where with isNull") {
        val q   = Query[User].where(_.email).isNull()
        val sql = q.build.sql
        assertTrue(sql.contains("IS NULL"))
      },
      test("type-safe where with isNotNull") {
        val q   = Query[User].where(_.email).isNotNull()
        val sql = q.build.sql
        assertTrue(sql.contains("IS NOT NULL"))
      },
    ),
    suite("IN Subqueries")(
      test("simple IN subquery") {
        val subquery = Query[Order].select(_.userId)
        val q        = Query[User].where(_.id).in(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("IN (select userId from orders as orders_ref_1)")
        )
      },
      test("IN subquery with where clause") {
        val subquery = Query[Order].where(_.status).eq("paid").select(_.userId)
        val q        = Query[User].where(_.id).in(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("IN (select userId from orders as orders_ref_1 where"),
          sql.contains("status ="),
        )
      },
      test("NOT IN subquery") {
        val subquery = Query[Order].select(_.userId)
        val q        = Query[User].where(_.id).notIn(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("NOT IN (select userId from orders as orders_ref_1)")
        )
      },
      test("select modifies query to select specific column") {
        val q   = Query[User].select(_.id)
        val sql = q.build.sql
        assertTrue(
          sql.startsWith("select id from users")
        )
      },
    ),
    suite("EXISTS Subqueries")(
      test("simple EXISTS subquery") {
        val subquery = Query[Order].all
        val q        = Query[User].whereExists(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("EXISTS (select * from orders as orders_ref_1)")
        )
      },
      test("EXISTS subquery with where clause") {
        val subquery = Query[Order].where(_.status).eq("paid")
        val q        = Query[User].whereExists(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("EXISTS (select * from orders as orders_ref_1 where")
        )
      },
      test("NOT EXISTS subquery") {
        val subquery = Query[Order].all
        val q        = Query[User].whereNotExists(subquery)
        val sql      = q.build.sql
        assertTrue(
          sql.contains("NOT EXISTS (select * from orders as orders_ref_1)")
        )
      },
      test("correlated EXISTS using sql interpolation") {
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
          sql.contains("select * from orders as orders_ref_1"),
          sql.contains("where"),
        )
      },
      test("Query.from creates derived table query") {
        @tableName("paid_orders")
        case class PaidOrder(userId: Int, amount: BigDecimal, status: String) derives Table

        val subquery = Query[Order]
          .where(_.status)
          .eq("paid")
          .selectAll[PaidOrder]

        val q   = Query.from(subquery, "paid_summary").all
        val sql = q.build.sql
        assertTrue(
          sql.contains("select * from (select * from orders as orders_ref_1 where"),
          sql.contains(") as paid_summary"),
        )
      },
      test("derived table with where clause") {
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
          sql.contains("(select * from orders as orders_ref_1 where"),
          sql.contains(") as high_value"),
          sql.contains("\"high_value\".userId >"),
        )
      },
      test("derived table with join") {
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
          sql.contains("(select * from orders as orders_ref_1 where"),
          sql.contains(") as totals"),
          sql.contains("inner join users as users_ref_1"),
          sql.contains(""""totals".userId = users_ref_1.id"""),
        )
      },
    ),
  ) @@ TestAspect.sequential
end QuerySpecs

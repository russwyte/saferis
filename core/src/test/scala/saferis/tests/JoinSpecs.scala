package saferis.tests

import saferis.*
import saferis.postgres.given
import zio.*
import zio.test.*

object JoinSpecs extends ZIOSpecDefault:

  // Test tables
  @tableName("join_users")
  case class User(@generated @key id: Int, name: String, email: String) derives Table

  @tableName("join_orders")
  case class Order(@generated @key id: Int, userId: Int, amount: BigDecimal) derives Table

  @tableName("join_products")
  case class Product(@generated @key id: Int, name: String, price: BigDecimal) derives Table

  @tableName("join_order_items")
  case class OrderItem(@key orderId: Int, @key productId: Int, quantity: Int) derives Table

  @tableName("join_categories")
  case class Category(@generated @key id: Int, name: String) derives Table

  val spec = suite("Type-Safe Join API")(
    suite("JoinType")(
      test("toSql returns correct SQL for each join type") {
        assertTrue(
          JoinType.Inner.toSql == "inner join",
          JoinType.Left.toSql == "left join",
          JoinType.Right.toSql == "right join",
          JoinType.Full.toSql == "full join",
          JoinType.Cross.toSql == "cross join",
        )
      }
    ),
    suite("JoinSpec1 - Single table")(
      test("JoinSpec[A] creates a spec with aliased table") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
        val sql  = spec.build.sql
        assertTrue(
          sql.contains("select * from join_users as t1")
        )
      },
      test("where adds predicate") {
        AliasGenerator.reset()
        val spec = JoinSpec[User].where(sql"name = 'Alice'")
        val sql  = spec.build.sql
        assertTrue(
          sql.contains("where name = 'Alice'")
        )
      },
      test("orderBy adds sort clause") {
        AliasGenerator.reset()
        val users = Table[User]
        val spec  = JoinSpec[User].orderBy(users.name.asc)
        val sql   = spec.build.sql
        assertTrue(
          sql.contains("order by name asc")
        )
      },
      test("limit and offset are applied") {
        AliasGenerator.reset()
        val spec = JoinSpec[User].limit(10).offset(20)
        val sql  = spec.build.sql
        assertTrue(
          sql.contains("limit 10"),
          sql.contains("offset 20"),
        )
      },
    ),
    suite("ON Clause - Basic")(
      test("type-safe on().eq() generates correct SQL") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
        val sql = spec.build.sql
        assertTrue(
          sql.contains("select * from join_users as t1"),
          sql.contains("inner join join_orders as t2"),
          sql.contains("""on "t1".id = "t2".userId"""),
        )
      },
      test("type-safe leftJoin generates correct SQL") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .leftJoin[Order]
          .on(_.id)
          .eq(_.userId)
        val sql = spec.build.sql
        assertTrue(
          sql.contains("left join join_orders as t2"),
          sql.contains("""on "t1".id = "t2".userId"""),
        )
      },
      test("type-safe rightJoin generates correct SQL") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .rightJoin[Order]
          .on(_.id)
          .eq(_.userId)
        val sql = spec.build.sql
        assertTrue(
          sql.contains("right join join_orders as t2")
        )
      },
      test("type-safe fullJoin generates correct SQL") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .fullJoin[Order]
          .on(_.id)
          .eq(_.userId)
        val sql = spec.build.sql
        assertTrue(
          sql.contains("full join join_orders as t2")
        )
      },
    ),
    suite("ON Clause - Operators")(
      test("neq operator generates <>") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .neq(_.userId)
        val sql = spec.build.sql
        assertTrue(
          sql.contains("""on "t1".id <> "t2".userId""")
        )
      },
      test("lt operator generates <") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .lt(_.userId)
        val sql = spec.build.sql
        assertTrue(
          sql.contains("""on "t1".id < "t2".userId""")
        )
      },
      test("lte operator generates <=") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .lte(_.userId)
        val sql = spec.build.sql
        assertTrue(
          sql.contains("""on "t1".id <= "t2".userId""")
        )
      },
      test("gt operator generates >") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .gt(_.userId)
        val sql = spec.build.sql
        assertTrue(
          sql.contains("""on "t1".id > "t2".userId""")
        )
      },
      test("gte operator generates >=") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .gte(_.userId)
        val sql = spec.build.sql
        assertTrue(
          sql.contains("""on "t1".id >= "t2".userId""")
        )
      },
      test("custom operator with op()") {
        AliasGenerator.reset()
        // Compare two Int fields with a custom operator
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .op(JoinOperator.Gte)(_.userId)
        val sql = spec.build.sql
        assertTrue(
          sql.contains("""on "t1".id >= "t2".userId""")
        )
      },
    ),
    suite("ON Clause - IS NULL / IS NOT NULL")(
      test("isNull in ON clause") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .isNull()
        val sql = spec.build.sql
        assertTrue(
          sql.contains("""on "t1".id IS NULL""")
        )
      },
      test("isNotNull in ON clause") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .isNotNull()
        val sql = spec.build.sql
        assertTrue(
          sql.contains("""on "t1".id IS NOT NULL""")
        )
      },
    ),
    suite("ON Clause - Chaining")(
      test("and() chains another condition from left table") {
        AliasGenerator.reset()
        // Chain with matching types (Int fields)
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .and(_.id)
          .eq(_.id)
        val sql = spec.build.sql
        assertTrue(
          sql.contains("""on "t1".id = "t2".userId AND "t1".id = "t2".id""")
        )
      },
      test("andRight() chains condition from right table") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .andRight(_.amount)
          .isNotNull()
        val sql = spec.build.sql
        assertTrue(
          sql.contains(""""t2".amount IS NOT NULL""")
        )
      },
    ),
    suite("WHERE Clause - Type-Safe")(
      test("type-safe where().eq() with literal value") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .where(_.name)
          .eq("Alice")
        val fragment = spec.build
        assertTrue(
          fragment.sql.contains("""where "t1".name = ?"""),
          fragment.writes.length == 1,
        )
      },
      test("type-safe where().gte() with literal value") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .whereFrom(_.amount)
          .gte(BigDecimal(100))
        val fragment = spec.build
        assertTrue(
          fragment.sql.contains("""where "t2".amount >= ?"""),
          fragment.writes.length == 1,
        )
      },
      test("where IS NULL on left table") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .whereIsNull(_.email)
        val sql = spec.build.sql
        assertTrue(
          sql.contains(""""t1".email IS NULL""")
        )
      },
      test("where IS NULL on right table (common LEFT JOIN pattern)") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .leftJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .whereIsNullFrom(_.id)
        val sql = spec.build.sql
        assertTrue(
          sql.contains("left join"),
          sql.contains(""""t2".id IS NULL"""),
        )
      },
    ),
    suite("WHERE Clause - Chaining")(
      test("chained where conditions with and()") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .where(_.name)
          .eq("Alice")
          .and(_.email)
          .isNotNull()
        val fragment = spec.build
        assertTrue(
          fragment.sql.contains("""where "t1".name = ?"""),
          fragment.sql.contains(""""t1".email IS NOT NULL"""),
        )
      },
      test("chained where conditions from both tables") {
        AliasGenerator.reset()
        val spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .where(_.name)
          .eq("Alice")
          .andFrom(_.amount)
          .gt(BigDecimal(50))
        val fragment = spec.build
        assertTrue(
          fragment.sql.contains("""where "t1".name = ?"""),
          fragment.sql.contains(""""t2".amount > ?"""),
          fragment.writes.length == 2,
        )
      },
    ),
    suite("Complete Type-Safe Query")(
      test("full query with type-safe on, where, orderBy, limit") {
        AliasGenerator.reset()
        val users = Table[User]
        val spec  = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .where(_.name)
          .eq("Alice")
          .andFrom(_.amount)
          .gt(BigDecimal(100))
          .orderBy(users.name.asc)
          .limit(10)
        val fragment = spec.build
        assertTrue(
          fragment.sql.contains("select * from join_users as t1"),
          fragment.sql.contains("""inner join join_orders as t2 on "t1".id = "t2".userId"""),
          fragment.sql.contains("""where "t1".name = ?"""),
          fragment.sql.contains(""""t2".amount > ?"""),
          fragment.sql.contains("order by"),
          fragment.sql.contains("limit 10"),
          fragment.writes.length == 2,
        )
      }
    ),
  ) @@ TestAspect.sequential
end JoinSpecs

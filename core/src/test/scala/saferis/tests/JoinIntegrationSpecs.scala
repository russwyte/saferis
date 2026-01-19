package saferis.tests

import saferis.*
import saferis.ddl.*
import saferis.dml.*
import saferis.postgres.given
import zio.*
import zio.test.*
import PostgresTestContainer.DataSourceProvider

object JoinIntegrationSpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default

  // Test tables
  @tableName("join_int_users")
  case class User(@generated @key id: Int, name: String, email: String) derives Table

  @tableName("join_int_products")
  case class Product(@generated @key id: Int, name: String, price: BigDecimal) derives Table

  @tableName("join_int_orders")
  case class Order(@generated @key id: Int, userId: Int, productId: Int, amount: BigDecimal) derives Table

  @tableName("join_int_categories")
  case class Category(@generated @key id: Int, name: String) derives Table

  @tableName("join_int_product_categories")
  case class ProductCategory(@key productId: Int, @key categoryId: Int) derives Table

  // Result types for queries
  case class UserWithOrderAmount(name: String, amount: BigDecimal) derives Table
  case class UserOrderProduct(userName: String, orderAmount: BigDecimal, productName: String) derives Table
  case class UserCount(name: String, orderCount: Int) derives Table

  // Setup: create tables and insert test data
  def setupTables(xa: Transactor): Task[Unit] =
    for
      // Drop tables in correct order (children first)
      _ <- xa.run(dropTable[ProductCategory](ifExists = true))
      _ <- xa.run(dropTable[Order](ifExists = true))
      _ <- xa.run(dropTable[Product](ifExists = true))
      _ <- xa.run(dropTable[User](ifExists = true))
      _ <- xa.run(dropTable[Category](ifExists = true))
      // Create tables
      _ <- xa.run(createTable[User]())
      _ <- xa.run(createTable[Product]())
      _ <- xa.run(createTable[Order]())
      _ <- xa.run(createTable[Category]())
      _ <- xa.run(createTable[ProductCategory]())
      // Insert test data - Users
      _ <- xa.run(sql"insert into join_int_users (name, email) values ('Alice', 'alice@test.com')".insert)
      _ <- xa.run(sql"insert into join_int_users (name, email) values ('Bob', 'bob@test.com')".insert)
      _ <- xa.run(sql"insert into join_int_users (name, email) values ('Charlie', 'charlie@test.com')".insert)
      // Insert test data - Products
      _ <- xa.run(sql"insert into join_int_products (name, price) values ('Widget', 9.99)".insert)
      _ <- xa.run(sql"insert into join_int_products (name, price) values ('Gadget', 19.99)".insert)
      _ <- xa.run(sql"insert into join_int_products (name, price) values ('Gizmo', 29.99)".insert)
      // Insert test data - Categories
      _ <- xa.run(sql"insert into join_int_categories (name) values ('Electronics')".insert)
      _ <- xa.run(sql"insert into join_int_categories (name) values ('Accessories')".insert)
      // Insert test data - Orders (Alice: 2 orders, Bob: 1 order, Charlie: 0 orders)
      _ <- xa.run(sql"insert into join_int_orders (userId, productId, amount) values (1, 1, 100.00)".insert)
      _ <- xa.run(sql"insert into join_int_orders (userId, productId, amount) values (1, 2, 200.00)".insert)
      _ <- xa.run(sql"insert into join_int_orders (userId, productId, amount) values (2, 3, 150.00)".insert)
      // Insert test data - ProductCategories
      _ <- xa.run(sql"insert into join_int_product_categories (productId, categoryId) values (1, 1)".insert)
      _ <- xa.run(sql"insert into join_int_product_categories (productId, categoryId) values (2, 1)".insert)
      _ <- xa.run(sql"insert into join_int_product_categories (productId, categoryId) values (2, 2)".insert)
      _ <- xa.run(sql"insert into join_int_product_categories (productId, categoryId) values (3, 1)".insert)
    yield ()

  val spec = suite("Join Integration Tests")(
    test("setup tables for all tests") {
      for
        xa <- ZIO.service[Transactor]
        _  <- setupTables(xa)
      yield assertTrue(true)
    },
    test("inner join returns matching rows") {
      AliasGenerator.reset()
      for
        xa <- ZIO.service[Transactor]
        spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
        query = sql"${spec.build}".query[User]
        result <- xa.run(query)
      yield assertTrue(
        result.length == 3, // Alice has 2 orders, Bob has 1
        result.map(_.name).toSet == Set("Alice", "Bob"),
      )
      end for
    },
    test("left join includes rows without matches") {
      AliasGenerator.reset()
      for
        xa <- ZIO.service[Transactor]
        spec = JoinSpec[User]
          .leftJoin[Order]
          .on(_.id)
          .eq(_.userId)
        query = sql"${spec.build}".query[User]
        result <- xa.run(query)
      yield assertTrue(
        result.length == 4, // Alice (2), Bob (1), Charlie (1 with null order)
        result.map(_.name).toSet == Set("Alice", "Bob", "Charlie"),
      )
      end for
    },
    test("join with type-safe where clause filters results") {
      AliasGenerator.reset()
      val minAmount = BigDecimal(150)
      for
        xa <- ZIO.service[Transactor]
        spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .whereFrom(_.amount)
          .gte(minAmount)
        query = sql"${spec.build}".query[User]
        result <- xa.run(query)
      yield assertTrue(
        result.length == 2, // Alice's 200 order and Bob's 150 order
        result.map(_.name).toSet == Set("Alice", "Bob"),
      )
      end for
    },
    test("join with limit restricts results") {
      AliasGenerator.reset()
      for
        xa <- ZIO.service[Transactor]
        spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .limit(2)
        query = sql"${spec.build}".query[User]
        result <- xa.run(query)
      yield assertTrue(result.length == 2)
      end for
    },
    test("join with offset skips results") {
      AliasGenerator.reset()
      for
        xa <- ZIO.service[Transactor]
        spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .limit(2)
          .offset(1)
        query = sql"${spec.build}".query[User]
        result <- xa.run(query)
      yield assertTrue(result.length == 2)
      end for
    },
    test("join with parameterized where clause") {
      AliasGenerator.reset()
      val userName = "Alice"
      for
        xa <- ZIO.service[Transactor]
        spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .where(_.name)
          .eq(userName)
        query = sql"${spec.build}".query[User]
        result <- xa.run(query)
      yield assertTrue(
        result.length == 2,
        result.forall(_.name == "Alice"),
      )
      end for
    },
    test("join with multiple where conditions") {
      AliasGenerator.reset()
      val userName  = "Alice"
      val minAmount = BigDecimal(150)
      for
        xa <- ZIO.service[Transactor]
        spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .where(_.name)
          .eq(userName)
          .andFrom(_.amount)
          .gte(minAmount)
        query = sql"${spec.build}".query[User]
        result <- xa.run(query)
      yield assertTrue(
        result.length == 1, // Only Alice's 200 order
        result.head.name == "Alice",
      )
      end for
    },
    test("left join with whereIsNullFrom finds users without orders") {
      AliasGenerator.reset()
      for
        xa <- ZIO.service[Transactor]
        spec = JoinSpec[User]
          .leftJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .whereIsNullFrom(_.id)
        query = sql"${spec.build}".query[User]
        result <- xa.run(query)
      yield assertTrue(
        result.length == 1,
        result.head.name == "Charlie",
      )
      end for
    },
    test("join with IS NOT NULL in ON clause") {
      AliasGenerator.reset()
      for
        xa <- ZIO.service[Transactor]
        spec = JoinSpec[User]
          .innerJoin[Order]
          .on(_.id)
          .eq(_.userId)
          .andRight(_.amount)
          .isNotNull()
        query = sql"${spec.build}".query[User]
        result <- xa.run(query)
      yield assertTrue(
        result.length == 3 // All orders have non-null amounts
      )
      end for
    },
    // TODO: Multi-table joins (3+) will be added after OnConditionBuilder2-4 are implemented
  ).provideShared(xaLayer) @@ TestAspect.sequential
end JoinIntegrationSpecs

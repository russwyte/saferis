package saferis.tests

import saferis.*
import saferis.TableAspects.*
import saferis.postgres.given
import zio.*
import zio.test.*

object ForeignKeySpecs extends ZIOSpecDefault:

  // Test case classes
  @tableName("fk_test_users")
  case class User(@generated @key id: Int, name: String, email: String) derives Table

  @tableName("fk_test_products")
  case class Product(@generated @key id: Int, name: String, price: BigDecimal) derives Table

  @tableName("fk_test_orders")
  case class Order(@generated @key id: Int, userId: Int, productId: Int, amount: BigDecimal) derives Table

  // Compound key example
  @tableName("fk_test_order_details")
  case class OrderDetail(@key orderId: Int, @key lineNum: Int, sku: String, quantity: Int) derives Table

  @tableName("fk_test_order_items")
  case class OrderItem(@key id: Int, orderId: Int, lineNum: Int, price: BigDecimal) derives Table

  val spec = suite("ForeignKey API")(
    suite("ForeignKeySpec")(
      test("toConstraintSql generates correct SQL for single column FK") {
        val fk = ForeignKeySpec[Order, User](
          fromColumns = Seq("userId"),
          toTable = "fk_test_users",
          toColumns = Seq("id"),
          onDelete = ForeignKeyAction.Cascade,
          onUpdate = ForeignKeyAction.NoAction,
          constraintName = None,
        )
        val sql = fk.toConstraintSql
        assertTrue(
          sql.contains("foreign key (userId)"),
          sql.contains("references fk_test_users (id)"),
          sql.contains("on delete cascade"),
          !sql.contains("on update"),
        )
      },
      test("toConstraintSql includes constraint name when provided") {
        val fk = ForeignKeySpec[Order, User](
          fromColumns = Seq("userId"),
          toTable = "fk_test_users",
          toColumns = Seq("id"),
          onDelete = ForeignKeyAction.NoAction,
          onUpdate = ForeignKeyAction.NoAction,
          constraintName = Some("fk_order_user"),
        )
        val sql = fk.toConstraintSql
        assertTrue(sql.startsWith("constraint fk_order_user foreign key"))
      },
      test("toConstraintSql handles compound foreign keys") {
        val fk = ForeignKeySpec[OrderItem, OrderDetail](
          fromColumns = Seq("orderId", "lineNum"),
          toTable = "fk_test_order_details",
          toColumns = Seq("orderId", "lineNum"),
          onDelete = ForeignKeyAction.Cascade,
          onUpdate = ForeignKeyAction.Cascade,
          constraintName = None,
        )
        val sql = fk.toConstraintSql
        assertTrue(
          sql.contains("foreign key (orderId, lineNum)"),
          sql.contains("references fk_test_order_details (orderId, lineNum)"),
          sql.contains("on delete cascade"),
          sql.contains("on update cascade"),
        )
      },
    ),
    suite("ForeignKeyAction")(
      test("toSql returns correct SQL for each action") {
        assertTrue(
          ForeignKeyAction.NoAction.toSql == "no action",
          ForeignKeyAction.Cascade.toSql == "cascade",
          ForeignKeyAction.SetNull.toSql == "set null",
          ForeignKeyAction.SetDefault.toSql == "set default",
          ForeignKeyAction.Restrict.toSql == "restrict",
        )
      }
    ),
    suite("TableAspects.foreignKey builder")(
      test("foreignKey extracts correct column name") {
        val builder = foreignKey[Order, Int](_.userId)
        assertTrue(builder.fromColumns == Seq("userId"))
      },
      test("foreignKey with two columns extracts both names") {
        val builder = foreignKey[OrderItem, Int, Int](_.orderId, _.lineNum)
        assertTrue(builder.fromColumns == Seq("orderId", "lineNum"))
      },
      test("references extracts correct table and column") {
        val config = foreignKey[Order, Int](_.userId).references[User](_.id)
        assertTrue(
          config.fromColumns == Seq("userId"),
          config.toTable == "fk_test_users",
          config.toColumns == Seq("id"),
        )
      },
      test("onDelete sets the delete action") {
        val config = foreignKey[Order, Int](_.userId)
          .references[User](_.id)
          .onDelete(ForeignKeyAction.Cascade)
        assertTrue(config.onDeleteAction == ForeignKeyAction.Cascade)
      },
      test("onUpdate sets the update action") {
        val config = foreignKey[Order, Int](_.userId)
          .references[User](_.id)
          .onUpdate(ForeignKeyAction.SetNull)
        assertTrue(config.onUpdateAction == ForeignKeyAction.SetNull)
      },
      test("named sets the constraint name") {
        val config = foreignKey[Order, Int](_.userId)
          .references[User](_.id)
          .named("fk_order_user")
        assertTrue(config.name == Some("fk_order_user"))
      },
      test("build creates correct ForeignKeySpec") {
        val spec = foreignKey[Order, Int](_.userId)
          .references[User](_.id)
          .onDelete(ForeignKeyAction.Cascade)
          .named("fk_order_user")
          .build

        assertTrue(
          spec.fromColumns == Seq("userId"),
          spec.toTable == "fk_test_users",
          spec.toColumns == Seq("id"),
          spec.onDelete == ForeignKeyAction.Cascade,
          spec.onUpdate == ForeignKeyAction.NoAction,
          spec.constraintName == Some("fk_order_user"),
        )
      },
    ),
    suite("Instance @@ composition")(
      test("Instance @@ creates instance with one FK") {
        val orders = Table[Order]
          @@ foreignKey[Order, Int](_.userId).references[User](_.id)
        assertTrue(
          orders.foreignKeys.length == 1,
          orders.foreignKeys.head.fromColumns == Seq("userId"),
        )
      },
      test("Instance @@ chains multiple FKs") {
        val orders = Table[Order]
          @@ foreignKey[Order, Int](_.userId).references[User](_.id).onDelete(ForeignKeyAction.Cascade)
          @@ foreignKey[Order, Int](_.productId).references[Product](_.id).onDelete(ForeignKeyAction.SetNull)

        assertTrue(
          orders.foreignKeys.length == 2,
          orders.foreignKeys(0).fromColumns == Seq("userId"),
          orders.foreignKeys(0).onDelete == ForeignKeyAction.Cascade,
          orders.foreignKeys(1).fromColumns == Seq("productId"),
          orders.foreignKeys(1).onDelete == ForeignKeyAction.SetNull,
        )
      },
      test("Instance with FK has correct table metadata") {
        val orders = Table[Order]
          @@ foreignKey[Order, Int](_.userId).references[User](_.id)
        assertTrue(
          orders.tableName == "fk_test_orders",
          orders.columns.map(_.name).toSet == Set("id", "userId", "productId", "amount"),
        )
      },
      test("foreignKeyConstraints generates SQL for all FKs") {
        val orders = Table[Order]
          @@ foreignKey[Order, Int](_.userId).references[User](_.id).onDelete(ForeignKeyAction.Cascade)
          @@ foreignKey[Order, Int](_.productId).references[Product](_.id)

        val constraints = orders.foreignKeyConstraints
        assertTrue(
          constraints.length == 2,
          constraints(0).contains("foreign key (userId)"),
          constraints(0).contains("on delete cascade"),
          constraints(1).contains("foreign key (productId)"),
        )
      },
    ),
  )
end ForeignKeySpecs

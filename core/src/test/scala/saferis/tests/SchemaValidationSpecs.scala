package saferis.tests

import saferis.*
import saferis.Schema.*
import saferis.ddl.*
import saferis.postgres.PostgresDialect
import saferis.tests.PostgresTestContainer.DataSourceProvider
import zio.*
import zio.test.*

/** Integration tests for Schema validation feature. */
object SchemaValidationSpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default

  given Dialect = PostgresDialect

  // === Test Tables ===

  @tableName("validation_users")
  final case class User(
      @generated @key id: Int,
      name: String,
      email: String,
      age: Option[Int],
  ) derives Table

  @tableName("validation_orders")
  final case class Order(
      @generated @key id: Int,
      @label("user_id") userId: Int,
      amount: BigDecimal,
  ) derives Table

  // Helper to get validation issues from the flipped error
  extension (zio: IO[Throwable, Unit])
    def validationError: IO[Unit, SchemaValidationError] =
      zio.flip.map(_.asInstanceOf[SchemaValidationError])

  val spec = suite("Schema Validation")(
    suite("Basic verification")(
      test("verify succeeds when schema matches") {
        val schema = Schema[User].build
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[User](ifExists = true))
          _  <- xa.run(createTable(schema))
          _  <- xa.run(Schema(schema).verify)
        yield assertCompletes
      },
      test("verify fails with TableNotFound when table missing") {
        val schema = Schema[User].build
        for
          xa    <- ZIO.service[Transactor]
          _     <- xa.run(dropTable[User](ifExists = true))
          error <- xa.run(Schema(schema).verify).validationError
        yield assertTrue(error.issues.exists(_.isInstanceOf[SchemaIssue.TableNotFound]))
      },
      test("verify fails with MissingColumn when column missing") {
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(sql"DROP TABLE IF EXISTS validation_users".execute)
          // Create table missing the 'age' column
          _ <- xa.run(
            sql"CREATE TABLE validation_users (id SERIAL PRIMARY KEY, name VARCHAR(255) NOT NULL, email VARCHAR(255) NOT NULL)".execute
          )
          schema = Schema[User].build
          error <- xa.run(Schema(schema).verify).validationError
        yield assertTrue(error.issues.exists {
          case SchemaIssue.MissingColumn(_, "age", _) => true
          case _                                     => false
        })
      },
      test("verify fails with ExtraColumn when DB has extra columns") {
        val schema = Schema[User].build
        for
          xa    <- ZIO.service[Transactor]
          _     <- xa.run(dropTable[User](ifExists = true))
          _     <- xa.run(createTable(schema))
          _     <- xa.run(sql"ALTER TABLE validation_users ADD COLUMN extra_col VARCHAR(100)".execute)
          error <- xa.run(Schema(schema).verify).validationError
        yield assertTrue(error.issues.exists {
          case SchemaIssue.ExtraColumn(_, "extra_col", _) => true
          case _                                          => false
        })
      },
      test("verify succeeds with checkExtraColumns = false") {
        val schema  = Schema[User].build
        val options = VerifyOptions(checkExtraColumns = false)
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[User](ifExists = true))
          _  <- xa.run(createTable(schema))
          _  <- xa.run(sql"ALTER TABLE validation_users ADD COLUMN extra_col VARCHAR(100)".execute)
          _  <- xa.run(Schema(schema).verifyWith(options))
        yield assertCompletes
      },
      test("verify fails with NullabilityMismatch when nullability differs") {
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(sql"DROP TABLE IF EXISTS validation_users".execute)
          // Create table with age as NOT NULL instead of nullable
          _ <- xa.run(
            sql"CREATE TABLE validation_users (id SERIAL PRIMARY KEY, name VARCHAR(255) NOT NULL, email VARCHAR(255) NOT NULL, age INT NOT NULL)".execute
          )
          schema = Schema[User].build
          error <- xa.run(Schema(schema).verify).validationError
        yield assertTrue(error.issues.exists {
          case SchemaIssue.NullabilityMismatch(_, "age", true, false) => true
          case _                                                     => false
        })
      },
    ),
    suite("Index verification")(
      test("verify fails with MissingIndex when expected index missing") {
        val schema = Schema[User]
          .withIndex(_.email)
          .named("idx_users_email")
          .build
        for
          xa    <- ZIO.service[Transactor]
          _     <- xa.run(dropTable[User](ifExists = true))
          _     <- xa.run(createTable[User]())
          error <- xa.run(Schema(schema).verify).validationError
        yield assertTrue(error.issues.exists(_.isInstanceOf[SchemaIssue.MissingIndex]))
      },
      test("verify succeeds when index exists") {
        val schema = Schema[User]
          .withIndex(_.email)
          .named("idx_users_email")
          .build
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[User](ifExists = true))
          _  <- xa.run(createTable(schema))
          _  <- xa.run(Schema(schema).verify)
        yield assertCompletes
      },
      test("verify succeeds with checkIndexes = false even if index missing") {
        val schema = Schema[User]
          .withIndex(_.email)
          .named("idx_users_email")
          .build
        val options = VerifyOptions(checkIndexes = false)
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[User](ifExists = true))
          _  <- xa.run(createTable[User]())
          _  <- xa.run(Schema(schema).verifyWith(options))
        yield assertCompletes
      },
    ),
    suite("Foreign key verification")(
      test("verify fails with MissingForeignKey when FK missing") {
        val ordersSchema = Schema[Order]
          .withForeignKey(_.userId)
          .references[User](_.id)
          .onDelete(Cascade)
          .build
        for
          xa    <- ZIO.service[Transactor]
          _     <- xa.run(dropTable[Order](ifExists = true))
          _     <- xa.run(dropTable[User](ifExists = true))
          _     <- xa.run(createTable[User]())
          _     <- xa.run(createTable[Order]())
          error <- xa.run(Schema(ordersSchema).verify).validationError
        yield assertTrue(error.issues.exists(_.isInstanceOf[SchemaIssue.MissingForeignKey]))
      },
      test("verify succeeds when FK exists") {
        val ordersSchema = Schema[Order]
          .withForeignKey(_.userId)
          .references[User](_.id)
          .onDelete(Cascade)
          .build
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Order](ifExists = true))
          _  <- xa.run(dropTable[User](ifExists = true))
          _  <- xa.run(createTable[User]())
          _  <- xa.run(createTable(ordersSchema))
          _  <- xa.run(Schema(ordersSchema).verify)
        yield assertCompletes
      },
    ),
    suite("Unique constraint verification")(
      test("verify fails with MissingUniqueConstraint when constraint missing") {
        val schema = Schema[User]
          .withUniqueConstraint(_.email)
          .named("uq_users_email")
          .build
        for
          xa    <- ZIO.service[Transactor]
          _     <- xa.run(dropTable[Order](ifExists = true))
          _     <- xa.run(dropTable[User](ifExists = true))
          _     <- xa.run(createTable[User]())
          error <- xa.run(Schema(schema).verify).validationError
        yield assertTrue(error.issues.exists(_.isInstanceOf[SchemaIssue.MissingUniqueConstraint]))
      },
      test("verify succeeds when unique constraint exists") {
        val schema = Schema[User]
          .withUniqueConstraint(_.email)
          .named("uq_users_email")
          .build
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Order](ifExists = true))
          _  <- xa.run(dropTable[User](ifExists = true))
          _  <- xa.run(createTable(schema))
          _  <- xa.run(Schema(schema).verify)
        yield assertCompletes
      },
    ),
    suite("VerifyOptions presets")(
      test("VerifyOptions.minimal only checks table and columns") {
        // Build schema with index and unique constraint
        val withIndex = Schema[User].withIndex(_.email).build
        val schema    = Schema(withIndex).withUniqueConstraint(_.name).build
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Order](ifExists = true))
          _  <- xa.run(dropTable[User](ifExists = true))
          // Create table without index or unique constraint
          _  <- xa.run(createTable[User]())
          _  <- xa.run(sql"ALTER TABLE validation_users ADD COLUMN extra_col VARCHAR(100)".execute)
          // Minimal should pass despite missing index/constraint and extra column
          _  <- xa.run(Schema(schema).verifyWith(VerifyOptions.minimal))
        yield assertCompletes
      },
    ),
  ).provideShared(xaLayer) @@ TestAspect.sequential

end SchemaValidationSpecs

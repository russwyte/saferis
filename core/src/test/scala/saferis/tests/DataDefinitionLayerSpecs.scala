package saferis.tests

import saferis.*
import saferis.ddl.*
import saferis.dml.*
import saferis.postgres.given
import zio.*
import zio.test.*
import PostgresTestContainer.DataSourceProvider
import java.util.UUID

object DataDefinitionLayerSpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default

  // Case classes for aspect-based index tests (must be at object level for inline macros)
  @tableName("test_ddl_compound_index")
  case class CompoundIndexTable(
      @key id: Int,
      singleCol: String,
      tenantId: Int,
      eventTime: Long,
      userId: Int,
      email: String,
      description: String,
  ) derives Table

  @tableName("test_ddl_partial_index_aspect")
  case class PartialIndexTable(
      @key id: Int,
      @label("nextretryat") nextRetryAt: Option[java.time.Instant],
      status: String,
  ) derives Table

  @tableName("test_ddl_partial_unique_aspect")
  case class PartialUniqueTable(
      @key id: Int,
      email: String,
      active: Boolean,
  ) derives Table

  val ddlTests = suiteAll("should handle DDL operations"):
    test("create table"):
      @tableName("test_ddl_create")
      case class TestTable(@key id: Int, name: String, age: Option[Int]) derives Table

      for
        xa <- ZIO.service[Transactor]
        // First, ensure the table doesn't exist by trying to drop it
        _ <- xa.run:
          dropTable[TestTable](ifExists = true)
        // Create the table using Schema.ddl()
        result <- xa.run:
          Schema[TestTable].ddl().execute
        // Verify table exists by checking schema
        tableExists <- xa.run:
          sql"select count(*) as count from information_schema.tables where table_name = ${"test_ddl_create"}"
            .queryOne[CountResult]
      yield assertTrue(result >= 0) && // DDL operations may return 0 for success
        assertTrue(tableExists.map(_.count).contains(1))
      end for

    test("create table with generated primary key"):
      @tableName("test_ddl_generated")
      case class GeneratedTable(@generated @key id: Int, name: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[GeneratedTable](ifExists = true)
        result <- xa.run:
          Schema[GeneratedTable].ddl().execute
        // Verify table was created with proper identity column
        tableExists <- xa.run:
          sql"select count(*) as count from information_schema.tables where table_name = ${"test_ddl_generated"}"
            .queryOne[CountResult]
      yield assertTrue(result >= 0) &&
        assertTrue(tableExists.map(_.count).contains(1))
      end for

    test("drop table"):
      @tableName("test_ddl_drop")
      case class DropTable(@key id: Int, name: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        // Create a table first using Schema.ddl()
        _ <- xa.run:
          Schema[DropTable].ddl(ifNotExists = false).execute
        // Insert some data
        _ <- xa.run:
          insert(DropTable(1, "To be dropped"))
        // Drop the table
        dropResult <- xa.run:
          dropTable[DropTable]()
        // Try to query from dropped table (should fail)
        queryAttempt <- xa
          .run:
            sql"select * from test_ddl_drop".query[DropTable]
          .either
      yield assertTrue(dropResult >= 0) &&
        assertTrue(queryAttempt.isLeft) // Should fail because table was dropped
      end for

    test("truncate table"):
      @tableName("test_ddl_truncate")
      case class TruncateTable(@key id: Int, name: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[TruncateTable](ifExists = true)
        _ <- xa.run:
          Schema[TruncateTable].ddl().execute
        // Insert some test data
        _ <- xa.run:
          insert(TruncateTable(1, "Data 1"))
        _ <- xa.run:
          insert(TruncateTable(2, "Data 2"))
        // Verify data exists
        beforeTruncate <- xa.run:
          sql"select count(*) as count from test_ddl_truncate".queryOne[CountResult]
        // Truncate the table
        truncateResult <- xa.run:
          truncateTable[TruncateTable]()
        // Verify table is empty
        afterTruncate <- xa.run:
          sql"select count(*) as count from test_ddl_truncate".queryOne[CountResult]
      yield assertTrue(truncateResult >= 0) &&
        assertTrue(beforeTruncate.map(_.count).contains(2)) &&
        assertTrue(afterTruncate.map(_.count).contains(0))
      end for

    test("add column with string type"):
      @tableName("test_ddl_alter_string")
      case class AlterTableString(@key id: Int, name: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[AlterTableString](ifExists = true)
        _ <- xa.run:
          Schema[AlterTableString].ddl().execute
        // Add a new column using string type
        addResult <- xa.run:
          addColumn[AlterTableString, String]("description")
        // Insert data including the new column (using raw SQL since our case class doesn't have it)
        _ <- xa.run:
          sql"insert into test_ddl_alter_string (id, name, description) values (1, ${"Test"}, ${"Test description"})".insert
        // Verify the data was inserted
        checkResult <- xa.run:
          sql"select id, name from test_ddl_alter_string where id = 1".queryOne[AlterTableString]
        // Drop the column
        dropColResult <- xa.run:
          dropColumn[AlterTableString]("description")
      yield assertTrue(addResult >= 0) &&
        assertTrue(checkResult.contains(AlterTableString(1, "Test"))) &&
        assertTrue(dropColResult >= 0)
      end for

    test("add column with encoder - integer type"):
      @tableName("test_ddl_alter_int")
      case class AlterTableInt(@key id: Int, name: String) derives Table
      case class ScoreResult(score: Int) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[AlterTableInt](ifExists = true)
        _ <- xa.run:
          Schema[AlterTableInt].ddl().execute
        // Add a new integer column using encoder
        addResult <- xa.run:
          addColumn[AlterTableInt, Int]("score")
        // Insert data including the new column
        _ <- xa.run:
          sql"insert into test_ddl_alter_int (id, name, score) values (1, ${"Test User"}, ${85})".insert
        // Verify the data was inserted
        checkResult <- xa.run:
          sql"select id, name from test_ddl_alter_int where id = 1".queryOne[AlterTableInt]
        // Verify the score column exists and has correct data
        scoreResult <- xa.run:
          sql"select score from test_ddl_alter_int where id = 1".queryOne[ScoreResult]
        // Drop the column
        dropColResult <- xa.run:
          dropColumn[AlterTableInt]("score")
      yield assertTrue(addResult >= 0) &&
        assertTrue(checkResult.contains(AlterTableInt(1, "Test User"))) &&
        assertTrue(scoreResult.map(_.score).contains(85)) &&
        assertTrue(dropColResult >= 0)
      end for

    test("add column with encoder - boolean type"):
      @tableName("test_ddl_alter_bool")
      case class AlterTableBool(@key id: Int, name: String) derives Table
      case class ActiveResult(is_active: Boolean) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[AlterTableBool](ifExists = true)
        _ <- xa.run:
          Schema[AlterTableBool].ddl().execute
        // Add a new boolean column using encoder
        addResult <- xa.run:
          addColumn[AlterTableBool, Boolean]("is_active")
        // Insert data including the new column
        _ <- xa.run:
          sql"insert into test_ddl_alter_bool (id, name, is_active) values (1, ${"Active User"}, ${true})".insert
        _ <- xa.run:
          sql"insert into test_ddl_alter_bool (id, name, is_active) values (2, ${"Inactive User"}, ${false})".insert
        // Verify the data was inserted
        activeResult <- xa.run:
          sql"select is_active from test_ddl_alter_bool where id = 1".queryOne[ActiveResult]
        inactiveResult <- xa.run:
          sql"select is_active from test_ddl_alter_bool where id = 2".queryOne[ActiveResult]
        // Drop the column
        dropColResult <- xa.run:
          dropColumn[AlterTableBool]("is_active")
      yield assertTrue(addResult >= 0) &&
        assertTrue(activeResult.map(_.is_active).contains(true)) &&
        assertTrue(inactiveResult.map(_.is_active).contains(false)) &&
        assertTrue(dropColResult >= 0)
      end for

    test("add column with encoder - double type"):
      @tableName("test_ddl_alter_double")
      case class AlterTableDouble(@key id: Int, name: String) derives Table
      case class PriceResult(price: Double) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[AlterTableDouble](ifExists = true)
        _ <- xa.run:
          Schema[AlterTableDouble].ddl().execute
        // Add a new double column using encoder
        addResult <- xa.run:
          addColumn[AlterTableDouble, Double]("price")
        // Insert data including the new column
        _ <- xa.run:
          sql"insert into test_ddl_alter_double (id, name, price) values (1, ${"Product A"}, ${99.99})".insert
        // Verify the data was inserted
        priceResult <- xa.run:
          sql"select price from test_ddl_alter_double where id = 1".queryOne[PriceResult]
        // Drop the column
        dropColResult <- xa.run:
          dropColumn[AlterTableDouble]("price")
      yield assertTrue(addResult >= 0) &&
        assertTrue(priceResult.map(_.price).contains(99.99)) &&
        assertTrue(dropColResult >= 0)
      end for

    test("add column with encoder - optional type"):
      @tableName("test_ddl_alter_optional")
      case class AlterTableOptional(@key id: Int, name: String) derives Table
      case class AgeResult(age: Option[Int]) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[AlterTableOptional](ifExists = true)
        _ <- xa.run:
          Schema[AlterTableOptional].ddl().execute
        // Add a new optional integer column using encoder
        addResult <- xa.run:
          addColumn[AlterTableOptional, Option[Int]]("age")
        // Insert data with and without the optional column
        _ <- xa.run:
          sql"insert into test_ddl_alter_optional (id, name, age) values (1, ${"Person with age"}, ${25})".insert
        _ <- xa.run:
          sql"insert into test_ddl_alter_optional (id, name, age) values (2, ${"Person without age"}, ${Option.empty[Int]})".insert
        // Verify the data was inserted
        ageResult <- xa.run:
          sql"select age from test_ddl_alter_optional where id = 1".queryOne[AgeResult]
        noAgeResult <- xa.run:
          sql"select age from test_ddl_alter_optional where id = 2".queryOne[AgeResult]
        // Drop the column
        dropColResult <- xa.run:
          dropColumn[AlterTableOptional]("age")
      yield assertTrue(addResult >= 0) &&
        assertTrue(ageResult.map(_.age).contains(Some(25))) &&
        assertTrue(noAgeResult.map(_.age).contains(None)) &&
        assertTrue(dropColResult >= 0)
      end for

    test("create and drop index"):
      @tableName("test_ddl_index")
      case class IndexTable(@key id: Int, name: String, email: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[IndexTable](ifExists = true)
        _ <- xa.run:
          Schema[IndexTable].ddl().execute
        // Create a regular index
        createIndexResult <- xa.run:
          createIndex[IndexTable]("idx_test_name", Seq("name"))
        // Create a unique index
        createUniqueIndexResult <- xa.run:
          createIndex[IndexTable]("idx_test_email", Seq("email"), unique = true)
        // Insert some test data
        _ <- xa.run:
          insert(IndexTable(1, "John", "john@example.com"))
        // Try to insert duplicate email (should fail with unique constraint)
        duplicateAttempt <- xa
          .run:
            insert(IndexTable(2, "Jane", "john@example.com"))
          .either
        // Drop the indexes
        dropIndex1Result <- xa.run:
          dropIndex("idx_test_name")
        dropIndex2Result <- xa.run:
          dropIndex("idx_test_email")
      yield assertTrue(createIndexResult >= 0) &&
        assertTrue(createUniqueIndexResult >= 0) &&
        assertTrue(duplicateAttempt.isLeft) && // Should fail due to unique constraint
        assertTrue(dropIndex1Result >= 0) &&
        assertTrue(dropIndex2Result >= 0)
      end for

    test("create table with compound keys"):
      @tableName("test_ddl_compound_key")
      case class CompoundKeyTable(@key userId: Int, @key roleId: Int, grantedAt: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[CompoundKeyTable](ifExists = true)
        result <- xa.run:
          Schema[CompoundKeyTable].ddl().execute
        // Insert test data
        _ <- xa.run:
          insert(CompoundKeyTable(1, 2, "2023-01-01"))
        _ <- xa.run:
          insert(CompoundKeyTable(1, 3, "2023-01-02"))
        // Verify data exists first
        queryResult <- xa.run:
          sql"select * from test_ddl_compound_key where userid = 1 and roleid = 2".queryOne[CompoundKeyTable]
        // Try to insert duplicate compound key (should fail)
        duplicateAttempt <- xa
          .run:
            insert(CompoundKeyTable(1, 2, "2023-01-03"))
          .either
      yield assertTrue(result >= 0) &&
        assertTrue(queryResult.contains(CompoundKeyTable(1, 2, "2023-01-01"))) &&
        assertTrue(duplicateAttempt.isLeft) // Should fail due to primary key constraint
      end for

    test("create table with indexes via createIndex"):
      @tableName("test_ddl_indexed_cols")
      case class IndexedTable(
          @key id: Int,
          name: String,
          email: String,
          description: String,
      ) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[IndexedTable](ifExists = true)
        _ <- xa.run:
          createTable[IndexedTable](createIndexes = false)
        // Create indexes using createIndex DDL function
        _ <- xa.run:
          createIndex[IndexedTable]("idx_name", Seq("name"))
        _ <- xa.run:
          createIndex[IndexedTable]("idx_email", Seq("email"), unique = true)
        // Insert test data
        _ <- xa.run:
          insert(IndexedTable(1, "John", "john@example.com", "Test user"))
        // Try to insert duplicate unique index (should fail)
        duplicateEmailAttempt <- xa
          .run:
            insert(IndexedTable(2, "Jane", "john@example.com", "Another user"))
          .either
        // Insert with same name but different email (should succeed)
        _ <- xa.run:
          insert(IndexedTable(2, "John", "john2@example.com", "Another John"))
      yield assertTrue(duplicateEmailAttempt.isLeft) // Should fail due to unique constraint
      end for

    test("create table without indexes"):
      @tableName("test_ddl_no_indexes")
      case class NoIndexTable(@key id: Int, name: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[NoIndexTable](ifExists = true)
        result <- xa.run:
          createTable[NoIndexTable](createIndexes = false)
        // Insert test data
        _ <- xa.run:
          insert(NoIndexTable(1, "Test"))
        queryResult <- xa.run:
          sql"select * from test_ddl_no_indexes where id = 1".queryOne[NoIndexTable]
      yield assertTrue(result >= 0) &&
        assertTrue(queryResult.contains(NoIndexTable(1, "Test")))
      end for

    test("createIndexes function for compound keys"):
      @tableName("test_ddl_create_indexes")
      case class CreateIndexesTable(
          @key userId: Int,
          @key roleId: Int,
          name: String,
      ) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[CreateIndexesTable](ifExists = true)
        // Create table without indexes
        _ <- xa.run:
          createTable[CreateIndexesTable](createIndexes = false)
        // Create indexes separately (will create compound key index)
        indexResults <- xa.run:
          createIndexes[CreateIndexesTable]()
        // Insert test data
        _ <- xa.run:
          insert(CreateIndexesTable(1, 2, "John"))
        queryResult <- xa.run:
          sql"select * from test_ddl_create_indexes where userid = 1 and roleid = 2".queryOne[CreateIndexesTable]
      yield assertTrue(indexResults.nonEmpty) &&
        assertTrue(queryResult.contains(CreateIndexesTable(1, 2, "John")))
      end for

    test("createIndexesSql function for compound keys"):
      @tableName("test_ddl_indexes_sql")
      case class IndexesSqlTable(
          @key userId: Int,
          @key roleId: Int,
          name: String,
      ) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[IndexesSqlTable](ifExists = true)
        // Create table without indexes
        _ <- xa.run:
          createTable[IndexesSqlTable](createIndexes = false)
        // Get index creation SQL (only compound key index)
        indexSql = createIndexesSql[IndexesSqlTable]()
        // Execute the generated SQL statements
        indexSqlStatements = indexSql.split("\n").filter(_.nonEmpty)
        _ <- ZIO.foreachDiscard(indexSqlStatements): stmt =>
          xa.run:
            SqlFragment(stmt, Seq.empty).dml
        // Insert test data
        _ <- xa.run:
          insert(IndexesSqlTable(1, 2, "John"))
        queryResult <- xa.run:
          sql"select * from test_ddl_indexes_sql where userid = 1 and roleid = 2".queryOne[IndexesSqlTable]
      yield assertTrue(indexSql.nonEmpty) &&
        assertTrue(indexSql.contains("compound_key")) && // Should have compound key index
        assertTrue(queryResult.contains(IndexesSqlTable(1, 2, "John")))
      end for

    test("table with compound key and generated column"):
      @tableName("test_ddl_compound_generated")
      case class CompoundGeneratedTable(
          @generated @key id: Int,
          @key categoryId: Int,
          name: String,
      ) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[CompoundGeneratedTable](ifExists = true)
        result <- xa.run:
          Schema[CompoundGeneratedTable].ddl().execute
        // Insert test data (id should be generated)
        inserted1 <- xa.run:
          insertReturning(CompoundGeneratedTable(-1, 1, "Item 1"))
        inserted2 <- xa.run:
          insertReturning(CompoundGeneratedTable(-1, 2, "Item 2"))
        // Try to insert with specific ID that would create duplicate compound key
        duplicateAttempt <- xa
          .run:
            sql"insert into test_ddl_compound_generated (id, categoryid, name) values (${inserted1.id}, ${inserted1.categoryId}, ${"Duplicate"})".insert
          .either
      yield assertTrue(result >= 0) &&
        assertTrue(inserted1.id > 0) &&
        assertTrue(inserted2.id > 0) &&
        assertTrue(inserted1.id != inserted2.id) &&
        assertTrue(duplicateAttempt.isLeft) // Should fail due to compound primary key constraint
      end for

    test("create table with unique constraint columns"):
      @tableName("test_ddl_unique_constraints")
      case class UniqueConstraintTable(
          @key id: Int,
          name: String,
          email: String,
          username: String,
          description: String,
      ) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[UniqueConstraintTable](ifExists = true)
        _ <- xa.run:
          Schema[UniqueConstraintTable]
            .withUniqueConstraint(_.username)
            .ddl()
            .execute
        // Create indexes using createIndex DDL function
        _ <- xa.run:
          createIndex[UniqueConstraintTable]("idx_unique_name", Seq("name"))
        _ <- xa.run:
          createIndex[UniqueConstraintTable]("idx_unique_email", Seq("email"), unique = true)
        // Insert test data
        _ <- xa.run:
          insert(UniqueConstraintTable(1, "John", "john@example.com", "john_unique", "Test user"))
        // Try to insert duplicate unique constraint (should fail)
        duplicateUsernameAttempt <- xa
          .run:
            insert(UniqueConstraintTable(2, "Jane", "jane@example.com", "john_unique", "Another user"))
          .either
        // Try to insert duplicate unique index (should fail)
        duplicateEmailAttempt <- xa
          .run:
            insert(UniqueConstraintTable(3, "Bob", "john@example.com", "bob_unique", "Bob user"))
          .either
        // Insert with different unique values (should succeed)
        _ <- xa.run:
          insert(UniqueConstraintTable(4, "Alice", "alice@example.com", "alice_unique", "Alice user"))
      yield assertTrue(duplicateUsernameAttempt.isLeft) && // Should fail due to unique constraint
        assertTrue(duplicateEmailAttempt.isLeft)           // Should fail due to unique index
      end for

    test("verify encoder method infers correct PostgreSQL types"):
      @tableName("test_ddl_encoder_types")
      case class EncoderTestTable(@key id: Int, name: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[EncoderTestTable](ifExists = true)
        _ <- xa.run:
          Schema[EncoderTestTable].ddl().execute
        // Add various columns using encoders to verify type inference
        _ <- xa.run:
          addColumn[EncoderTestTable, String]("text_col")
        _ <- xa.run:
          addColumn[EncoderTestTable, Int]("int_col")
        _ <- xa.run:
          addColumn[EncoderTestTable, Boolean]("bool_col")
        _ <- xa.run:
          addColumn[EncoderTestTable, Double]("double_col")
        _ <- xa.run:
          addColumn[EncoderTestTable, Float]("float_col")
        // Verify that the operation succeeded and data can be inserted with correct types
        _ <- xa.run:
          sql"""insert into test_ddl_encoder_types 
                (id, name, text_col, int_col, bool_col, double_col, float_col) 
                values (1, ${"test"}, ${"text"}, ${42}, ${true}, ${3.14}, ${2.71f})""".insert
        result <- xa.run:
          sql"select id, name from test_ddl_encoder_types where id = 1".queryOne[EncoderTestTable]
      yield assertTrue(result.contains(EncoderTestTable(1, "test")))
      end for

    test("create table with UUID primary key"):
      @tableName("test_ddl_uuid_key")
      case class UuidKeyTable(@key id: UUID, name: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[UuidKeyTable](ifExists = true)
        result <- xa.run:
          Schema[UuidKeyTable].ddl().execute
        tableExists <- xa.run:
          sql"select count(*) as count from information_schema.tables where table_name = ${"test_ddl_uuid_key"}"
            .queryOne[CountResult]
        uuidVal = UUID.randomUUID()
        _ <- xa.run:
          insert(UuidKeyTable(uuidVal, "Test Name"))
        fetched <- xa.run:
          sql"select id, name from test_ddl_uuid_key where id = $uuidVal".queryOne[UuidKeyTable]
      yield assertTrue(result >= 0) &&
        assertTrue(tableExists.map(_.count).contains(1)) &&
        assertTrue(fetched.contains(UuidKeyTable(uuidVal, "Test Name")))
      end for

    test("compound unique constraints with named groups"):
      @tableName("test_ddl_compound_unique")
      case class CompoundUniqueTable(
          @key id: Int,
          tenantId: Int,
          eventId: Long,
          singleUnique: String,
          description: String,
      ) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[CompoundUniqueTable](ifExists = true)
        result <- xa.run:
          Schema[CompoundUniqueTable]
            .withUniqueConstraint(_.tenantId)
            .and(_.eventId)
            .named("tenant_event")
            .withUniqueConstraint(_.singleUnique)
            .ddl()
            .execute
        // Insert first record
        _ <- xa.run:
          insert(CompoundUniqueTable(1, 100, 1000L, "unique1", "First"))
        // Insert with same tenantId but different eventId (should succeed - different compound key)
        _ <- xa.run:
          insert(CompoundUniqueTable(2, 100, 2000L, "unique2", "Second"))
        // Insert with different tenantId but same eventId (should succeed)
        _ <- xa.run:
          insert(CompoundUniqueTable(3, 200, 1000L, "unique3", "Third"))
        // Try to insert duplicate compound key (same tenantId AND eventId - should fail)
        duplicateCompoundAttempt <- xa
          .run:
            insert(CompoundUniqueTable(4, 100, 1000L, "unique4", "Duplicate compound"))
          .either
        // Try to insert duplicate single-column unique (should fail)
        duplicateSingleAttempt <- xa
          .run:
            insert(CompoundUniqueTable(5, 300, 3000L, "unique1", "Duplicate single"))
          .either
      yield assertTrue(result >= 0) &&
        assertTrue(duplicateCompoundAttempt.isLeft) && // Should fail due to compound unique constraint
        assertTrue(duplicateSingleAttempt.isLeft)      // Should fail due to single-column unique constraint
      end for

    test("default values from case class constructor are used in DDL"):
      @tableName("test_ddl_defaults")
      case class DefaultsTable(
          @key id: Int,
          name: String,
          status: String = "pending",
          retryCount: Int = 0,
          isActive: Boolean = true,
      ) derives Table

      // Helper case classes to query individual columns without defaults
      case class StatusResult(status: String) derives Table
      case class RetryCountResult(@label("retrycount") retryCount: Int) derives Table
      case class IsActiveResult(@label("isactive") isActive: Boolean) derives Table

      // Verify column metadata - extract values to avoid path-dependent type issues with ZIO's macro
      val table               = Table[DefaultsTable]
      val statusCol           = table.columns.find(_.name == "status").get
      val retryCountCol       = table.columns.find(_.name == "retryCount").get
      val isActiveCol         = table.columns.find(_.name == "isActive").get
      val nameCol             = table.columns.find(_.name == "name").get
      val statusHasDefault    = statusCol.defaultValue.isDefined
      val retryHasDefault     = retryCountCol.defaultValue.isDefined
      val activeHasDefault    = isActiveCol.defaultValue.isDefined
      val nameHasDefault      = nameCol.defaultValue.isDefined
      val statusDefaultClause = statusCol.defaultClause
      val retryDefaultClause  = retryCountCol.defaultClause
      val activeDefaultClause = isActiveCol.defaultClause

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[DefaultsTable](ifExists = true)
        result <- xa.run:
          Schema[DefaultsTable].ddl().execute
        // Insert without specifying default columns - they should use DB defaults
        _ <- xa.run:
          sql"insert into test_ddl_defaults (id, name) values (1, ${"Test"})".insert
        // Query individual columns to verify DB-level defaults (not case class defaults)
        statusResult <- xa.run:
          sql"select status from test_ddl_defaults where id = 1".queryOne[StatusResult]
        retryResult <- xa.run:
          sql"select retrycount from test_ddl_defaults where id = 1".queryOne[RetryCountResult]
        activeResult <- xa.run:
          sql"select isactive from test_ddl_defaults where id = 1".queryOne[IsActiveResult]
      yield assertTrue(result >= 0) &&
        assertTrue(statusHasDefault) &&
        assertTrue(retryHasDefault) &&
        assertTrue(activeHasDefault) &&
        assertTrue(!nameHasDefault) &&
        assertTrue(statusDefaultClause.contains("default 'pending'")) &&
        assertTrue(retryDefaultClause.contains("default 0")) &&
        assertTrue(activeDefaultClause.contains("default true")) &&
        assertTrue(statusResult.map(_.status).contains("pending")) &&
        assertTrue(retryResult.map(_.retryCount).contains(0)) &&
        assertTrue(activeResult.map(_.isActive).contains(true))
      end for

    test("partial indexes with WHERE clause"):
      @tableName("test_ddl_partial_index")
      case class PartialIndexTable(
          @key id: Int,
          status: String,
          @label("nextretryat") nextRetryAt: Option[java.time.Instant],
      ) derives Table

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[PartialIndexTable](ifExists = true)
        _ <- xa.run:
          createTable[PartialIndexTable](createIndexes = false)
        // Create a partial index on nextRetryAt only for pending records
        _ <- xa.run:
          createIndex[PartialIndexTable](
            "idx_pending_retry",
            Seq("nextretryat"),
            where = Some("status = 'pending'"),
          )
        // Insert some test data
        _ <- xa.run:
          sql"insert into test_ddl_partial_index (id, status, nextretryat) values (1, ${"pending"}, ${java.time.Instant.now()})".insert
        _ <- xa.run:
          sql"insert into test_ddl_partial_index (id, status, nextretryat) values (2, ${"completed"}, ${Option.empty[java.time.Instant]})".insert
        // Query to verify data exists (partial indexes are transparent to queries)
        count <- xa.run:
          sql"select count(*) as count from test_ddl_partial_index".queryOne[CountResult]
        // Drop the index to clean up
        _ <- xa.run:
          dropIndex("idx_pending_retry")
      yield assertTrue(count.map(_.count).contains(2))
      end for

    test("aspect-based compound indexes"):
      import saferis.Schema.*

      // Using Schema[A] for fluent index and FK configuration
      val compoundTable = Schema[CompoundIndexTable]
        .withIndex(_.singleCol)
        .withIndex(_.tenantId)
        .and(_.eventTime)
        .named("idx_tenant_event")
        .withUniqueIndex(_.userId)
        .and(_.email)
        .named("uidx_user_email")
        .build

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[CompoundIndexTable](ifExists = true)
        result <- xa.run:
          createTable(compoundTable)
        // Verify indexes were created by inserting data and querying
        _ <- xa.run:
          sql"insert into test_ddl_compound_index values (1, ${"single"}, 100, 1000, 200, ${"test@test.com"}, ${"desc"})".insert
        // Try to insert duplicate compound unique index (should fail)
        duplicateAttempt <- xa
          .run:
            sql"insert into test_ddl_compound_index values (2, ${"other"}, 101, 1001, 200, ${"test@test.com"}, ${"desc2"})".insert
          .either
        count <- xa.run:
          sql"select count(*) as count from test_ddl_compound_index".queryOne[CountResult]
      yield assertTrue(result >= 0) &&
        assertTrue(duplicateAttempt.isLeft) && // Should fail due to compound unique index
        assertTrue(count.map(_.count).contains(1))
      end for

    test("non-Option fields are NOT NULL, Option fields are nullable"):
      @tableName("test_ddl_not_null")
      case class NotNullTable(
          @key id: Int,
          name: String,                // NOT NULL
          email: String,               // NOT NULL
          description: Option[String], // nullable
          age: Option[Int],            // nullable
      ) derives Table

      // Verify column metadata
      val table    = Table[NotNullTable]
      val idCol    = table.columns.find(_.name == "id").get
      val nameCol  = table.columns.find(_.name == "name").get
      val emailCol = table.columns.find(_.name == "email").get
      val descCol  = table.columns.find(_.name == "description").get
      val ageCol   = table.columns.find(_.name == "age").get

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[NotNullTable](ifExists = true)
        result <- xa.run:
          Schema[NotNullTable].ddl().execute
        // Insert valid data with optional fields as None
        _ <- xa.run:
          insert(NotNullTable(1, "John", "john@example.com", None, None))
        // Insert valid data with optional fields populated
        _ <- xa.run:
          insert(NotNullTable(2, "Jane", "jane@example.com", Some("A description"), Some(25)))
        // Verify data exists
        fetched <- xa.run:
          sql"select * from test_ddl_not_null where id = 1".queryOne[NotNullTable]
        // Try to insert with missing NOT NULL column - should fail
        // (omitting 'name' which is NOT NULL)
        missingNotNullAttempt <- xa
          .run:
            sql"insert into test_ddl_not_null (id, email) values (3, ${"test@example.com"})".insert
          .either
      yield assertTrue(result >= 0) &&
        assertTrue(!idCol.isNullable) &&
        assertTrue(!nameCol.isNullable) &&
        assertTrue(!emailCol.isNullable) &&
        assertTrue(descCol.isNullable) &&
        assertTrue(ageCol.isNullable) &&
        assertTrue(fetched.isDefined) &&
        assertTrue(missingNotNullAttempt.isLeft) // Should fail due to NOT NULL constraint
      end for

    test("aspect-based partial indexes with WHERE clause"):
      import saferis.Schema.*

      // Using Schema[A] with partial index WHERE clause
      val partialTable = Schema[PartialIndexTable]
        .withIndex(_.nextRetryAt)
        .where(_.status)
        .eql("pending")
        .named("idx_pending_retry")
        .build

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[PartialIndexTable](ifExists = true)
        result <- xa.run:
          createTable(partialTable)
        // Insert test data
        _ <- xa.run:
          sql"insert into test_ddl_partial_index_aspect (id, nextretryat, status) values (1, ${java.time.Instant.now()}, ${"pending"})".insert
        _ <- xa.run:
          sql"insert into test_ddl_partial_index_aspect (id, nextretryat, status) values (2, ${Option.empty[java.time.Instant]}, ${"completed"})".insert
        count <- xa.run:
          sql"select count(*) as count from test_ddl_partial_index_aspect".queryOne[CountResult]
      yield assertTrue(result >= 0) &&
        assertTrue(count.map(_.count).contains(2))
      end for

    test("aspect-based partial unique indexes"):
      import saferis.Schema.*

      // Using Schema[A] with partial unique index WHERE clause
      val partialUniqueTable = Schema[PartialUniqueTable]
        .withUniqueIndex(_.email)
        .where(_.active)
        .eql(true)
        .named("uidx_active_email")
        .build

      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run:
          dropTable[PartialUniqueTable](ifExists = true)
        result <- xa.run:
          createTable(partialUniqueTable)
        // Insert active user with email
        _ <- xa.run:
          sql"insert into test_ddl_partial_unique_aspect (id, email, active) values (1, ${"user@example.com"}, ${true})".insert
        // Insert inactive user with same email (should succeed because unique only applies to active=true)
        _ <- xa.run:
          sql"insert into test_ddl_partial_unique_aspect (id, email, active) values (2, ${"user@example.com"}, ${false})".insert
        // Insert another inactive user with same email (should succeed)
        _ <- xa.run:
          sql"insert into test_ddl_partial_unique_aspect (id, email, active) values (3, ${"user@example.com"}, ${false})".insert
        // Try to insert another active user with same email (should fail due to partial unique constraint)
        duplicateActiveAttempt <- xa
          .run:
            sql"insert into test_ddl_partial_unique_aspect (id, email, active) values (4, ${"user@example.com"}, ${true})".insert
          .either
        count <- xa.run:
          sql"select count(*) as count from test_ddl_partial_unique_aspect".queryOne[CountResult]
      yield assertTrue(result >= 0) &&
        assertTrue(count.map(_.count).contains(3)) && // 3 rows inserted before the duplicate attempt
        assertTrue(duplicateActiveAttempt.isLeft)     // Should fail due to partial unique constraint
      end for

  case class CountResult(count: Int) derives Table

  val spec = suite("DDL Operations")(ddlTests).provideShared(xaLayer) @@ TestAspect.sequential
end DataDefinitionLayerSpecs

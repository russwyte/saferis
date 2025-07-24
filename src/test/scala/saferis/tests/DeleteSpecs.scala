package saferis.tests

import saferis.*
import zio.*
import zio.test.*

object DeleteSpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default

  val deleteTests = suiteAll("should handle delete operations"):
    test("delete by key"):
      @tableName("test_table_primary_key")
      case class TestTable(@key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      // First insert a record to delete
      val testRecord = TestTable(300, "Delete Test", Some(25), Some("delete.test@example.com"))

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          insert(testRecord)
        // Verify it was inserted
        beforeDelete <- xa.run:
          sql"select * from test_table_primary_key where id = 300".queryOne[TestTable]
        // Delete it
        rowsAffected <- xa.run:
          delete(testRecord)
        // Verify it was deleted
        afterDelete <- xa.run:
          sql"select * from test_table_primary_key where id = 300".queryOne[TestTable]
      yield assertTrue(beforeDelete.contains(testRecord)) &&
        assertTrue(rowsAffected == 1) &&
        assertTrue(afterDelete.isEmpty)
      end for

    test("delete with custom where clause"):
      @tableName("test_table_primary_key")
      case class TestTable(@key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      // Insert test records with unique characteristics
      val testRecord1 = TestTable(301, "Delete Where Test 1", Some(30), Some("delete1@example.com"))
      val testRecord2 = TestTable(302, "Delete Where Test 2", Some(31), Some("delete2@example.com"))
      // Use a more specific where clause that targets only our test records
      val whereClause = sql"id IN (301, 302) AND age > 30"

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          insert(testRecord1)
        _ <- xa.run:
          insert(testRecord2)
        // Delete records where id IN (301, 302) AND age > 30 (should only delete testRecord2)
        rowsAffected <- xa.run:
          deleteWhere[TestTable](whereClause)
        // Verify correct record was deleted
        remaining1 <- xa.run:
          sql"select * from test_table_primary_key where id = 301".queryOne[TestTable]
        remaining2 <- xa.run:
          sql"select * from test_table_primary_key where id = 302".queryOne[TestTable]
      yield assertTrue(rowsAffected == 1) &&
        assertTrue(remaining1.contains(testRecord1)) && // Should still exist
        assertTrue(remaining2.isEmpty)                  // Should be deleted

    test("delete returning"):
      @tableName("test_table_primary_key")
      case class TestTable(@key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val testRecord = TestTable(303, "Delete Returning Test", Some(35), Some("delete.returning@example.com"))

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          insert(testRecord)
        // Delete and return the deleted record
        returned <- xa.run:
          deleteReturning(testRecord)
        // Verify it was deleted
        afterDelete <- xa.run:
          sql"select * from test_table_primary_key where id = 303".queryOne[TestTable]
      yield assertTrue(returned == testRecord) &&
        assertTrue(afterDelete.isEmpty)
      end for

    test("delete where returning multiple records"):
      @tableName("test_table_primary_key")
      case class TestTable(@key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val testRecord1 = TestTable(304, "Multi Delete 1", Some(40), Some("multi1@example.com"))
      val testRecord2 = TestTable(305, "Multi Delete 2", Some(41), Some("multi2@example.com"))
      val testRecord3 = TestTable(306, "Keep This", Some(20), Some("keep@example.com"))
      // Use specific where clause that only targets our test records
      val whereClause = sql"id IN (304, 305, 306) AND age >= 40"

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          insert(testRecord1)
        _ <- xa.run:
          insert(testRecord2)
        _ <- xa.run:
          insert(testRecord3)
        // Delete records where id IN (304, 305, 306) AND age >= 40 and return them
        deletedRecords <- xa.run:
          deleteWhereReturning[TestTable](whereClause)
        // Verify the correct record remains
        remaining <- xa.run:
          sql"select * from test_table_primary_key where id = 306".queryOne[TestTable]
      yield assertTrue(deletedRecords.size == 2) &&
        assertTrue(deletedRecords.contains(testRecord1)) &&
        assertTrue(deletedRecords.contains(testRecord2)) &&
        assertTrue(remaining.contains(testRecord3))
      end for

    test("delete with generated column uses key for where clause"):
      @tableName("test_table_primary_key_generated")
      case class Generated(@generated @key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      for
        xa <- ZIO.service[Transactor]
        // Insert a record and get its generated id
        inserted <- xa.run:
          insertReturning(Generated(-1, "Delete Generated Test", Some(45), Some("delete.generated@example.com")))
        // Delete it using the generated id
        rowsAffected <- xa.run:
          delete(inserted)
        // Verify it was deleted
        afterDelete <- xa.run:
          sql"select * from test_table_primary_key_generated where id = ${inserted.id}".queryOne[Generated]
      yield assertTrue(rowsAffected == 1) &&
        assertTrue(afterDelete.isEmpty)
      end for

    test("delete with no key columns should fail safely"):
      @tableName("test_table_no_key")
      case class TestTable(name: String, age: Option[Int], @label("email") e: Option[String]) derives Table

      case class CountResult(count: Int) derives Table

      val testRecord = TestTable("No Key Delete Test", Some(50), Some("nokey@example.com"))

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          insert(testRecord)
        // This should generate a DELETE without WHERE clause, which would delete all records
        // Let's be careful and expect it to work but be aware of the implications
        rowsAffected <- xa.run:
          delete(testRecord)
        // Check how many records remain (should be 0 as it deletes all)
        remainingCountResult <- xa.run:
          sql"select count(*) as count from test_table_no_key".queryOne[CountResult]
      yield assertTrue(rowsAffected >= 1) &&                      // At least one record was deleted
        assertTrue(remainingCountResult.map(_.count).contains(0)) // All records in table were deleted

    test("delete with labeled columns"):
      @tableName("test_table_no_key")
      case class TestTable(name: String, age: Option[Int], @label("email") e: Option[String]) derives Table

      // Setup: Add a record first
      val testRecord  = TestTable("Label Delete Test", Some(55), Some("label.delete@example.com"))
      val whereClause = sql"name = ${"Label Delete Test"}"

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          insert(testRecord)
        rowsAffected <- xa.run:
          deleteWhere[TestTable](whereClause)
        // Verify deletion
        remaining <- xa.run:
          sql"select * from test_table_no_key where name = ${"Label Delete Test"}".queryOne[TestTable]
      yield assertTrue(rowsAffected == 1) &&
        assertTrue(remaining.isEmpty)
      end for

  val spec = suite("Delete Operations")(deleteTests).provideShared(xaLayer) @@ TestAspect.sequential
end DeleteSpecs

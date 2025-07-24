package saferis.tests

import saferis.*
import saferis.DataManipulationLayer.*
import zio.*
import zio.test.*

object UpdateSpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default

  val updateTests = suiteAll("should handle update operations"):
    test("update by key"):
      @tableName("test_table_primary_key")
      case class TestTable(@key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val updatedRecord = TestTable(1, "Alice Updated", Some(31), Some("alice.updated@example.com"))

      for
        xa <- ZIO.service[Transactor]
        rowsAffected <- xa.run:
          update(updatedRecord)
        updatedRow <- xa.run:
          sql"select * from test_table_primary_key where id = 1".queryOne[TestTable]
      yield assertTrue(rowsAffected == 1) &&
        assertTrue(updatedRow.contains(updatedRecord))

    test("update with custom where clause"):
      @tableName("test_table_primary_key")
      case class TestTable(@key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val updatedRecord = TestTable(2, "Bob Special", Some(26), Some("bob.special@example.com"))
      val whereClause   = sql"id = 2"

      for
        xa <- ZIO.service[Transactor]
        rowsAffected <- xa.run:
          updateWhere(updatedRecord, whereClause)
        updatedRow <- xa.run:
          sql"select * from test_table_primary_key where id = 2".queryOne[TestTable]
      yield assertTrue(rowsAffected == 1) &&
        assertTrue(updatedRow.map(_.name).contains("Bob Special"))

    test("update returning"):
      @tableName("test_table_primary_key")
      case class TestTable(@key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val updatedRecord = TestTable(3, "Charlie Modified", Some(40), Some("charlie.modified@example.com"))

      for
        xa <- ZIO.service[Transactor]
        returned <- xa.run:
          updateReturning(updatedRecord)
      yield assertTrue(returned == updatedRecord)

    test("update where returning"):
      @tableName("test_table_primary_key")
      case class TestTable(@key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val updatedRecord = TestTable(4, "Diana Enhanced", Some(29), Some("diana.enhanced@example.com"))
      val whereClause   = sql"id = 4"

      for
        xa <- ZIO.service[Transactor]
        returned <- xa.run:
          updateWhereReturning(updatedRecord, whereClause)
      yield assertTrue(returned == updatedRecord)

    test("update with generated column excludes generated fields"):
      @tableName("test_table_primary_key_generated")
      case class Generated(@generated @key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val updatedRecord = Generated(1, "Generated Updated", Some(35), Some("generated.updated@example.com"))

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          insertReturning(Generated(-1, "Test User", Some(25), Some("test@example.com")))
        rowsAffected <- xa.run:
          update(updatedRecord)
        updatedRow <- xa.run:
          sql"select * from test_table_primary_key_generated where id = 1".queryOne[Generated]
      yield assertTrue(rowsAffected == 1) &&
        assertTrue(updatedRow.map(_.name).contains("Generated Updated"))
      end for

    test("update with no key columns should generate UPDATE without WHERE"):
      @tableName("test_table_no_key")
      case class TestTable(name: String, age: Option[Int], @label("email") e: Option[String]) derives Table

      val updatedRecord = TestTable("Universal Update", Some(99), Some("universal@example.com"))

      for
        xa <- ZIO.service[Transactor]
        rowsAffected <- xa.run:
          update(updatedRecord)
        allRows <- xa.run:
          sql"select * from test_table_no_key".query[TestTable]
      yield assertTrue(rowsAffected == 4) && // Should update all 4 rows
        assertTrue(allRows.forall(_.name == "Universal Update"))

  val spec = suite("Update Operations")(updateTests).provideShared(xaLayer) @@ TestAspect.sequential
end UpdateSpecs

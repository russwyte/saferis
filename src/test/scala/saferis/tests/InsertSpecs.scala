package saferis.tests

import saferis.*
import saferis.DataManipulationLayer.*
import zio.*
import zio.test.*

object InsertSpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default

  val insertTests = suiteAll("should handle insert operations"):
    test("insert basic record"):
      @tableName("test_table_primary_key")
      case class TestTable(@key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val newRecord = TestTable(100, "Insert Test", Some(25), Some("insert.test@example.com"))

      for
        xa <- ZIO.service[Transactor]
        rowsAffected <- xa.run:
          insert(newRecord)
        insertedRow <- xa.run:
          sql"select * from test_table_primary_key where id = 100".queryOne[TestTable]
      yield assertTrue(rowsAffected == 1) &&
        assertTrue(insertedRow.contains(newRecord))

    test("insert record with null optional fields"):
      @tableName("test_table_primary_key")
      case class TestTable(@key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val newRecord = TestTable(101, "Null Fields Test", None, None)

      for
        xa <- ZIO.service[Transactor]
        rowsAffected <- xa.run:
          insert(newRecord)
        insertedRow <- xa.run:
          sql"select * from test_table_primary_key where id = 101".queryOne[TestTable]
      yield assertTrue(rowsAffected == 1) &&
        assertTrue(insertedRow.contains(newRecord))

    test("insert returning with non-generated primary key"):
      @tableName("test_table_primary_key")
      case class TestTable(@key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val newRecord = TestTable(102, "Insert Returning Test", Some(30), Some("returning.test@example.com"))

      for
        xa <- ZIO.service[Transactor]
        returned <- xa.run:
          insertReturning(newRecord)
      yield assertTrue(returned == newRecord)

    test("insert returning with generated primary key"):
      @tableName("test_table_primary_key_generated")
      case class Generated(@generated @key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val newRecord = Generated(-1, "Generated Test", Some(35), Some("generated.test@example.com"))

      for
        xa <- ZIO.service[Transactor]
        returned <- xa.run:
          insertReturning(newRecord)
        // Verify the id was generated (should be > 0 and not -1)
        _ <- ZIO.unless(returned.id > 0):
          ZIO.fail(new Exception(s"Expected generated id > 0, got ${returned.id}"))
      yield assertTrue(returned.name == newRecord.name) &&
        assertTrue(returned.age == newRecord.age) &&
        assertTrue(returned.email == newRecord.email) &&
        assertTrue(returned.id > 0)
      end for

    test("insert with generated column excludes generated fields from values"):
      @tableName("test_table_primary_key_generated")
      case class Generated(@generated @key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val newRecord = Generated(-1, "Generated Insert", Some(40), Some("generated.insert@example.com"))

      for
        xa <- ZIO.service[Transactor]
        rowsAffected <- xa.run:
          insert(newRecord)
        // Find the inserted record by name since we don't know the generated id
        insertedRow <- xa.run:
          sql"select * from test_table_primary_key_generated where name = ${newRecord.name}".queryOne[Generated]
      yield assertTrue(rowsAffected == 1) &&
        assertTrue(insertedRow.isDefined) &&
        assertTrue(insertedRow.map(_.name).contains(newRecord.name)) &&
        assertTrue(insertedRow.map(_.age).contains(newRecord.age)) &&
        assertTrue(insertedRow.map(_.email).contains(newRecord.email)) &&
        assertTrue(insertedRow.exists(_.id > 0)) // Generated id should be positive

    test("insert record with labeled columns"):
      @tableName("test_table_no_key")
      case class TestTable(name: String, age: Option[Int], @label("email") e: Option[String]) derives Table

      val newRecord = TestTable("Label Test", Some(45), Some("label.test@example.com"))

      for
        xa <- ZIO.service[Transactor]
        rowsAffected <- xa.run:
          insert(newRecord)
        insertedRow <- xa.run:
          sql"select * from test_table_no_key where name = ${newRecord.name}".queryOne[TestTable]
      yield assertTrue(rowsAffected == 1) &&
        assertTrue(insertedRow.contains(newRecord))

    test("insert returning with labeled columns"):
      @tableName("test_table_no_key")
      case class TestTable(name: String, age: Option[Int], @label("email") e: Option[String]) derives Table

      val newRecord = TestTable("Label Returning Test", Some(50), Some("label.returning@example.com"))

      for
        xa <- ZIO.service[Transactor]
        returned <- xa.run:
          insertReturning(newRecord)
      yield assertTrue(returned == newRecord)

    test("insert multiple records using raw SQL"):
      @tableName("test_table_primary_key")
      case class TestTable(@key id: Int, name: String, age: Option[Int], email: Option[String]) derives Table

      val age1: Option[Int]      = Some(25)
      val age2: Option[Int]      = Some(26)
      val email1: Option[String] = Some("bulk1@example.com")
      val email2: Option[String] = Some("bulk2@example.com")

      for
        xa <- ZIO.service[Transactor]
        rowsAffected <- xa.run:
          sql"insert into test_table_primary_key (id, name, age, email) values (200, ${"Bulk Insert 1"}, $age1, $email1), (201, ${"Bulk Insert 2"}, $age2, $email2)".insert
        insertedRows <- xa.run:
          sql"select * from test_table_primary_key where id in (200, 201) order by id".query[TestTable]
      yield assertTrue(rowsAffected == 2) &&
        assertTrue(insertedRows.size == 2) &&
        assertTrue(insertedRows.map(_.name).contains("Bulk Insert 1")) &&
        assertTrue(insertedRows.map(_.name).contains("Bulk Insert 2"))
      end for

  val spec = suite("Insert Operations")(insertTests).provideShared(xaLayer) @@ TestAspect.sequential
end InsertSpecs

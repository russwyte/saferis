package saferis.tests
import saferis.*
import zio.*
import zio.test.*

object TableSpecs extends ZIOSpecDefault:
  @tableName("test_table")
  final case class TestTable(
      @generated name: String,
      age: Option[Int],
      @label("email") e: Option[String],
  ) derives Table

  val x = TestTable("Frank", Some(42), None)

  println(x.values)

  val testTable = Table[TestTable]
  val spec = suiteAll("Table"):
    test("table name"):
      assertTrue(testTable.sql == "test_table")

    test("column labels"):
      assertTrue(testTable.name.sql == "name") &&
        assertTrue(testTable.age.sql == "age") &&
        assertTrue(testTable.e.sql == "email")

    test("generated annotation"):
      assertTrue(testTable.name.isGenerated) &&
        assertTrue(!testTable.age.isGenerated) &&
        assertTrue(!testTable.e.isGenerated)

    test("key is assumed by generated annotation"):
      assertTrue(testTable.name.isKey) &&
        assertTrue(!testTable.age.isKey) &&
        assertTrue(!testTable.e.isKey)

    test("alias for table"):
      assertTrue(testTable.withAlias("tt").sql == "test_table as tt")

    test("alias for columns"):
      val tt     = testTable.withAlias("tt")
      val labels = tt.columns.map(_.sql)
      assertTrue(labels.forall(_.startsWith("tt.")))

    test("de-aliasing"):
      val tt     = testTable.withAlias("tt")
      val labels = tt.deAliased.columns.map(_.sql)
      assertTrue(labels.forall(!_.contains(".")))

    test("columns scala field names and labels"):
      assertTrue(testTable.columns.map(_.name) == List("name", "age", "e")) &&
        assertTrue(testTable.columns.map(_.label) == List("name", "age", "email"))

    test("provide getByKey"):
      val sql = testTable.getByKey("Frank").sql
      assertTrue(sql == "select * from test_table where name = ?")
      val sql2 = testTable.withAlias("tt").getByKey("Frank").sql
      assertTrue(sql2 == "select * from test_table as tt where tt.name = ?")

end TableSpecs

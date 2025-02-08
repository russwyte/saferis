package saferis.tests
import zio.test.*
import zio.*
import saferis.*

object TableSpecs extends ZIOSpecDefault:
  @tableName("test_table")
  final case class TestTable(
      @generated name: String,
      age: Option[Int],
      @label("email") e: Option[String],
  ) derives Table

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
end TableSpecs

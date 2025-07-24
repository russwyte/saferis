package saferis.tests

import saferis.*
import zio.test.*

object IndexAnnotationSpecs extends ZIOSpecDefault:
  @tableName("test_table_with_indexes")
  case class TestTableWithIndexes(
      @key id: Int,
      @indexed name: String,
      @uniqueIndex email: String,
      @indexed @label("user_age") age: Option[Int],
      description: String,
  ) derives Table

  val spec = suite("Index Annotations")(
    test("identify indexed columns") {
      val table       = Table[TestTableWithIndexes]
      val indexedCols = table.indexedColumns.map(_.name)
      assertTrue(indexedCols.contains("name")) &&
      assertTrue(indexedCols.contains("age")) &&
      assertTrue(!indexedCols.contains("email")) && // uniqueIndex is separate
      assertTrue(!indexedCols.contains("id")) &&
      assertTrue(!indexedCols.contains("description"))
    },
    test("identify unique index columns") {
      val table           = Table[TestTableWithIndexes]
      val uniqueIndexCols = table.uniqueIndexColumns.map(_.name)
      assertTrue(uniqueIndexCols.contains("email")) &&
      assertTrue(!uniqueIndexCols.contains("name")) &&
      assertTrue(!uniqueIndexCols.contains("age"))
    },
    test("column has correct index flags") {
      val table    = Table[TestTableWithIndexes]
      val nameCol  = table.columns.find(_.name == "name").get
      val emailCol = table.columns.find(_.name == "email").get
      val descCol  = table.columns.find(_.name == "description").get

      assertTrue(nameCol.isIndexed && !nameCol.isUniqueIndex) &&
      assertTrue(!emailCol.isIndexed && emailCol.isUniqueIndex) &&
      assertTrue(!descCol.isIndexed && !descCol.isUniqueIndex)
    },
  )
end IndexAnnotationSpecs

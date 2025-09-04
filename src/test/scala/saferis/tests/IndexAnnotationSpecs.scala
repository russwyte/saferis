package saferis.tests

import saferis.*
import zio.test.*

object IndexAnnotationSpecs extends ZIOSpecDefault:
  @tableName("test_table_with_indexes")
  case class TestTableWithIndexes(
      @key id: Int,
      @indexed name: String,
      @uniqueIndex email: String,
      @unique username: String,
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
      assertTrue(!uniqueIndexCols.contains("username")) && // unique is separate from uniqueIndex
      assertTrue(!uniqueIndexCols.contains("name")) &&
      assertTrue(!uniqueIndexCols.contains("age"))
    },
    test("identify unique constraint columns") {
      val table      = Table[TestTableWithIndexes]
      val uniqueCols = table.uniqueColumns.map(_.name)
      assertTrue(uniqueCols.contains("username")) &&
      assertTrue(!uniqueCols.contains("email")) && // uniqueIndex is separate from unique
      assertTrue(!uniqueCols.contains("name")) &&
      assertTrue(!uniqueCols.contains("age"))
    },
    test("column has correct index flags") {
      val table       = Table[TestTableWithIndexes]
      val nameCol     = table.columns.find(_.name == "name").get
      val emailCol    = table.columns.find(_.name == "email").get
      val usernameCol = table.columns.find(_.name == "username").get
      val descCol     = table.columns.find(_.name == "description").get

      assertTrue(nameCol.isIndexed && !nameCol.isUniqueIndex && !nameCol.isUnique) &&
      assertTrue(!emailCol.isIndexed && emailCol.isUniqueIndex && !emailCol.isUnique) &&
      assertTrue(!usernameCol.isIndexed && !usernameCol.isUniqueIndex && usernameCol.isUnique) &&
      assertTrue(!descCol.isIndexed && !descCol.isUniqueIndex && !descCol.isUnique)
    },
  )
end IndexAnnotationSpecs

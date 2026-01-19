package saferis.tests

import saferis.*
// NOTE: No explicit postgres import needed! The default dialect should be available via wildcard import
import zio.test.*

object DefaultDialectSpecs extends ZIOSpecDefault:

  val spec = suite("Default Dialect Support")(
    test("PostgreSQL dialect is available by default without explicit import") {
      // This should work without importing saferis.postgres.{given}
      val dialect = summon[Dialect]
      assertTrue(dialect.name == "PostgreSQL")
    },
    test("Default dialect provides PostgreSQL-specific features") {
      val dialect = summon[Dialect]
      assertTrue(dialect.autoIncrementClause(true, true, false) == " generated always as identity primary key") &&
      assertTrue(dialect.identifierQuote == "\"") &&
      assertTrue(dialect.columnType(java.sql.Types.VARCHAR) == "varchar(255)")
    },
    test("Can create tables with just import saferis.*") {
      // This tests that all the necessary implicits are available
      @tableName("test_default_dialect")
      case class TestTable(@key id: Int, name: String) derives Table

      val table = Table[TestTable]
      assertTrue(table.sql == "test_default_dialect")
    },
    test("Can generate index SQL with default dialect") {
      @tableName("test_create_sql")
      case class TestTable(@generated @key id: Int, @indexed name: String) derives Table

      import saferis.ddl.*
      val sql = createIndexesSql[TestTable]()

      // Should contain PostgreSQL-specific syntax
      assertTrue(sql.contains("create index if not exists")) &&
      assertTrue(sql.contains("idx_test_create_sql_name")) &&
      assertTrue(sql.contains("test_create_sql"))
    },
  )
end DefaultDialectSpecs

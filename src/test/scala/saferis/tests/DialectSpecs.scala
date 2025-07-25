package saferis.tests

import saferis.*
import saferis.postgres.{given}
import saferis.mysql.MySQLDialect
import saferis.sqlite.SQLiteDialect
import zio.test.*
import saferis.postgres.PostgresDialect

object DialectSpecs extends ZIOSpecDefault:

  val spec = suite("Dialect Support")(
    test("PostgreSQL dialect provides correct auto-increment clause") {
      val dialect = summon[Dialect]
      assertTrue(dialect.name == "PostgreSQL") &&
      assertTrue(dialect.autoIncrementClause(true, true, false) == " generated always as identity primary key") &&
      assertTrue(dialect.autoIncrementClause(true, true, true) == " generated always as identity") &&
      assertTrue(dialect.autoIncrementClause(false, true, false) == " primary key") &&
      assertTrue(dialect.autoIncrementClause(false, false, false) == "")
    },
    test("MySQL dialect provides correct auto-increment clause") {
      assertTrue(MySQLDialect.name == "MySQL") &&
      assertTrue(MySQLDialect.autoIncrementClause(true, true, false) == " auto_increment primary key") &&
      assertTrue(MySQLDialect.autoIncrementClause(true, true, true) == " auto_increment") &&
      assertTrue(MySQLDialect.autoIncrementClause(false, true, false) == " primary key") &&
      assertTrue(MySQLDialect.autoIncrementClause(false, false, false) == "")
    },
    test("SQLite dialect provides correct auto-increment clause") {
      assertTrue(SQLiteDialect.name == "SQLite") &&
      assertTrue(SQLiteDialect.autoIncrementClause(true, true, false) == " primary key autoincrement") &&
      assertTrue(SQLiteDialect.autoIncrementClause(false, true, false) == " primary key") &&
      assertTrue(SQLiteDialect.autoIncrementClause(true, false, false) == " autoincrement") &&
      assertTrue(SQLiteDialect.autoIncrementClause(false, false, false) == "")
    },
    test("Dialects have different column type mappings") {
      import java.sql.Types
      val pgDialect = summon[Dialect]
      assertTrue(pgDialect.columnType(Types.VARCHAR) == "varchar(255)") &&
      assertTrue(MySQLDialect.columnType(Types.VARCHAR) == "varchar(255)") &&
      assertTrue(SQLiteDialect.columnType(Types.VARCHAR) == "text") &&
      assertTrue(pgDialect.columnType(Types.INTEGER) == "integer") &&
      assertTrue(MySQLDialect.columnType(Types.INTEGER) == "int") &&
      assertTrue(SQLiteDialect.columnType(Types.INTEGER) == "integer")
    },
    test("Dialects have different identifier quoting") {
      val pgDialect = summon[Dialect]
      assertTrue(pgDialect.identifierQuote == "\"") &&
      assertTrue(MySQLDialect.identifierQuote == "`") &&
      assertTrue(SQLiteDialect.identifierQuote == "\"")
    },
    test("Dialects generate correct index SQL") {
      val pgDialect = summon[Dialect]
      val indexSql  = pgDialect.createIndexSql("idx_test", "test_table", Seq("name"), true)
      assertTrue(indexSql == "create index if not exists idx_test on test_table (name)")

      val mysqlIndexSql = MySQLDialect.createIndexSql("idx_test", "test_table", Seq("name"), true)
      assertTrue(mysqlIndexSql == "create index idx_test on test_table (name)")

      val sqliteIndexSql = SQLiteDialect.createIndexSql("idx_test", "test_table", Seq("name"), true)
      assertTrue(sqliteIndexSql == "create index if not exists idx_test on test_table (name)")
    },
  )
end DialectSpecs

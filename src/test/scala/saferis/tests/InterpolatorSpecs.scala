package saferis.tests

import saferis.*
import saferis.postgres.given
import saferis.mysql.MySQLDialect
import saferis.sqlite.SQLiteDialect
import zio.*
import zio.test.*

object InterpolatorSpecs extends ZIOSpecDefault:
  val spec =
    suiteAll("interpolator"):
      test("simple interpolation"):
        val name = "Bob"
        val sql  = sqlEcho"select * from test_table_no_key where name = $name"
        assertTrue(sql.sql == "select * from test_table_no_key where name = ?")
      test("multiple interpolations"):
        val name = "Bob"
        val age  = 42
        val sql  = sql"select * from test_table_no_key where name = $name and age = $age"
        assertTrue(sql.sql == "select * from test_table_no_key where name = ? and age = ?") && assertTrue(
          sql.writes.size == 2
        )
      test("interpolation with a fragment"):
        val name = "Bob"
        val age  = 42
        val id   = 1L
        val frag = sql"where name = $name and age = $age"
        val sql  = sql"select * from test_table_no_key $frag and id = $id"
        assertTrue(sql.sql == "select * from test_table_no_key where name = ? and age = ? and id = ?") && assertTrue(
          sql.writes.size == 3
        )
      test("with margin"):
        val name = "Bob"
        val age  = 42

        val frag =
          sqlEcho"""|select *
                |from test_table_no_key
                |where name = $name and age = $age""".stripMargin
        assertTrue(
          frag.sql ==
            """|select *
               |from test_table_no_key
               |where name = ? and age = ?""".stripMargin
        ) && assertTrue(
          frag.show ==
            """|select *
               |from test_table_no_key
               |where name = 'Bob' and age = 42""".stripMargin
        )
      test("with constant argument"):
        val sql = sql"select * from test_table_no_key where name = ${"foo"} and age = ${12} and id = 1"
        assertTrue(sql.sql == "select * from test_table_no_key where name = ? and age = ? and id = 1") && assertTrue(
          sql.writes.size == 2
        )
      test("Placeholder.identifier with PostgreSQL dialect prevents SQL injection"):
        val pgDialect = summon[Dialect]
        // Test normal column name
        val col1 = Placeholder.identifier("user_id")(using pgDialect)
        assertTrue(col1.sql == "\"user_id\"") &&
        // Test column name with embedded quotes - should be escaped
        assertTrue(Placeholder.identifier("my\"column")(using pgDialect).sql == "\"my\"\"column\"") &&
        // Test SQL injection attempt
        assertTrue(
          Placeholder.identifier("\"; DROP TABLE users--")(using pgDialect).sql == "\"\"\"; DROP TABLE users--\""
        ) &&
        // Test in a query context
        assertTrue {
          val tableName = Placeholder.identifier("users")(using pgDialect)
          val colName   = Placeholder.identifier("name")(using pgDialect)
          val value     = "Alice"
          val query     = sql"SELECT * FROM $tableName WHERE $colName = $value"
          query.sql == "SELECT * FROM \"users\" WHERE \"name\" = ?"
        }
      test("Placeholder.identifier with MySQL dialect prevents SQL injection"):
        given Dialect = MySQLDialect
        // Test normal column name
        val col1 = Placeholder.identifier("user_id")
        assertTrue(col1.sql == "`user_id`") &&
        // Test column name with embedded backticks - should be escaped
        assertTrue(Placeholder.identifier("my`column").sql == "`my``column`") &&
        // Test SQL injection attempt
        assertTrue(Placeholder.identifier("`; DROP TABLE users--").sql == "```; DROP TABLE users--`") &&
        // Test in a query context
        assertTrue {
          val tableName = Placeholder.identifier("users")
          val colName   = Placeholder.identifier("name")
          val value     = "Alice"
          val query     = sql"SELECT * FROM $tableName WHERE $colName = $value"
          query.sql == "SELECT * FROM `users` WHERE `name` = ?"
        }
      test("Placeholder.identifier with SQLite dialect prevents SQL injection"):
        given Dialect = SQLiteDialect
        // Test normal column name
        val col1 = Placeholder.identifier("user_id")
        assertTrue(col1.sql == "\"user_id\"") &&
        // Test column name with embedded quotes - should be escaped
        assertTrue(Placeholder.identifier("my\"column").sql == "\"my\"\"column\"") &&
        // Test SQL injection attempt
        assertTrue(Placeholder.identifier("\"; DROP TABLE users--").sql == "\"\"\"; DROP TABLE users--\"") &&
        // Test in a query context
        assertTrue {
          val tableName = Placeholder.identifier("users")
          val colName   = Placeholder.identifier("name")
          val value     = "Alice"
          val query     = sql"SELECT * FROM $tableName WHERE $colName = $value"
          query.sql == "SELECT * FROM \"users\" WHERE \"name\" = ?"
        }

end InterpolatorSpecs

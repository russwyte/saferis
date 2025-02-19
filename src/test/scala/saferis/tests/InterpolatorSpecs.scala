package saferis.tests

import saferis.*
import zio.*
import zio.test.*

object InterpolatorSpecs extends ZIOSpecDefault:
  val spec =
    suiteAll("interpolator"):
      test("simple interpolation"):
        val name = "Bob"
        val sql  = sql"select * from test_table_no_key where name = $name"
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
          sql"""|select *
                |from test_table_no_key
                |where name = $name and age = $age""".stripMargin
        assertTrue(
          frag.sql ==
            """|select *
               |from test_table_no_key
               |where name = ? and age = ?""".stripMargin
        )

end InterpolatorSpecs

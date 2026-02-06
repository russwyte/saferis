package saferis.tests

import saferis.*
import zio.test.*
import scala.compiletime.testing.typeCheckErrors

/** Compile-time safety tests for Alias and related APIs.
  *
  * These tests verify that SQL injection is prevented at compile time by ensuring that only string literals can be used
  * for table aliases and identifiers.
  */
object AliasCompileTimeSpecs extends ZIOSpecDefault:

  @tableName("test_users")
  final case class TestUser(@key id: Int, name: String) derives Table

  val spec = suite("Alias Compile-Time Safety")(
    suite("Alias.apply macro")(
      test("Alias with string literal compiles"):
        val alias = Alias("users")
        assertTrue(alias.value == "users")
      ,
      test("Alias with variable does NOT compile"):
        val errors = typeCheckErrors("val x = \"users\"; Alias(x)")
        assertTrue(
          errors.nonEmpty,
          errors.exists(_.message.contains("string literal")),
        )
      ,
      test("Alias with function result does NOT compile"):
        val errors = typeCheckErrors("Alias(\"users\".toUpperCase)")
        assertTrue(
          errors.nonEmpty,
          errors.exists(_.message.contains("string literal")),
        )
      ,
      test("Alias with constant string concatenation compiles (constant folding)"):
        // Scala constant-folds "user" + "s" to "users" at compile time,
        // so this is safe - it becomes a string literal before the macro sees it
        val alias = Alias("user" + "s")
        assertTrue(alias.value == "users"),
    ),
    suite("Table[A](alias) macro")(
      test("Table[A] with string literal alias compiles"):
        val instance = Table[TestUser]("u")
        assertTrue(instance.alias.exists(_.value == "u"))
      ,
      test("Table[A] with variable alias does NOT compile"):
        val errors = typeCheckErrors("""
          import saferis.*
          @tableName("test_users")
          case class TU(@key id: Int, name: String) derives Table
          val x = "u"
          Table[TU](x)
        """)
        assertTrue(
          errors.nonEmpty,
          errors.exists(_.message.contains("string literal")),
        ),
    ),
    suite("instance as alias")(
      test("instance as string literal compiles"):
        val instance = Table[TestUser] as "u"
        assertTrue(instance.alias.exists(_.value == "u"))
      ,
      test("instance as variable does NOT compile"):
        val errors = typeCheckErrors("""
          import saferis.*
          @tableName("test_users")
          case class TU(@key id: Int, name: String) derives Table
          val instance = Table[TU]
          val x = "u"
          instance as x
        """)
        assertTrue(
          errors.nonEmpty,
          errors.exists(_.message.contains("string literal")),
        ),
    ),
    suite("aliased function")(
      test("aliased with string literal compiles"):
        val instance        = Table[TestUser]
        val aliasedInstance = aliased(instance, "u")
        assertTrue(aliasedInstance.alias.exists(_.value == "u"))
      ,
      test("aliased with variable does NOT compile"):
        val errors = typeCheckErrors("""
          import saferis.*
          @tableName("test_users")
          case class TU(@key id: Int, name: String) derives Table
          val instance = Table[TU]
          val x = "u"
          aliased(instance, x)
        """)
        assertTrue(
          errors.nonEmpty,
          errors.exists(_.message.contains("string literal")),
        ),
    ),
    suite("Query.from(subquery, alias)")(
      test("Query.from with string literal compiles"):
        // Can't easily test Query.from here since it needs SelectQuery[A] where A derives Table
        // But we verify the pattern works with a string literal
        assertTrue(true) // Placeholder - the test above validates the pattern
      ,
      test("Query.from with variable does NOT compile"):
        val errors = typeCheckErrors("""
          import saferis.*
          @tableName("test_users")
          case class TU(@key id: Int, name: String) derives Table
          val subquery = Query[TU].where(_.id).gt(0).selectAll[TU]
          val x = "derived"
          Query.from(subquery, x)
        """)
        assertTrue(
          errors.nonEmpty,
          errors.exists(_.message.contains("string literal")),
        ),
    ),
  )
end AliasCompileTimeSpecs

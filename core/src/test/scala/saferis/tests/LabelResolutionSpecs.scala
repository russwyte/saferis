package saferis.tests

import saferis.*
import saferis.Schema.*
import saferis.postgres.PostgresDialect
import zio.json.*
import zio.test.*

/** Rule 1 of the identifier-handling contract: every generated SQL identifier must be the resolved `@label` (the one
  * true column name), never the raw Scala field name.
  *
  * These tests use a table whose JSON column's `@label` deliberately DIFFERS from its field name (`data` field ->
  * `payload` column). A correct implementation must emit `payload`; the pre-fix JSON where-ops emit the raw field name
  * `data`, so these start RED and go green once the JSON ops resolve through `fieldToLabel`.
  */
object LabelResolutionSpecs extends ZIOSpecDefault:

  given (Dialect & JsonSupport) = PostgresDialect

  final case class Payload(kind: String, level: Int) derives JsonCodec

  @tableName("label_resolution_probe")
  final case class Probe(
      @generated @key id: Int,
      @label("payload") data: Json[Payload],
  ) derives Table

  /** Extract the single generated WHERE condition string from a Schema JSON where-op. */
  private def condition(build: => InstanceWhereConditionBuilder[Probe]): String =
    build.conditions.mkString(" and ")

  def spec = suite("Label resolution (Rule 1: emit the label, never the field name)")(
    test("jsonContains emits the column label, not the field name"):
      val cond = condition(Schema[Probe].withIndex(_.data).where(_.data).jsonContains(Payload("a", 1)))
      assertTrue(cond.contains("payload"), !cond.contains("data"))
    ,
    test("jsonHasKey emits the column label, not the field name"):
      val cond = condition(Schema[Probe].withIndex(_.data).where(_.data).jsonHasKey("kind"))
      assertTrue(cond.contains("payload"), !cond.contains("data"))
    ,
    test("jsonHasAnyKey emits the column label, not the field name"):
      val cond = condition(Schema[Probe].withIndex(_.data).where(_.data).jsonHasAnyKey(Seq("kind", "level")))
      assertTrue(cond.contains("payload"), !cond.contains("data"))
    ,
    test("jsonHasAllKeys emits the column label, not the field name"):
      val cond = condition(Schema[Probe].withIndex(_.data).where(_.data).jsonHasAllKeys(Seq("kind", "level")))
      assertTrue(cond.contains("payload"), !cond.contains("data"))
    ,
    test("jsonPath emits the column label, not the field name"):
      val cond = condition(Schema[Probe].withIndex(_.data).where(_.data).jsonPath("kind").eql("a"))
      assertTrue(cond.contains("payload"), !cond.contains("data")),
  )
end LabelResolutionSpecs

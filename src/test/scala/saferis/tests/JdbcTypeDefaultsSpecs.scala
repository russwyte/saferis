package saferis.tests

import saferis.*
import zio.test.*

object JdbcTypeDefaultsSpecs extends ZIOSpecDefault:
  val spec = suite("Database Column Type Mappings")(
    test("string encoder uses varchar(255) column type") {
      val encoder = summon[Encoder[String]]
      assertTrue(encoder.columnType == "varchar(255)")
    },
    test("short encoder uses smallint column type") {
      val encoder = summon[Encoder[Short]]
      assertTrue(encoder.columnType == "smallint")
    },
    test("int encoder uses integer column type") {
      val encoder = summon[Encoder[Int]]
      assertTrue(encoder.columnType == "integer")
    },
    test("long encoder uses bigint column type") {
      val encoder = summon[Encoder[Long]]
      assertTrue(encoder.columnType == "bigint")
    },
    test("boolean encoder uses boolean column type") {
      val encoder = summon[Encoder[Boolean]]
      assertTrue(encoder.columnType == "boolean")
    },
    test("float encoder uses real column type") {
      val encoder = summon[Encoder[Float]]
      assertTrue(encoder.columnType == "real")
    },
    test("double encoder uses PostgreSQL-specific 'double precision'") {
      val encoder = summon[Encoder[Double]]
      assertTrue(encoder.columnType == "double precision")
    },
    test("date encoder uses date column type") {
      val encoder = summon[Encoder[java.sql.Date]]
      assertTrue(encoder.columnType == "date")
    },
    test("url encoder uses PostgreSQL-specific 'text'") {
      val encoder = summon[Encoder[java.net.URL]]
      assertTrue(encoder.columnType == "text")
    },
  )
end JdbcTypeDefaultsSpecs

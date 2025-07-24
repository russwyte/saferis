package saferis.tests

import saferis.*
import zio.test.*

object JdbcTypeDefaultsSpecs extends ZIOSpecDefault:
  val spec = suite("JDBC Type Defaults")(
    test("string encoder uses varchar(255) from JDBC default") {
      val encoder = summon[Encoder[String]]
      assertTrue(encoder.postgresType == "varchar(255)")
    },
    test("int encoder uses smallint from JDBC default") {
      val encoder = summon[Encoder[Short]]
      assertTrue(encoder.postgresType == "smallint")
    },
    test("int encoder uses integer from JDBC default") {
      val encoder = summon[Encoder[Int]]
      assertTrue(encoder.postgresType == "integer")
    },
    test("long encoder uses bigint from JDBC default") {
      val encoder = summon[Encoder[Long]]
      assertTrue(encoder.postgresType == "bigint")
    },
    test("boolean encoder uses boolean from JDBC default") {
      val encoder = summon[Encoder[Boolean]]
      assertTrue(encoder.postgresType == "boolean")
    },
    test("float encoder uses real from JDBC default") {
      val encoder = summon[Encoder[Float]]
      assertTrue(encoder.postgresType == "real")
    },
    test("double encoder uses PostgreSQL-specific 'double precision'") {
      val encoder = summon[Encoder[Double]]
      assertTrue(encoder.postgresType == "double precision")
    },
    test("date encoder uses date from JDBC default") {
      val encoder = summon[Encoder[java.sql.Date]]
      assertTrue(encoder.postgresType == "date")
    },
    test("url encoder uses PostgreSQL-specific 'text'") {
      val encoder = summon[Encoder[java.net.URL]]
      assertTrue(encoder.postgresType == "text")
    },
  )
end JdbcTypeDefaultsSpecs

package saferis.tests

import saferis.Encoder
import zio.*
import zio.test.*

import java.net.URL
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.Time
import java.sql.Timestamp

object EncoderSpecs extends ZIOSpecDefault:
  val dateVal      = new Date(1660000000000L)
  val timeVal      = new Time(12345678L)
  val timestampVal = new Timestamp(1660000000000L)
  val urlVal       = java.net.URI.create("https://example.com").toURL

  val spec = suite("Encoder should encode all supported types")(
    test("literal method") {
      val stringLit     = summon[Encoder[String]].literal("O'Reilly")
      val someStringLit = summon[Encoder[Option[String]]].literal(Some("O'Reilly"))
      val intLit        = summon[Encoder[Int]].literal(42)
      val boolLit       = summon[Encoder[Boolean]].literal(true)
      val dateLit       = summon[Encoder[Date]].literal(dateVal)
      val floatLit      = summon[Encoder[Float]].literal(3.14f)
      val noneLitInt    = summon[Encoder[Option[Int]]].literal(None)
      val noneLitStr    = summon[Encoder[Option[String]]].literal(None)
      val someLitInt    = summon[Encoder[Option[Int]]].literal(Some(99))
      val nullLitStr    = summon[Encoder[String]].literal(null)
      val nullLitInt    = summon[Encoder[BigInt]].literal(null)
      val timeLit       = summon[Encoder[Time]].literal(timeVal)
      val timestampLit  = summon[Encoder[Timestamp]].literal(timestampVal)
      val urlLit        = summon[Encoder[URL]].literal(urlVal)
      val unkownEncoder = new Encoder[Any]:
        def encode(a: Any, stmt: PreparedStatement, idx: Int)(using Trace): Task[Unit] = ???
        override val jdbcType: Int                                                     = java.sql.Types.OTHER
      val unknownLit = unkownEncoder.literal("unknown")
      assertTrue(
        stringLit == "'O''Reilly'",
        someStringLit == "'O''Reilly'",
        intLit == "42",
        boolLit == "true",
        dateLit == s"DATE '${dateVal.toString}'",
        floatLit == "3.14",
        noneLitInt == "null",
        noneLitStr == "null",
        someLitInt == "99",
        nullLitStr == "null",
        nullLitInt == "null",
        timeLit == s"TIME '${timeVal.toString}'",
        timestampLit == s"TIMESTAMP '${timestampVal.toString}'",
        urlLit == "'https://example.com'",
        unknownLit == "'unknown'",
      )
    }
  )
end EncoderSpecs

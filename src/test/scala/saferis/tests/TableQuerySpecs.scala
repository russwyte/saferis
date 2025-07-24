package saferis.tests
import saferis.*
import saferis.DataManipulationLayer.*
import zio.*
import zio.test.*

object TableQuerySpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default
  val queries = suiteAll("should run queries"):
    test("a select all query"):
      @tableName("test_table_primary_key_generated")
      case class Generated(@generated id: Int, name: String, age: Option[Int], email: Option[String]) derives Table
      for
        xa <- ZIO.service[Transactor]
        a <- xa.run:
          insertReturning(Generated(-1, "Ben", None, None)) // id is generated
        b <- xa.run:
          sql"select * from test_table_primary_key_generated"
            .query[Generated]
      yield assertTrue(a.id == 5) && assertTrue(b.size == 5)
  val spec = suite("Tables")(queries).provideShared(xaLayer)
end TableQuerySpecs

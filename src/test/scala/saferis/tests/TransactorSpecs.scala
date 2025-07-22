package foo.tests

import saferis.*
import saferis.tests.DataSourceProvider
import zio.*
import zio.test.*

import java.sql.Connection
import java.sql.SQLException

object TransactorSpecs extends ZIOSpecDefault:
  val readCommitted = DataSourceProvider.default >>> Transactor.layer(
    configurator = _.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE),
    maxConcurrency = 1,
  )
  val datasource = DataSourceProvider.default >>> Transactor.default

  val frank                = "Frank"
  val bob                  = "Bob"
  val alice                = "Alice"
  val charlie              = "Charlie"
  val none: Option[String] = None

  @tableName("test_table_no_key")
  final case class TestTable(
      @key name: String,
      age: Option[Int],
      @label("email") e: Option[String],
  ) derives Table

  val testTable = Table[TestTable]

  val runSuite =
    suiteAll("should run"):
      test("a select all query"):
        val sql = sql"select * from $testTable"
        for
          xa <- ZIO.service[Transactor]
          a <- xa.run:
            sql.query[TestTable]
        yield assertTrue(a.size == 4)

      test("a single row query"):
        val sql = sql"select * from $testTable where name = $alice"
        for
          xa <- ZIO.service[Transactor]
          a <- xa.run:
            sql.queryOne[TestTable]
        yield assertTrue(a == Some(TestTable("Alice", Some(30), Some("alice@example.com"))))
  end runSuite

  val transactionSuite =
    suiteAll("should run statements in a transaction"):
      test("yielding an effect of the result"):
        for
          xa <- ZIO.service[Transactor]
          a <- xa.transact:
            for
              a <- sql"select * from $testTable where name = $alice".queryOne[TestTable]
              b <- sql"select * from $testTable where name = $bob".queryOne[TestTable]
              c <- sql"select * from $testTable where name = $none".queryOne[TestTable]
            yield a.toSeq ++ b.toSeq ++ c.toSeq
        yield assertTrue(a.size == 2)

      test("commit on success"):
        for
          xa <- ZIO.service[Transactor]
          a <- xa
            .transact:
              insertReturning(TestTable(frank, Some(42), None))
          newNames <- xa.run:
            sql"select * from $testTable".query[TestTable].map(_.map(_.name))
        yield assertTrue(a.name == frank) &&
          assertTrue(newNames.contains(frank)) &&
          assertTrue(newNames.size == 5)

      test("rollback on effect failure"):
        val names = sql"select * from $testTable".query[TestTable].map(_.map(_.name))
        for
          xa <- ZIO.service[Transactor]
          before <- xa.run:
            names
          error <- xa
            .transact:
              for
                _ <- sql"delete from $testTable where name = $bob".delete
                _ <- sql"delete from $testTable where name = $alice".delete
                _ <- sql"deli meat from $testTable where name = $charlie".delete // pastrami please - this will fail
              yield ()
            .flip
          after <- xa
            .run:
              names
        yield assertTrue(error.isInstanceOf[java.sql.SQLException]) && // the pastrami is a lie
          assertTrue(after == before)
        end for
  end transactionSuite

  val all = suiteAll("A transactor"):
    runSuite.provideShared(datasource)
    transactionSuite.provideShared(readCommitted)

  val spec = all @@ TestAspect.sequential

end TransactorSpecs

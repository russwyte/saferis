package foo.tests

import saferis.*
import zio.*
import zio.test.*

import java.sql.Connection
import saferis.tests.DataSourceProvider
import java.sql.SQLException

object TransactorSpecs extends ZIOSpecDefault:
  val readCommitted = DataSourceProvider.default >+> Transactor.withConfig(
    _.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED)
  )
  val datasource = DataSourceProvider.default >+> Transactor.default

  val frank = "Frank"
  val bob   = "Bob"
  val alice = "Alice"

  @tableName("test_table")
  final case class TestTable(
      @generated name: String,
      age: Option[Int],
      @label("email") e: Option[String],
  ) derives Table

  val testTable = Table[TestTable]

  val spec =
    suiteAll("A transactor"):
      suiteAll("should run"):
        test("an all query"):
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
      suiteAll("should run statements in a transaction"):
        test("yielding an effect of the result"):
          for
            xa <- ZIO.service[Transactor]
            a <- xa.transact:
              for
                a <- sql"select * from $testTable where name = $alice".queryOne[TestTable]
                b <- sql"select * from $testTable where name = $bob".queryOne[TestTable]
              yield a.toSeq ++ b.toSeq
          yield assertTrue(a.size == 2)
        test("rollback on effect failure"):
          val ex = new SQLException("nope")
          for
            xa <- ZIO.service[Transactor]
            a <- xa
              .transact:
                for
                  _ <- sql"delete from $testTable where name = $bob".delete
                  _ <- sql"delete from $testTable where name = $alice".delete.flatMap(_ => ZIO.fail(ex))
                yield ()
              .flip
            b <- xa
              .run:
                sql"select * from $testTable".query[TestTable].map(_.map(_.name))
          yield assertTrue(a == ex) && assertTrue(b.contains(bob)) && assertTrue(b.contains(alice))
          end for
      .provideShared(readCommitted)
    .provideShared(datasource)
end TransactorSpecs

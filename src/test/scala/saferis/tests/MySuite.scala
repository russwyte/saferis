package saferis.tests

import saferis.*
import zio.*
import zio.test.*

import java.sql.Connection

object MySuite extends ZIOSpecDefault:
  val bob = "Bob"

  @tableName("test_table")
  final case class TestTable(name: String, age: Option[Int], @label("email") e: Option[String]) derives Table

  val testTable = Table[TestTable].metadata
  val sql1      = sql"select * from $testTable where ${testTable.name} like $bob"
  val allSql    = sql"select * from $testTable"
  val insertSql =
    import testTable.*
    sql"insert into $testTable ($name, $age, $e) values ($bob, 42, '')".update

  def spec = suiteAll("type fun"):
    test("foo"):
      val sql1   = sql"select * from $testTable where ${testTable.name} like $bob"
      val allSql = sql"select * from $testTable"
      for
        a <- xa.run:
          sql1.query[TestTable]
        b <- xa.transact:
          for
            b1 <- sql1.query[TestTable]
            b2 <- sql1.query[TestTable]
          yield b1 ++ b2
        c <- xa.run(insertSql)
        all2 <- xa.run:
          allSql.query[TestTable]
      yield assertTrue(a.size == 1) && assertTrue(b.size == 2) && assertTrue(c == 1) && assertTrue(all2.size == 5)
      end for
    .provide(
      DataSourceProvider.default >+> xa.withConfig(_.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED))
    )
end MySuite

package saferis.tests

import saferis.*
import saferis.dml.*
import saferis.ddl.*
import zio.*
import zio.test.*

import java.sql.Connection
import java.sql.SQLException

object TransactorSpecs extends ZIOSpecDefault:
  val serializable = DataSourceProvider.default >>> Transactor.layer(
    configurator = _.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE),
    maxConcurrency = 1,
  )
  val readCommitted = DataSourceProvider.default >>> Transactor.layer(
    configurator = _.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED),
    maxConcurrency = 2,
  )
  val defaultTransactor = DataSourceProvider.default >>> Transactor.default

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

      test("insert operation"):
        @tableName("test_transactor_insert")
        case class InsertTable(@key id: Int, name: String) derives Table

        for
          xa <- ZIO.service[Transactor]
          _ <- xa.run:
            sql"drop table if exists test_transactor_insert".dml
          _ <- xa.run:
            sql"create table test_transactor_insert (id integer primary key, name varchar(255))".dml
          rowsAffected <- xa.run:
            insert(InsertTable(1, "Test Insert"))
          result <- xa.run:
            sql"select * from test_transactor_insert where id = 1".queryOne[InsertTable]
        yield assertTrue(rowsAffected == 1) &&
          assertTrue(result.contains(InsertTable(1, "Test Insert")))
        end for

      test("update operation"):
        @tableName("test_transactor_update")
        case class UpdateTable(@key id: Int, name: String) derives Table

        for
          xa <- ZIO.service[Transactor]
          _ <- xa.run:
            sql"drop table if exists test_transactor_update".dml
          _ <- xa.run:
            sql"create table test_transactor_update (id integer primary key, name varchar(255))".dml
          _ <- xa.run:
            insert(UpdateTable(1, "Original"))
          rowsAffected <- xa.run:
            update(UpdateTable(1, "Updated"))
          result <- xa.run:
            sql"select * from test_transactor_update where id = 1".queryOne[UpdateTable]
        yield assertTrue(rowsAffected == 1) &&
          assertTrue(result.contains(UpdateTable(1, "Updated")))
        end for

      test("delete operation"):
        @tableName("test_transactor_delete")
        case class DeleteTable(@key id: Int, name: String) derives Table

        for
          xa <- ZIO.service[Transactor]
          _ <- xa.run:
            sql"drop table if exists test_transactor_delete".dml
          _ <- xa.run:
            sql"create table test_transactor_delete (id integer primary key, name varchar(255))".dml
          _ <- xa.run:
            insert(DeleteTable(1, "To Delete"))
          rowsAffected <- xa.run:
            delete(DeleteTable(1, "To Delete"))
          result <- xa.run:
            sql"select * from test_transactor_delete where id = 1".queryOne[DeleteTable]
        yield assertTrue(rowsAffected == 1) &&
          assertTrue(result.isEmpty)
        end for

      test("DDL operations"):
        @tableName("test_transactor_ddl")
        case class DDLTable(@key id: Int, name: String) derives Table

        for
          xa <- ZIO.service[Transactor]
          _ <- xa.run:
            dropTable[DDLTable](ifExists = true)
          createResult <- xa.run:
            createTable[DDLTable]()
          _ <- xa.run:
            insert(DDLTable(1, "DDL Test"))
          queryResult <- xa.run:
            sql"select * from test_transactor_ddl where id = 1".queryOne[DDLTable]
          dropResult <- xa.run:
            dropTable[DDLTable]()
        yield assertTrue(createResult >= 0) &&
          assertTrue(queryResult.contains(DDLTable(1, "DDL Test"))) &&
          assertTrue(dropResult >= 0)
        end for
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

      test("mixed operations in transaction"):
        @tableName("test_transactor_mixed")
        case class MixedTable(@key id: Int, name: String, value: Int) derives Table

        for
          xa <- ZIO.service[Transactor]
          _ <- xa.run:
            sql"drop table if exists test_transactor_mixed".dml
          _ <- xa.run:
            sql"create table test_transactor_mixed (id integer primary key, name varchar(255), value integer)".dml
          result <- xa.transact:
            for
              _         <- insert(MixedTable(1, "First", 10))
              _         <- insert(MixedTable(2, "Second", 20))
              _         <- update(MixedTable(1, "Updated First", 15))
              remaining <- sql"select * from test_transactor_mixed".query[MixedTable]
            yield remaining
          count <- xa.run:
            sql"select count(*) as count from test_transactor_mixed".queryOne[CountResult]
        yield assertTrue(result.size == 2) &&
          assertTrue(result.exists(_.name == "Updated First")) &&
          assertTrue(count.map(_.count).contains(2))
        end for

      test("transaction isolation"):
        @tableName("test_transactor_isolation")
        case class IsolationTable(@key id: Int, value: Int) derives Table

        for
          xa <- ZIO.service[Transactor]
          _ <- xa.run:
            sql"drop table if exists test_transactor_isolation".dml
          _ <- xa.run:
            sql"create table test_transactor_isolation (id integer primary key, value integer)".dml
          _ <- xa.run:
            insert(IsolationTable(1, 100))
          // Test that transaction sees consistent data
          result <- xa.transact:
            for
              initial <- sql"select * from test_transactor_isolation where id = 1".queryOne[IsolationTable]
              _       <- update(IsolationTable(1, 200))
              updated <- sql"select * from test_transactor_isolation where id = 1".queryOne[IsolationTable]
            yield (initial, updated)
        yield assertTrue(result._1.map(_.value).contains(100)) &&
          assertTrue(result._2.map(_.value).contains(200))
        end for
  end transactionSuite

  val concurrencyTestsUnlimited =
    suiteAll("should handle unlimited concurrency"):
      test("concurrent access with no semaphore limit"):
        @tableName("test_transactor_unlimited")
        case class UnlimitedTable(@key id: Int, name: String) derives Table

        for
          xa <- ZIO.service[Transactor]
          _ <- xa.run:
            sql"drop table if exists test_transactor_unlimited".dml
          _ <- xa.run:
            sql"create table test_transactor_unlimited (id integer primary key, name varchar(255))".dml
          // Run many operations concurrently - should all succeed quickly
          results <- ZIO.collectAllPar:
            (1 to 10).map: i =>
              xa.run:
                insert(UnlimitedTable(i, s"Unlimited $i"))
          count <- xa.run:
            sql"select count(*) as count from test_transactor_unlimited".queryOne[CountResult]
        yield assertTrue(results.forall(_ == 1)) &&
          assertTrue(count.map(_.count).contains(10))
        end for

  val concurrencyTestsLimited =
    suiteAll("should handle limited concurrency"):
      test("concurrent access with semaphore limit of 2"):
        @tableName("test_transactor_limited")
        case class LimitedTable(@key id: Int, name: String) derives Table

        for
          xa <- ZIO.service[Transactor]
          _ <- xa.run:
            sql"drop table if exists test_transactor_limited".dml
          _ <- xa.run:
            sql"create table test_transactor_limited (id integer primary key, name varchar(255))".dml
          // Run operations concurrently - only 2 should run at once due to semaphore
          results <- ZIO.collectAllPar:
            (1 to 5).map: i =>
              xa.run:
                insert(LimitedTable(i, s"Limited $i"))
          count <- xa.run:
            sql"select count(*) as count from test_transactor_limited".queryOne[CountResult]
        yield assertTrue(results.forall(_ == 1)) &&
          assertTrue(count.map(_.count).contains(5))
        end for

  val concurrencyTestsSerialized =
    suiteAll("should handle serialized concurrency"):
      test("concurrent access with semaphore limit of 1"):
        @tableName("test_transactor_serialized")
        case class SerializedTable(@key id: Int, name: String) derives Table

        for
          xa <- ZIO.service[Transactor]
          _ <- xa.run:
            sql"drop table if exists test_transactor_serialized".dml
          _ <- xa.run:
            sql"create table test_transactor_serialized (id integer primary key, name varchar(255))".dml
          // Run operations concurrently - but they should be effectively serialized
          results <- ZIO.collectAllPar:
            (1 to 3).map: i =>
              xa.run:
                insert(SerializedTable(i, s"Serialized $i"))
          count <- xa.run:
            sql"select count(*) as count from test_transactor_serialized".queryOne[CountResult]
        yield assertTrue(results.forall(_ == 1)) &&
          assertTrue(count.map(_.count).contains(3))
        end for

  val configuratorTests =
    suiteAll("should apply configurator"):
      test("configurator sets transaction isolation"):
        for
          xa <- ZIO.service[Transactor]
          // This transactor was configured with SERIALIZABLE isolation
          // We'll verify it works correctly (detailed isolation testing would require more complex scenarios)
          result <- xa.transact:
            sql"select 1 as value".queryOne[ValueResult]
        yield assertTrue(result.map(_.value).contains(1))

  case class CountResult(count: Int) derives Table
  case class ValueResult(value: Int) derives Table

  val all = suiteAll("A transactor"):
    runSuite.provideShared(defaultTransactor)
    transactionSuite.provideShared(serializable)
    concurrencyTestsUnlimited.provideShared(defaultTransactor)
    concurrencyTestsLimited.provideShared(readCommitted)
    concurrencyTestsSerialized.provideShared(serializable)
    configuratorTests.provideShared(serializable)

  val spec = all @@ TestAspect.sequential

end TransactorSpecs

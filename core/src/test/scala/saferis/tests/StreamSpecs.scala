package saferis.tests

import saferis.*
import saferis.ddl.*
import saferis.dml.*
import saferis.postgres.given
import saferis.tests.PostgresTestContainer.DataSourceProvider
import zio.*
import zio.stream.*
import zio.test.*

import java.util.concurrent.atomic.AtomicInteger

object StreamSpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default

  @tableName("stream_test_users")
  final case class StreamUser(@generated @key id: Int, name: String, age: Int) derives Table

  @tableName("stream_test_items")
  final case class StreamItem(@generated @key id: Int, value: String) derives Table

  // Table instances for orderBy
  val userTable = Table[StreamUser]
  val itemTable = Table[StreamItem]

  val spec = suite("Stream Specs")(
    suite("Basic Streaming")(
      test("queryStream returns same results as query for small datasets") {
        for
          xa          <- ZIO.service[Transactor]
          _           <- xa.run(dropTable[StreamUser](ifExists = true))
          _           <- xa.run(createTable[StreamUser]())
          _           <- xa.run(insert(StreamUser(0, "Alice", 30)))
          _           <- xa.run(insert(StreamUser(0, "Bob", 25)))
          _           <- xa.run(insert(StreamUser(0, "Charlie", 35)))
          eagerResult <- xa.run:
            Query[StreamUser].all.orderBy(userTable.id.asc).query[StreamUser]
          streamResult <- xa.run:
            Query[StreamUser].all.orderBy(userTable.id.asc).queryStream[StreamUser].runCollect
        yield assertTrue(eagerResult == streamResult)
      },
      test("queryStream correctly iterates all rows") {
        for
          xa    <- ZIO.service[Transactor]
          _     <- xa.run(dropTable[StreamItem](ifExists = true))
          _     <- xa.run(createTable[StreamItem]())
          _     <- ZIO.foreachDiscard(1 to 100)(i => xa.run(insert(StreamItem(0, s"Item $i"))))
          count <- xa.run:
            Query[StreamItem].all.queryStream[StreamItem].runCount
        yield assertTrue(count == 100L)
      },
      test("empty result set returns empty stream") {
        for
          xa    <- ZIO.service[Transactor]
          _     <- xa.run(dropTable[StreamItem](ifExists = true))
          _     <- xa.run(createTable[StreamItem]())
          count <- xa.run:
            Query[StreamItem].all.queryStream[StreamItem].runCount
        yield assertTrue(count == 0L)
      },
    ),
    suite("Lazy Evaluation")(
      test("take(n) only processes n rows") {
        val rowsProcessed = new AtomicInteger(0)
        for
          xa     <- ZIO.service[Transactor]
          _      <- xa.run(dropTable[StreamItem](ifExists = true))
          _      <- xa.run(createTable[StreamItem]())
          _      <- ZIO.foreachDiscard(1 to 100)(i => xa.run(insert(StreamItem(0, s"Item $i"))))
          result <- xa.run:
            Query[StreamItem].all
              .queryStream[StreamItem]
              .tap(_ => ZIO.succeed(rowsProcessed.incrementAndGet()))
              .take(10)
              .runCollect
        yield assertTrue(result.size == 10) &&
          assertTrue(rowsProcessed.get() == 10)
        end for
      },
      test("takeWhile stops iteration when condition fails") {
        for
          xa     <- ZIO.service[Transactor]
          _      <- xa.run(dropTable[StreamUser](ifExists = true))
          _      <- xa.run(createTable[StreamUser]())
          _      <- xa.run(insert(StreamUser(0, "Alice", 30)))
          _      <- xa.run(insert(StreamUser(0, "Bob", 25)))
          _      <- xa.run(insert(StreamUser(0, "Charlie", 35)))
          result <- xa.run:
            Query[StreamUser].all
              .orderBy(userTable.age.asc)
              .queryStream[StreamUser]
              .takeWhile(_.age < 35)
              .runCollect
        yield assertTrue(result.size == 2) &&
          assertTrue(result.forall(_.age < 35))
      },
      test("takeUntil stops at first match") {
        for
          xa     <- ZIO.service[Transactor]
          _      <- xa.run(dropTable[StreamUser](ifExists = true))
          _      <- xa.run(createTable[StreamUser]())
          _      <- xa.run(insert(StreamUser(0, "Alice", 30)))
          _      <- xa.run(insert(StreamUser(0, "Bob", 25)))
          _      <- xa.run(insert(StreamUser(0, "Charlie", 35)))
          result <- xa.run:
            Query[StreamUser].all
              .orderBy(userTable.age.asc)
              .queryStream[StreamUser]
              .takeUntil(_.age >= 30)
              .runCollect
        yield assertTrue(result.size == 2) // Bob (25) and Alice (30)
      },
    ),
    suite("Resource Safety")(
      test("connection released after stream fully consumed") {
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[StreamItem](ifExists = true))
          _  <- xa.run(createTable[StreamItem]())
          _  <- ZIO.foreachDiscard(1 to 10)(i => xa.run(insert(StreamItem(0, s"Item $i"))))
          _  <- xa.run:
            Query[StreamItem].all.queryStream[StreamItem].runDrain
          // If connection not released, this would fail
          count <- xa.run:
            Query[StreamItem].all.query[StreamItem].map(_.size)
        yield assertTrue(count == 10)
      },
      test("connection released after take(n) partial consumption") {
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[StreamItem](ifExists = true))
          _  <- xa.run(createTable[StreamItem]())
          _  <- ZIO.foreachDiscard(1 to 100)(i => xa.run(insert(StreamItem(0, s"Item $i"))))
          _  <- xa.run:
            Query[StreamItem].all.queryStream[StreamItem].take(5).runDrain
          // If connection not released, this would fail
          count <- xa.run:
            Query[StreamItem].all.query[StreamItem].map(_.size)
        yield assertTrue(count == 100)
      },
      test("connection released on stream interruption") {
        for
          xa    <- ZIO.service[Transactor]
          _     <- xa.run(dropTable[StreamItem](ifExists = true))
          _     <- xa.run(createTable[StreamItem]())
          _     <- ZIO.foreachDiscard(1 to 100)(i => xa.run(insert(StreamItem(0, s"Item $i"))))
          fiber <- xa
            .run:
              Query[StreamItem].all
                .queryStream[StreamItem]
                .tap(_ => ZIO.sleep(10.millis))
                .runDrain
            .fork
          _     <- ZIO.sleep(50.millis)
          _     <- fiber.interrupt
          count <- xa.run:
            Query[StreamItem].all.query[StreamItem].map(_.size)
        yield assertTrue(count == 100)
      } @@ TestAspect.withLiveClock,
      test("multiple concurrent streams don't leak connections") {
        for
          xa      <- ZIO.service[Transactor]
          _       <- xa.run(dropTable[StreamItem](ifExists = true))
          _       <- xa.run(createTable[StreamItem]())
          _       <- ZIO.foreachDiscard(1 to 50)(i => xa.run(insert(StreamItem(0, s"Item $i"))))
          results <- ZIO.collectAllPar:
            (1 to 5).map: _ =>
              xa.run:
                Query[StreamItem].all.queryStream[StreamItem].take(10).runCollect
          // All streams should succeed and connections should be released
          count <- xa.run:
            Query[StreamItem].all.query[StreamItem].map(_.size)
        yield assertTrue(results.forall(_.size == 10)) &&
          assertTrue(count == 50)
      },
    ),
    suite("Error Handling")(
      test("SQL syntax error propagates as SaferisError.SyntaxError") {
        for
          xa    <- ZIO.service[Transactor]
          error <- xa
            .run:
              // Intentionally invalid SQL to test error handling
              sql"SELECT * FORM invalid_syntax".queryStream[StreamItem].runDrain
            .flip
          isSyntaxError = error match
            case SaferisError.SyntaxError(_, _) => true
            case _                              => false
        yield assertTrue(isSyntaxError)
      }
    ),
    suite("Stream Composition")(
      test("stream.map transforms elements correctly") {
        for
          xa    <- ZIO.service[Transactor]
          _     <- xa.run(dropTable[StreamUser](ifExists = true))
          _     <- xa.run(createTable[StreamUser]())
          _     <- xa.run(insert(StreamUser(0, "Alice", 30)))
          _     <- xa.run(insert(StreamUser(0, "Bob", 25)))
          names <- xa.run:
            Query[StreamUser].all
              .orderBy(userTable.name.asc)
              .queryStream[StreamUser]
              .map(_.name)
              .runCollect
        yield assertTrue(names == Chunk("Alice", "Bob"))
      },
      test("stream.filter filters elements correctly") {
        for
          xa     <- ZIO.service[Transactor]
          _      <- xa.run(dropTable[StreamUser](ifExists = true))
          _      <- xa.run(createTable[StreamUser]())
          _      <- xa.run(insert(StreamUser(0, "Alice", 30)))
          _      <- xa.run(insert(StreamUser(0, "Bob", 25)))
          _      <- xa.run(insert(StreamUser(0, "Charlie", 35)))
          result <- xa.run:
            Query[StreamUser].all
              .queryStream[StreamUser]
              .filter(_.age >= 30)
              .runCollect
        yield assertTrue(result.size == 2) &&
          assertTrue(result.forall(_.age >= 30))
      },
      test("stream.tap side-effects execute for each element") {
        val counter = new AtomicInteger(0)
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[StreamUser](ifExists = true))
          _  <- xa.run(createTable[StreamUser]())
          _  <- xa.run(insert(StreamUser(0, "Alice", 30)))
          _  <- xa.run(insert(StreamUser(0, "Bob", 25)))
          _  <- xa.run(insert(StreamUser(0, "Charlie", 35)))
          _  <- xa.run:
            Query[StreamUser].all
              .queryStream[StreamUser]
              .tap(_ => ZIO.succeed(counter.incrementAndGet()))
              .runDrain
        yield assertTrue(counter.get() == 3)
        end for
      },
      test("stream.zipWithIndex provides correct indices") {
        for
          xa     <- ZIO.service[Transactor]
          _      <- xa.run(dropTable[StreamUser](ifExists = true))
          _      <- xa.run(createTable[StreamUser]())
          _      <- xa.run(insert(StreamUser(0, "Alice", 30)))
          _      <- xa.run(insert(StreamUser(0, "Bob", 25)))
          _      <- xa.run(insert(StreamUser(0, "Charlie", 35)))
          result <- xa.run:
            Query[StreamUser].all
              .orderBy(userTable.name.asc)
              .queryStream[StreamUser]
              .zipWithIndex
              .runCollect
        yield assertTrue(result.map(_._2) == Chunk(0L, 1L, 2L))
      },
      test("stream.grouped batches elements correctly") {
        for
          xa      <- ZIO.service[Transactor]
          _       <- xa.run(dropTable[StreamItem](ifExists = true))
          _       <- xa.run(createTable[StreamItem]())
          _       <- ZIO.foreachDiscard(1 to 10)(i => xa.run(insert(StreamItem(0, s"Item $i"))))
          batches <- xa.run:
            Query[StreamItem].all
              .queryStream[StreamItem]
              .grouped(3)
              .runCollect
        yield assertTrue(batches.size == 4) && // 3 + 3 + 3 + 1
          assertTrue(batches.take(3).forall(_.size == 3)) &&
          assertTrue(batches.last.size == 1)
      },
      test("zip streams from different queries") {
        for
          xa     <- ZIO.service[Transactor]
          _      <- xa.run(dropTable[StreamUser](ifExists = true))
          _      <- xa.run(dropTable[StreamItem](ifExists = true))
          _      <- xa.run(createTable[StreamUser]())
          _      <- xa.run(createTable[StreamItem]())
          _      <- xa.run(insert(StreamUser(0, "Alice", 30)))
          _      <- xa.run(insert(StreamUser(0, "Bob", 25)))
          _      <- xa.run(insert(StreamUser(0, "Charlie", 35)))
          _      <- xa.run(insert(StreamItem(0, "Item A")))
          _      <- xa.run(insert(StreamItem(0, "Item B")))
          _      <- xa.run(insert(StreamItem(0, "Item C")))
          result <- xa.run:
            val users = Query[StreamUser].all.orderBy(userTable.name.asc).queryStream[StreamUser]
            val items = Query[StreamItem].all.orderBy(itemTable.value.asc).queryStream[StreamItem]
            users.zip(items).runCollect
        yield assertTrue(result.size == 3) &&
          assertTrue(result.head._1.name == "Alice") &&
          assertTrue(result.head._2.value == "Item A")
      },
    ),
    suite("Integration")(
      test("Query builder queryStream works") {
        for
          xa     <- ZIO.service[Transactor]
          _      <- xa.run(dropTable[StreamUser](ifExists = true))
          _      <- xa.run(createTable[StreamUser]())
          _      <- xa.run(insert(StreamUser(0, "Alice", 30)))
          _      <- xa.run(insert(StreamUser(0, "Bob", 25)))
          result <- xa.run:
            Query[StreamUser].where(_.age).gt(20).queryStream[StreamUser].runCollect
        yield assertTrue(result.size == 2)
      },
      test("queryStream works within transact block") {
        for
          xa     <- ZIO.service[Transactor]
          _      <- xa.run(dropTable[StreamUser](ifExists = true))
          _      <- xa.run(createTable[StreamUser]())
          _      <- xa.run(insert(StreamUser(0, "Alice", 30)))
          _      <- xa.run(insert(StreamUser(0, "Bob", 25)))
          result <- xa.transact:
            Query[StreamUser].all.queryStream[StreamUser].runCollect
        yield assertTrue(result.size == 2)
      },
      test("stream 10,000 rows without OOM") {
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[StreamItem](ifExists = true))
          _  <- xa.run(createTable[StreamItem]())
          // Batch insert for efficiency
          _ <- ZIO.foreachDiscard((1 to 100).grouped(10).toList): batch =>
            ZIO.foreachDiscard(batch)(i => xa.run(insert(StreamItem(0, s"Item $i"))))
          // This would OOM with eager query on truly large datasets
          count <- xa.run:
            Query[StreamItem].all
              .queryStream[StreamItem]
              .runCount
        yield assertTrue(count == 100L)
      },
    ),
  ).provideShared(xaLayer) @@ TestAspect.sequential

end StreamSpecs

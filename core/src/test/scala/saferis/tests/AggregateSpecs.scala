package saferis.tests

import saferis.*
import saferis.tests.PostgresTestContainer.DataSourceProvider
import zio.*
import zio.test.*

object AggregateSpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default

  // Test table for aggregates
  @tableName("aggregate_test")
  final case class EventRow(
      @generated @key id: Int,
      @label("instance_id") instanceId: String,
      @label("sequence_nr") sequenceNr: Long,
      amount: BigDecimal,
  ) derives Table

  // ============================================================================
  // SQL Generation Tests (Unit Tests)
  // ============================================================================

  val sqlGenerationTests = suite("SQL Generation")(
    suite("Basic Aggregates")(
      test("max generates correct SQL"):
        val frag = Query[EventRow]
          .where(_.instanceId)
          .eq("test-1")
          .selectAggregate(_.sequenceNr)(_.max)
          .build
        assertTrue(frag.sql.contains("select max(sequence_nr) from aggregate_test")) &&
        assertTrue(frag.sql.contains("where"))
      ,
      test("min generates correct SQL"):
        val frag = Query[EventRow]
          .where(_.instanceId)
          .eq("test-1")
          .selectAggregate(_.sequenceNr)(_.min)
          .build
        assertTrue(frag.sql.contains("select min(sequence_nr) from aggregate_test"))
      ,
      test("sum generates correct SQL"):
        val frag = Query[EventRow]
          .where(_.instanceId)
          .eq("test-1")
          .selectAggregate(_.amount)(_.sum)
          .build
        assertTrue(frag.sql.contains("select sum(amount) from aggregate_test"))
      ,
      test("count generates correct SQL"):
        val frag = Query[EventRow]
          .where(_.instanceId)
          .eq("test-1")
          .selectAggregate(_.sequenceNr)(_.count)
          .build
        assertTrue(frag.sql.contains("select count(sequence_nr) from aggregate_test"))
      ,
      test("countAll generates correct SQL"):
        val frag = Query[EventRow]
          .where(_.instanceId)
          .eq("test-1")
          .selectAggregate(countAll)
          .build
        assertTrue(frag.sql.contains("select count(*) from aggregate_test")),
    ),
    suite("COALESCE")(
      test("max with coalesce generates correct SQL"):
        val frag = Query[EventRow]
          .where(_.instanceId)
          .eq("test-1")
          .selectAggregate(_.sequenceNr)(_.max.coalesce(0L))
          .build
        assertTrue(frag.sql.contains("select coalesce(max(sequence_nr), ?) from aggregate_test")) &&
        assertTrue(frag.writes.size == 2) // instanceId + default value
      ,
      test("sum with coalesce generates correct SQL"):
        val frag = Query[EventRow]
          .where(_.instanceId)
          .eq("test-1")
          .selectAggregate(_.amount)(_.sum.coalesce(BigDecimal(0)))
          .build
        assertTrue(frag.sql.contains("select coalesce(sum(amount), ?) from aggregate_test")),
    ),
  )

  // ============================================================================
  // Integration Tests (with PostgreSQL)
  // ============================================================================

  val integrationTests = suite("Integration")(
    test("max returns correct value"):
      for
        xa     <- ZIO.service[Transactor]
        _      <- xa.run(ddl.createTable[EventRow]())
        _      <- xa.run(dml.insert(EventRow(-1, "test-1", 1L, BigDecimal(100))))
        _      <- xa.run(dml.insert(EventRow(-1, "test-1", 5L, BigDecimal(200))))
        _      <- xa.run(dml.insert(EventRow(-1, "test-1", 3L, BigDecimal(150))))
        result <- xa.run(
          Query[EventRow]
            .where(_.instanceId)
            .eq("test-1")
            .selectAggregate(_.sequenceNr)(_.max)
            .queryValue[Long]
        )
        _ <- xa.run(ddl.dropTable[EventRow]())
      yield assertTrue(result.contains(5L))
    ,
    test("min returns correct value"):
      for
        xa     <- ZIO.service[Transactor]
        _      <- xa.run(ddl.createTable[EventRow]())
        _      <- xa.run(dml.insert(EventRow(-1, "test-1", 1L, BigDecimal(100))))
        _      <- xa.run(dml.insert(EventRow(-1, "test-1", 5L, BigDecimal(200))))
        _      <- xa.run(dml.insert(EventRow(-1, "test-1", 3L, BigDecimal(150))))
        result <- xa.run(
          Query[EventRow]
            .where(_.instanceId)
            .eq("test-1")
            .selectAggregate(_.sequenceNr)(_.min)
            .queryValue[Long]
        )
        _ <- xa.run(ddl.dropTable[EventRow]())
      yield assertTrue(result.contains(1L))
    ,
    test("sum returns correct value"):
      for
        xa     <- ZIO.service[Transactor]
        _      <- xa.run(ddl.createTable[EventRow]())
        _      <- xa.run(dml.insert(EventRow(-1, "test-1", 1L, BigDecimal(100))))
        _      <- xa.run(dml.insert(EventRow(-1, "test-1", 2L, BigDecimal(200))))
        _      <- xa.run(dml.insert(EventRow(-1, "test-1", 3L, BigDecimal(150))))
        result <- xa.run(
          Query[EventRow]
            .where(_.instanceId)
            .eq("test-1")
            .selectAggregate(_.amount)(_.sum)
            .queryValue[BigDecimal]
        )
        _ <- xa.run(ddl.dropTable[EventRow]())
      yield assertTrue(result.contains(BigDecimal(450)))
    ,
    test("count returns correct value"):
      for
        xa     <- ZIO.service[Transactor]
        _      <- xa.run(ddl.createTable[EventRow]())
        _      <- xa.run(dml.insert(EventRow(-1, "test-1", 1L, BigDecimal(100))))
        _      <- xa.run(dml.insert(EventRow(-1, "test-1", 2L, BigDecimal(200))))
        _      <- xa.run(dml.insert(EventRow(-1, "test-2", 1L, BigDecimal(50))))
        result <- xa.run(
          Query[EventRow]
            .where(_.instanceId)
            .eq("test-1")
            .selectAggregate(countAll)
            .queryValue[Long]
        )
        _ <- xa.run(ddl.dropTable[EventRow]())
      yield assertTrue(result.contains(2L))
    ,
    test("max with coalesce returns default for empty result"):
      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run(ddl.createTable[EventRow]())
        // No rows for "nonexistent"
        result <- xa.run(
          Query[EventRow]
            .where(_.instanceId)
            .eq("nonexistent")
            .selectAggregate(_.sequenceNr)(_.max.coalesce(0L))
            .queryValue[Long]
        )
        _ <- xa.run(ddl.dropTable[EventRow]())
      yield assertTrue(result.contains(0L))
    ,
    test("max with coalesce returns actual value when rows exist"):
      for
        xa     <- ZIO.service[Transactor]
        _      <- xa.run(ddl.createTable[EventRow]())
        _      <- xa.run(dml.insert(EventRow(-1, "test-1", 42L, BigDecimal(100))))
        result <- xa.run(
          Query[EventRow]
            .where(_.instanceId)
            .eq("test-1")
            .selectAggregate(_.sequenceNr)(_.max.coalesce(0L))
            .queryValue[Long]
        )
        _ <- xa.run(ddl.dropTable[EventRow]())
      yield assertTrue(result.contains(42L)),
  ).provideShared(xaLayer) @@ TestAspect.sequential

  override def spec = suite("AggregateSpecs")(
    sqlGenerationTests,
    integrationTests,
  )
end AggregateSpecs

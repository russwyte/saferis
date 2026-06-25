# Aggregate Functions

[← Back to index](index.md)

Saferis provides type-safe aggregate functions with the `selectAggregate` method.

## Basic Aggregates

```scala marklit:silent,id=agg
import saferis.*
import saferis.postgres.given
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("event_rows")
case class EventRow(
  @generated @key id: Int,
  instanceId: String,
  sequenceNr: Long,
  amount: BigDecimal
) derives Table
```

```scala marklit:extends=agg
// MAX aggregate
println(Query[EventRow]
  .where(_.instanceId).eq("instance-1")
  .selectAggregate(_.sequenceNr)(_.max)
  .build.sql)
```

```scala marklit:extends=agg
// MIN aggregate
println(Query[EventRow]
  .where(_.instanceId).eq("instance-1")
  .selectAggregate(_.amount)(_.min)
  .build.sql)
```

```scala marklit:extends=agg
// SUM aggregate
println(Query[EventRow]
  .where(_.instanceId).eq("instance-1")
  .selectAggregate(_.amount)(_.sum)
  .build.sql)
```

```scala marklit:extends=agg
// COUNT on a column
println(Query[EventRow]
  .where(_.instanceId).eq("instance-1")
  .selectAggregate(_.sequenceNr)(_.count)
  .build.sql)
```

```scala marklit:extends=agg
// COUNT(*) - count all rows
println(Query[EventRow]
  .where(_.instanceId).eq("instance-1")
  .selectAggregate(countAll)
  .build.sql)
```

## COALESCE for Default Values

Handle NULL results from aggregates with `coalesce`:

```scala marklit:extends=agg
// MAX with COALESCE - returns 0 if no rows match
println(Query[EventRow]
  .where(_.instanceId).eq("instance-1")
  .selectAggregate(_.sequenceNr)(_.max.coalesce(0L))
  .build.sql)
```

```scala marklit:extends=agg
// SUM with COALESCE
println(Query[EventRow]
  .where(_.instanceId).eq("nonexistent")
  .selectAggregate(_.amount)(_.sum.coalesce(BigDecimal(0)))
  .build.sql)
```

## Executing Aggregate Queries

Use `queryValue[T]` to get the aggregate result:

```scala marklit:zio-app,extends=agg
xa.run(for
  _ <- ddl.createTable[EventRow](ifNotExists = true)
  _ <- dml.insert(EventRow(-1, "test", 1L, BigDecimal(100)))
  _ <- dml.insert(EventRow(-1, "test", 5L, BigDecimal(200)))
  _ <- dml.insert(EventRow(-1, "test", 3L, BigDecimal(150)))
  maxSeq <- Query[EventRow]
    .where(_.instanceId).eq("test")
    .selectAggregate(_.sequenceNr)(_.max.coalesce(0L))
    .queryValue[Long]
  total <- Query[EventRow]
    .where(_.instanceId).eq("test")
    .selectAggregate(_.amount)(_.sum)
    .queryValue[BigDecimal]
  count <- Query[EventRow]
    .where(_.instanceId).eq("test")
    .selectAggregate(countAll)
    .queryValue[Long]
yield (maxSeq, total, count)).debug("aggregates")
```

## Available Aggregate Functions

| Function | Description |
|----------|-------------|
| `_.max` | Maximum value |
| `_.min` | Minimum value |
| `_.sum` | Sum of values |
| `_.count` | Count of non-null values |
| `_.avg` | Average value |
| `countAll` | Count all rows (`COUNT(*)`) |
| `.coalesce(default)` | Return default if NULL |

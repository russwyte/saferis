# Aggregate Functions

[← Back to index](index.md)

Saferis provides type-safe aggregate functions with the `selectAggregate` method.

## Basic Aggregates

```scala
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

```scala
// MAX aggregate
println(Query[EventRow]
  .where(_.instanceId).eq("instance-1")
  .selectAggregate(_.sequenceNr)(_.max)
  .build.sql)
```
```
// Scala 3.3.8
select max(sequenceNr) from event_rows as event_rows_ref_1 where event_rows_ref_1.instanceId = ?
```

```scala
// MIN aggregate
println(Query[EventRow]
  .where(_.instanceId).eq("instance-1")
  .selectAggregate(_.amount)(_.min)
  .build.sql)
```
```
// Scala 3.3.8
select min(amount) from event_rows as event_rows_ref_1 where event_rows_ref_1.instanceId = ?
```

```scala
// SUM aggregate
println(Query[EventRow]
  .where(_.instanceId).eq("instance-1")
  .selectAggregate(_.amount)(_.sum)
  .build.sql)
```
```
// Scala 3.3.8
select sum(amount) from event_rows as event_rows_ref_1 where event_rows_ref_1.instanceId = ?
```

```scala
// COUNT on a column
println(Query[EventRow]
  .where(_.instanceId).eq("instance-1")
  .selectAggregate(_.sequenceNr)(_.count)
  .build.sql)
```
```
// Scala 3.3.8
select count(sequenceNr) from event_rows as event_rows_ref_1 where event_rows_ref_1.instanceId = ?
```

```scala
// COUNT(*) - count all rows
println(Query[EventRow]
  .where(_.instanceId).eq("instance-1")
  .selectAggregate(countAll)
  .build.sql)
```
```
// Scala 3.3.8
select count(*) from event_rows as event_rows_ref_1 where event_rows_ref_1.instanceId = ?
```

## COALESCE for Default Values

Handle NULL results from aggregates with `coalesce`:

```scala
// MAX with COALESCE - returns 0 if no rows match
println(Query[EventRow]
  .where(_.instanceId).eq("instance-1")
  .selectAggregate(_.sequenceNr)(_.max.coalesce(0L))
  .build.sql)
```
```
// Scala 3.3.8
select coalesce(max(sequenceNr), ?) from event_rows as event_rows_ref_1 where event_rows_ref_1.instanceId = ?
```

```scala
// SUM with COALESCE
println(Query[EventRow]
  .where(_.instanceId).eq("nonexistent")
  .selectAggregate(_.amount)(_.sum.coalesce(BigDecimal(0)))
  .build.sql)
```
```
// Scala 3.3.8
select coalesce(sum(amount), ?) from event_rows as event_rows_ref_1 where event_rows_ref_1.instanceId = ?
```

## Executing Aggregate Queries

Use `queryValue[T]` to get the aggregate result:

```scala
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

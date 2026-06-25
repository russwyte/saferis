# Streaming with ZStream

[← Back to index](index.md)

For large result sets, Saferis provides `queryStream` which returns a `ZStream` that lazily iterates through results. This is ideal when you need to process rows one at a time without loading the entire result set into memory.

## Basic Streaming

Use `queryStream` instead of `query` to get a stream:

```scala marklit:silent,id=stream
import saferis.*
import saferis.postgres.given
import saferis.docs.DocsTransactor.transactor as xa
import zio.*
import zio.stream.*

@tableName("stream_events")
case class StreamEvent(@generated @key id: Int, name: String, payload: String) derives Table

val events = Table[StreamEvent]
```

```scala marklit:zio-app,extends=stream
xa.run(for
  _ <- ddl.createTable[StreamEvent](ifNotExists = true)
  _ <- dml.insert(StreamEvent(-1, "event1", "data1"))
  _ <- dml.insert(StreamEvent(-1, "event2", "data2"))
  _ <- dml.insert(StreamEvent(-1, "event3", "data3"))
  // Stream returns a ZStream, use runCollect to materialize
  result <- Query[StreamEvent].all.queryStream[StreamEvent].runCollect
yield result).debug("result")
```

## Stream vs Eager Query

Both `query` and `queryStream` return the same data, but with different memory characteristics:

| Method | Return Type | Memory Usage | Best For |
|--------|-------------|--------------|----------|
| `.query[T]` | `Chunk[T]` | All rows loaded at once | Small to medium result sets |
| `.queryStream[T]` | `ZStream[..., T]` | One row at a time | Large result sets, real-time processing |

## Lazy Evaluation

Streams are evaluated lazily - rows are only fetched as they're consumed:

```scala marklit:zio-app,extends=stream
xa.run(for
  _ <- ddl.createTable[StreamEvent](ifNotExists = true)
  _ <- dml.insert(StreamEvent(-1, "lazy1", "data"))
  _ <- dml.insert(StreamEvent(-1, "lazy2", "data"))
  _ <- dml.insert(StreamEvent(-1, "lazy3", "data"))
  // Only fetches 2 rows from the database, even though more exist
  first2 <- Query[StreamEvent].all.queryStream[StreamEvent].take(2).runCollect
yield first2).debug("first2")
```

## Stream Composition

ZStream provides powerful composition operators:

```scala marklit:zio-app,extends=stream
xa.run(for
  _ <- ddl.createTable[StreamEvent](ifNotExists = true)
  _ <- dml.insert(StreamEvent(-1, "event1", "data1"))
  _ <- dml.insert(StreamEvent(-1, "event2", "data2"))
  _ <- dml.insert(StreamEvent(-1, "other", "data3"))
  // Map, filter, and transform streams
  names <- Query[StreamEvent].all
    .queryStream[StreamEvent]
    .map(_.name)
    .filter(_.startsWith("event"))
    .runCollect

  // Batch processing with grouped
  batches <- Query[StreamEvent].all
    .queryStream[StreamEvent]
    .grouped(2)
    .runCollect
yield (names, batches.map(_.size))).debug("composition")
```

## Resource Safety

The database connection is automatically released when the stream completes, errors, or is interrupted:

```scala marklit:compile-only
import saferis.*
import zio.*
import zio.stream.*

@tableName("resource_events")
case class ResourceEvent(@generated @key id: Int, data: String) derives Table

// Connection released after stream fully consumed
Query[ResourceEvent].all.queryStream[ResourceEvent].runDrain

// Connection released after take(n) partial consumption
Query[ResourceEvent].all.queryStream[ResourceEvent].take(10).runDrain

// Connection released on stream interruption
val fiber = Query[ResourceEvent].all
  .queryStream[ResourceEvent]
  .tap(_ => ZIO.sleep(10.millis))
  .runDrain
  .fork
// fiber.interrupt releases the connection
```

## Streaming with Query Builder

All query builder methods support streaming:

```scala marklit:zio-app,extends=stream
xa.run(for
  _ <- ddl.createTable[StreamEvent](ifNotExists = true)
  _ <- dml.insert(StreamEvent(-1, "event1", "data1"))
  _ <- dml.insert(StreamEvent(-1, "event2", "data2"))
  result <- Query[StreamEvent]
    .where(_.name).eq("event1")
    .orderBy(events.id.asc)
    .queryStream[StreamEvent]
    .runCollect
yield result).debug("result")
```

## Streaming with Mutations (RETURNING)

For dialects that support RETURNING (PostgreSQL, SQLite), you can stream returned rows:

```scala marklit:zio-app,extends=stream
xa.run(for
  _ <- ddl.createTable[StreamEvent](ifNotExists = true)
  _ <- dml.insert(StreamEvent(-1, "event1", "data1"))
  _ <- dml.insert(StreamEvent(-1, "keep", "data2"))
  deleted <- Delete[StreamEvent]
    .where(_.name).eq("event1")
    .returningAs
    .queryStream
    .runCollect
yield deleted).debug("deleted")
```

## Combining Streams

You can compose streams from different queries:

```scala marklit:silent,id=stream_zip
import saferis.*
import saferis.postgres.given
import saferis.docs.DocsTransactor.transactor as xa
import zio.*
import zio.stream.*

@tableName("zip_users")
case class ZipUser(@generated @key id: Int, name: String) derives Table

@tableName("zip_items")
case class ZipItem(@generated @key id: Int, value: String) derives Table

val users = Table[ZipUser]
val items = Table[ZipItem]
```

```scala marklit:zio-app,extends=stream_zip
xa.run(for
  _ <- ddl.createTable[ZipUser](ifNotExists = true)
  _ <- ddl.createTable[ZipItem](ifNotExists = true)
  _ <- dml.insert(ZipUser(-1, "Alice"))
  _ <- dml.insert(ZipUser(-1, "Bob"))
  _ <- dml.insert(ZipItem(-1, "Item A"))
  _ <- dml.insert(ZipItem(-1, "Item B"))
  // Zip two streams together
  zipped <- {
    val userStream = Query[ZipUser].all.orderBy(users.name.asc).queryStream[ZipUser]
    val itemStream = Query[ZipItem].all.orderBy(items.value.asc).queryStream[ZipItem]
    userStream.zip(itemStream).runCollect
  }
yield zipped.map((u, i) => s"${u.name} -> ${i.value}")).debug("zipped")
```

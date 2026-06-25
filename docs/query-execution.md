# Query Execution Methods

[← Back to index](index.md)

`SqlFragment` provides several methods for executing queries:

| Method | Returns | Description |
|--------|---------|-------------|
| `.query[T]` | `Chunk[T]` | Execute query, return all matching rows |
| `.queryOne[T]` | `Option[T]` | Execute query, return first row if exists |
| `.queryStream[T]` | `ZStream[..., T]` | Execute query, lazily stream rows (see [Streaming with ZStream](streaming.md)) |
| `.queryValue[T]` | `Option[T]` | Execute query, return single value from first column |
| `.execute` / `.dml` | `Int` | Execute DML statement, return affected row count |

## queryValue for Single Values

Use `.queryValue[T]` for queries that return a single value (aggregates, counts, etc.):

```scala
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("value_items")
case class ValueItem(@generated @key id: Int, name: String, price: Double) derives Table
val items = Table[ValueItem]
```

```scala
xa.run(for
  _ <- ddl.createTable[ValueItem](ifNotExists = true)
  _ <- dml.insert(ValueItem(-1, "Widget", 10.0))
  _ <- dml.insert(ValueItem(-1, "Gadget", 25.0))
  _ <- dml.insert(ValueItem(-1, "Gizmo", 15.0))
  count    <- sql"SELECT COUNT(*) FROM $items".queryValue[Int]
  maxPrice <- sql"SELECT MAX(${items.price}) FROM $items".queryValue[Double]
  avgPrice <- sql"SELECT AVG(${items.price}) FROM $items".queryValue[Double]
yield (count, maxPrice, avgPrice)).debug("aggregates")
```

## execute for Mutation Builders

The mutation builders (Insert, Update, Delete) support `.build.execute` to run the statement:

```scala
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("exec_items")
case class ExecItem(@generated @key id: Int, name: String, quantity: Int) derives Table
```

```scala
xa.run(for
  _ <- ddl.createTable[ExecItem](ifNotExists = true)
  // Insert using builder and execute
  insertCount <- Insert[ExecItem]
    .value(_.name, "Widget")
    .value(_.quantity, 10)
    .build
    .execute
  // Update using builder and execute
  updateCount <- Update[ExecItem]
    .set(_.quantity, 20)
    .where(_.name).eq("Widget")
    .build
    .execute
  // Verify
  result <- sql"SELECT * FROM ${Table[ExecItem]}".query[ExecItem]
yield (insertCount, updateCount, result)).debug("execute")
```

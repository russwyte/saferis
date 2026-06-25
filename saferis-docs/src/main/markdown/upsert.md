# Conditional Upsert DSL

[← Back to index](index.md)

Saferis provides a type-safe UPSERT (INSERT ... ON CONFLICT) builder for PostgreSQL.

## Basic Upsert

```scala marklit:silent,id=upsert_lock
import saferis.*
import zio.*

@tableName("upsert_locks")
case class UpsertLock(
  @key instanceId: String,
  nodeId: String,
  acquiredAt: java.time.Instant,
  expiresAt: java.time.Instant
) derives Table
```

```scala marklit:extends=upsert_lock
// Basic upsert - update all non-key columns on conflict
val now = java.time.Instant.now()
val lock = UpsertLock("instance-1", "node-1", now, now.plusSeconds(60))

println(Upsert[UpsertLock]
  .values(lock)
  .onConflict(_.instanceId)
  .doUpdateAll
  .build.sql)
```

## Conditional Upsert with WHERE

Add conditions to control when the update happens:

```scala marklit:extends=upsert_lock
val now = java.time.Instant.now()
val lock = UpsertLock("instance-1", "node-1", now, now.plusSeconds(60))

// Only update if the existing row has expired
println(Upsert[UpsertLock]
  .values(lock)
  .onConflict(_.instanceId)
  .doUpdateAll
  .where(_.expiresAt).lt(now)
  .build.sql)
```

This generates: `INSERT INTO ... ON CONFLICT (instance_id) DO UPDATE SET ... WHERE upsert_locks.expires_at < ?`

## Reference EXCLUDED Pseudo-Table

Use `.eqExcluded` to compare with the value being inserted:

```scala marklit:extends=upsert_lock
val now = java.time.Instant.now()
val lock = UpsertLock("instance-1", "node-1", now, now.plusSeconds(60))

// Update only if we own the lock (same nodeId) OR it has expired
println(Upsert[UpsertLock]
  .values(lock)
  .onConflict(_.instanceId)
  .doUpdateAll
  .where(_.expiresAt).lt(now)
  .or(_.nodeId).eqExcluded
  .build.sql)
```

The `.eqExcluded` generates `table.column = excluded.column`, referencing the value from the INSERT.

## Upsert with DO NOTHING

Skip the update entirely on conflict:

```scala marklit:extends=upsert_lock
val now = java.time.Instant.now()
val lock = UpsertLock("instance-1", "node-1", now, now.plusSeconds(60))

// Insert only if no conflict
println(Upsert[UpsertLock]
  .values(lock)
  .onConflict(_.instanceId)
  .doNothing
  .build.sql)
```

## Upsert with RETURNING

Get the resulting row back:

```scala marklit:extends=upsert_lock
val now = java.time.Instant.now()
val lock = UpsertLock("instance-1", "node-1", now, now.plusSeconds(60))

// Upsert with RETURNING - returns ReturningQuery which wraps SqlFragment
println(Upsert[UpsertLock]
  .values(lock)
  .onConflict(_.instanceId)
  .doUpdateAll
  .returning
  .build.sql)
```

```scala marklit:extends=upsert_lock
val now = java.time.Instant.now()
val lock = UpsertLock("instance-1", "node-1", now, now.plusSeconds(60))

// Type-safe returning with WHERE clause
println(Upsert[UpsertLock]
  .values(lock)
  .onConflict(_.instanceId)
  .doUpdateAll
  .where(_.expiresAt).lt(now)
  .returning
  .build.sql)
```

## Compound Conflict Columns

Specify multiple columns for the conflict target:

```scala marklit:silent,id=upsert_item
import saferis.*
import zio.*

@tableName("upsert_items")
case class UpsertItem(
  @key tenantId: String,
  @key sku: String,
  name: String,
  quantity: Int
) derives Table
```

```scala marklit:extends=upsert_item
// Conflict on compound key
val item = UpsertItem("tenant-1", "SKU-001", "Widget", 10)

println(Upsert[UpsertItem]
  .values(item)
  .onConflict(_.tenantId).and(_.sku)
  .doUpdateAll
  .build.sql)
```

## Full Atomic Lock Acquisition Example

Here's a complete example of atomic lock acquisition, run against the database:

```scala marklit:silent,id=upsert_atomic
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("atomic_locks")
case class AtomicLock(
  @key instanceId: String,
  nodeId: String,
  acquiredAt: java.time.Instant,
  expiresAt: java.time.Instant
) derives Table
```

```scala marklit:zio-app,extends=upsert_atomic
xa.run(for
  _   <- ddl.createTable[AtomicLock](ifNotExists = true)
  now = java.time.Instant.now()
  lock = AtomicLock("lock-1", "node-A", now, now.plusSeconds(60))

  // First acquisition - should succeed
  result1 <- Upsert[AtomicLock]
    .values(lock)
    .onConflict(_.instanceId)
    .doUpdateAll
    .where(_.expiresAt).lt(now)         // Only if expired
    .or(_.nodeId).eqExcluded            // Or we own it
    .returning
    .queryOne

  // Second acquisition by same node - should succeed (we own it)
  result2 <- Upsert[AtomicLock]
    .values(lock.copy(expiresAt = now.plusSeconds(120)))
    .onConflict(_.instanceId)
    .doUpdateAll
    .where(_.expiresAt).lt(now)
    .or(_.nodeId).eqExcluded
    .returning
    .queryOne

yield (result1, result2)).debug("locks")
```

## Capability Requirements

The Upsert DSL requires `UpsertSupport`:
- PostgreSQL: Full support
- MySQL: Not supported (use `ON DUPLICATE KEY UPDATE` syntax via raw SQL)
- SQLite: Not supported

For `returningAs`, also requires `ReturningSupport`:
- PostgreSQL: Full support
- SQLite: Supported
- MySQL: Not supported

See [Type-Safe Capabilities](capabilities.md) for how these constraints are enforced at compile time.

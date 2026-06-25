# Type-Safe Capabilities

[← Back to index](index.md)

Saferis uses Scala 3's type system to ensure operations are only available when the database supports them.

## Capability Traits

Each dialect mixes in capability traits that enable specific operations:

| Trait | Operations Enabled |
|-------|-------------------|
| `ReturningSupport` | `insertReturning`, `updateReturning`, `deleteReturning` |
| `JsonSupport` | `jsonExtract`, JSON type mappings |
| `ArraySupport` | `arrayContains`, array type mappings |
| `UpsertSupport` | `upsert` |
| `IndexIfNotExistsSupport` | Conditional index creation |

## Using SpecializedDML

The `SpecializedDML` object provides type-safe operations that only compile when the dialect supports them:

```scala marklit:silent,id=cap
import saferis.*
import saferis.docs.DocsTransactor.transactor as xa
import zio.*

@tableName("specialized_items")
case class SpecializedItem(@generated @key id: Int, name: String, category: String) derives Table
```

```scala marklit:zio-app,extends=cap
// Create table and use insertReturning (PostgreSQL supports RETURNING)
xa.run(for
  _        <- ddl.createTable[SpecializedItem](ifNotExists = true)
  inserted <- dml.insertReturning(SpecializedItem(-1, "Widget", "hardware"))
  _        <- dml.insert(SpecializedItem(-1, "Gadget", "electronics"))
  all      <- sql"SELECT * FROM ${Table[SpecializedItem]}".query[SpecializedItem]
yield (inserted, all)).debug("specialized")
```

## Compile-Time Safety

Capabilities are encoded in the dialect's type. You can ask the compiler to prove a
dialect supports a capability with a type ascription on `summon[Dialect]` — each
line below only compiles because PostgreSQL actually mixes in that capability:

```scala marklit:compile-only
import saferis.*
import saferis.postgres.given

// Compile-time evidence: these only typecheck because PostgreSQL provides them.
val _: Dialect & ReturningSupport        = summon[Dialect]
val _: Dialect & JsonSupport             = summon[Dialect]
val _: Dialect & ArraySupport            = summon[Dialect]
val _: Dialect & UpsertSupport           = summon[Dialect]
val _: Dialect & IndexIfNotExistsSupport = summon[Dialect]
```

Operations that require a capability take a `using Dialect & SomeSupport`
parameter. Because the default dialect (PostgreSQL) provides every capability,
`returningAs` compiles out of the box:

```scala marklit:compile-only
import saferis.*

@tableName("pg_items")
case class PgItem(@generated @key id: Int, name: String) derives Table

// PostgreSQL has ReturningSupport — this compiles.
Update[PgItem].set(_.name, "x").where(_.id).eq(1).returningAs.build.sql
```

Switch to a dialect that lacks a capability — for example a SQLite-only program
that tries an `Upsert` (SQLite has no `UpsertSupport`) — and the operation no
longer typechecks. The constraint is part of the method signature, so the mismatch
is caught at compile time rather than failing against the database at runtime.

Available capability-constrained operations in `SpecializedDML`:

| Operation | Required Capability |
|-----------|-------------------|
| `insertReturning` | `ReturningSupport` |
| `updateReturning` | `ReturningSupport` |
| `deleteReturning` | `ReturningSupport` |
| `upsert` | `UpsertSupport` |
| `jsonExtract` | `JsonSupport` |
| `arrayContains` | `ArraySupport` |

## Generic Functions with Capability Constraints

Write functions that require specific capabilities via `using` constraints — the
constraint propagates to every caller, so a function that needs RETURNING can only
be called where the dialect provides it:

```scala marklit:compile-only
import saferis.*

@tableName("returning_items")
case class ReturningItem(@generated @key id: Int, name: String) derives Table

// A helper that only compiles where the dialect provides RETURNING. The
// `Dialect & ReturningSupport` constraint propagates to every caller.
def updateAndReturn(name: String)(using Dialect & ReturningSupport): String =
  Update[ReturningItem].set(_.name, name).where(_.id).eq(1).returningAs.build.sql
```

We've already seen this in action — `insertReturning` works because PostgreSQL provides `ReturningSupport`:

```scala marklit:zio-app,extends=cap
xa.run(for
  _        <- ddl.createTable[SpecializedItem](ifNotExists = true)
  returned <- dml.insertReturning(SpecializedItem(-1, "Capability Demo", "demo"))
yield returned).debug("returned")
```

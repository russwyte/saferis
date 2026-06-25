# SQL Injection Prevention

[← Back to index](index.md)

Saferis is designed from the ground up to prevent SQL injection at multiple levels. This section explains the complete security model.

## The Three Layers of Protection

1. **Parameterized Values** - User data is always bound via prepared statements, never concatenated into SQL
2. **Compile-Time Literal Enforcement** - Table aliases must be string literals known at compile time
3. **Runtime Escaping** - When runtime identifiers are unavoidable, proper escaping is applied

## How the `sql"..."` Interpolator Works

The interpolator analyzes each interpolated expression at compile time and routes it appropriately:

```scala marklit:silent,id=inj_users
import saferis.*
import zio.*

@tableName("users")
case class User(@generated @key id: Int, name: String, email: String) derives Table

val users = Table[User]
val userName = "Alice"  // User input
```

```scala marklit:extends=inj_users
// This is SAFE - userName becomes a prepared statement parameter.
// `.sql` shows the statement actually sent to the database: a `?` placeholder.
println(sql"SELECT * FROM $users WHERE ${users.name} = $userName".sql)
```

The generated SQL uses a `?` placeholder, and the actual value is bound separately - it never touches the SQL string. Even malicious input is harmless:

```scala marklit:extends=inj_users
// Attempted SQL injection - completely harmless: still just a `?`
val malicious = "'; DROP TABLE users; --"
println(sql"SELECT * FROM $users WHERE ${users.name} = $malicious".sql)
```

The malicious string becomes a parameter value (the SQL still contains only a `?`), not part of the SQL syntax. (`.show`, used elsewhere in these docs, inlines the bound values for debugging — handy for reading a query, but it is **not** what gets sent to the database.)

## Table Aliases: Compile-Time Literal Enforcement

Table aliases appear directly in SQL (not as parameters), so they could be injection vectors. Saferis prevents this with a **macro that enforces string literals at compile time**:

```scala marklit:compile-only
import saferis.*

@tableName("users")
case class User(@key id: Int, name: String) derives Table

// These compile - string literals are safe
val u1 = Table[User]("u")           // OK
val u2 = Table[User] as "users"     // OK
val a = Alias("my_alias")           // OK
```

Try to build an alias from a *variable* and the code does not compile — the
**actual compiler error** is shown below the snippet, proving the guard is real
and not just documentation:

```scala marklit:fail
import saferis.*

@tableName("users")
case class User(@key id: Int, name: String) derives Table

// A variable could contain an injection vector — rejected at compile time.
val alias = "u"
val bad = Alias(alias)
```

The compile error guides you to the safe alternative: for runtime identifiers, use
`Placeholder.identifier()` or `dialect.escapeIdentifier()` instead.

This compile-time enforcement means SQL injection via aliases is **impossible** - there's no runtime path for user input to become an alias.

## Runtime Identifiers with `Placeholder.identifier()`

Sometimes you genuinely need runtime-determined identifiers (e.g., dynamic column names from configuration). For these cases, use `Placeholder.identifier()` which applies proper escaping:

```scala marklit:silent,id=inj_ident
import saferis.*
import zio.*

@tableName("users")
case class User(@key id: Int, name: String, email: String) derives Table
val users = Table[User]
```

```scala marklit:extends=inj_ident
// Safe runtime identifier - properly escaped
val columnName = "name"  // Could come from config, NOT user input
println(sql"SELECT ${Placeholder.identifier(columnName)} FROM $users".show)
```

The identifier is escaped using the dialect's quoting rules. For PostgreSQL, this means double-quote escaping:

```scala marklit:extends=inj_ident
// Even problematic identifiers are safely escaped
import saferis.postgres.PostgresDialect
println(PostgresDialect.escapeIdentifier("table"))  // Reserved word
```

```scala marklit:extends=inj_ident
import saferis.postgres.PostgresDialect
println(PostgresDialect.escapeIdentifier("user\"input"))  // Contains quote
```

**Important**: While `Placeholder.identifier()` escapes properly, you should still validate runtime identifiers against an allowlist when possible. Escaping is a defense-in-depth measure, not a replacement for input validation.

## The `Placeholder.raw()` Escape Hatch

For rare cases where you need to embed literal SQL (e.g., database-specific syntax not supported by Saferis), use `Placeholder.raw()`:

```scala marklit:extends=inj_ident
// Raw SQL - use with extreme caution
val trustedSql = "CURRENT_TIMESTAMP"
println(sql"SELECT ${Placeholder.raw(trustedSql)} as now".show)
```

⚠️ **Warning**: `Placeholder.raw()` bypasses all safety mechanisms. Only use it with:
- Hardcoded strings in your source code
- Values from trusted configuration (never user input)
- SQL syntax that Saferis doesn't support natively

Never pass user input to `Placeholder.raw()`.

## JSON Operations: Automatic Escaping

When using JSON operations in the Schema DSL or dialect methods, Saferis automatically escapes single quotes to prevent injection:

```scala marklit:silent,id=inj_json
import saferis.*
import saferis.Schema.*
import zio.*

@tableName("profiles")
case class Profile(@key id: Int, name: String, data: Json[Map[String, String]]) derives Table
```

```scala marklit:extends=inj_json
// JSON key with special characters - automatically escaped
import saferis.postgres.PostgresDialect
println(PostgresDialect.jsonHasKeySql("data", "user's_key"))
```

```scala marklit:extends=inj_json
// Even attempted injection is escaped
import saferis.postgres.PostgresDialect
println(PostgresDialect.jsonHasKeySql("data", "'); DROP TABLE profiles; --"))
```

The single quote in the injection attempt is escaped to `''`, rendering it harmless.

## Security Summary

| Mechanism | What It Protects | How It Works |
|-----------|------------------|--------------|
| Parameterized queries | User data values | Bound via `?` placeholders, never in SQL string |
| Alias macro | Table aliases | Compile-time string literal enforcement |
| `Placeholder.identifier()` | Runtime column/table names | Dialect-specific identifier escaping |
| JSON escaping | JSON keys and paths | Automatic single-quote escaping |
| `Placeholder.raw()` | Escape hatch | Developer takes responsibility |

## Best Practices

1. **Use the `sql"..."` interpolator** for all queries - it handles parameterization automatically
2. **Use literal strings** for table aliases - the compiler enforces this
3. **Validate runtime identifiers** against an allowlist before using `Placeholder.identifier()`
4. **Never use `Placeholder.raw()`** with user input
5. **Prefer the Query builder** for dynamic queries - it's type-safe end-to-end

# Saferis Database Dialect System - Complete Guide

## Overview
Saferis provides a comprehensive database dialect system that supports multiple databases with compile-time type safety. The system has ### PostgreSQL with Full Capabilitie### SQLite with Limited Capabilities
### MySQL with JSON Support

```scala
import saferis.*
import saferis.mysql.{given}
import saferis.SpecializedDML.*

@tableName("users")
case class User(@generated @key id: Int, name: String, data: String) derives Table

val userTable = Table[User]

// JSON operations are available
val jsonData = jsonExtract("user_data", "profile.name")  // JSON support

// Safe SQL construction with proper interpolation
val usersWithData = sql"SELECT * FROM $userTable WHERE ${userTable.data} IS NOT NULL".query[User]

// But RETURNING operations would not compile:
// val user = insertReturning(newUser)                   // Compilation error!
```ort saferis.*
import saferis.sqlite.{given}
import saferis.SpecializedDML.*

@tableName("users") 
case class User(@generated @key id: Int, name: String, email: String) derives Table

val userTable = Table[User]

// RETURNING operations are available
val newUser = User(-1, "John", "john@example.com")
val user = insertReturning(newUser)

// Safe SQL interpolation with table references
val recentUsers = sql"SELECT * FROM $userTable WHERE ${userTable.id} > ${100}".query[User]

// These would NOT compile - SQLite doesn't support these capabilities:
// val jsonData = jsonExtract("data", "user.name")     // Compilation error!
// val upserted = upsert(user, Seq("email"))           // Compilation error!
```t saferis.*
import saferis.postgres.{given}
import saferis.SpecializedDML.*

@tableName("users")
case class User(@generated @key id: Int, name: String, email: String) derives Table

val userTable = Table[User]

// All operations available due to PostgreSQL's comprehensive capability support
val newUser = User(-1, "John", "john@example.com")
val user = insertReturning(newUser)                              // RETURNING support
val updated = updateReturning(user.copy(name = "John Smith"))    // RETURNING support

// Direct SQL with table and column references
val customQuery = sql"SELECT ${userTable.name}, ${userTable.email} FROM $userTable WHERE ${userTable.id} > ${100}"

// JSON operations (PostgreSQL specific)
val jsonData = jsonExtract("profile", "settings.theme")         // JSON support
val arrayMatch = arrayContains("tags", "'scala'")               // Array support

// UPSERT operation
val upserted = upsert(user, Seq("email"))                       // UPSERT support
```from simple column type mapping to a sophisticated trait-based capability system that ensures operations are only available when the underlying database supports them.

## Architecture

### Core Components

1. **`Dialect`** - Base trait providing database-specific type mappings and SQL generation
2. **Capability Traits** - Mix-in traits that provide database-specific features with compile-time safety
3. **`SpecializedDML`** - Type-safe operations only available when dialects support specific capabilities
4. **Database-Specific Dialects** - Implementations for PostgreSQL, MySQL, and SQLite

### Dialect Trait

The base [`Dialect`](src/main/scala/saferis/Dialect.scala) trait provides comprehensive database abstraction with methods for:

- **Core identification**: Database name and version info
- **Type mappings**: Converting JDBC types to database-specific SQL types
- **Auto-increment and primary keys**: Database-specific syntax for generated columns
- **Index operations**: Creating, dropping, and managing indexes
- **Table operations**: Creating, dropping, and truncating tables
- **Column operations**: Adding and dropping columns
- **Identifier escaping**: Database-specific quoting rules

See the [full Dialect trait definition](src/main/scala/saferis/Dialect.scala) for complete method signatures and documentation.

### Capability Traits

The system includes capability traits defined in [`DialectCapabilities.scala`](src/main/scala/saferis/DialectCapabilities.scala) that provide compile-time type safety:

#### `ReturningSupport`
For databases supporting RETURNING clauses in INSERT/UPDATE/DELETE operations.

#### `JsonSupport`
For databases with JSON operations and data types.

#### `ArraySupport`
For databases with array operations and types.

#### `UpsertSupport`
For INSERT ... ON CONFLICT operations (PostgreSQL style upserts).

#### Additional Capabilities
- **`IndexIfNotExistsSupport`** - Conditional index creation
- **`AdvancedAlterTableSupport`** - Complex DDL operations
- **`WindowFunctionSupport`** - Advanced SQL window functions
- **`CommonTableExpressionSupport`** - CTE support

See the [complete capability definitions](src/main/scala/saferis/DialectCapabilities.scala) for detailed method signatures.

## Database Implementations

### PostgreSQL Dialect
**Full-featured implementation with all capabilities:** [`PostgresDialect.scala`](src/main/scala/saferis/postgres/PostgresDialect.scala)

- **Capabilities**: All capabilities supported (most comprehensive)
- **Auto-increment**: Uses `GENERATED ALWAYS AS IDENTITY`
- **JSON**: Native `jsonb` type with `->` and `->>` operators
- **Arrays**: Native array support with `ANY()` operations
- **UPSERT**: Full `ON CONFLICT ... DO UPDATE` support
- **Identifiers**: Double-quoted identifiers

### SQLite Dialect
**Subset of capabilities with SQLite-specific behavior:** [`SQLiteDialect.scala`](src/main/scala/saferis/sqlite/SQLiteDialect.scala)

- **Capabilities**: RETURNING, window functions, CTEs
- **Auto-increment**: Uses `AUTOINCREMENT` 
- **Type system**: Simplified type affinity (most types become `text`, `integer`, `real`, or `blob`)
- **Table operations**: Uses `DELETE FROM` instead of `TRUNCATE`
- **Limitations**: No `DROP COLUMN` support (requires table recreation)
- **Identifiers**: Double-quoted identifiers

### MySQL Dialect
**MySQL-specific features with limited capabilities:** [`MySQLDialect.scala`](src/main/scala/saferis/mysql/MySQLDialect.scala)

- **Capabilities**: JSON support, window functions, CTEs
- **Auto-increment**: Uses `AUTO_INCREMENT`
- **JSON**: `JSON_EXTRACT()` function support
- **Limitations**: No RETURNING clause, limited IF NOT EXISTS support for indexes
- **Identifiers**: Backtick identifiers (`table_name`)

## Data Manipulation and Definition Layers

Saferis provides two main layers for database operations:

### 1. Data Definition Layer (DDL)
The `DataDefinitionLayer` provides dialect-aware DDL operations:

```scala
val ddl = DataDefinitionLayer // short alias

object DataDefinitionLayer:
  // Create tables with dialect-specific SQL
  inline def createTable[A <: Product: Table as table](
      ifNotExists: Boolean = true, 
      createIndexes: Boolean = true
  )(using dialect: Dialect): ZIO[ConnectionProvider & Scope, Throwable, Int]
  
  // Add/drop columns using dialect-specific types  
  inline def addColumn[A <: Product: Table as table, T: Encoder as encoder](columnName: String): ...
  inline def dropColumn[A <: Product: Table as table](columnName: String): ...
  
  // Create indexes with dialect-specific syntax
  inline def createIndex[A <: Product: Table as table](
      indexName: String, 
      columnNames: Seq[String], 
      unique: Boolean = false
  ): ...
```

### 2. Data Manipulation Layer (DML)
There are two DML layers:

#### Basic DML Operations (`DataManipulationLayer`)
Provides basic CRUD operations that work with any dialect:

```scala
val dml = DataManipulationLayer // short alias

object DataManipulationLayer:
  inline def insert[A <: Product: Table as table](a: A): ...
  inline def update[A <: Product: Table as table](a: A): ...
  inline def delete[A <: Product: Table as table](a: A): ...
  
  // RETURNING operations (available on all dialects, but may fail at runtime)
  inline def insertReturning[A <: Product: Table as table](a: A): ...
  inline def updateReturning[A <: Product: Table as table](a: A): ...
  inline def deleteReturning[A <: Product: Table as table](a: A): ...
```

### Type-Safe Specialized Operations

The [`SpecializedDML`](src/main/scala/saferis/SpecializedDML.scala) object provides operations that are only available when dialects support them:

- **`insertReturning`** - Only available with `ReturningSupport`
- **`updateReturning`** - Only available with `ReturningSupport`  
- **`deleteReturning`** - Only available with `ReturningSupport`
- **`upsert`** - Only available with `UpsertSupport`
- **`createIndexIfNotExists`** - Only available with `IndexIfNotExistsSupport`
- **`jsonExtract`** - Only available with `JsonSupport`
- **`arrayContains`** - Only available with `ArraySupport`

These operations use Scala 3's intersection types (`Dialect & ReturningSupport`) to ensure compile-time safety.

#### Key Differences Between DML Approaches

**`DataManipulationLayer` (Basic DML):**
- Operations work with any dialect
- RETURNING operations may fail at runtime on unsupported databases
- Simpler imports: `import saferis.dml.*`
- Good for straightforward CRUD operations

**`SpecializedDML` (Type-Safe DML):**
- Operations only compile when dialect supports them
- Compile-time guarantees prevent runtime failures
- Requires dialect capability constraints in method signatures
- Good for advanced operations that vary by database

Choose `DataManipulationLayer` for simple applications where you control the database choice, or `SpecializedDML` for libraries or applications that need to support multiple databases with compile-time safety.

## Usage Examples

## Usage Examples

### Basic DDL and DML Operations

```scala
import saferis.*
import saferis.ddl.*  // DataDefinitionLayer operations
import saferis.dml.*  // DataManipulationLayer operations  
import saferis.postgres.{given}

@tableName("users")
case class User(@generated @key id: Int, name: String, email: String) derives Table

// DDL operations
val createResult = createTable[User]()                    // Uses PostgreSQL dialect
val indexResult = createIndex[User]("idx_email", Seq("email"))

// Basic DML operations (work with any dialect)
val insertResult = insert(User(-1, "John", "john@example.com"))
val updateResult = update(user.copy(name = "John Smith"))
val deleteResult = delete(user)

// RETURNING operations (available but may fail at runtime on unsupported dialects)
val insertedUser = insertReturning(User(-1, "Jane", "jane@example.com"))
```

### PostgreSQL with Full Type-Safe Capabilities

```scala
import saferis.*
import saferis.postgres.{given}
import saferis.SpecializedDML.*  // Type-safe dialect-constrained operations

@tableName("users")
case class User(@generated @key id: Int, name: String, email: String) derives Table

// All operations available due to PostgreSQL's comprehensive capability support
val user = insertReturning(User(-1, "John", "john@example.com"))    // RETURNING support - compile-time guaranteed
val updated = updateReturning(user.copy(name = "John Smith"))       // RETURNING support
val jsonData = jsonExtract("profile", "settings.theme")            // JSON support
val arrayMatch = arrayContains("tags", "'scala'")                  // Array support

// UPSERT operation
val upserted = upsert(user, Seq("email"))                          // UPSERT support
```

### SQLite with Limited Type-Safe Capabilities

```scala
import saferis.*
import saferis.sqlite.{given}
import saferis.SpecializedDML.*

@tableName("users") 
case class User(@generated @key id: Int, name: String, email: String) derives Table

// RETURNING operations are available (compile-time guaranteed)
val user = insertReturning(User(-1, "John", "john@example.com"))

// These would NOT compile - SQLite doesn't support these capabilities:
// val jsonData = jsonExtract("data", "user.name")     // Compilation error!
// val upserted = upsert(user, Seq("email"))           // Compilation error!
```

### MySQL with JSON Support

```scala
import saferis.*
import saferis.mysql.{given}
import saferis.SpecializedDML.*

// JSON operations are available (compile-time guaranteed)
val jsonData = jsonExtract("user_data", "profile.name")  // JSON support

// But RETURNING operations would not compile:
// val user = insertReturning(newUser)                   // Compilation error!
```

### Generic Dialect Usage

```scala
import saferis.*

// Works with any dialect - safe table and column references
def getColumnType[A](encoder: Encoder[A])(using dialect: Dialect): String =
  dialect.columnType(encoder)

@tableName("users")
case class User(@key id: Int, name: String) derives Table

val userTable = Table[User]

// Safe generic queries that work with any dialect
def findUserById(id: Int)(using dialect: Dialect) =
  sql"SELECT * FROM $userTable WHERE ${userTable.id} = $id".queryOne[User]

def createUserTable()(using dialect: Dialect) = 
  createTable[User]()  // Uses appropriate SQL for each database

// Type mappings vary by database:
// PostgreSQL
import saferis.postgres.{given}
val pgType = summon[Encoder[String]].columnType  // "varchar(255)"

// SQLite  
import saferis.sqlite.{given}
val sqliteType = summon[Encoder[String]].columnType  // "text"
```

## Database Differences Summary

### Auto-increment Syntax
| Database   | Syntax                           |
|------------|----------------------------------|
| PostgreSQL | `GENERATED ALWAYS AS IDENTITY`   |
| MySQL      | `AUTO_INCREMENT`                 |
| SQLite     | `AUTOINCREMENT`                  |

### Type Mappings
| JDBC Type  | PostgreSQL       | MySQL        | SQLite    |
|------------|------------------|--------------|-----------|
| VARCHAR    | varchar(255)     | varchar(255) | text      |
| INTEGER    | integer          | int          | integer   |
| DOUBLE     | double precision | double       | real      |
| BOOLEAN    | boolean          | boolean      | integer   |
| TIMESTAMP  | timestamp        | timestamp    | text      |

### Feature Support Matrix
| Feature                | PostgreSQL | MySQL | SQLite |
|------------------------|------------|-------|--------|
| RETURNING clause       | ✅         | ❌    | ✅     |
| JSON operations        | ✅         | ✅    | ❌     |
| Array operations       | ✅         | ❌    | ❌     |
| UPSERT operations      | ✅         | ❌    | ❌     |
| IF NOT EXISTS indexes  | ✅         | ❌    | ✅     |
| Window functions       | ✅         | ✅    | ✅     |
| CTEs                   | ✅         | ✅    | ✅     |

### Identifier Escaping
| Database   | Quote Character |
|------------|----------------|
| PostgreSQL | `"`            |
| MySQL      | `` ` ``        |
| SQLite     | `"`            |

## Benefits

### 1. Compile-Time Safety
Operations are only available when the database supports them:

```scala
// Old approach - runtime checking
if (dialect.supportsReturning) {
  // use returning operations
}

// New approach - compile-time constraints  
def insertWithReturning[A](entity: A)(using dialect: Dialect & ReturningSupport) = 
  // This method only compiles when dialect supports RETURNING
```

### 2. Database Independence
Write once, run on multiple databases:

```scala
def createUserTable[A <: Product: Table]()(using dialect: Dialect) =
  createTable[A]()  // Uses appropriate SQL for each database
```

### 3. Feature Detection
Clear indication of what each database supports through trait mixing.

### 4. Extensible Architecture
Easy to add new capabilities:

```scala
trait FullTextSearchSupport:
  self: Dialect =>
  def fullTextSearchSql(column: String, query: String): String
```

## Migration Guide

### From Basic Type Mapping
**Before:**
```scala
val columnType = dialect.columnType(jdbcType)  // Only basic type mapping
```

**After:**  
```scala
// Comprehensive SQL generation
val createSql = createTable[User]()                    // Uses dialect.autoIncrementClause()
val indexSql = createIndex("idx_name", Seq("name"))    // Uses dialect.createIndexSql()
val alterSql = addColumn[User, String]("email")        // Uses dialect.addColumnSql()
```

### Adopting Specialized Operations
**Before:**
```scala
// Manual SQL construction (unsafe)
val sql = s"INSERT INTO users (name) VALUES (?) RETURNING *"
```

**After:**
```scala
// Type-safe, dialect-aware operation with proper interpolation
import saferis.SpecializedDML.*

@tableName("users")
case class User(@generated @key id: Int, name: String) derives Table

val userTable = Table[User]
val newUser = User(-1, "John")

// Only compiles if dialect supports RETURNING
val user = insertReturning(newUser)

// Or safe manual construction when needed
val customQuery = sql"SELECT * FROM $userTable WHERE ${userTable.name} = ${"John"}".queryOne[User]
```

## Implementation Details

### Trait Mixing Pattern
Dialects mix in only the capabilities they support:
```scala
// PostgreSQL supports all capabilities
object PostgresDialect extends Dialect
    with ReturningSupport
    with JsonSupport  
    with ArraySupport
    // ... other capabilities
```
See: [`PostgresDialect.scala`](src/main/scala/saferis/postgres/PostgresDialect.scala)

### Compile-Time Constraints
Method signatures ensure compile-time checking:
```scala
// Method only available when dialect has specific capabilities
def insertReturning[A](entity: A)(using dialect: Dialect & ReturningSupport) = 
  // Implementation uses dialect.insertReturningSql()
```
See: [`SpecializedDML.scala`](src/main/scala/saferis/SpecializedDML.scala)

### Default Implementations
Capability traits provide sensible defaults:
```scala
trait ReturningSupport:
  def insertReturningSql(tableName: String, insertColumns: String, returningColumns: String): String = 
    s"insert into $tableName $insertColumns returning $returningColumns"
```
See: [`DialectCapabilities.scala`](src/main/scala/saferis/DialectCapabilities.scala)

## Testing

The implementation includes comprehensive tests verifying:

1. **Capability Detection** - Ensuring dialects mix in expected traits
2. **Type Safety** - Verifying operations only compile when supported
3. **SQL Generation** - Testing database-specific SQL output
4. **Runtime Behavior** - Integration tests with actual databases

See the test specifications:
- [`DialectSpecs.scala`](src/test/scala/saferis/tests/DialectSpecs.scala) - Dialect-specific tests
- [`SpecializedDMLSpec.scala`](src/test/scala/saferis/SpecializedDMLSpec.scala) - Type-safe operation tests
- [`DataDefinitionLayerSpecs.scala`](src/test/scala/saferis/tests/DataDefinitionLayerSpecs.scala) - DDL operation tests

## Conclusion

The Saferis dialect system transforms the library from basic type mapping into a comprehensive, type-safe database abstraction. It leverages Scala 3's advanced type system to provide:

- **Compile-time safety** ensuring operations are only available when supported
- **Database independence** allowing code to work across multiple databases  
- **Feature detection** through clear trait-based capabilities
- **Extensible architecture** making it easy to add new database support
- **Backward compatibility** preserving existing functionality

This approach provides developers with confidence that their database operations will work at runtime while maintaining clean, readable code that clearly expresses database capabilities and constraints.

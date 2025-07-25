# Database Dialect Support

Saferis supports pluggable database dialects, allowing you to use the library with different databases that support JDBC. Each dialect provides database-specific implementations for:

- **Column Types**: Database-specific type mappings (e.g., PostgreSQL `varchar(255)` vs MySQL `varchar(255)` vs SQLite `text`)
- **Auto-increment**: Different syntax for generated primary keys (`GENERATED ALWAYS AS IDENTITY` vs `AUTO_INCREMENT` vs `AUTOINCREMENT`)
- **Index Creation**: Support for different index creation syntax and features
- **SQL Syntax Variations**: Database-specific SQL generation for DDL operations
- **Feature Support**: Different databases support different SQL features

## Supported Dialects

### PostgreSQL (Default)
- Uses `GENERATED ALWAYS AS IDENTITY` for auto-increment
- Supports `IF NOT EXISTS` for indexes
- Uses double quotes for identifier escaping
- Supports `RETURNING` clause
- Rich type system with native JSON, arrays, etc.

### MySQL 
- Uses `AUTO_INCREMENT` for auto-increment  
- Limited `IF NOT EXISTS` support for indexes
- Uses backticks for identifier escaping
- No `RETURNING` clause support
- JSON support in newer versions

### SQLite
- Uses `AUTOINCREMENT` for auto-increment
- Simplified type system with type affinity
- Uses `DELETE FROM` instead of `TRUNCATE`
- Limited `ALTER TABLE` support
- Stores dates as text

## Usage

### PostgreSQL (Default)

To use PostgreSQL-specific features, import the PostgreSQL dialect:

```scala
import saferis.*
import saferis.postgres.{given, *}  // Imports PostgreSQL dialect and extensions

// Now you can use PostgreSQL-specific features
@tableName("users") 
case class User(@key id: Int, name: String, email: Option[String]) derives Table

// Create table with PostgreSQL-specific SQL generation
createTable[User]()

// Add column with PostgreSQL type
addColumn[User, String]("phone")

// Get column type for an encoder
val encoder = summon[Encoder[String]]
val colType = encoder.columnType      // "varchar(255)" - PostgreSQL specific
```

### MySQL

```scala
import saferis.*
import saferis.mysql.MySQLDialect
given Dialect = MySQLDialect

@tableName("users")
case class User(@generated @key id: Int, name: String) derives Table

// Creates table with MySQL-specific syntax:
// CREATE TABLE users (id int auto_increment primary key, name varchar(255))
createTable[User]()
```

### SQLite

```scala
import saferis.*
import saferis.sqlite.SQLiteDialect
given Dialect = SQLiteDialect

@tableName("users")
case class User(@generated @key id: Int, name: String) derives Table

// Creates table with SQLite-specific syntax:
// CREATE TABLE users (id integer primary key autoincrement, name text)
createTable[User]()
```

### Generic Usage

You can also work with dialects generically:

```scala
import saferis.*

// Work with any dialect
def getTypeString[A](encoder: Encoder[A])(using dialect: Dialect): String =
  dialect.columnType(encoder)

// Create table that works with any dialect
def createUserTable[A <: Product: Table]()(using dialect: Dialect) =
  createTable[A]()

// Use with different dialects
import saferis.postgres.{given}
val pgStringType = summon[Encoder[String]].columnType  // "varchar(255)"

import saferis.sqlite.SQLiteDialect
given Dialect = SQLiteDialect  
val sqliteStringType = summon[Encoder[String]].columnType  // "text"
```

## Architecture

### Core Components

1. **`Encoder[A]`** - Generic JDBC encoding (database-agnostic)
2. **`Dialect`** - Database-specific type mappings and SQL generation
3. **DDL Operations** - Use dialect methods for database-specific SQL generation

### Dialect Interface

The `Dialect` trait provides comprehensive database abstraction:

```scala
trait Dialect:
  // Core identification
  def name: String
  
  // Type mappings
  def columnType(jdbcType: Int): String
  def columnType[A](encoder: Encoder[A]): String
  
  // Auto-increment and primary keys
  def autoIncrementClause(isGenerated: Boolean, isPrimaryKey: Boolean, hasCompoundKey: Boolean): String
  def primaryKeyClause: String
  def compoundPrimaryKeyClause(columnNames: Seq[String]): String
  
  // Index operations
  def createIndexSql(indexName: String, tableName: String, columnNames: Seq[String], ifNotExists: Boolean): String
  def createUniqueIndexSql(indexName: String, tableName: String, columnNames: Seq[String], ifNotExists: Boolean): String
  def dropIndexSql(indexName: String, ifExists: Boolean): String
  
  // Table operations
  def createTableClause(ifNotExists: Boolean): String
  def dropTableSql(tableName: String, ifExists: Boolean): String
  def truncateTableSql(tableName: String): String
  
  // Column operations
  def addColumnSql(tableName: String, columnName: String, columnType: String): String
  def dropColumnSql(tableName: String, columnName: String): String
  
  // Feature support
  def supportsReturning: Boolean
  def supportsIndexIfNotExists: Boolean
  def identifierQuote: String
  def escapeIdentifier(identifier: String): String
```

### Database Differences

Different databases have significant variations in SQL syntax and features:

#### Auto-increment Syntax
- **PostgreSQL**: `GENERATED ALWAYS AS IDENTITY`
- **MySQL**: `AUTO_INCREMENT`
- **SQLite**: `AUTOINCREMENT`

#### Index Creation
- **PostgreSQL**: Supports `IF NOT EXISTS` for indexes
- **MySQL**: Limited support (version dependent)
- **SQLite**: Supports `IF NOT EXISTS`

#### Type Systems
- **PostgreSQL**: Rich type system (arrays, JSON, custom types)
- **MySQL**: Traditional SQL types with some JSON support
- **SQLite**: Simplified type affinity system

#### Query Features
- **PostgreSQL**: Full `RETURNING` clause support
- **MySQL**: No `RETURNING` clause
- **SQLite**: `RETURNING` support in recent versions

### Type Mappings by Database

| JDBC Type | PostgreSQL | MySQL | SQLite |
|-----------|------------|-------|---------|
| VARCHAR   | varchar(255) | varchar(255) | text |
| INTEGER   | integer | int | integer |
| DOUBLE    | double precision | double | real |
| BOOLEAN   | boolean | boolean | integer |
| TIMESTAMP | timestamp | timestamp | text |
| BLOB      | bytea | blob | blob |

## Adding New Dialects

To add support for a new database:

1. **Create dialect object**:
```scala
package saferis.h2

import saferis.Dialect
import java.sql.Types

object H2Dialect extends Dialect:
  val name = "H2"
  
  def columnType(jdbcType: Int): String = jdbcType match
    case Types.VARCHAR => s"varchar($DefaultVarcharLength)"
    case Types.INTEGER => "integer"
    case Types.BOOLEAN => "boolean"
    // ... other mappings
    
  def autoIncrementClause(isGenerated: Boolean, isPrimaryKey: Boolean, hasCompoundKey: Boolean): String =
    if isGenerated && isPrimaryKey && !hasCompoundKey then
      " auto_increment primary key"
    else if isGenerated then
      " auto_increment"
    else if isPrimaryKey && !hasCompoundKey then
      " primary key"
    else
      ""
      
  override def supportsReturning: Boolean = false
  override def identifierQuote: String = "\""
```

2. **Use the dialect**:
```scala
import saferis.*
import saferis.h2.H2Dialect

given Dialect = H2Dialect

@tableName("users")
case class User(@generated @key id: Int, name: String) derives Table

createTable[User]()  // Uses H2-specific SQL generation
```

## Migration from Old API

**Before** (limited to column types):
```scala
// Only column type mapping was supported
val columnType = dialect.columnType(jdbcType)
```

**After** (comprehensive dialect support):
```scala
// Full SQL generation with dialect-specific syntax
val createTableSql = createTable[User]()          // Uses dialect.autoIncrementClause()
val indexSql = createIndex("idx_name", Seq("name"))  // Uses dialect.createIndexSql()
val addColSql = addColumn[User, String]("email")      // Uses dialect.addColumnSql()
```

## Benefits

1. **Database Independence** - Write once, run on any supported database
2. **Correct SQL Generation** - Each database gets its proper syntax
3. **Feature Detection** - Dialects specify what features they support
4. **Type Safety** - Compile-time checking of dialect availability
5. **Extensibility** - Easy to add new database support
6. **Clean Separation** - Database-specific code is isolated in dialect packages
7. **Backwards Compatibility** - Existing PostgreSQL code continues to work

package saferis.examples

import saferis.*
import saferis.ddl.*

object DialectExample:

  // Example showing PostgreSQL dialect usage
  def postgresExample() =
    import saferis.postgres.{given, *}

    @tableName("users")
    case class User(@generated @key id: Int, name: String, email: Option[String]) derives Table

    // Create table using PostgreSQL dialect - uses GENERATED ALWAYS AS IDENTITY
    createTable[User]()

    // Add column using PostgreSQL dialect
    addColumn[User, String]("phone")

    // Extension method for getting column type
    val stringEncoder = summon[Encoder[String]]
    val dialect = summon[Dialect]
    val columnTypeStr = dialect.columnType(stringEncoder) // "varchar(255)"

    println(s"String column type in PostgreSQL: $columnTypeStr")
  end postgresExample

  // Example showing MySQL dialect usage
  def mysqlExample() =
    // Note: MySQL dialect import temporarily commented out
    // import saferis.mysql.{given, *}
    println("MySQL dialect example - temporarily disabled")

    /*
    @tableName("products")
    case class Product(@generated @key id: Int, name: String, price: Double) derives Table

    // Create table using MySQL dialect - uses AUTO_INCREMENT
    createTable[Product]()

    // Add column using MySQL dialect
    addColumn[Product, String]("description")

    val stringEncoder = summon[Encoder[String]]
    val dialect = summon[Dialect]
    val columnTypeStr = dialect.columnType(stringEncoder) // "varchar(255)"

    println(s"String column type in MySQL: $columnTypeStr")
    */
  end mysqlExample

  // Example showing SQLite dialect usage
  def sqliteExample() =
    import saferis.sqlite.{given, *}
    @tableName("posts")
    case class Post(@generated @key id: Int, title: String, content: String) derives Table

    // Create table using SQLite dialect - uses AUTOINCREMENT
    createTable[Post]()

    // Add column using SQLite dialect
    addColumn[Post, Int]("views")

    val stringEncoder = summon[Encoder[String]]
    val dialect = summon[Dialect]
    val columnTypeStr = dialect.columnType(stringEncoder) // "text"

    println(s"String column type in SQLite: $columnTypeStr")
  end sqliteExample

  // Example showing generic dialect usage
  def genericExample() =
    def createUserTable[A <: Product: Table]()(using dialect: Dialect) =
      println(s"Creating table using ${dialect.name} dialect")
      createTable[A]()

    @tableName("users")
    case class User(@generated @key id: Int, name: String) derives Table

    // Works with any dialect - use in separate scopes to avoid ambiguity
    locally {
      import saferis.postgres.{given}
      createUserTable[User]() // "Creating table using PostgreSQL dialect"
    }

    // MySQL temporarily disabled
    /*
    locally {
      import saferis.mysql.{given}
      createUserTable[User]() // "Creating table using MySQL dialect"
    }
    */

    locally {
      import saferis.sqlite.{given}
      createUserTable[User]() // "Creating table using SQLite dialect"
    }
  end genericExample

  // Example showing dialect-specific capabilities using trait-based features
  def dialectCapabilitiesExample() =
    import saferis.postgres.{given}
    val pgDialect = summon[Dialect]

    println(s"PostgreSQL dialect name: ${pgDialect.name}")
    println(s"PostgreSQL identifier quote: ${pgDialect.identifierQuote}")
    println(s"PostgreSQL auto-increment: ${pgDialect.autoIncrementClause(true, true, false)}")

    // PostgreSQL supports RETURNING - we can check with pattern matching or casting
    pgDialect match
      case returningDialect: ReturningSupport =>
        println("PostgreSQL supports RETURNING operations")
        // Could use returning-specific operations here
      case _ =>
        println("PostgreSQL does not support RETURNING operations")

    // JSON operations are available for PostgreSQL
    pgDialect match
      case jsonDialect: JsonSupport =>
        println(s"PostgreSQL JSON type: ${jsonDialect.jsonType}")
        val jsonQuery = jsonDialect.jsonExtractSql("data", "user.name")
        println(s"PostgreSQL JSON extract: $jsonQuery")
      case _ =>
        println("PostgreSQL does not support JSON operations")

    // Array operations are available for PostgreSQL
    pgDialect match
      case arrayDialect: ArraySupport =>
        val arrayQuery = arrayDialect.arrayContainsSql("tags", "'scala'")
        println(s"PostgreSQL array contains: $arrayQuery")
      case _ =>
        println("PostgreSQL does not support array operations")
  end dialectCapabilitiesExample

  // Example showing specialized DML operations
  def specializedDMLExample() =
    import saferis.postgres.{given}
    import saferis.SpecializedDML.*

    @tableName("users")
    case class User(@generated @key id: Int, name: String, email: String) derives Table

    val newUser = User(0, "John Doe", "john@example.com")

    // This would only compile with dialects that support RETURNING
    // val insertedUser = insertReturning(newUser)
    println("PostgreSQL supports insertReturning, updateReturning, and deleteReturning")

    // Create an index with IF NOT EXISTS (only works with supporting dialects)
    // val result = createIndexIfNotExists[User]("idx_user_email", Seq("email"))
    println("PostgreSQL supports createIndexIfNotExists")

    // JSON operations (PostgreSQL supports JSON)
    given Dialect & JsonSupport = summon[Dialect].asInstanceOf[Dialect & JsonSupport]
    val jsonExtractFragment = jsonExtract("user_data", "profile.age")
    println(s"JSON extract fragment: ${jsonExtractFragment.sql}")

    // Array operations (PostgreSQL supports arrays)
    given Dialect & ArraySupport = summon[Dialect].asInstanceOf[Dialect & ArraySupport]
    val arrayContainsFragment = arrayContains("tags", "'scala'")
    println(s"Array contains fragment: ${arrayContainsFragment.sql}")
  end specializedDMLExample

end DialectExample

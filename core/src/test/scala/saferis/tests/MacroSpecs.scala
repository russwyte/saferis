package saferis.tests

import saferis.*
import zio.test.*

object MacroSpecs extends ZIOSpecDefault:

  @tableName("test_users")
  case class TestUser(@key id: Int, name: String, age: Int) derives Table

  // Table with a field named "column" to test naming collision avoidance
  @tableName("with_column_field")
  case class WithColumnField(@key id: Int, column: String, data: Int) derives Table

  // Table with fields that could collide with Instance methods
  @tableName("reserved_names")
  case class ReservedNames(
      @key id: Int,
      sql: String,       // Collides with Placeholder.sql
      withAlias: String, // Collides with Instance.withAlias
      deAliased: String, // Collides with Instance.deAliased
  ) derives Table

  val spec = suite("MacroSpecs")(
    suiteAll("Macro extractColumn"):
      test("extractColumn returns typed Column[String] for string field"):
        val instance                 = Table[TestUser]
        val nameCol: Column[String] = instance.column(_.name)
        assertTrue(
          nameCol.label == "name",
          nameCol.name == "name",
        )

      test("extractColumn returns typed Column[Int] for int field"):
        val instance              = Table[TestUser]
        val ageCol: Column[Int] = instance.column(_.age)
        assertTrue(
          ageCol.label == "age",
          ageCol.name == "age",
        )

      test("extractColumn returns typed Column[Int] for key field"):
        val instance             = Table[TestUser]
        val idCol: Column[Int] = instance.column(_.id)
        assertTrue(
          idCol.label == "id",
          idCol.name == "id",
          idCol.isKey,
        )

      test("extractColumn preserves column metadata"):
        val instance                 = Table[TestUser]
        val idCol: Column[Int]      = instance.column(_.id)
        val nameCol: Column[String] = instance.column(_.name)
        assertTrue(
          idCol.isKey,
          !nameCol.isKey,
          !idCol.isNullable,
          !nameCol.isNullable,
        )

      test("table with field named 'column' works correctly"):
        val instance                    = Table[WithColumnField]
        // Can access the "column" field via the column() method
        val columnCol: Column[String] = instance.column(_.column)
        assertTrue(
          columnCol.label == "column",
          columnCol.name == "column",
        )

      test("table with field named 'column' - selectDynamic still works"):
        val instance = Table[WithColumnField]
        // From within saferis.*, the column method shadows selectDynamic("column")
        // But we can still access via fieldNamesToColumns
        val columnFromMap = instance.fieldNamesToColumns("column")
        assertTrue(
          columnFromMap.label == "column",
          columnFromMap.name == "column",
        )
    ,
    suiteAll("Reserved field names"):
      test("table with field named 'sql' - method takes precedence over selectDynamic"):
        val instance = Table[ReservedNames]
        // instance.sql returns the Placeholder.sql value, NOT the column
        // This is a known limitation - these field names shadow Instance methods
        val sqlFromMethod: String = instance.sql
        assertTrue(
          sqlFromMethod == "reserved_names", // The table name SQL
        )

      test("table with field named 'sql' - can still access column via fieldNamesToColumns"):
        val instance = Table[ReservedNames]
        // Users can access shadowed fields via the map
        val sqlCol = instance.fieldNamesToColumns("sql")
        assertTrue(
          sqlCol.label == "sql",
          sqlCol.name == "sql",
        )

      test("table with reserved names - can use columns in SQL interpolator"):
        val instance = Table[ReservedNames]
        // The sql interpolator works correctly with any field name
        val sqlCol   = instance.fieldNamesToColumns("sql").asInstanceOf[Column[String]]
        val fragment = sql"SELECT $sqlCol FROM $instance"
        assertTrue(
          fragment.sql.contains("sql"),
          fragment.sql.contains("reserved_names"),
        )

      test("table with reserved names - Query builder works"):
        // Query builder uses fieldNamesToColumns internally, so it works
        val query    = Query[ReservedNames].where(_.sql).eq("test")
        val fragment = query.build
        assertTrue(
          fragment.sql.contains("sql"),
        )
    ,
  )

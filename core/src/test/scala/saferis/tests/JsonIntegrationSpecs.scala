package saferis.tests

import saferis.*
import saferis.Schema.*
import saferis.ddl.*
import saferis.dml.*
import saferis.postgres.PostgresDialect
import saferis.tests.PostgresTestContainer.DataSourceProvider
import zio.*
import zio.json.*
import zio.test.*

/** Integration tests for JSON support, including SQL injection prevention via escaping. */
object JsonIntegrationSpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default

  given (Dialect & JsonSupport) = PostgresDialect

  // === Test Data Types ===

  final case class UserData(email: String, verified: Boolean, role: String) derives JsonCodec
  final case class Settings(theme: String, notifications: Boolean) derives JsonCodec

  // === Test Tables ===

  @tableName("json_test_profiles")
  final case class Profile(
      @generated @key id: Int,
      name: String,
      data: Json[UserData],
  ) derives Table

  @tableName("json_test_settings")
  final case class UserSettings(
      @generated @key id: Int,
      userId: Int,
      settings: Json[Settings],
  ) derives Table

  // Helper for counting
  final case class CountResult(count: Long) derives Table

  // Helper for JSON field extraction
  final case class JsonFieldResult(value: Option[String]) derives Table

  val spec = suite("JSON Integration Tests")(
    // === Dialect SQL Generation with Escaping ===
    suite("PostgreSQL dialect escaping")(
      test("jsonExtractSql escapes single quotes in field path"):
        val sql = PostgresDialect.jsonExtractSql("data", "user's_field")
        assertTrue(sql == "data->>'user''s_field'")
      ,
      test("jsonHasKeySql escapes single quotes in key"):
        val sql = PostgresDialect.jsonHasKeySql("data", "user's_key")
        assertTrue(sql == "jsonb_exists(data, 'user''s_key')")
      ,
      test("jsonContainsSql escapes single quotes in value"):
        val sql = PostgresDialect.jsonContainsSql("data", """{"name":"O'Brien"}""")
        assertTrue(sql == """data @> '{"name":"O''Brien"}'""")
      ,
      test("jsonHasAnyKeySql escapes single quotes in keys"):
        val sql = PostgresDialect.jsonHasAnyKeySql("data", Seq("key1", "user's_key", "key2"))
        assertTrue(sql == "jsonb_exists_any(data, array['key1', 'user''s_key', 'key2'])")
      ,
      test("jsonHasAllKeysSql escapes single quotes in keys"):
        val sql = PostgresDialect.jsonHasAllKeysSql("data", Seq("user's_key", "another's_key"))
        assertTrue(sql == "jsonb_exists_all(data, array['user''s_key', 'another''s_key'])"),
    ),
    suite("MySQL dialect escaping")(
      test("jsonExtractSql escapes single quotes in field path"):
        val sql = saferis.mysql.MySQLDialect.jsonExtractSql("data", "user's_field")
        assertTrue(sql == "JSON_EXTRACT(data, '$.user''s_field')")
      ,
      test("jsonHasKeySql escapes single quotes in key"):
        val sql = saferis.mysql.MySQLDialect.jsonHasKeySql("data", "user's_key")
        assertTrue(sql == "JSON_CONTAINS_PATH(data, 'one', '$.user''s_key')")
      ,
      test("jsonContainsSql escapes single quotes in value"):
        val sql = saferis.mysql.MySQLDialect.jsonContainsSql("data", """{"name":"O'Brien"}""")
        assertTrue(sql == """JSON_CONTAINS(data, '{"name":"O''Brien"}')""")
      ,
      test("jsonHasAnyKeySql escapes single quotes in keys"):
        val sql = saferis.mysql.MySQLDialect.jsonHasAnyKeySql("data", Seq("key1", "user's_key"))
        assertTrue(sql == "JSON_CONTAINS_PATH(data, 'one', '$.key1', '$.user''s_key')")
      ,
      test("jsonHasAllKeysSql escapes single quotes in keys"):
        val sql = saferis.mysql.MySQLDialect.jsonHasAllKeysSql("data", Seq("user's_key", "another's_key"))
        assertTrue(sql == "JSON_CONTAINS_PATH(data, 'all', '$.user''s_key', '$.another''s_key')"),
    ),
    suite("Spark dialect escaping")(
      test("jsonExtractSql escapes single quotes in field path"):
        val sql = saferis.spark.SparkDialect.jsonExtractSql("data", "user's_field")
        assertTrue(sql == "get_json_object(data, '$.user''s_field')")
      ,
      test("jsonHasKeySql escapes single quotes in key"):
        val sql = saferis.spark.SparkDialect.jsonHasKeySql("data", "user's_key")
        assertTrue(sql == "get_json_object(data, '$.user''s_key') is not null")
      ,
      test("jsonHasAnyKeySql escapes single quotes in keys"):
        val sql = saferis.spark.SparkDialect.jsonHasAnyKeySql("data", Seq("key1", "user's_key"))
        assertTrue(
          sql == "(get_json_object(data, '$.key1') is not null or get_json_object(data, '$.user''s_key') is not null)"
        )
      ,
      test("jsonHasAllKeysSql escapes single quotes in keys"):
        val sql = saferis.spark.SparkDialect.jsonHasAllKeysSql("data", Seq("user's_key", "another's_key"))
        assertTrue(
          sql == "(get_json_object(data, '$.user''s_key') is not null and get_json_object(data, '$.another''s_key') is not null)"
        ),
    ),
    // === Database Integration Tests ===
    suite("PostgreSQL database integration")(
      test("insert and query JSON data with special characters"):
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Profile](ifExists = true))
          _  <- xa.run(createTable[Profile]())
          // Insert profile with data containing special characters
          userData = UserData("o'brien@test.com", true, "admin's_role")
          _ <- xa.run(insert(Profile(0, "O'Brien", Json(userData))))
          // Query back the data
          profiles <- xa.run(sql"SELECT id, name, data FROM json_test_profiles".query[Profile])
        yield assertTrue(
          profiles.length == 1,
          profiles.head.name == "O'Brien",
          profiles.head.data.value.email == "o'brien@test.com",
          profiles.head.data.value.role == "admin's_role",
        )
      ,
      test("jsonb_exists with key containing single quote"):
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Profile](ifExists = true))
          _  <- xa.run(createTable[Profile]())
          // Insert data with a key that contains a single quote (via raw JSON)
          _ <- xa.run(
            sql"""INSERT INTO json_test_profiles (name, data)
                  VALUES ('Test', '{"user''s_email":"test@test.com","verified":true,"role":"user"}'::jsonb)""".insert
          )
          // Query using jsonb_exists with escaped key
          count <- xa.run(
            sql"""SELECT count(*) as count FROM json_test_profiles
                  WHERE jsonb_exists(data, 'user''s_email')""".queryOne[CountResult]
          )
        yield assertTrue(count.exists(_.count == 1))
      ,
      test("JSON path extraction with field containing single quote"):
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Profile](ifExists = true))
          _  <- xa.run(createTable[Profile]())
          // Insert data with a key that contains a single quote
          _ <- xa.run(
            sql"""INSERT INTO json_test_profiles (name, data)
                  VALUES ('Test', '{"user''s_name":"O''Connor","verified":true,"role":"user"}'::jsonb)""".insert
          )
          // Query using ->> with escaped key
          result <- xa.run(
            sql"""SELECT data->>'user''s_name' as value FROM json_test_profiles""".queryOne[JsonFieldResult]
          )
        yield assertTrue(result.flatMap(_.value).contains("O'Connor"))
      ,
      test("Schema DSL jsonHasKey with runtime-safe key"):
        // This tests that even if someone passes a key with special chars, it's properly escaped
        val profiles = Schema[Profile]
          .withIndex(_.data)
          .where(_.data)
          .jsonHasKey("user's_field")
          .named("idx_users_field")
          .build

        for
          xa      <- ZIO.service[Transactor]
          _       <- xa.run(dropTable[Profile](ifExists = true))
          _       <- xa.run(createTable(profiles))
          indexes <- xa.run(
            sql"""SELECT indexname, indexdef FROM pg_indexes
                  WHERE tablename = 'json_test_profiles' AND indexname = 'idx_users_field'""".query[IndexInfo]
          )
        yield assertTrue(
          indexes.nonEmpty,
          // Verify the SQL contains the escaped single quote
          indexes.head.indexdef.contains("user''s_field"),
        )
        end for
      ,
      test("Schema DSL jsonPath with field containing single quote"):
        val profiles = Schema[Profile]
          .withIndex(_.data)
          .where(_.data)
          .jsonPath("user's_email")
          .eql("test@test.com")
          .named("idx_users_email_path")
          .build

        for
          xa      <- ZIO.service[Transactor]
          _       <- xa.run(dropTable[Profile](ifExists = true))
          _       <- xa.run(createTable(profiles))
          indexes <- xa.run(
            sql"""SELECT indexname, indexdef FROM pg_indexes
                  WHERE tablename = 'json_test_profiles' AND indexname = 'idx_users_email_path'""".query[IndexInfo]
          )
        yield assertTrue(
          indexes.nonEmpty,
          // Verify the SQL contains the escaped single quote in the path
          indexes.head.indexdef.contains("user''s_email"),
        )
        end for
      ,
      test("SQL injection attempt in JSON key is properly escaped"):
        // Attempt SQL injection via JSON key - this should be safely escaped
        val maliciousKey = "'); DROP TABLE json_test_profiles; --"
        val sql          = PostgresDialect.jsonHasKeySql("data", maliciousKey)

        // The escaped SQL should have the single quote doubled: ' -> ''
        // This means the injection attempt becomes a string literal, not SQL syntax
        // Original:  jsonb_exists(data, ''); DROP TABLE...  <- would execute DROP
        // Escaped:   jsonb_exists(data, '''); DROP TABLE... <- '' is literal quote char
        assertTrue(
          sql == "jsonb_exists(data, '''); DROP TABLE json_test_profiles; --')"
        )
      ,
      test("SQL injection attempt in JSON path is properly escaped"):
        val maliciousPath = "field'); DELETE FROM users; --"
        val sql           = PostgresDialect.jsonExtractSql("data", maliciousPath)

        // The single quote after 'field' is escaped to ''
        assertTrue(
          sql == "data->>'field''); DELETE FROM users; --'"
        ),
    ),
    // === End-to-End JSON Type Tests ===
    suite("End-to-end Json[A] type operations")(
      test("insert and select using Json[A] type"):
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Profile](ifExists = true))
          _  <- xa.run(createTable[Profile]())
          // Insert using the type-safe insert function
          userData = UserData("alice@test.com", true, "admin")
          profile  = Profile(0, "Alice", Json(userData))
          _ <- xa.run(insert(profile))
          // Query using type-safe query
          profiles <- xa.run(Query[Profile].all.query[Profile])
        yield assertTrue(
          profiles.length == 1,
          profiles.head.name == "Alice",
          profiles.head.data.value.email == "alice@test.com",
          profiles.head.data.value.verified == true,
          profiles.head.data.value.role == "admin",
        )
      ,
      test("insert multiple profiles with Json[A] and query all"):
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Profile](ifExists = true))
          _  <- xa.run(createTable[Profile]())
          // Insert multiple profiles
          _ <- xa.run(insert(Profile(0, "Alice", Json(UserData("alice@test.com", true, "admin")))))
          _ <- xa.run(insert(Profile(0, "Bob", Json(UserData("bob@test.com", false, "user")))))
          _ <- xa.run(insert(Profile(0, "Charlie", Json(UserData("charlie@test.com", true, "moderator")))))
          // Query all
          profiles <- xa.run(Query[Profile].all.query[Profile])
        yield assertTrue(
          profiles.length == 3,
          profiles.map(_.name).toSet == Set("Alice", "Bob", "Charlie"),
          profiles.find(_.name == "Bob").exists(_.data.value.verified == false),
        )
      ,
      test("update Json[A] column using update function"):
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Profile](ifExists = true))
          _  <- xa.run(createTable[Profile]())
          // Insert initial profile
          userData = UserData("alice@test.com", false, "user")
          _ <- xa.run(insert(Profile(0, "Alice", Json(userData))))
          // Get the inserted profile
          inserted <- xa.run(Query[Profile].where(_.name).eq("Alice").query[Profile])
          // Update the profile with new JSON data
          updatedData = UserData("alice@test.com", true, "admin")
          _ <- xa.run(update(inserted.head.copy(data = Json(updatedData))))
          // Query back
          updated <- xa.run(Query[Profile].where(_.name).eq("Alice").query[Profile])
        yield assertTrue(
          updated.length == 1,
          updated.head.data.value.verified == true,
          updated.head.data.value.role == "admin",
        )
      ,
      test("Query builder where clause with Json column"):
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Profile](ifExists = true))
          _  <- xa.run(createTable[Profile]())
          // Insert profiles
          _ <- xa.run(insert(Profile(0, "Alice", Json(UserData("alice@test.com", true, "admin")))))
          _ <- xa.run(insert(Profile(0, "Bob", Json(UserData("bob@test.com", false, "user")))))
          // Query using where clause on non-JSON column
          admins <- xa.run(Query[Profile].where(_.name).eq("Alice").query[Profile])
        yield assertTrue(
          admins.length == 1,
          admins.head.data.value.role == "admin",
        )
      ,
      test("insertReturning with Json[A] type"):
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Profile](ifExists = true))
          _  <- xa.run(createTable[Profile]())
          // Insert and get returned row
          userData = UserData("alice@test.com", true, "admin")
          returned <- xa.run(insertReturning(Profile(0, "Alice", Json(userData))))
        yield assertTrue(
          returned.id > 0, // Generated ID should be assigned
          returned.name == "Alice",
          returned.data.value.email == "alice@test.com",
        )
      ,
      test("delete with Json[A] type"):
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Profile](ifExists = true))
          _  <- xa.run(createTable[Profile]())
          // Insert profile
          userData = UserData("alice@test.com", true, "admin")
          inserted <- xa.run(insertReturning(Profile(0, "Alice", Json(userData))))
          // Delete it
          _ <- xa.run(delete(inserted))
          // Verify deleted
          remaining <- xa.run(Query[Profile].all.query[Profile])
        yield assertTrue(remaining.isEmpty)
      ,
      test("Json[A] with special characters in values - end to end"):
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[Profile](ifExists = true))
          _  <- xa.run(createTable[Profile]())
          // Insert profile with special characters in JSON data
          userData = UserData("o'brien@test.com", true, "admin's_special_role")
          _ <- xa.run(insert(Profile(0, "O'Brien", Json(userData))))
          // Query back and verify data integrity
          profiles <- xa.run(Query[Profile].all.query[Profile])
        yield assertTrue(
          profiles.length == 1,
          profiles.head.name == "O'Brien",
          profiles.head.data.value.email == "o'brien@test.com",
          profiles.head.data.value.role == "admin's_special_role",
        )
      ,
      test("multiple Json[A] columns in same table"):
        // Define a table with multiple JSON columns
        @tableName("json_test_multi")
        case class MultiJson(
            @generated @key id: Int,
            profile: Json[UserData],
            settings: Json[Settings],
        ) derives Table

        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(dropTable[MultiJson](ifExists = true))
          _  <- xa.run(createTable[MultiJson]())
          // Insert with both JSON columns
          userData = UserData("alice@test.com", true, "admin")
          settings = Settings("dark", true)
          _ <- xa.run(insert(MultiJson(0, Json(userData), Json(settings))))
          // Query back
          results <- xa.run(Query[MultiJson].all.query[MultiJson])
        yield assertTrue(
          results.length == 1,
          results.head.profile.value.email == "alice@test.com",
          results.head.settings.value.theme == "dark",
          results.head.settings.value.notifications == true,
        )
        end for
      ,
      test("Option[Json[A]] nullable JSON column"):
        @tableName("json_test_optional")
        case class OptionalJson(
            @generated @key id: Int,
            name: String,
            data: Option[Json[UserData]],
        ) derives Table

        val res =
          for
            xa <- ZIO.service[Transactor]
            _  <- xa.run(dropTable[OptionalJson](ifExists = true))
            _  <- xa.run(createTable[OptionalJson]())
            // Insert with JSON data
            userData = UserData("alice@test.com", true, "admin")
            _ <- xa.run(insert(OptionalJson(0, "Alice", Some(Json(userData)))))
            // Insert without JSON data (null)
            _ <- xa.run(insert(OptionalJson(0, "Bob", None)))
            // Query back
            results <- xa.run(Query[OptionalJson].all.query[OptionalJson])
            alice = results.find(_.name == "Alice")
            bob   = results.find(_.name == "Bob")
          yield assertTrue(
            results.length == 2,
            alice.exists(_.data.isDefined),
            alice.flatMap(_.data).exists(_.value.email == "alice@test.com"),
            bob.exists(_.data.isEmpty),
          )
        end res
        res,
    ),
    // === Generic Json[E] Type Derivation Tests ===
    suite("Generic Json[E] table derivation")(
      test("derive Table for generic case class with Json[E] field"):
        // This tests the fix for path-dependent type codec resolution
        // When E is a type parameter, the macro resolves it using type parameter substitution
        final case class GenericEvent(kind: String, value: Int) derives JsonCodec

        // Json[E] now requires JsonCodec[E] as a context bound, which captures the evidence
        @tableName("generic_json_events")
        case class GenericRow[E: JsonCodec](@generated @key id: Long, data: Json[E])

        // The JsonCodec[E] context bound on GenericRow ensures the evidence is available
        class GenericStore[E: JsonCodec](xa: Transactor):
          given Table[GenericRow[E]]                    = Table.derived[GenericRow[E]]
          def insert(row: GenericRow[E])(using Dialect) = xa.run(saferis.dml.insert(row))

        // Helper to read raw JSON data
        case class RawJsonRow(id: Long, data: String) derives Table

        for
          xa <- ZIO.service[Transactor]
          store                                 = new GenericStore[GenericEvent](xa)
          given Table[GenericRow[GenericEvent]] = store.given_Table_GenericRow
          _ <- xa.run(dropTable[GenericRow[GenericEvent]](ifExists = true))
          _ <- xa.run(createTable[GenericRow[GenericEvent]]())
          _ <- store.insert(GenericRow(0, Json(GenericEvent("test", 42))))
          // Use raw SQL query to verify the data was inserted correctly
          rows <- xa.run(sql"SELECT id, data FROM generic_json_events".query[RawJsonRow])
        yield assertTrue(
          rows.length == 1,
          rows.head.data.contains("test"), // JSON contains "test"
          rows.head.data.contains("42"),   // JSON contains 42
        )
        end for
    ),
  ).provideShared(xaLayer) @@ TestAspect.sequential

  // Helper for querying index info from PostgreSQL
  final case class IndexInfo(indexname: String, indexdef: String) derives Table

end JsonIntegrationSpecs

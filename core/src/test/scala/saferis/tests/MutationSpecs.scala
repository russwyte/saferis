package saferis.tests

import saferis.*
import saferis.tests.PostgresTestContainer.DataSourceProvider
import zio.*
import zio.test.*

object MutationSpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default

  // Test table for mutation specs
  @tableName("test_mutation")
  final case class TestUser(@generated @key id: Int, name: String, age: Int, status: String) derives Table

  // ============================================================================
  // SQL Generation Tests (Unit Tests)
  // ============================================================================

  val sqlGenerationTests = suite("SQL Generation")(
    suite("Insert")(
      test("single value generates correct SQL"):
        val frag = Insert[TestUser].value(_.name, "Alice").build
        assertTrue(frag.sql == "insert into test_mutation (name) values (?)") &&
        assertTrue(frag.writes.size == 1)
      ,
      test("multiple values generates correct SQL"):
        val frag = Insert[TestUser]
          .value(_.name, "Alice")
          .value(_.age, 30)
          .value(_.status, "active")
          .build
        assertTrue(frag.sql == "insert into test_mutation (name, age, status) values (?, ?, ?)") &&
        assertTrue(frag.writes.size == 3)
      ,
      test("returning appends RETURNING clause"):
        val frag = Insert[TestUser].value(_.name, "Alice").returning
        assertTrue(frag.sql.contains("returning *")),
    ),
    suite("Update")(
      test("single set with where generates correct SQL"):
        val frag = Update[TestUser]
          .set(_.name, "Bob")
          .where(_.id)
          .eq(1)
          .build
        assertTrue(frag.sql.contains("update test_mutation set name = ?")) &&
        assertTrue(frag.sql.contains("where")) &&
        assertTrue(frag.writes.size == 2) // name + id
      ,
      test("multiple set clauses generates correct SQL"):
        val frag = Update[TestUser]
          .set(_.name, "Bob")
          .set(_.age, 25)
          .where(_.id)
          .eq(1)
          .build
        assertTrue(frag.sql.contains("update test_mutation set name = ?, age = ?")) &&
        assertTrue(frag.writes.size == 3) // name + age + id
      ,
      test("multiple where clauses AND together"):
        val frag = Update[TestUser]
          .set(_.status, "active")
          .where(_.age)
          .gte(18)
          .where(_.status)
          .neq("banned")
          .build
        assertTrue(frag.sql.contains("where")) &&
        assertTrue(frag.sql.contains(" and "))
      ,
      test(".all allows building without where"):
        val frag = Update[TestUser]
          .set(_.status, "inactive")
          .all
          .build
        assertTrue(frag.sql == "update test_mutation set status = ?") &&
        assertTrue(!frag.sql.contains("where"))
      ,
      test("returning appends RETURNING clause"):
        val frag = Update[TestUser]
          .set(_.name, "Bob")
          .where(_.id)
          .eq(1)
          .returning
        assertTrue(frag.sql.contains("returning *")),
    ),
    suite("Delete")(
      test("where generates correct SQL"):
        val frag = Delete[TestUser]
          .where(_.id)
          .eq(1)
          .build
        assertTrue(frag.sql.contains("delete from test_mutation")) &&
        assertTrue(frag.sql.contains("where"))
      ,
      test("multiple where clauses AND together"):
        val frag = Delete[TestUser]
          .where(_.status)
          .eq("inactive")
          .where(_.age)
          .lt(18)
          .build
        assertTrue(frag.sql.contains(" and "))
      ,
      test(".all allows building without where"):
        val frag = Delete[TestUser].all.build
        assertTrue(frag.sql == "delete from test_mutation") &&
        assertTrue(!frag.sql.contains("where"))
      ,
      test("returning appends RETURNING clause"):
        val frag = Delete[TestUser]
          .where(_.id)
          .eq(1)
          .returning
        assertTrue(frag.sql.contains("returning *")),
    ),
    suite("WHERE operators")(
      test("eq generates correct SQL"):
        val frag = Update[TestUser].set(_.name, "X").where(_.id).eq(1).build
        assertTrue(frag.sql.contains("="))
      ,
      test("neq generates correct SQL"):
        val frag = Update[TestUser].set(_.name, "X").where(_.status).neq("banned").build
        assertTrue(frag.sql.contains("<>"))
      ,
      test("lt generates correct SQL"):
        val frag = Update[TestUser].set(_.name, "X").where(_.age).lt(18).build
        assertTrue(frag.sql.contains("<"))
      ,
      test("lte generates correct SQL"):
        val frag = Update[TestUser].set(_.name, "X").where(_.age).lte(18).build
        assertTrue(frag.sql.contains("<="))
      ,
      test("gt generates correct SQL"):
        val frag = Update[TestUser].set(_.name, "X").where(_.age).gt(18).build
        assertTrue(frag.sql.contains(">"))
      ,
      test("gte generates correct SQL"):
        val frag = Update[TestUser].set(_.name, "X").where(_.age).gte(18).build
        assertTrue(frag.sql.contains(">="))
      ,
      test("isNull generates correct SQL"):
        val frag = Delete[TestUser].where(_.status).isNull().build
        assertTrue(frag.sql.contains("IS NULL"))
      ,
      test("isNotNull generates correct SQL"):
        val frag = Delete[TestUser].where(_.status).isNotNull().build
        assertTrue(frag.sql.contains("IS NOT NULL")),
    ),
  )

  // ============================================================================
  // Integration Tests (with PostgresTestContainer)
  // ============================================================================

  val integrationTests = suite("Integration Tests")(
    test("Insert.build.execute inserts a row"):
      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run(sql"drop table if exists test_mutation".dml)
        _  <- xa.run(sql"""create table test_mutation (
                id integer generated always as identity primary key,
                name varchar(255) not null,
                age integer not null,
                status varchar(50) not null
              )""".dml)
        insertResult <- xa.run(
          Insert[TestUser]
            .value(_.name, "Alice")
            .value(_.age, 30)
            .value(_.status, "active")
            .build
            .execute
        )
        queryResult <- xa.run(sql"select * from test_mutation where name = ${"Alice"}".queryOne[TestUser])
      yield assertTrue(insertResult == 1) &&
        assertTrue(queryResult.exists(_.name == "Alice")) &&
        assertTrue(queryResult.exists(_.age == 30))
    ,
    test("Insert.returning returns inserted row"):
      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run(sql"drop table if exists test_mutation".dml)
        _  <- xa.run(sql"""create table test_mutation (
                id integer generated always as identity primary key,
                name varchar(255) not null,
                age integer not null,
                status varchar(50) not null
              )""".dml)
        inserted <- xa.run(
          Insert[TestUser]
            .value(_.name, "Bob")
            .value(_.age, 25)
            .value(_.status, "pending")
            .returning
            .queryOne[TestUser]
        )
      yield assertTrue(inserted.exists(_.name == "Bob")) &&
        assertTrue(inserted.exists(_.id > 0))
    ,
    test("Update.build.execute updates rows"):
      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run(sql"drop table if exists test_mutation".dml)
        _  <- xa.run(sql"""create table test_mutation (
                id integer generated always as identity primary key,
                name varchar(255) not null,
                age integer not null,
                status varchar(50) not null
              )""".dml)
        _            <- xa.run(sql"insert into test_mutation (name, age, status) values ('Alice', 30, 'active')".dml)
        _            <- xa.run(sql"insert into test_mutation (name, age, status) values ('Bob', 25, 'active')".dml)
        updateResult <- xa.run(
          Update[TestUser]
            .set(_.status, "inactive")
            .where(_.name)
            .eq("Alice")
            .build
            .execute
        )
        aliceStatus <- xa.run(sql"select status from test_mutation where name = ${"Alice"}".queryValue[String])
        bobStatus   <- xa.run(sql"select status from test_mutation where name = ${"Bob"}".queryValue[String])
      yield assertTrue(updateResult == 1) &&
        assertTrue(aliceStatus.contains("inactive")) &&
        assertTrue(bobStatus.contains("active"))
    ,
    test("Update with multiple set clauses"):
      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run(sql"drop table if exists test_mutation".dml)
        _  <- xa.run(sql"""create table test_mutation (
                id integer generated always as identity primary key,
                name varchar(255) not null,
                age integer not null,
                status varchar(50) not null
              )""".dml)
        _            <- xa.run(sql"insert into test_mutation (name, age, status) values ('Alice', 30, 'active')".dml)
        updateResult <- xa.run(
          Update[TestUser]
            .set(_.name, "Alice Updated")
            .set(_.age, 31)
            .where(_.name)
            .eq("Alice")
            .build
            .execute
        )
        result <- xa.run(sql"select * from test_mutation".queryOne[TestUser])
      yield assertTrue(updateResult == 1) &&
        assertTrue(result.exists(_.name == "Alice Updated")) &&
        assertTrue(result.exists(_.age == 31))
    ,
    test("Update.all updates all rows"):
      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run(sql"drop table if exists test_mutation".dml)
        _  <- xa.run(sql"""create table test_mutation (
                id integer generated always as identity primary key,
                name varchar(255) not null,
                age integer not null,
                status varchar(50) not null
              )""".dml)
        _            <- xa.run(sql"insert into test_mutation (name, age, status) values ('Alice', 30, 'active')".dml)
        _            <- xa.run(sql"insert into test_mutation (name, age, status) values ('Bob', 25, 'active')".dml)
        updateResult <- xa.run(
          Update[TestUser]
            .set(_.status, "archived")
            .all
            .build
            .execute
        )
        archivedCount <- xa.run(sql"select count(*) from test_mutation where status = ${"archived"}".queryValue[Int])
      yield assertTrue(updateResult == 2) &&
        assertTrue(archivedCount.contains(2))
    ,
    test("Delete.build.execute deletes rows"):
      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run(sql"drop table if exists test_mutation".dml)
        _  <- xa.run(sql"""create table test_mutation (
                id integer generated always as identity primary key,
                name varchar(255) not null,
                age integer not null,
                status varchar(50) not null
              )""".dml)
        _            <- xa.run(sql"insert into test_mutation (name, age, status) values ('Alice', 30, 'active')".dml)
        _            <- xa.run(sql"insert into test_mutation (name, age, status) values ('Bob', 25, 'inactive')".dml)
        deleteResult <- xa.run(
          Delete[TestUser]
            .where(_.status)
            .eq("inactive")
            .build
            .execute
        )
        remaining <- xa.run(sql"select count(*) from test_mutation".queryValue[Int])
      yield assertTrue(deleteResult == 1) &&
        assertTrue(remaining.contains(1))
    ,
    test("Delete.all deletes all rows"):
      for
        xa <- ZIO.service[Transactor]
        _  <- xa.run(sql"drop table if exists test_mutation".dml)
        _  <- xa.run(sql"""create table test_mutation (
                id integer generated always as identity primary key,
                name varchar(255) not null,
                age integer not null,
                status varchar(50) not null
              )""".dml)
        _            <- xa.run(sql"insert into test_mutation (name, age, status) values ('Alice', 30, 'active')".dml)
        _            <- xa.run(sql"insert into test_mutation (name, age, status) values ('Bob', 25, 'active')".dml)
        deleteResult <- xa.run(
          Delete[TestUser].all.build.execute
        )
        remaining <- xa.run(sql"select count(*) from test_mutation".queryValue[Int])
      yield assertTrue(deleteResult == 2) &&
        assertTrue(remaining.contains(0)),
  ).provideShared(xaLayer) @@ TestAspect.sequential

  val spec = suite("Mutation DSL")(
    sqlGenerationTests,
    integrationTests,
  )

end MutationSpecs

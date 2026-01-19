package saferis.tests

import saferis.*
import zio.*
import zio.test.*
import PostgresTestContainer.DataSourceProvider

object PageSpecSpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default

  // Test table for unit tests (doesn't require database)
  @tableName("test_users")
  case class TestUser(@generated @key id: Long, name: String, age: Option[Int]) derives Table
  val testUsers = Table[TestUser]

  // Integration test table
  @tableName("page_spec_test_users")
  case class User(@generated @key id: Long, name: String, age: Option[Int]) derives Table
  val userTable = Table[User]

  // === SQL Generation Tests ===

  val sortSqlGenerationTests = suiteAll("Sort SQL generation"):
    test("ascending sort"):
      val sort = Sort(testUsers.name, SortOrder.Asc)
      assertTrue(sort.toSqlFragment.sql == "name asc")

    test("descending sort"):
      val sort = Sort(testUsers.name, SortOrder.Desc)
      assertTrue(sort.toSqlFragment.sql == "name desc")

    test("sort with nulls first"):
      val sort = Sort(testUsers.age, SortOrder.Asc, NullOrder.First)
      assertTrue(sort.toSqlFragment.sql == "age asc nulls first")

    test("sort with nulls last"):
      val sort = Sort(testUsers.age, SortOrder.Desc, NullOrder.Last)
      assertTrue(sort.toSqlFragment.sql == "age desc nulls last")

    test("sort with default null order emits no null clause"):
      val sort = Sort(testUsers.name, SortOrder.Asc, NullOrder.Default)
      assertTrue(!sort.toSqlFragment.sql.contains("nulls"))

  val seekSqlGenerationTests = suiteAll("Seek SQL generation"):
    test("seekAfter generates greater than predicate"):
      val sql = PageSpec[TestUser].seekAfter(testUsers.id, 100L).build.sql
      assertTrue(sql.contains("id >") && sql.contains("order by"))

    test("seekBefore generates less than predicate"):
      val sql = PageSpec[TestUser].seekBefore(testUsers.id, 100L).build.sql
      assertTrue(sql.contains("id <") && sql.contains("order by"))

    test("seek with ascending sort"):
      val sql = PageSpec[TestUser].seekAfter(testUsers.id, 100L, SortOrder.Asc).build.sql
      assertTrue(sql.contains("id asc"))

    test("seek with string value"):
      val sql = PageSpec[TestUser].seekAfter(testUsers.name, "Alice").build.sql
      assertTrue(sql.contains("name >") && sql.contains("order by"))

  val pageSpecSqlGenerationTests = suiteAll("PageSpec SQL generation"):
    test("empty spec generates basic select"):
      val sql = PageSpec[TestUser].build.sql
      assertTrue(sql == "select * from test_users")

    test("where clause"):
      val sql = PageSpec[TestUser].where(sql"active = true").build.sql
      assertTrue(sql.contains("where") && sql.contains("active = true"))

    test("multiple where clauses are ANDed"):
      val sql = PageSpec[TestUser]
        .where(sql"active = true")
        .where(sql"verified = true")
        .build
        .sql
      assertTrue(
        sql.contains("active = true") &&
          sql.contains(" and ") &&
          sql.contains("verified = true")
      )

    test("order by single column"):
      val sql = PageSpec[TestUser].orderBy(testUsers.name, SortOrder.Asc).build.sql
      assertTrue(sql.contains("order by") && sql.contains("name asc"))

    test("order by multiple columns"):
      val sql = PageSpec[TestUser]
        .orderBy(testUsers.name, SortOrder.Asc)
        .orderBy(testUsers.id, SortOrder.Desc)
        .build
        .sql
      assertTrue(sql.contains("name asc") && sql.contains("id desc"))

    test("limit only"):
      val sql = PageSpec[TestUser].limit(10).build.sql
      assertTrue(sql.contains("limit 10") && !sql.contains("offset"))

    test("offset only"):
      val sql = PageSpec[TestUser].offset(20).build.sql
      assertTrue(sql.contains("offset 20") && !sql.contains("limit"))

    test("limit and offset"):
      val sql = PageSpec[TestUser].limit(10).offset(20).build.sql
      assertTrue(sql.contains("limit 10") && sql.contains("offset 20"))

    test("seek adds where and order by"):
      val sql = PageSpec[TestUser].seekAfter(testUsers.id, 100L).build.sql
      assertTrue(sql.contains("where") && sql.contains(">") && sql.contains("order by"))

    test("combined where, seek, and explicit sort"):
      val sql = PageSpec[TestUser]
        .where(sql"active = true")
        .orderBy(testUsers.name, SortOrder.Desc)
        .seekAfter(testUsers.id, 100L)
        .limit(10)
        .build
        .sql
      assertTrue(
        sql.contains("active = true") &&
          sql.contains("id >") &&
          sql.contains("name desc") &&
          sql.contains("limit 10")
      )

  // === Edge Cases ===

  val edgeCaseTests = suiteAll("Edge cases"):
    test("zero limit"):
      val sql = PageSpec[TestUser].limit(0).build.sql
      assertTrue(sql.contains("limit 0"))

    test("zero offset"):
      val sql = PageSpec[TestUser].offset(0).build.sql
      assertTrue(sql.contains("offset 0"))

    test("large offset value"):
      val sql = PageSpec[TestUser].offset(Long.MaxValue).build.sql
      assertTrue(sql.contains(Long.MaxValue.toString))

    test("empty where fragment is ignored"):
      val sql = PageSpec[TestUser]
        .where(sql"")
        .where(sql"active = true")
        .build
        .sql
      // Should not have dangling "and" or empty predicate
      assertTrue(!sql.contains("where  and") && !sql.contains("where and"))

    test("no predicates means no where clause"):
      val sql = PageSpec[TestUser].limit(10).build.sql
      assertTrue(!sql.contains("where"))

    test("no sorts means no order by clause"):
      val sql = PageSpec[TestUser].limit(10).build.sql
      assertTrue(!sql.contains("order by"))

    test("multiple seeks create multiple conditions"):
      val sql = PageSpec[TestUser]
        .seek(testUsers.id, SeekDir.Gt, 100L)
        .seek(testUsers.name, SeekDir.Gt, "Alice")
        .build
        .sql
      assertTrue(sql.contains("id >") && sql.contains("name >"))

    test("immutability - original spec unchanged"):
      val base       = PageSpec[TestUser]
      val withLimit  = base.limit(10)
      val withOffset = base.offset(20)
      assertTrue(
        base.build.sql != withLimit.build.sql &&
          base.build.sql != withOffset.build.sql &&
          withLimit.build.sql != withOffset.build.sql
      )

  // === Column Extension Tests ===

  val columnExtensionTests = suiteAll("Column extensions"):
    test("asc extension creates ascending sort"):
      val sort = testUsers.name.asc
      assertTrue(sort.toSqlFragment.sql == "name asc")

    test("desc extension creates descending sort"):
      val sort = testUsers.name.desc
      assertTrue(sort.toSqlFragment.sql == "name desc")

    test("ascNullsFirst extension"):
      val sort = testUsers.age.ascNullsFirst
      assertTrue(sort.toSqlFragment.sql == "age asc nulls first")

    test("ascNullsLast extension"):
      val sort = testUsers.age.ascNullsLast
      assertTrue(sort.toSqlFragment.sql == "age asc nulls last")

    test("descNullsFirst extension"):
      val sort = testUsers.age.descNullsFirst
      assertTrue(sort.toSqlFragment.sql == "age desc nulls first")

    test("descNullsLast extension"):
      val sort = testUsers.age.descNullsLast
      assertTrue(sort.toSqlFragment.sql == "age desc nulls last")

    test("gt extension creates seek with Gt direction"):
      val seek = testUsers.id.gt(100L)
      assertTrue(seek.direction == SeekDir.Gt && seek.value == 100L)

    test("lt extension creates seek with Lt direction"):
      val seek = testUsers.id.lt(50L)
      assertTrue(seek.direction == SeekDir.Lt && seek.value == 50L)

    test("PageSpec with extension-based sort"):
      val sql = PageSpec[TestUser].orderBy(testUsers.name.ascNullsLast).build.sql
      assertTrue(sql.contains("name asc nulls last"))

    test("PageSpec with extension-based seek via seek(Seek)"):
      val sql = PageSpec[TestUser].seek(testUsers.id.gt(100L)).build.sql
      assertTrue(sql.contains("id >") && sql.contains("order by"))

    test("PageSpec with multiple extension-based sorts"):
      val sql = PageSpec[TestUser]
        .orderBy(testUsers.age.descNullsLast)
        .orderBy(testUsers.name.asc)
        .build
        .sql
      assertTrue(sql.contains("age desc nulls last") && sql.contains("name asc"))

  // === Integration Test Helpers ===

  def createTestTable(xa: Transactor) =
    xa.run(sql"drop table if exists page_spec_test_users".dml) *>
      xa.run(sql"""create table page_spec_test_users (
      id bigint generated always as identity primary key,
      name varchar(255) not null,
      age integer
    )""".dml)

  def insertUsers(xa: Transactor, count: Int) =
    ZIO.foreach(1 to count): i =>
      xa.run(sql"insert into page_spec_test_users (name, age) values (${s"User$i"}, ${20 + i})".dml)

  def insertUsersWithNames(xa: Transactor, names: List[String]) =
    ZIO.foreach(names): name =>
      xa.run(sql"insert into page_spec_test_users (name, age) values ($name, 25)".dml)

  def insertUsersWithNullAges(xa: Transactor) =
    for
      _ <- xa.run(sql"insert into page_spec_test_users (name, age) values ('WithAge1', 20)".dml)
      _ <- xa.run(sql"insert into page_spec_test_users (name, age) values ('WithAge2', 30)".dml)
      _ <- xa.run(sql"insert into page_spec_test_users (name, age) values ('NullAge1', null)".dml)
      _ <- xa.run(sql"insert into page_spec_test_users (name, age) values ('NullAge2', null)".dml)
    yield ()

  // === Integration Tests ===

  val offsetPaginationTests = suiteAll("Offset pagination integration"):
    test("returns correct page size"):
      for
        xa   <- ZIO.service[Transactor]
        _    <- createTestTable(xa)
        _    <- insertUsers(xa, 20)
        page <- xa.run(PageSpec[User].orderBy(userTable.id).limit(5).query[User])
      yield assertTrue(page.size == 5)

    test("offset skips correct number of rows"):
      for
        xa    <- ZIO.service[Transactor]
        _     <- createTestTable(xa)
        _     <- insertUsers(xa, 20)
        page1 <- xa.run(PageSpec[User].orderBy(userTable.id).limit(5).query[User])
        page2 <- xa.run(PageSpec[User].orderBy(userTable.id).limit(5).offset(5).query[User])
      yield assertTrue(page2.head.id > page1.last.id)

    test("last page returns remaining rows"):
      for
        xa       <- ZIO.service[Transactor]
        _        <- createTestTable(xa)
        _        <- insertUsers(xa, 23)
        lastPage <- xa.run(PageSpec[User].orderBy(userTable.id).limit(10).offset(20).query[User])
      yield assertTrue(lastPage.size == 3)

    test("offset beyond data returns empty"):
      for
        xa    <- ZIO.service[Transactor]
        _     <- createTestTable(xa)
        _     <- insertUsers(xa, 10)
        empty <- xa.run(PageSpec[User].orderBy(userTable.id).offset(100).query[User])
      yield assertTrue(empty.isEmpty)

  val seekPaginationTests = suiteAll("Seek pagination integration"):
    test("seekAfter returns rows after cursor"):
      for
        xa  <- ZIO.service[Transactor]
        _   <- createTestTable(xa)
        _   <- insertUsers(xa, 20)
        all <- xa.run(PageSpec[User].orderBy(userTable.id).query[User])
        cursor = all(9).id // 10th user's id
        results <- xa.run(PageSpec[User].seekAfter(userTable.id, cursor).limit(5).query[User])
      yield assertTrue(results.forall(_.id > cursor) && results.size == 5)

    test("seekBefore returns rows before cursor"):
      for
        xa  <- ZIO.service[Transactor]
        _   <- createTestTable(xa)
        _   <- insertUsers(xa, 20)
        all <- xa.run(PageSpec[User].orderBy(userTable.id).query[User])
        cursor = all(10).id // 11th user's id
        results <- xa.run(PageSpec[User].seekBefore(userTable.id, cursor).limit(5).query[User])
      yield assertTrue(results.forall(_.id < cursor))

    test("seek on non-existent cursor returns empty"):
      for
        xa      <- ZIO.service[Transactor]
        _       <- createTestTable(xa)
        _       <- insertUsers(xa, 10)
        results <- xa.run(PageSpec[User].seekAfter(userTable.id, 1000000L).query[User])
      yield assertTrue(results.isEmpty)

    test("seek with string column"):
      for
        xa      <- ZIO.service[Transactor]
        _       <- createTestTable(xa)
        _       <- insertUsersWithNames(xa, List("Alice", "Bob", "Carol", "Dave"))
        results <- xa.run(PageSpec[User].seekAfter(userTable.name, "Bob").query[User])
      yield assertTrue(results.map(_.name).toSet == Set("Carol", "Dave"))

    test("seek using extension method"):
      for
        xa  <- ZIO.service[Transactor]
        _   <- createTestTable(xa)
        _   <- insertUsers(xa, 20)
        all <- xa.run(PageSpec[User].orderBy(userTable.id).query[User])
        cursor = all(9).id
        results <- xa.run(PageSpec[User].seek(userTable.id.gt(cursor)).limit(5).query[User])
      yield assertTrue(results.forall(_.id > cursor) && results.size == 5)

  val combinedOperationsTests = suiteAll("Combined operations integration"):
    test("where + orderBy + limit"):
      for
        xa     <- ZIO.service[Transactor]
        _      <- createTestTable(xa)
        _      <- insertUsers(xa, 20) // ages 21-40
        adults <- xa.run(
          PageSpec[User]
            .where(sql"${userTable.age} >= 30")
            .orderBy(userTable.age, SortOrder.Asc)
            .limit(5)
            .query[User]
        )
      yield assertTrue(adults.forall(_.age.exists(_ >= 30)) && adults.size == 5)

    test("where + seek + limit"):
      for
        xa  <- ZIO.service[Transactor]
        _   <- createTestTable(xa)
        _   <- insertUsers(xa, 20)
        all <- xa.run(PageSpec[User].orderBy(userTable.id).query[User])
        cursor = all(4).id // 5th user
        results <- xa.run(
          PageSpec[User]
            .where(sql"${userTable.age} >= 25")
            .seekAfter(userTable.id, cursor)
            .limit(5)
            .query[User]
        )
      yield assertTrue(results.forall(u => u.age.exists(_ >= 25) && u.id > cursor))

    test("multiple sort columns"):
      for
        xa      <- ZIO.service[Transactor]
        _       <- createTestTable(xa)
        _       <- xa.run(sql"insert into page_spec_test_users (name, age) values ('Alice', 30)".dml)
        _       <- xa.run(sql"insert into page_spec_test_users (name, age) values ('Bob', 30)".dml)
        _       <- xa.run(sql"insert into page_spec_test_users (name, age) values ('Carol', 25)".dml)
        results <- xa.run(
          PageSpec[User]
            .orderBy(userTable.age, SortOrder.Desc)
            .orderBy(userTable.name, SortOrder.Asc)
            .query[User]
        )
      yield
        // Age 30 first (Alice, Bob), then age 25 (Carol)
        // Within age 30, alphabetically: Alice before Bob
        assertTrue(results.map(_.name) == Seq("Alice", "Bob", "Carol"))

    test("combined extensions with table columns"):
      for
        xa      <- ZIO.service[Transactor]
        _       <- createTestTable(xa)
        _       <- xa.run(sql"insert into page_spec_test_users (name, age) values ('Alice', 30)".dml)
        _       <- xa.run(sql"insert into page_spec_test_users (name, age) values ('Bob', 30)".dml)
        _       <- xa.run(sql"insert into page_spec_test_users (name, age) values ('Carol', 25)".dml)
        results <- xa.run(
          PageSpec[User]
            .orderBy(userTable.age.desc)
            .orderBy(userTable.name.asc)
            .query[User]
        )
      yield assertTrue(results.map(_.name) == Seq("Alice", "Bob", "Carol"))

  val nullHandlingTests = suiteAll("Null handling integration"):
    test("nulls last in ascending sort"):
      for
        xa      <- ZIO.service[Transactor]
        _       <- createTestTable(xa)
        _       <- insertUsersWithNullAges(xa)
        results <- xa.run(
          PageSpec[User]
            .orderBy(userTable.age, SortOrder.Asc, NullOrder.Last)
            .query[User]
        )
      yield assertTrue(results.takeRight(2).forall(_.age.isEmpty))

    test("nulls first in descending sort"):
      for
        xa      <- ZIO.service[Transactor]
        _       <- createTestTable(xa)
        _       <- insertUsersWithNullAges(xa)
        results <- xa.run(
          PageSpec[User]
            .orderBy(userTable.age, SortOrder.Desc, NullOrder.First)
            .query[User]
        )
      yield assertTrue(results.take(2).forall(_.age.isEmpty))

    test("nulls last using extension"):
      for
        xa      <- ZIO.service[Transactor]
        _       <- createTestTable(xa)
        _       <- insertUsersWithNullAges(xa)
        results <- xa.run(PageSpec[User].orderBy(userTable.age.ascNullsLast).query[User])
      yield assertTrue(results.takeRight(2).forall(_.age.isEmpty))

  val emptyTableTests = suiteAll("Empty table"):
    test("query on empty table returns empty seq"):
      for
        xa      <- ZIO.service[Transactor]
        _       <- createTestTable(xa)
        results <- xa.run(PageSpec[User].query[User])
      yield assertTrue(results.isEmpty)

    test("queryOne on empty table returns None"):
      for
        xa     <- ZIO.service[Transactor]
        _      <- createTestTable(xa)
        result <- xa.run(PageSpec[User].queryOne[User])
      yield assertTrue(result.isEmpty)

  // Combine all test suites
  val spec = suite("PageSpec")(
    sortSqlGenerationTests,
    seekSqlGenerationTests,
    pageSpecSqlGenerationTests,
    edgeCaseTests,
    columnExtensionTests,
    offsetPaginationTests,
    seekPaginationTests,
    combinedOperationsTests,
    nullHandlingTests,
    emptyTableTests,
  ).provideShared(xaLayer) @@ TestAspect.sequential

end PageSpecSpecs

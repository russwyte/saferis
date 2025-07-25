package saferis.tests

import saferis.*
import saferis.dml.*
import zio.*
import zio.test.*

object DataManipulationLayerSpecs extends ZIOSpecDefault:
  val xaLayer = DataSourceProvider.default >>> Transactor.default

  val dmlTests = suiteAll("should handle DML operations"):

    // Insert operations
    test("insert basic record"):
      @tableName("test_dml_insert_basic")
      case class TestTable(@key id: Int, name: String, age: Option[Int]) derives Table

      for
        xa <- ZIO.service[Transactor]
        // Create table first
        _ <- xa.run:
          sql"drop table if exists test_dml_insert_basic".dml
        _ <- xa.run:
          sql"""create table test_dml_insert_basic (
                  id integer primary key,
                  name varchar(255) not null,
                  age integer
                )""".dml
        // Test basic insert
        insertResult <- xa.run:
          insert(TestTable(1, "Test User", Some(30)))
        // Verify the record was inserted
        queryResult <- xa.run:
          sql"select * from test_dml_insert_basic where id = 1".queryOne[TestTable]
      yield assertTrue(insertResult == 1) &&
        assertTrue(queryResult.contains(TestTable(1, "Test User", Some(30))))
      end for

    test("insert with generated primary key"):
      @tableName("test_dml_insert_generated")
      case class GeneratedTable(@generated @key id: Int, name: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          sql"drop table if exists test_dml_insert_generated".dml
        _ <- xa.run:
          sql"""create table test_dml_insert_generated (
                  id integer generated always as identity primary key,
                  name varchar(255) not null
                )""".dml
        // Test insertReturning with generated key
        insertedRecord <- xa.run:
          insertReturning(GeneratedTable(-1, "Generated Test"))
      yield assertTrue(insertedRecord.name == "Generated Test") &&
        assertTrue(insertedRecord.id > 0)
      end for

    test("insert with optional fields"):
      @tableName("test_dml_insert_optional")
      case class OptionalTable(@key id: Int, name: String, email: Option[String]) derives Table

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          sql"drop table if exists test_dml_insert_optional".dml
        _ <- xa.run:
          sql"""create table test_dml_insert_optional (
                  id integer primary key,
                  name varchar(255) not null,
                  email varchar(255)
                )""".dml
        // Insert with Some value
        result1 <- xa.run:
          insert(OptionalTable(1, "John", Some("john@example.com")))
        // Insert with None value
        result2 <- xa.run:
          insert(OptionalTable(2, "Jane", None))
        // Verify both records
        query1 <- xa.run:
          sql"select * from test_dml_insert_optional where id = 1".queryOne[OptionalTable]
        query2 <- xa.run:
          sql"select * from test_dml_insert_optional where id = 2".queryOne[OptionalTable]
      yield assertTrue(result1 == 1) &&
        assertTrue(result2 == 1) &&
        assertTrue(query1.contains(OptionalTable(1, "John", Some("john@example.com")))) &&
        assertTrue(query2.contains(OptionalTable(2, "Jane", None)))
      end for
    // Update operations
    test("update by key"):
      @tableName("test_dml_update_key")
      case class UpdateTable(@key id: Int, name: String, age: Int) derives Table

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          sql"drop table if exists test_dml_update_key".dml
        _ <- xa.run:
          sql"""create table test_dml_update_key (
                  id integer primary key,
                  name varchar(255) not null,
                  age integer not null
                )""".dml
        // Insert initial record
        _ <- xa.run:
          insert(UpdateTable(1, "Original Name", 25))
        // Update the record
        updateResult <- xa.run:
          update(UpdateTable(1, "Updated Name", 30))
        // Verify the update
        queryResult <- xa.run:
          sql"select * from test_dml_update_key where id = 1".queryOne[UpdateTable]
      yield assertTrue(updateResult == 1) &&
        assertTrue(queryResult.contains(UpdateTable(1, "Updated Name", 30)))
      end for

    test("update with custom where clause"):
      @tableName("test_dml_update_where")
      case class UpdateWhereTable(@key id: Int, name: String, category: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          sql"drop table if exists test_dml_update_where".dml
        _ <- xa.run:
          sql"""create table test_dml_update_where (
                  id integer primary key,
                  name varchar(255) not null,
                  category varchar(255) not null
                )""".dml
        // Insert test records
        _ <- xa.run:
          insert(UpdateWhereTable(1, "Item 1", "A"))
        _ <- xa.run:
          insert(UpdateWhereTable(2, "Item 2", "A"))
        _ <- xa.run:
          insert(UpdateWhereTable(3, "Item 3", "B"))
        // Update all records in category A
        updateResult <- xa.run:
          updateWhere(UpdateWhereTable(0, "Updated Item", "A"), sql"category = ${"A"}")
        // Verify updates
        countA <- xa.run:
          sql"select count(*) as count from test_dml_update_where where name = ${"Updated Item"}".queryOne[CountResult]
        countB <- xa.run:
          sql"select count(*) as count from test_dml_update_where where category = ${"B"} and name != ${"Updated Item"}"
            .queryOne[CountResult]
      yield assertTrue(updateResult == 2) && // Should update 2 records
        assertTrue(countA.map(_.count).contains(2)) &&
        assertTrue(countB.map(_.count).contains(1))
      end for

    test("update returning"):
      @tableName("test_dml_update_returning")
      case class UpdateReturningTable(@key id: Int, name: String, version: Int) derives Table

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          sql"drop table if exists test_dml_update_returning".dml
        _ <- xa.run:
          sql"""create table test_dml_update_returning (
                  id integer primary key,
                  name varchar(255) not null,
                  version integer not null
                )""".dml
        // Insert initial record
        _ <- xa.run:
          insert(UpdateReturningTable(1, "Test", 1))
        // Update and return the updated record
        updatedRecord <- xa.run:
          updateReturning(UpdateReturningTable(1, "Updated Test", 2))
      yield assertTrue(updatedRecord.name == "Updated Test") &&
        assertTrue(updatedRecord.version == 2)
      end for
    // Delete operations
    test("delete by key"):
      @tableName("test_dml_delete_key")
      case class DeleteTable(@key id: Int, name: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          sql"drop table if exists test_dml_delete_key".dml
        _ <- xa.run:
          sql"""create table test_dml_delete_key (
                  id integer primary key,
                  name varchar(255) not null
                )""".dml
        // Insert test record
        _ <- xa.run:
          insert(DeleteTable(1, "To be deleted"))
        // Delete the record
        deleteResult <- xa.run:
          delete(DeleteTable(1, "To be deleted"))
        // Verify deletion
        queryResult <- xa.run:
          sql"select * from test_dml_delete_key where id = 1".queryOne[DeleteTable]
      yield assertTrue(deleteResult == 1) &&
        assertTrue(queryResult.isEmpty)
      end for

    test("delete with custom where clause"):
      @tableName("test_dml_delete_where")
      case class DeleteWhereTable(@key id: Int, name: String, status: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          sql"drop table if exists test_dml_delete_where".dml
        _ <- xa.run:
          sql"""create table test_dml_delete_where (
                  id integer primary key,
                  name varchar(255) not null,
                  status varchar(255) not null
                )""".dml
        // Insert test records
        _ <- xa.run:
          insert(DeleteWhereTable(1, "Item 1", "active"))
        _ <- xa.run:
          insert(DeleteWhereTable(2, "Item 2", "inactive"))
        _ <- xa.run:
          insert(DeleteWhereTable(3, "Item 3", "inactive"))
        // Delete all inactive records
        deleteResult <- xa.run:
          deleteWhere[DeleteWhereTable](sql"status = ${"inactive"}")
        // Verify deletion
        remainingCount <- xa.run:
          sql"select count(*) as count from test_dml_delete_where".queryOne[CountResult]
      yield assertTrue(deleteResult == 2) &&
        assertTrue(remainingCount.map(_.count).contains(1))
      end for

    test("delete returning"):
      @tableName("test_dml_delete_returning")
      case class DeleteReturningTable(@key id: Int, name: String, value: Int) derives Table

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          sql"drop table if exists test_dml_delete_returning".dml
        _ <- xa.run:
          sql"""create table test_dml_delete_returning (
                  id integer primary key,
                  name varchar(255) not null,
                  value integer not null
                )""".dml
        // Insert test record
        testRecord = DeleteReturningTable(1, "Test Record", 42)
        _ <- xa.run:
          insert(testRecord)
        // Delete and return the deleted record
        deletedRecord <- xa.run:
          deleteReturning(testRecord)
      yield assertTrue(deletedRecord.name == "Test Record") &&
        assertTrue(deletedRecord.value == 42)
      end for

    test("delete where returning multiple records"):
      @tableName("test_dml_delete_where_returning")
      case class DeleteWhereReturningTable(@key id: Int, name: String, category: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          sql"drop table if exists test_dml_delete_where_returning".dml
        _ <- xa.run:
          sql"""create table test_dml_delete_where_returning (
                  id integer primary key,
                  name varchar(255) not null,
                  category varchar(255) not null
                )""".dml
        // Insert test records
        _ <- xa.run:
          insert(DeleteWhereReturningTable(1, "Item 1", "A"))
        _ <- xa.run:
          insert(DeleteWhereReturningTable(2, "Item 2", "A"))
        _ <- xa.run:
          insert(DeleteWhereReturningTable(3, "Item 3", "B"))
        // Delete all records in category A and return them
        deletedRecords <- xa.run:
          deleteWhereReturning[DeleteWhereReturningTable](sql"category = ${"A"}")
      yield assertTrue(deletedRecords.length == 2) &&
        assertTrue(deletedRecords.forall(_.category == "A"))
      end for
    // Compound key operations
    test("operations with compound keys"):
      @tableName("test_dml_compound_key")
      case class CompoundKeyTable(@key userId: Int, @key roleId: Int, grantedAt: String) derives Table

      for
        xa <- ZIO.service[Transactor]
        _ <- xa.run:
          sql"drop table if exists test_dml_compound_key".dml
        _ <- xa.run:
          sql"""create table test_dml_compound_key (
                  userid integer not null,
                  roleid integer not null,
                  grantedat varchar(255) not null,
                  primary key (userid, roleid)
                )""".dml
        // Insert test data
        record1 = CompoundKeyTable(1, 2, "2023-01-01")
        record2 = CompoundKeyTable(1, 3, "2023-01-02")
        _ <- xa.run:
          insert(record1)
        _ <- xa.run:
          insert(record2)
        // Update using compound key
        updatedRecord = CompoundKeyTable(1, 2, "2023-01-15")
        updateResult <- xa.run:
          update(updatedRecord)
        // Delete using compound key
        deleteResult <- xa.run:
          delete(CompoundKeyTable(1, 3, "2023-01-02"))
        // Verify operations
        queryResult <- xa.run:
          sql"select * from test_dml_compound_key where userid = 1 and roleid = 2".queryOne[CompoundKeyTable]
        countResult <- xa.run:
          sql"select count(*) as count from test_dml_compound_key".queryOne[CountResult]
      yield assertTrue(updateResult == 1) &&
        assertTrue(deleteResult == 1) &&
        assertTrue(queryResult.contains(CompoundKeyTable(1, 2, "2023-01-15"))) &&
        assertTrue(countResult.map(_.count).contains(1))
      end for

  case class CountResult(count: Int) derives Table

  val spec = suite("DML Operations")(dmlTests).provideShared(xaLayer) @@ TestAspect.sequential
end DataManipulationLayerSpecs

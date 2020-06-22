package com.datastax.spark.connector.cql

import java.io.IOException

import com.datastax.spark.connector.SparkCassandraITWordSpecBase
import com.datastax.spark.connector.cluster.DefaultCluster
import com.datastax.spark.connector.types._
import com.datastax.spark.connector.util.schemaFromCassandra
import org.apache.commons.lang3.SerializationUtils
import org.scalatest.Inspectors._
import org.scalatest.OptionValues._

class SchemaSpec extends SparkCassandraITWordSpecBase with DefaultCluster {

  override lazy val conn = CassandraConnector(defaultConf)

  val altKeyspaceName = "another_keyspace"

  conn.withSessionDo { session =>
    createKeyspace(session)
    createKeyspace(session, altKeyspaceName)

    session.execute(
      s"""CREATE TYPE $ks.address (street varchar, city varchar, zip int)""")
    session.execute(
      s"""CREATE TABLE $ks.test(
         |  k1 int,
         |  k2 varchar,
         |  k3 timestamp,
         |  c1 bigint,
         |  c2 varchar,
         |  c3 uuid,
         |  d1_blob blob,
         |  d2_boolean boolean,
         |  d3_decimal decimal,
         |  d4_double double,
         |  d5_float float,
         |  d6_inet inet,
         |  d7_int int,
         |  d8_list list<int>,
         |  d9_map map<int, varchar>,
         |  d10_set set<int>,
         |  d11_timestamp timestamp,
         |  d12_uuid uuid,
         |  d13_timeuuid timeuuid,
         |  d14_varchar varchar,
         |  d15_varint varint,
         |  d16_address frozen<address>,
         |  PRIMARY KEY ((k1, k2, k3), c1, c2, c3)
         |)
      """.stripMargin)
    session.execute(
      s"""CREATE INDEX test_d9_map_idx ON $ks.test (keys(d9_map))""")
    session.execute(
      s"""CREATE INDEX test_d7_int_idx ON $ks.test (d7_int)""")
    session.execute(
      s"""CREATE TABLE $ks.another_test(k1 int, PRIMARY KEY (k1))""")
    session.execute(
      s"""CREATE TABLE $ks.yet_another_test(k1 int, PRIMARY KEY (k1))""")
  }

  val schema = schemaFromCassandra(conn)

  "A Schema" should {
    "allow to get a list of keyspaces" in {
      schema.keyspaces.map(_.keyspaceName) should contain(ks)
    }

    "allow to look up a keyspace by name" in {
      val keyspace = schema.keyspaceByName(ks)
      keyspace.keyspaceName shouldBe ks
    }

    "find the correct table using Schema.tableFromCassandra" in {
      conn.withSessionDo(s => {
        Schema.tableFromCassandra(s, ks, "test").tableName shouldBe "test"
        Schema.tableFromCassandra(s, ks, "another_test").tableName shouldBe "another_test"
        Schema.tableFromCassandra(s, ks, "yet_another_test").tableName shouldBe "yet_another_test"
      })
    }

    "enforce constraints in fromCassandra" in {
      conn.withSessionDo(s => {
        val selectedTableName = "yet_another_test"
        Schema.fromCassandra(s, None, None).keyspaceByName(ks).keyspaceName shouldBe ks
        Schema.fromCassandra(s, None, None).keyspaceByName(altKeyspaceName).keyspaceName shouldBe altKeyspaceName
        val schema1 = Schema.fromCassandra(s, Some(altKeyspaceName), None)
        schema1.keyspaces.size shouldBe 1
        schema1.keyspaces.head.keyspaceName shouldBe altKeyspaceName
        val schema2 = Schema.fromCassandra(s, Some(ks), Some(selectedTableName))
        schema2.keyspaces.size shouldBe 1
        schema2.keyspaces.head.keyspaceName shouldBe ks
        schema2.keyspaceByName(ks).tableByName.size shouldBe 1
        schema2.keyspaceByName(ks).tableByName(selectedTableName).tableName shouldBe selectedTableName
      })
    }

    "throw IOException for tableFromCassandra call with unknown table" in {
      assertThrows[IOException] {
        conn.withSessionDo(s => {
          Schema.tableFromCassandra(s, ks, "unknown_table")
        })
      }
    }
  }

  "A KeyspaceDef" should {

    "be serializable" in {
      SerializationUtils.roundtrip(schema.keyspaceByName(ks))
    }

    "allow to get a list of tables in the given keyspace" in {
      val keyspace = schema.keyspaceByName(ks)
      keyspace.tableByName.values.map(_.tableName).toSet shouldBe Set("another_test", "yet_another_test", "test")
    }

    "allow to look up a table by name" in {
      val keyspace = schema.keyspaceByName(ks)
      keyspace.tableByName("test").tableName shouldBe "test"
      keyspace.tableByName("another_test").tableName shouldBe "another_test"
      keyspace.tableByName("yet_another_test").tableName shouldBe "yet_another_test"
    }

    "allow to look up user type by name" in {
      val keyspace = schema.keyspaceByName(ks)
      val userType = keyspace.userTypeByName("address")
      userType.name shouldBe "address"
    }
  }

  "A TableDef" should {
    val keyspace = schema.keyspaceByName(ks)
    val table = keyspace.tableByName("test")

    "be serializable" in {
      SerializationUtils.roundtrip(table)
    }

    "list all columns" in {
      val colNames = table.columns.map(_.columnName)
      colNames.size shouldBe 22

      // Spot checks of a few column values only here
      colNames should contain("k2")
      colNames should contain("c3")
      colNames should contain("d12_uuid")
    }

    "allow to read column definitions by name" in {
      table.columnByName("k1").columnName shouldBe "k1"
    }

    "allow to read column definitions by index" in {
      table.columnByIndex(0).columnName shouldBe "k1"
      table.columnByIndex(4).columnName shouldBe "c2"
    }

    "allow to read primary key column definitions" in {
      table.primaryKey.size shouldBe 6
      table.primaryKey.map(_.columnName) shouldBe Seq(
        "k1", "k2", "k3", "c1", "c2", "c3")
      table.primaryKey.map(_.columnType) shouldBe
        Seq(IntType, VarCharType, TimestampType, BigIntType, VarCharType, UUIDType)
      table.primaryKey.forall(_.isPrimaryKeyColumn)
    }

    "allow to read partitioning key column definitions" in {
      table.partitionKey.size shouldBe 3
      table.partitionKey.map(_.columnName) shouldBe Seq("k1", "k2", "k3")
      forAll(table.partitionKey) { c => c.isPartitionKeyColumn shouldBe true }
      forAll(table.partitionKey) { c => c.isPrimaryKeyColumn shouldBe true }
    }

    "allow to read regular column definitions" in {
      val regularColumns = table.regularColumns
      regularColumns.size shouldBe 16
      regularColumns.map(_.columnName).toSet shouldBe Set(
        "d1_blob", "d2_boolean", "d3_decimal", "d4_double", "d5_float",
        "d6_inet", "d7_int", "d8_list", "d9_map", "d10_set",
        "d11_timestamp", "d12_uuid", "d13_timeuuid", "d14_varchar",
        "d15_varint", "d16_address")
    }

    "allow to read proper types of columns" in {
      table.columnByName("d1_blob").columnType shouldBe BlobType
      table.columnByName("d2_boolean").columnType shouldBe BooleanType
      table.columnByName("d3_decimal").columnType shouldBe DecimalType
      table.columnByName("d4_double").columnType shouldBe DoubleType
      table.columnByName("d5_float").columnType shouldBe FloatType
      table.columnByName("d6_inet").columnType shouldBe InetType
      table.columnByName("d7_int").columnType shouldBe IntType
      table.columnByName("d8_list").columnType shouldBe ListType(IntType)
      table.columnByName("d9_map").columnType shouldBe MapType(IntType, VarCharType)
      table.columnByName("d10_set").columnType shouldBe SetType(IntType)
      table.columnByName("d11_timestamp").columnType shouldBe TimestampType
      table.columnByName("d12_uuid").columnType shouldBe UUIDType
      table.columnByName("d13_timeuuid").columnType shouldBe TimeUUIDType
      table.columnByName("d14_varchar").columnType shouldBe VarCharType
      table.columnByName("d15_varint").columnType shouldBe VarIntType
      table.columnByName("d16_address").columnType shouldBe a [UserDefinedType]
    }

    "allow to list fields of a user defined type" in {
      val udt = table.columnByName("d16_address").columnType.asInstanceOf[UserDefinedType]
      udt.columnNames shouldBe Seq("street", "city", "zip")
      udt.columnTypes shouldBe Seq(VarCharType, VarCharType, IntType)
    }

    "should not recognize column with collection index as indexed" in {
      table.indexedColumns.size shouldBe 1
      table.indexedColumns.head.columnName shouldBe "d7_int"
    }

    "should hold all indices retrieved from cassandra" in {
      table.indexes.size shouldBe 2
    }

    "have a sane check for missing columns" in {
      val missing1 = table.missingColumns(Seq("k1", "c2", "d12_uuid"))
      missing1.size shouldBe 0
      val missing2 = table.missingColumns(Seq("k1", "c2", "d12_uuid", "made_up_column_name"))
      missing2.size shouldBe 1
      missing2.head.columnName shouldBe "made_up_column_name"
      val missing3 = table.missingColumns(Seq("k1", "another_made_up_column_name", "c2", "d12_uuid", "made_up_column_name"))
      missing3.size shouldBe 2
      missing3.head.columnName shouldBe "another_made_up_column_name"
      missing3.tail.head.columnName shouldBe "made_up_column_name"
    }

    "support generating a DefaultTableDef" in {
      val defaultDef = DefaultTableDef.fromDriverDef(table.asInstanceOf[DriverTableDef])
      defaultDef.keyspaceName shouldBe table.keyspaceName
      defaultDef.tableName shouldBe table.tableName

      defaultDef.partitionKey.map(_.columnName) shouldBe table.partitionKey.map(_.columnName)
      defaultDef.clusteringColumns.map(_.columnName) shouldBe table.clusteringColumns.map(_.columnName)
      defaultDef.regularColumns.map(_.columnName) shouldBe table.regularColumns.map(_.columnName)
      defaultDef.primaryKey.map(_.columnName) shouldBe table.primaryKey.map(_.columnName)
      defaultDef.indexes.map(_.indexName) shouldBe table.indexes.map(_.indexName)
    }
  }

  "A ColumnDef" should {

    val keyspace = schema.keyspaceByName(ks)
    val table = keyspace.tableByName("test")
    val column = table.columnByName("c2")

    "be serializable" in {
      SerializationUtils.roundtrip(column)
    }

    "correctly find it's index if it's a clustering column" in {
      column.componentIndex.value shouldBe 1
    }

    "return None if it's not a clustering column" in {
      table.columnByName("k1").componentIndex shouldBe None
    }
  }
}

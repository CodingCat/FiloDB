package filodb.cassandra.metastore

import com.typesafe.config.ConfigFactory
import com.websudos.phantom.dsl._
import com.websudos.phantom.testkit._
import filodb.coordinator.{MetadataException, Success}
import org.scalatest.BeforeAndAfter
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

import filodb.core._
import filodb.core.metadata.Column

class ColumnTableSpec extends CassandraFlatSpec with BeforeAndAfter {
  import Column.ColumnType

  val firstColumn = Column("first", "foo", 1, ColumnType.StringColumn)

  val config = ConfigFactory.load("application_test.conf").getConfig("cassandra")
  val columnTable = new ColumnTable(config)
  implicit val keySpace = KeySpace(config.getString("keyspace"))

  // First create the columns table
  override def beforeAll() {
    super.beforeAll()
    // Note: This is a CREATE TABLE IF NOT EXISTS
    columnTable.initialize().futureValue
  }

  before {
    columnTable.clearAll().futureValue
  }

  import scala.concurrent.ExecutionContext.Implicits.global

  "ColumnTable" should "return empty schema if a dataset does not exist in columns table" in {
    columnTable.getSchema("foo", 1).futureValue should equal (Map())
  }

  it should "add the first column and read it back as a schema" in {
    columnTable.insertColumn(firstColumn).futureValue should equal (Success)
    columnTable.getSchema("foo", 2).futureValue should equal (Map("first" -> firstColumn))

    // Check that limiting the getSchema query to version 0 does not return the version 1 column
    columnTable.getSchema("foo", 0).futureValue should equal (Map())
  }

  it should "return MetadataException if illegal column type encoded in Cassandra" in {
    val f = columnTable.insert.value(_.dataset, "bar")
                              .value(_.name, "age")
                              .value(_.version, 5)
                              .value(_.columnType, "_so_not_a_real_type")
                              .future()
    f.futureValue

    columnTable.getSchema("bar", 7).failed.futureValue shouldBe a [MetadataException]
  }
}

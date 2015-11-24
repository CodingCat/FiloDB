package filodb.cassandra.metastore

import com.typesafe.config.ConfigFactory
import com.websudos.phantom.dsl._
import com.websudos.phantom.testkit._
import filodb.coordinator.{NotFoundError, Success, AlreadyExists}
import filodb.core.store.Dataset
import org.scalatest.BeforeAndAfter
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

import filodb.core._

class DatasetTableSpec extends CassandraFlatSpec with BeforeAndAfter {
  val config = ConfigFactory.load("application_test.conf").getConfig("cassandra")
  val datasetTable = new ProjectionTable(config)
  implicit val keySpace = KeySpace(config.getString("keyspace"))

  // First create the datasets table
  override def beforeAll() {
    super.beforeAll()
    // Note: This is a CREATE TABLE IF NOT EXISTS
    datasetTable.initialize().futureValue
  }

  before {
    datasetTable.clearAll().futureValue
  }

  val fooDataset = Dataset("foo", "someSortCol")

  import scala.concurrent.ExecutionContext.Implicits.global

  "DatasetTable" should "create a dataset successfully, then return AlreadyExists" in {
    whenReady(datasetTable.createNewDataset(fooDataset)) { response =>
      response should equal (Success)
    }

    // Second time around, dataset already exists
    whenReady(datasetTable.createNewDataset(fooDataset)) { response =>
      response should equal (AlreadyExists)
    }
  }

  // Apparently, deleting a nonexisting dataset also returns success.  :/

  it should "delete a dataset" in {
    whenReady(datasetTable.createNewDataset(fooDataset)) { response =>
      response should equal (Success)
    }
    whenReady(datasetTable.deleteDataset("foo")) { response =>
      response should equal (Success)
    }

    whenReady(datasetTable.getDataset("foo").failed) { err =>
      err shouldBe a [NotFoundError]
    }
  }

  it should "return NotFoundError when trying to get nonexisting dataset" in {
    whenReady(datasetTable.getDataset("foo").failed) { err =>
      err shouldBe a [NotFoundError]
    }
  }

  it should "return the Dataset if it exists" in {
    val barDataset = Dataset("bar", "sortCol")
    datasetTable.createNewDataset(barDataset).futureValue should equal (Success)

    whenReady(datasetTable.getDataset("bar")) { dataset =>
      dataset should equal (barDataset)
    }
  }
}

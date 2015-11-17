package filodb.core.memtable

import com.typesafe.config.ConfigFactory
import org.velvia.filo.TupleRowReader

import filodb.core.metadata._
import filodb.core.store.{MetaStore, Dataset}
import scala.concurrent.Future

import org.scalatest.{FunSpec, Matchers, BeforeAndAfter}
import org.scalatest.concurrent.ScalaFutures

class MapDBMemTableSpec extends FunSpec with Matchers with BeforeAndAfter with ScalaFutures {
  import filodb.core.Setup._
  val keyRange = KeyRange(0L,1000000L)
  val config = ConfigFactory.load("application_test.conf")
  import scala.concurrent.ExecutionContext.Implicits.global

  var resp: Int = 0

  before {
    resp = -1
  }

  val schemaWithPartCol = schema ++ Seq(
    Column("league", "dataset", 0, Column.ColumnType.StringColumn)
  )

  val namesWithPartCol = (0 until 50).flatMap { partNum =>
    names.map { t => (t._1, t._2, t._3, Some(partNum.toString)) }
  }

  val projWithPartCol = MetaStore.projectionInfo[String,Long](dataset.copy(partitionColumn = "league"), schemaWithPartCol)

  val namesWithNullPartCol =
    util.Random.shuffle(namesWithPartCol ++ namesWithPartCol.take(3).map { t => (t._1, t._2, t._3, None) })

  // Must be more than the max-rows-per-table setting in application_test.conf
  val lotsOfNames = (0 until 400).flatMap { partNum =>
    names.map { t => (t._1, t._2, t._3, Some(partNum.toString)) }
  }

  describe("insertRows, readRows, flip") {
    it("should insert out of order rows and read them back in order") {
      val mTable = new MapDBMemTable(projection, config)
      mTable.numRows should be (0)

      mTable.ingestRows(names.map(TupleRowReader)) { resp = 2 }
      resp should equal (2)

      mTable.numRows should equal (6)

      val outRows = mTable.readRows(Dataset.DefaultPartitionKey,keyRange)
      outRows.toSeq.map(_.getString(0)) should equal (firstNames)
    }

    it("should replace rows and read them back in order") {
      val mTable = new MapDBMemTable(projection, config)
      mTable.ingestRows(names.take(4).map(TupleRowReader)) { resp = 1 }
      resp should equal (1)
      mTable.ingestRows(names.take(2).map(TupleRowReader)) { resp = 3 }
      resp should equal (3)

      mTable.numRows should equal (4)

      val outRows = mTable.readRows(Dataset.DefaultPartitionKey,keyRange)
      outRows.toSeq.map(_.getString(0)) should equal (Seq("Khalil", "Rodney", "Ndamukong", "Jerry"))
    }

    it("should ingest into multiple partitions using partition column") {
      val memTable = new MapDBMemTable(projWithPartCol, config)

      memTable.ingestRows(namesWithPartCol.map(TupleRowReader)) { resp = 66 }
      resp should equal (66)

      memTable.numRows should equal (50 * names.length)

      val outRows = memTable.readRows("5",keyRange)
      outRows.toSeq.map(_.getString(0)) should equal (firstNames)
    }

    it("should throw error if null partition col value and no defaultPartitionKey") {
      val mTable = new MapDBMemTable(projWithPartCol, config)

      intercept[Dataset.NullPartitionValue] {
        mTable.ingestRows(namesWithNullPartCol.map(TupleRowReader)) { resp = 22 }
      }
    }

    it("should use defaultPartitionKey if one provided and null part col value") {
      val newOptions = dataset.options.copy(defaultPartitionKey = Some("foobar"))
      val datasetWithDefPartKey = dataset.copy(options = newOptions, partitionColumn = "league")
      val newProj = MetaStore.projectionInfo[String,Long](datasetWithDefPartKey, schemaWithPartCol)
      val mTable = new MapDBMemTable(newProj, config)

      mTable.ingestRows(namesWithNullPartCol.map(TupleRowReader)) { resp = 99 }
      resp should equal (99)

      mTable.numRows should equal (namesWithNullPartCol.length)
      val outRows = mTable.readRows("foobar",keyRange)
      outRows.toSeq should have length (3)
    }
  }

  describe("removeRows") {
    it("should be able to delete rows") {
      val mTable = new MapDBMemTable(projection, config)
      mTable.ingestRows(names.map(TupleRowReader)) { resp = 17 }
      resp should equal (17)

      mTable.removeRows(Dataset.DefaultPartitionKey,keyRange)
      mTable.numRows should equal (0)
    }
  }
}

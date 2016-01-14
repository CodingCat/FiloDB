package filodb.core.store

import filodb.cassandra.CassandraTest
import filodb.cassandra.columnstore.CassandraColumnStore
import filodb.core.query.{Dataflow, SegmentedPartitionScanInfo}
import filodb.core.reprojector.Reprojector
import filodb.core.reprojector.Reprojector.SegmentFlush
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfter, Matchers}
import org.velvia.filo.TupleRowReader

import scala.concurrent.{Await, Future}
import scala.language.postfixOps

class CassandraColumnStoreSpec extends CassandraTest
with BeforeAndAfter with Matchers with ScalaFutures {
  implicit val rowReaderFactory: Dataflow.RowReaderFactory = Dataflow.DefaultReaderFactory

  import com.websudos.phantom.dsl._
  import filodb.core.Setup._

  import scala.concurrent.duration._

  def flushPartitions(mapColumnStore: ColumnStore, partitions: Iterator[SegmentFlush]): Seq[Boolean] = {
    partitions.map { flush =>
      Await.result(mapColumnStore.flushToSegment(flush), 100 seconds)
    }.toSeq
  }


  def checkResults(results: Seq[Boolean]): Unit = {
    results.foreach { r: Boolean =>
      r should be(true)
    }
  }

  var columnStore: CassandraColumnStore = null

  override def beforeAll() {
    super.beforeAll()
    columnStore = new CassandraColumnStore(keySpace, session)
    Await.result(columnStore.initialize, 10 seconds)
  }

  before {
    Await.result(columnStore.deleteProjectionData(projection), 10 seconds)
    Await.result(columnStore.clearAll, 10 seconds)
    Await.result(
      columnStore.getChunkTable(projection).initialize(),
      10 seconds)
  }

  override def afterAll(): Unit = {
    Await.result(columnStore.clearAll, 10 seconds)
  }


  describe("Concurrent flushes") {
    it("should NOT allow concurrent flushes to write against the same summary version") {
      val rows = names.map(TupleRowReader)
      val partitions = Reprojector.project(projection, rows.iterator)
        .toSeq.groupBy(f => f.partition)

      partitions.size should be(2)
      val results = flushPartitions(columnStore,
        partitions.values.flatten.iterator)
      checkResults(results)


      val rows1 = names2.map(TupleRowReader)
      val partitions1 = Reprojector.project(projection, rows1.iterator)
        .toSeq.groupBy(f => f.partition)
      partitions1.size should be(2)
      val flushes1 = partitions1.values.flatten
      val flush1 = flushes1.head

      val rows2 = names3.map(TupleRowReader)
      val partitions2 = Reprojector.project(projection, rows2.iterator)
        .toSeq.groupBy(f => f.partition)
      partitions2.size should be(2)
      val flushes2 = partitions2.values.flatten
      val flush2 = flushes2.head

      flush1.partition should be(flush2.partition)
      flush1.segment should be(flush2.segment)

      val (v1, v2, s1, s2) = Await.result(for {
      //now read the segment summary
        (version1, summary1) <- columnStore.getVersionAndSummaryWithDefaults(projection, flush1.partition, flush1.segment)
        (version2, summary2) <- columnStore.getVersionAndSummaryWithDefaults(projection, flush2.partition, flush2.segment)
      } yield (version1, version2, summary1, summary2), 100 seconds)
      //check we got the same version
      v1.get should be(v2.get)

      val (result1, result2) = Await.result(for {
        newChunk1 <- columnStore.newChunkFromSummary(projection, flush1.partition, flush1.segment, flush1, s1)
        newChunk2 <- columnStore.newChunkFromSummary(projection, flush2.partition, flush2.segment, flush2, s2)
        newSummary1 = s1.withKeys(newChunk1.chunkId, newChunk1.keys)
        newSummary2 = s2.withKeys(newChunk2.chunkId, newChunk2.keys)

        r1 <- columnStore.compareAndSwapSummaryAndChunk(projection,
          flush1.partition, flush1.segment, v1, columnStore.getNewSegmentVersion, newChunk1, newSummary1)
        r2 <- columnStore.compareAndSwapSummaryAndChunk(projection,
          flush2.partition, flush2.segment, v2, columnStore.getNewSegmentVersion, newChunk2, newSummary2)

      } yield (r1, r2), 100 seconds)
      result1 should be(true)
      result2 should be(false)

    }
  }



  describe("Store and read rows") {
    it("should store and read one flush properly with Partition Key And Segment Range") {

      val rows = names.map(TupleRowReader)
      val partitions = Reprojector.project(projection, rows.iterator)
        .toSeq.groupBy(f => f.partition)

      partitions.size should be(2)
      val flushes = partitions.values.flatten
      flushes.last.partition should be("US")
      flushes.head.partition should be("UK")
      val results = flushPartitions(columnStore, flushes.iterator)
      checkResults(results)

      val segments = Await.result(columnStore.readSegments(
        SegmentedPartitionScanInfo(projection, projection.columnNames, "US", keyRange))
        , 10 seconds)

      segments.length should be(2)
      val scan = segments.head
      scan.hasNext should be(true)
      val threeReaders = getMoreRows(scan, 2)
      scan.hasNext should be(false)
      val reader = threeReaders.head
      reader.getString(0) should be("US")
      reader.getString(1) should be("NY")
      reader.getString(2) should be("Rodney")
      reader.getLong(4) should be(25)

      val scan2 = segments.last
      scan2.hasNext should be(true)
      val threeMore = getMoreRows(scan2, 1)
      scan.hasNext should be(false)
      val reader1 = threeMore.last
      reader1.getString(0) should be("US")
      reader1.getString(1) should be("SF")
      reader1.getString(2) should be("Khalil")
      reader1.getLong(4) should be(24)

    }

    it("should store and read data from multiples flushes properly with overrides") {

      val rows = names.map(TupleRowReader)
      val rows2 = names2.map(TupleRowReader)
      val partitions = Reprojector.project(projection, rows.iterator)
        .toSeq.groupBy(f => f.partition)
      val partitions2 = Reprojector.project(projection, rows2.iterator)
        .toSeq.groupBy(f => f.partition)
      partitions.size should be(2)
      partitions2.size should be(2)
      val flushes1 = partitions.values.flatten
      val flushes2 = partitions2.values.flatten
      val results = flushPartitions(columnStore, flushes1.iterator)
      checkResults(results)
      val results2 = flushPartitions(columnStore, flushes2.iterator)
      checkResults(results2)

      val segments = Await.result(columnStore.readSegments(
        SegmentedPartitionScanInfo(projection, projection.columnNames, "US", keyRange)
      ), 10 seconds)

      segments.length should be(2)

      val scan2 = segments.last
      scan2.hasNext should be(true)
      val threeMore = getMoreRows(scan2, 1)
      scan2.hasNext should be(false)
      val reader1 = threeMore.last
      reader1.getString(0) should be("US")
      reader1.getString(1) should be("SF")
      reader1.getString(2) should be("Khalil")
      reader1.getString(3) should be("Khadri")
      reader1.getLong(4) should be(24)

    }

    it("should store and read data for Full TokenRange") {
      val rows = names.map(TupleRowReader)
      val rows2 = names2.map(TupleRowReader)

      val partitions = Reprojector.project(projection, rows.iterator)
        .toSeq.groupBy(f => f.partition)
      val partitions2 = Reprojector.project(projection, rows2.iterator)
        .toSeq.groupBy(f => f.partition)
      partitions.size should be(2)
      partitions2.size should be(2)
      val flushes1 = partitions.values.flatten
      val flushes2 = partitions2.values.flatten
      val results = flushPartitions(columnStore, flushes1.iterator)
      checkResults(results)
      val results2 = flushPartitions(columnStore, flushes2.iterator)
      checkResults(results2)


      val scanSplits = columnStore.getScanSplits(1, 1000, projection, projection.columnNames, None, None)

      val future = for {
        s <- scanSplits
        scans = s.flatMap(_.scans)
        res <- Future sequence scans.map(sc => columnStore.readSegments(sc))
      } yield res.flatten

      val segments: Seq[Dataflow] = Await.result(future, 10 seconds)

      segments.length should be(3)

      val scan2 = segments.head
      scan2.hasNext should be(true)
      val more = getMoreRows(scan2, 4)
      scan2.hasNext should be(false)
      val reader1 = more.last
      reader1.getString(0) should be("UK")
      reader1.getString(1) should be("LN")
      reader1.getString(2) should be("Helen")
      reader1.getLong(4) should be(29)

    }

    it("should store and read data for TokenRange with Partition") {
      val rows = names.map(TupleRowReader)
      val rows2 = names2.map(TupleRowReader)

      val partitions = Reprojector.project(projection, rows.iterator)
        .toSeq.groupBy(f => f.partition)
      val partitions2 = Reprojector.project(projection, rows2.iterator)
        .toSeq.groupBy(f => f.partition)
      partitions.size should be(2)
      partitions2.size should be(2)
      val flushes1 = partitions.values.flatten
      val flushes2 = partitions2.values.flatten
      val results = flushPartitions(columnStore, flushes1.iterator)
      checkResults(results)
      val results2 = flushPartitions(columnStore, flushes2.iterator)
      checkResults(results2)


      val scanSplits = columnStore.getScanSplits(1, 1000,
        projection, projection.columnNames, Some("US"), None)

      val future = for {
        s <- scanSplits
        scans = s.flatMap(_.scans)
        res <- Future sequence scans.map(sc => columnStore.readSegments(sc))
      } yield res.flatten

      val segments: Seq[Dataflow] = Await.result(future, 10 seconds)

      segments.length should be(2)

      val scan2 = segments.last
      scan2.hasNext should be(true)
      val threeMore = getMoreRows(scan2, 1)
      scan2.hasNext should be(false)
      val reader1 = threeMore.last
      reader1.getString(0) should be("US")
      reader1.getString(1) should be("SF")
      reader1.getString(2) should be("Khalil")
      reader1.getString(3) should be("Khadri")
      reader1.getLong(4) should be(24)
    }

    it("should store and read data for TokenRange with Partition And Segment Range") {
      val rows = names.map(TupleRowReader)
      val rows2 = names2.map(TupleRowReader)

      val partitions = Reprojector.project(projection, rows.iterator)
        .toSeq.groupBy(f => f.partition)
      val partitions2 = Reprojector.project(projection, rows2.iterator)
        .toSeq.groupBy(f => f.partition)
      partitions.size should be(2)
      partitions2.size should be(2)
      val flushes1 = partitions.values.flatten
      val flushes2 = partitions2.values.flatten
      val results = flushPartitions(columnStore, flushes1.iterator)
      checkResults(results)
      val results2 = flushPartitions(columnStore, flushes2.iterator)
      checkResults(results2)

      val scanSplits = columnStore.getScanSplits(1, 1000, projection,
        projection.columnNames, Some("US"), Some(keyRange))
      val future = for {
        s <- scanSplits
        scans = s.flatMap(_.scans)
        res <- Future sequence scans.map(sc => columnStore.readSegments(sc))
      } yield res.flatten

      val segments: Seq[Dataflow] = Await.result(future, 10 seconds)
      segments.length should be(2)

      val scan2 = segments.last
      scan2.hasNext should be(true)
      val threeMore = getMoreRows(scan2, 1)
      scan2.hasNext should be(false)
      val reader1 = threeMore.last
      reader1.getString(0) should be("US")
      reader1.getString(1) should be("SF")
      reader1.getString(2) should be("Khalil")
      reader1.getString(3) should be("Khadri")
      reader1.getLong(4) should be(24)
    }
  }

}

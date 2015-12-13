package filodb.core.metadata

import java.io._
import java.nio.ByteBuffer

import com.typesafe.scalalogging.slf4j.StrictLogging
import enumeratum.{Enum, EnumEntry}
import filodb.core.Types._
import it.unimi.dsi.io.ByteBufferInputStream

/**
 * Defines a column of data and its properties.
 *
 * ==Columns and versions==
 * It is uncommon for the schema to change much between versions.
 * A DDL might change the type of a column; a column might be deleted
 *
 * 1. A Column def remains in effect for subsequent versions unless
 * 2. It has been marked deleted in subsequent versions, or
 * 3. Its columnType or serializer has been changed, in which case data
 * from previous versions are discarded (due to incompatibility)
 *
 * ==System Columns and Names==
 * Column names starting with a colon (':') are reserved for "system" columns:
 * - ':deleted' - special bitmap column marking deleted rows
 * - ':inherited' - special bitmap column, 1 means this row was inherited from
 * previous versions due to a chunk operation, but doesn't belong to this version
 *
 */
case class Column(name: String,
                  dataset: String,
                  version: Int,
                  columnType: Column.ColumnType,
                  serializer: Column.Serializer = Column.Serializer.FiloSerializer,
                  isDeleted: Boolean = false,
                  isSystem: Boolean = false) {
  // More type safe than just using ==, if we ever change the type of ColumnId
  def hasId(id: ColumnId): Boolean = name == id

  /**
   * Has one of the properties other than name, dataset, version changed?
   * (Name and dataset have to be constant for comparison to even be valid)
   * (Since name has to be constant, can't change from system to nonsystem)
   */
  def propertyChanged(other: Column): Boolean =
    (columnType != other.columnType) ||
      (serializer != other.serializer) ||
      (isDeleted != other.isDeleted)


  def write(o: DataOutput): Unit = {
    o.writeUTF(name)
    o.writeUTF(dataset)
    o.writeUTF(columnType.entryName)
    o.writeInt(version)
  }

}

object Column extends StrictLogging {


  def read(i: DataInput): Column = {
    val name = i.readUTF()
    val dataset = i.readUTF()
    val colTypeStr = i.readUTF()
    val version = i.readInt()
    val columnType = ColumnType.withName(colTypeStr)
    Column(name, dataset, version, columnType)
  }

  def readSchema(b: ByteBuffer): Seq[Column] = {
    val in = new DataInputStream(new ByteBufferInputStream(b))
    readSchema(in)
  }

  def readSchema(i: DataInput): Seq[Column] = {
    val l = i.readInt()
    (0 until l).map(c => read(i))
  }

  def schemaAsByteBuffer(schema: Seq[Column]): ByteBuffer = {
    val baos = new ByteArrayOutputStream()
    val os = new DataOutputStream(baos)
    writeSchema(schema, os)
    os.flush()
    baos.flush()
    ByteBuffer.wrap(baos.toByteArray)
  }

  def writeSchema(schema: Seq[Column], o: DataOutput): Unit = {
    o.writeInt(schema.length)
    schema.foreach(c => c.write(o))
  }

  sealed trait ColumnType extends EnumEntry {
    // NOTE: due to a Spark serialization bug, this cannot be a val
    // (https://github.com/apache/spark/pull/7122)
    def clazz: Class[_]
  }

  object ColumnType extends Enum[ColumnType] {
    val values = findValues

    //scalastyle:off
    case object IntColumn extends ColumnType {
      def clazz = classOf[Int]
    }

    case object LongColumn extends ColumnType {
      def clazz = classOf[Long]
    }

    case object DoubleColumn extends ColumnType {
      def clazz = classOf[Double]
    }

    case object StringColumn extends ColumnType {
      def clazz = classOf[String]
    }

    case object BitmapColumn extends ColumnType {
      def clazz = classOf[Boolean]
    }

    //scalastyle:on
  }

  sealed trait Serializer extends EnumEntry

  object Serializer extends Enum[Serializer] {
    val values = findValues

    case object FiloSerializer extends Serializer

  }

  type Schema = Map[String, Column]
  val EmptySchema = Map.empty[String, Column]

  /**
   * Fold function used to compute a schema up from a list of Column instances.
   * Assumes the list of columns is sorted in increasing version.
   * Contains the business rules above.
   */
  def schemaFold(schema: Schema, newColumn: Column): Schema = {
    if (newColumn.isDeleted) {
      schema - newColumn.name
    }
    else if (schema.contains(newColumn.name)) {
      // See if newColumn changed anything from older definition
      if (newColumn.propertyChanged(schema(newColumn.name))) {
        schema ++ Map(newColumn.name -> newColumn)
      } else {
        logger.warn(s"Skipping illegal or redundant column definition: $newColumn")
        schema
      }
    } else {
      // really a new column definition, just add it
      schema ++ Map(newColumn.name -> newColumn)
    }
  }

  /**
   * Checks for any problems with a column about to be "created" or changed.
   * @param dataset the name of the dataset for which this column change applies
   * @param schema the latest schema from scanning all versions
   * @param column the Column to be validated
   * @return A Seq[String] of every reason the column is not valid
   */
  def invalidateNewColumn(dataset: String, schema: Schema, column: Column): Seq[String] = {
    def check(requirement: => Boolean, failMessage: String): Option[String] =
      if (requirement) None else Some(failMessage)

    val startsWithColon = column.name.startsWith(":")
    val alreadyHaveIt = schema contains column.name
    Seq(
      check((column.isSystem && startsWithColon) || (!column.isSystem && !startsWithColon),
        "Only system columns can start with a colon"),
      check(!alreadyHaveIt || (alreadyHaveIt && column.version > schema(column.name).version),
        "Cannot add column at version lower than latest definition"),
      check(!alreadyHaveIt || (alreadyHaveIt && column.propertyChanged(schema(column.name))),
        "Nothing changed from previous definition"),
      check(alreadyHaveIt || (!alreadyHaveIt && !column.isDeleted), "New column cannot be deleted")
    ).flatten
  }
}

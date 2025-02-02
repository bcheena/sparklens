package com.qubole.sparklens.app

import java.io.{BufferedInputStream, InputStream}
import java.net.URI

import com.ning.compress.lzf.LZFInputStream
import com.qubole.sparklens.QuboleJobListener
import com.qubole.sparklens.common.Json4sWrapper
import com.qubole.sparklens.helper.HDFSConfigHelper
import net.jpountz.lz4.LZ4BlockInputStream
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.SparkConf
import org.json4s.DefaultFormats
import org.xerial.snappy.SnappyInputStream
import org.apache.spark.scheduler._

class EventHistoryReporter(file: String, extraConf: List[(String, String)] = List.empty) extends {

  // This is using reflection in spark-2.0.0 ReplayListenerBus
  val busKlass: Class[_] = Class.forName("org.apache.spark.scheduler.ReplayListenerBus")
  val bus: Any = busKlass.newInstance()
  val addListenerMethod = busKlass.getMethod("addListener", classOf[Object])
  val conf = new SparkConf()
    .set("spark.sparklens.reporting.disabled", "false")
    .set("spark.sparklens.save.data", "true")

//  val out = new ReplayListenerBus()
//  extraConf.foreach(x => {
//    conf.set(x._1, x._2)
//  })

  println("Filename: " + file)
  val listener = new QuboleJobListener(conf)
  addListenerMethod.invoke(bus, listener)


  try {
    val replayMethod = busKlass.getMethod("replay", classOf[InputStream], classOf[String],
      classOf[Boolean])

    println("Replay Method : " + replayMethod.getName)
    replayMethod.invoke(bus, getDecodedInputStream(file, conf), file, boolean2Boolean(false))
  } catch {
    case _: NoSuchMethodException => // spark binaries are 2.1* and above
      val replayMethod = busKlass.getMethod("replay", classOf[InputStream], classOf[String],
        classOf[Boolean], classOf[String => Boolean])
      replayMethod.invoke(bus, getDecodedInputStream(file, conf), file, boolean2Boolean(false),
        getFilter _)
    case x: Exception => {
     println(s"Failed replaying events from ${file} [${x.getMessage}]")
    }
  }


  // Borrowed from CompressionCodecs in spark
  private def getDecodedInputStream(file: String, conf: SparkConf): InputStream = {

    val fs = FileSystem.get(new URI(file), HDFSConfigHelper.getHadoopConf(Some(conf)))
    val path = new Path(file)
    val bufStream = new BufferedInputStream(fs.open(path))

    //println(bufStream)
    val logName = path.getName.stripSuffix(".inprogress")
    val codecName: Option[String] = logName.split("\\.").tail.lastOption

    //println("Codec Name: " + codecName.getOrElse("No Codec"))
    codecName.getOrElse("") match {
      case "lz4" => new LZ4BlockInputStream(bufStream)
      case "lzf" => new LZFInputStream(bufStream)
      case "snappy" => new SnappyInputStream(bufStream)
      case _ => bufStream
    }
  }

  private def getFilter(eventString: String): Boolean = {
    implicit val formats = DefaultFormats
//    println("Filtered Events :" + eventFilter.contains(Json4sWrapper.parse(eventString).extract[Map[String, Any]].get("Event")
//      .get.asInstanceOf[String]))

    eventFilter.contains(Json4sWrapper.parse(eventString).extract[Map[String, Any]].get("Event")
      .get.asInstanceOf[String])


  }

  private def eventFilter: Set[String] = {
    Set(
      "SparkListenerTaskEnd",
      "SparkListenerApplicationStart",
      "SparkListenerApplicationEnd",
      "SparkListenerExecutorAdded",
      "SparkListenerExecutorRemoved",
      "SparkListenerJobStart",
      "SparkListenerJobEnd",
      "SparkListenerStageSubmitted",
      "SparkListenerStageCompleted",
      "org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionStart"
    )
  }

}

package spark.broadcast

import com.ning.compress.lzf.{LZFInputStream, LZFOutputStream}

import java.io._
import java.net._
import java.util.UUID

import it.unimi.dsi.fastutil.io.FastBufferedInputStream
import it.unimi.dsi.fastutil.io.FastBufferedOutputStream

import spark._
import spark.storage.StorageLevel

private[spark] class HttpBroadcast[T](@transient var value_ : T, isLocal: Boolean, id: Long)
extends Broadcast[T](id) with Logging with Serializable {
  
  def value = value_

  def blockId: String = "broadcast_" + id

  HttpBroadcast.synchronized {
    SparkEnv.get.blockManager.putSingle(blockId, value_, StorageLevel.MEMORY_AND_DISK, false)
  }

  if (!isLocal) { 
    HttpBroadcast.write(id, value_)
  }

  // Called by JVM when deserializing an object
  private def readObject(in: ObjectInputStream) {
    in.defaultReadObject()
    HttpBroadcast.synchronized {
      SparkEnv.get.blockManager.getSingle(blockId) match {
        case Some(x) => value_ = x.asInstanceOf[T]
        case None => {
          logInfo("Started reading broadcast variable " + id)
          val start = System.nanoTime
          value_ = HttpBroadcast.read[T](id)
          SparkEnv.get.blockManager.putSingle(blockId, value_, StorageLevel.MEMORY_AND_DISK, false)
          val time = (System.nanoTime - start) / 1e9
          logInfo("Reading broadcast variable " + id + " took " + time + " s")
        }
      }
    }
  }
}

private[spark] class HttpBroadcastFactory extends BroadcastFactory {
  def initialize(isMaster: Boolean) { HttpBroadcast.initialize(isMaster) }

  def newBroadcast[T](value_ : T, isLocal: Boolean, id: Long) =
    new HttpBroadcast[T](value_, isLocal, id)

  def stop() { HttpBroadcast.stop() }
}

private object HttpBroadcast extends Logging {
  private var initialized = false

  private var broadcastDir: File = null
  private var compress: Boolean = false
  private var bufferSize: Int = 65536
  private var serverUri: String = null
  private var server: HttpServer = null

  def initialize(isMaster: Boolean) {
    synchronized {
      if (!initialized) {
        bufferSize = System.getProperty("spark.buffer.size", "65536").toInt
        compress = System.getProperty("spark.broadcast.compress", "true").toBoolean
        if (isMaster) {
          createServer()
        }
        serverUri = System.getProperty("spark.httpBroadcast.uri")
        initialized = true
      }
    }
  }
  
  def stop() {
    synchronized {
      if (server != null) {
        server.stop()
        server = null
      }
      initialized = false
    }
  }

  private def createServer() {
    broadcastDir = Utils.createTempDir(Utils.getLocalDir)
    server = new HttpServer(broadcastDir)
    server.start()
    serverUri = server.uri
    System.setProperty("spark.httpBroadcast.uri", serverUri)
    logInfo("Broadcast server started at " + serverUri)
  }

  def write(id: Long, value: Any) {
    val file = new File(broadcastDir, "broadcast-" + id)
    val out: OutputStream = if (compress) {
      new LZFOutputStream(new FileOutputStream(file)) // Does its own buffering
    } else {
      new FastBufferedOutputStream(new FileOutputStream(file), bufferSize)
    }
    val bufferSize = System.getProperty("spark.kryo.broadcast.buffer.mb", "32").toInt * 1024 * 1024
    val ser = new KryoSerializer(bufferSize).newInstance()
    val serOut = ser.serializeStream(out)
    serOut.writeObject(value)
    serOut.close()
  }

  def read[T](id: Long): T = {
    val url = serverUri + "/broadcast-" + id
    var in = if (compress) {
      new LZFInputStream(new URL(url).openStream()) // Does its own buffering
    } else {
      new FastBufferedInputStream(new URL(url).openStream(), bufferSize)
    }

    val bufferSize = System.getProperty("spark.kryo.broadcast.buffer.mb", "32").toInt * 1024 * 1024
    val ser = new KryoSerializer(bufferSize).newInstance()
    val serIn = ser.deserializeStream(in)
    val obj = serIn.readObject[T]()
    serIn.close()
    obj
  }
}

package spark.scheduler.cluster

import java.io.{File, FileInputStream, FileOutputStream}

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet

import spark._
import spark.TaskState.TaskState
import spark.scheduler._
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * The main TaskScheduler implementation, for running tasks on a cluster. Clients should first call
 * start(), then submit task sets through the runTasks method.
 */
private[spark] class ClusterScheduler(val sc: SparkContext)
  extends TaskScheduler
  with Logging {

  // How often to check for speculative tasks
  val SPECULATION_INTERVAL = System.getProperty("spark.speculation.interval", "100").toLong

  val BLACKLIST_FAILED_HOSTS = System.getProperty("spark.blacklisthosts", "true").toBoolean

  val activeTaskSets = new HashMap[String, TaskSetManager]
  var activeTaskSetsQueue = new ArrayBuffer[TaskSetManager]

  val taskIdToTaskSetId = new HashMap[Long, String]
  val taskIdToSlaveId = new HashMap[Long, String]
  val taskSetTaskIds = new HashMap[String, HashSet[Long]]

  // Incrementing Mesos task IDs
  val nextTaskId = new AtomicLong(0)

  // Which hosts in the cluster are alive (contains hostnames)
  val hostsAlive = new HashSet[String]
  val blackListedHosts = new HashSet[String]

  // Which slave IDs we have executors on
  val slaveIdsWithExecutors = new HashSet[String]

  val slaveIdToHost = new HashMap[String, String]

  // JAR server, if any JARs were added by the user to the SparkContext
  var jarServer: HttpServer = null

  // URIs of JARs to pass to executor
  var jarUris: String = ""

  // Listener object to pass upcalls into
  var listener: TaskSchedulerListener = null

  var backend: SchedulerBackend = null

  val mapOutputTracker = SparkEnv.get.mapOutputTracker

  override def setListener(listener: TaskSchedulerListener) {
    this.listener = listener
  }

  def initialize(context: SchedulerBackend) {
    backend = context
  }

  def newTaskId(): Long = nextTaskId.getAndIncrement()

  override def start() {
    backend.start()

    if (System.getProperty("spark.speculation", "false") == "true") {
      new Thread("ClusterScheduler speculation check") {
        setDaemon(true)

        override def run() {
          while (true) {
            try {
              Thread.sleep(SPECULATION_INTERVAL)
            } catch {
              case e: InterruptedException => {}
            }
            checkSpeculatableTasks()
          }
        }
      }.start()
    }
  }

  def submitTasks(taskSet: TaskSet) {
    val tasks = taskSet.tasks
    logInfo("Adding task set " + taskSet.id + " with " + tasks.length + " tasks")
    this.synchronized {
      val manager = new TaskSetManager(this, taskSet)
      activeTaskSets(taskSet.id) = manager
      activeTaskSetsQueue += manager
      taskSetTaskIds(taskSet.id) = new HashSet[Long]()
    }
    backend.reviveOffers()
  }

  def taskSetFinished(manager: TaskSetManager) {
    this.synchronized {
      activeTaskSets -= manager.taskSet.id
      activeTaskSetsQueue -= manager
      taskIdToTaskSetId --= taskSetTaskIds(manager.taskSet.id)
      taskIdToSlaveId --= taskSetTaskIds(manager.taskSet.id)
      taskSetTaskIds.remove(manager.taskSet.id)
    }
  }

  /**
   * Called by cluster manager to offer resources on slaves. We respond by asking our active task
   * sets for tasks in order of priority. We fill each node with tasks in a round-robin manner so
   * that tasks are balanced across the cluster.
   */
  def resourceOffers(offers: Seq[WorkerOffer]): Seq[Seq[TaskDescription]] = {
    synchronized {
      SparkEnv.set(sc.env)
      // Mark each slave as alive and remember its hostname
      val filteredOffers = offers.filter{o =>
        if (blackListedHosts(o.hostname)) {
          logInfo("ignoring offer of " + o + " because host is blaclisted")
          false
        }
        else
          true
      }
      for (o <- filteredOffers) {
        slaveIdToHost(o.slaveId) = o.hostname
        hostsAlive += o.hostname
      }
      // Build a list of tasks to assign to each slave
      val tasks = filteredOffers.map(o => new ArrayBuffer[TaskDescription](o.cores))
      val availableCpus = filteredOffers.map(o => o.cores).toArray
      var launchedTask = false
      for (manager <- activeTaskSetsQueue.sortBy(m => (m.taskSet.priority, m.taskSet.stageId))) {
        do {
          launchedTask = false
          for (i <- 0 until filteredOffers.size) {
            val sid = filteredOffers(i).slaveId
            val host = filteredOffers(i).hostname
            manager.slaveOffer(sid, host, availableCpus(i)) match {
              case Some(task) =>
                tasks(i) += task
                val tid = task.taskId
                taskIdToTaskSetId(tid) = manager.taskSet.id
                taskSetTaskIds(manager.taskSet.id) += tid
                taskIdToSlaveId(tid) = sid
                slaveIdsWithExecutors += sid
                availableCpus(i) -= 1
                launchedTask = true

              case None => {}
            }
          }
        } while (launchedTask)
      }
      return tasks
    }
  }

  def statusUpdate(tid: Long, state: TaskState, serializedData: ByteBuffer) {
    var taskSetToUpdate: Option[TaskSetManager] = None
    var failedHost: Option[String] = None
    var taskFailed = false
    synchronized {
      try {
        if (state == TaskState.LOST && taskIdToSlaveId.contains(tid)) {
          // We lost the executor on this slave, so remember that it's gone
          val slaveId = taskIdToSlaveId(tid)
          val host = slaveIdToHost(slaveId)
          if (hostsAlive.contains(host)) {
            slaveIdsWithExecutors -= slaveId
            hostsAlive -= host
            activeTaskSetsQueue.foreach(_.hostLost(host))
            failedHost = Some(host)
          }
        }
        taskIdToTaskSetId.get(tid) match {
          case Some(taskSetId) =>
            if (activeTaskSets.contains(taskSetId)) {
              //activeTaskSets(taskSetId).statusUpdate(status)
              taskSetToUpdate = Some(activeTaskSets(taskSetId))
            }
            if (TaskState.isFinished(state)) {
              taskIdToTaskSetId.remove(tid)
              if (taskSetTaskIds.contains(taskSetId)) {
                taskSetTaskIds(taskSetId) -= tid
              }
              taskIdToSlaveId.remove(tid)
            }
            if (state == TaskState.FAILED) {
              taskFailed = true
            }
          case None =>
            logInfo("Ignoring update from TID " + tid + " because its task set is gone")
        }
      } catch {
        case e: Exception => logError("Exception in statusUpdate", e)
      }
    }
    // Update the task set and DAGScheduler without holding a lock on this, because that can deadlock
    if (taskSetToUpdate != None) {
      taskSetToUpdate.get.statusUpdate(tid, state, serializedData)
    }
    if (failedHost != None) {
      listener.hostLost(failedHost.get)
    }
    if (taskFailed) {
      // Also revive offers if a task had failed for some reason other than host lost
      backend.reviveOffers()
    }
  }

  def error(message: String) {
    synchronized {
      if (activeTaskSets.size > 0) {
        // Have each task set throw a SparkException with the error
        for ((taskSetId, manager) <- activeTaskSets) {
          try {
            manager.error(message)
          } catch {
            case e: Exception => logError("Exception in error callback", e)
          }
        }
      } else {
        // No task sets are active but we still got an error. Just exit since this
        // must mean the error is during registration.
        // It might be good to do something smarter here in the future.
        logError("Exiting due to error from cluster scheduler: " + message)
        System.exit(1)
      }
    }
  }

  override def stop() {
    if (backend != null) {
      backend.stop()
    }
    if (jarServer != null) {
      jarServer.stop()
    }
  }

  override def defaultParallelism() = backend.defaultParallelism()
  
  // Check for speculatable tasks in all our active jobs.
  def checkSpeculatableTasks() {
    var shouldRevive = false
    synchronized {
      for (ts <- activeTaskSetsQueue) {
        shouldRevive |= ts.checkSpeculatableTasks()
      }
    }
    if (shouldRevive) {
      backend.reviveOffers()
    }
  }

  def slaveLost(slaveId: String) {
    var failedHost: Option[String] = None
    synchronized {
      val realSlaveId = ClusterScheduler.getRealSlaveId(slaveId)
      val hostOpt = slaveIdToHost.get(realSlaveId)
      hostOpt match {
        case None =>
          val msg = new StringBuilder()
          msg.append("Got slaveLost msg for slave: \"" + slaveId + "\", but no such slave exists.  Known Slaves:\n")
          slaveIdToHost.foreach{kv => msg.append(kv + "\n")}
          logWarning(msg.toString)
        case Some(host) =>
          if (hostsAlive.contains(host)) {
            slaveIdsWithExecutors -= realSlaveId
            hostsAlive -= host
            activeTaskSetsQueue.foreach(_.hostLost(host))
            failedHost = Some(host)
          }
      }
    }
    if (failedHost != None) {
      listener.hostLost(failedHost.get)
    }
  }

  def reviveOffers() {
    backend.reviveOffers()
  }
}


object ClusterScheduler {
  //the reported slaveId from mesos (at least in the current trunk) is weird: "value: \"201211021432-3171224074-5050-18217-18\"\n"
  val SLAVE_REGEX = """value: "([^"]*)"\n?""".r

  def getRealSlaveId(slaveId: String) = {
    slaveId match {
      case SLAVE_REGEX(realSlaveId) => realSlaveId
      case x => x
    }
  }

}

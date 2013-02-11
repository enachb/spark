package spark.executor

import spark.scheduler.Task

case class TaskMetrics(
  val totalBlocksFetched : Option[Int],
  val remoteBlocksFetched: Option[Int],
  val localBlocksFetched: Option[Int],
  val remoteFetchWaitTime: Option[Long],
  val remoteBytesRead: Option[Long],
  val shuffleBytesWritten: Option[Long],
  executorDeserializeTime: Int,
  executorRunTime:Int
) {

}

object TaskMetrics {
  private[spark] def apply(task: Task[_], executorStart: Long, taskStart: Long, taskFinish: Long) : TaskMetrics = {
    TaskMetrics(
      task.totalBlocksFetched,
      task.remoteBlocksFetched,
      task.localBlocksFetched,
      task.remoteFetchWaitTime,
      task.remoteReadBytes,
      task.shuffleBytesWritten,
      (taskStart - executorStart).toInt,
      (taskFinish - taskStart).toInt
    )
  }
}
package spark.scheduler.cluster

/**
 * Represents free resources available on a worker node.
 */
private[spark]
class WorkerOffer(val slaveId: String, val hostname: String, val cores: Int) {
  override
  def toString() = {
    "(" + slaveId + "," + hostname + "," + cores + ")"
  }
}

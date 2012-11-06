package spark.scheduler.cluster

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers

/**
 *
 */

class ClusterSchedulerSuite extends FunSuite with ShouldMatchers {
  test("getRealSlaveId") {
    val origSlaveLost = "value: \"201211021432-3171224074-5050-18217-18\"\n"
    ClusterScheduler.getRealSlaveId(origSlaveLost) should be ("201211021432-3171224074-5050-18217-18")
    ClusterScheduler.getRealSlaveId("xyz") should be ("xyz")
  }
}

package scalaz.stream
package performance

import org.scalameter.api._
import process1._

object Process1Perf extends PerformanceTest.Microbenchmark {
  val n = Gen.range("n")(1000, 25000, 4000)

  performance of "process1" in {
    measure method "stripNoneOld" in {
      using(n) in {
        n => Process.range(0, n).map(i => if (i % 2 == 0) Some(i) else None).pipe(stripNoneOld).run.run
      }
    }
    measure method "stripNoneOldCollect" in {
      using(n) in {
        n => Process.range(0, n).map(i => if (i % 2 == 0) Some(i) else None).pipe(stripNoneOldCollect).run.run
      }
    }
    measure method "stripNone" in {
      using(n) in {
        n => Process.range(0, n).map(i => if (i % 2 == 0) Some(i) else None).pipe(stripNone).run.run
      }
    }
  }
}

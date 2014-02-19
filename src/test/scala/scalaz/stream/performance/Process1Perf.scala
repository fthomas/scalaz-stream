package scalaz.stream

import org.scalameter.api._
import process1._

object Process1Perf extends PerformanceTest.Microbenchmark {
  val n = Gen.range("n")(1000, 25000, 4000)

  performance of "process1" in {
/*    measure method "dropLastIfOld" in {
      using(n) in {
        n => Process.range(0, n).pipe(dropLastIfOld(_ => true)).run.run
      }
    }

    measure method "dropLastIf" in {
      using(n) in {
        n => Process.range(0, n).pipe(dropLastIf(_ => true)).run.run
      }
    }

    measure method "lastOld" in {
      using(n) in {
        n => Process.range(0, n).pipe(lastOld).run.run
      }
    }

    measure method "last" in {
      using(n) in {
        n => Process.range(0, n).pipe(last).run.run
      }
    }*/

    measure method "lastOrOld" in {
      using(n) in {
        n => Process.range(0, n).pipe(lastOrOld(42)).run.run
      }
    }

    measure method "lastOr" in {
      using(n) in {
        n => Process.range(0, n).pipe(lastOr(42)).run.run
      }
    }

  }

}

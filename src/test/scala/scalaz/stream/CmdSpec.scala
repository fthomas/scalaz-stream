package scalaz.stream

import org.scalacheck.Prop._
import org.scalacheck.Properties
import scodec.bits.ByteVector

import scalaz.concurrent.Task
import scalaz.stream.cmd._

object CmdSpec extends Properties("Cmd") {
  val linesIn: Process1[ByteVector, String] =
    text.utf8Decode |> text.lines()

  val linesOut: Process1[String, ByteVector] =
    text.utf8Encode

  def runLogList[O](p: Process[Task, O]): List[O] = {
    val timeOut = 3000L
    p.runLog.timed(timeOut).run.toList
  }
/*
  property("run only") = secure {
    spawnCmd("true").flatMap(_.proc).run.run
    true
  }

  property("lifecycle") = secure {
    val lifecycle = spawnCmd("true").flatMap { h =>
      h.state.once ++ h.proc.flatMap(_ => h.state.once) ++ h.state.once
    }
    val expected = List(NotRunning, Running, Exited(0))
    (runLogList(lifecycle) ?= expected) &&
      (runLogList(lifecycle) ?= expected)
  }

  property("empty stdout") = secure {
    val p = spawnCmd("true").flatMap(_.proc).flatMap(_.stdOut)
    runLogList(p) ?= List()
  }

  property("empty stderr") = secure {
    val p = spawnCmd("true").flatMap(_.proc).flatMap(_.stdErr)
    runLogList(p) ?= List()
  }

  property("exit status of 'true'") = secure {
    val p = spawnCmd("true").flatMap(h => h.proc.drain ++ h.state.once)
    runLogList(p) ?= List(Exited(0))
  }

  property("exit status of 'false'") = secure {
    val p = spawnCmd("false").flatMap(h => h.proc.drain ++ h.state.once)
    runLogList(p) ?= List(Exited(1))
  }

  property("echo once") = secure {
    val p = spawnCmd("echo", "Hello World")
      .flatMap(_.proc).flatMap(_.stdOut.repeat.once).pipe(linesIn)
    runLogList(p) ?= List("Hello World")
  }

  property("bc exit status") = secure {
    val p = spawnCmd("bc").flatMap(h => h.proc.drain ++ h.state.once)
    runLogList(p) ?= List(Exited(0))
  }

  property("bc terminates") = secure {
    spawnCmd("bc").flatMap(_.proc).run.run
    true
  }

  property("bc quit") = secure {
    val quit = Process("quit\n").pipe(linesOut).toSource
    val p = spawnCmd("bc").flatMap { h =>
      h.proc.flatMap(quit to _.stdIn) ++ h.state.once
    }
    runLogList(p) ?= List((), Exited(0))
  }

  property("bc add once") = secure {
    val add = Process("2 + 3\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      add.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    runLogList(p) ?= List("5")
  }

  property("bc syntax error") = secure {
    val calc = Process("2 +\n").pipe(linesOut).toSource
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      calc.to(sp.stdIn).drain ++ sp.stdErr.repeat.once
    }.pipe(linesIn)
    runLogList(p).nonEmpty
  }

  property("bc add twice, 1 pass") = secure {
    val add = Process("2 + 3\n", "3 + 5\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      add.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    runLogList(p) ?= List("5", "8")
  }

  property("bc add twice, 2 pass") = secure {
    val add1 = Process("2 + 3\n").pipe(linesOut).toSource
    val add2 = Process("3 + 5\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      add1.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        add2.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    runLogList(p) ?= List("5", "8")
  }
*/
  property("bc add twice, 2 pass, for comprehension") = secure {
    val add1 = Process("2 + 3\n").pipe(linesOut).toSource
    val add2 = Process("3 + 5\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource

    val p = for {
      sp <- spawnCmd("bc").flatMap(_.proc)
      _ <- add1.to(sp.stdIn)
      r1 <- sp.stdOut.repeat.once
      _ <- add2.to(sp.stdIn)
      r2 <- sp.stdOut.repeat.once
      _ <- quit.to(sp.stdIn)
      rn <- Process(r2, r1).pipe(linesIn) ++ Process.emit("!")
    } yield rn

    runLogList(p) ?= List("8", "5")
  }
/*
  property("bc nat") = secure {
    val quit = Process("quit\n").pipe(linesOut).toSource
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      def plus1(i: Int): Process[Task, Int] =
        Process(s"$i + 1\n").pipe(linesOut).toSource.to(sp.stdIn).drain ++
          sp.stdOut.repeat.once.pipe(linesIn).map(_.toInt)
      def nat(start: Int): Process[Task, Int] =
        plus1(start).flatMap(n => Process(n) ++ nat(n))
      nat(0) onComplete quit.to(sp.stdIn).drain
    }
    runLogList(p.take(50)) ?= List.range(1, 51)
  }

  property("yes terminates") = secure {
    spawnCmd("yes").flatMap(_.proc).run.run
    true
  }

  property("yes output") = secure {
    val p = spawnCmd("yes").flatMap(_.proc)
      .flatMap(_.stdOut.repeat.take(2)).pipe(linesIn).take(2)
    runLogList(p) ?= List("y", "y")
  }

  property("sleep") = secure {
    val start = System.currentTimeMillis()
    spawnCmd("sleep", "0.2").flatMap(_.proc).run.run
    val end = System.currentTimeMillis()
    end - start >= 200
  }

  property("sleep terminated") = secure {
    val start = System.currentTimeMillis()
    spawnCmd("sleep", "2").flatMap(_.proc.evalMap(_.destroy)).run.run
    val end = System.currentTimeMillis()
    end - start < 500
  }
  */
}

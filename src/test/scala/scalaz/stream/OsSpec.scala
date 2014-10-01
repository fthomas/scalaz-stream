package scalaz.stream

import org.scalacheck._
import org.scalacheck.Prop._
import scalaz.concurrent.Task
import scodec.bits.ByteVector

import os._

object OsSpec extends Properties("OsSpec") {
  val linesIn: Process1[ByteVector, String] =
    text.utf8Decode |> text.lines()

  val linesOut: Process1[String, ByteVector] =
    text.utf8Encode

  def runLogList[O](p: Process[Task, O]): List[O] = {
    val timeOut = 3000L
    p.runLog.timed(timeOut).run.toList
  }

  property("run only") = secure {
    spawnCmd("true").flatMap(_.proc).run.run
    true
  }

  property("lifecycle") = secure {
    val lifecycle = spawnCmd("true").flatMap { s =>
      s.state.once ++ s.proc.flatMap(_ => s.state.once) ++ s.state.once
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

  property("exit value of 'true'") = secure {
    val p = spawnCmd("true").flatMap(s => s.proc.drain ++ s.state.once)
    runLogList(p) ?= List(Exited(0))
  }

  property("exit value of 'false'") = secure {
    val p = spawnCmd("false").flatMap(s => s.proc.drain ++ s.state.once)
    runLogList(p) ?= List(Exited(1))
  }

  property("echo once") = secure {
    val p = spawnCmd("echo", "Hello World")
      .flatMap(_.proc).flatMap(_.stdOut.repeat.once).pipe(linesIn)
    runLogList(p) ?= List("Hello World")
  }

  property("echo once delayed") = secure {
    val p = spawnCmd("bash", "-c", "sleep 0.5; echo World")
      .flatMap(_.proc).flatMap(_.stdOut.repeat.once).pipe(linesIn)
    runLogList(p) ?= List("World")
  }

  // failed in https://travis-ci.org/fthomas/scalaz-stream/jobs/36699854
  property("echo twice") = secure {
    val p = spawnCmd("sh", "-c", "echo Hello; echo World")
      .flatMap(_.proc).flatMap(_.stdOut.repeat.once).pipe(linesIn)
    runLogList(p) ?= List("Hello", "World")
  }

  property("echo twice delayed") = secure {
    val p = spawnCmd("bash", "-c", "echo Hello; sleep 0.5; echo World")
      .flatMap(_.proc).flatMap(_.stdOut.repeat.take(2)).pipe(linesIn)
    runLogList(p) ?= List("Hello", "World")
  }

  property("bc") = secure {
    val p = spawnCmd("bc").flatMap(s => s.proc.drain ++ s.state.once)
    runLogList(p) ?= List(Exited(0))
  }

  property("bc terminates") = secure {
    spawnCmd("bc").flatMap(_.proc).run.run
    true
  }

  property("bc quit") = secure {
    val quit = Process("quit\n").pipe(linesOut).liftIO
    val p = spawnCmd("bc").flatMap { s =>
      s.proc.flatMap(quit to _.stdIn) ++ s.state.once
    }
    runLogList(p) ?= List((), Exited(0))
  }

  property("bc add once") = secure {
    val add = Process("2 + 3\n").pipe(linesOut).liftIO
    val quit = Process("quit\n").pipe(linesOut).liftIO
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      add.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    runLogList(p) ?= List("5")
  }

  // failed in https://travis-ci.org/fthomas/scalaz-stream/jobs/36699851
  property("bc syntax error") = secure {
    val calc = Process("2 +\n").pipe(linesOut).liftIO
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      calc.to(sp.stdIn).drain ++ sp.stdErr.repeat.once
    }.pipe(linesIn)
    val l = runLogList(p)
    println(l)
    l.forall(_.contains("syntax error"))
  }

  property("bc add twice, 1 pass") = secure {
    val add = Process("2 + 3\n", "3 + 5\n").pipe(linesOut).liftIO
    val quit = Process("quit\n").pipe(linesOut).liftIO
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      add.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    runLogList(p) ?= List("5", "8")
  }

  property("bc add twice, 2 pass") = secure {
    val add1 = Process("2 + 3\n").pipe(linesOut).liftIO
    val add2 = Process("3 + 5\n").pipe(linesOut).liftIO
    val quit = Process("quit\n").pipe(linesOut).liftIO
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      add1.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        add2.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    runLogList(p) ?= List("5", "8")
  }

  property("bc nat") = secure {
    val quit = Process("quit\n").pipe(linesOut).liftIO
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      def plus1(i: Int): Process[Task, Int] =
        Process(s"$i + 1\n").pipe(linesOut).liftIO.to(sp.stdIn).drain ++
          sp.stdOut.repeat.once.pipe(linesIn).map(_.toInt)

      def nat(start: Int): Process[Task, Int] =
        plus1(start).flatMap(n => Process(n) ++ nat(n))

      nat(0) onComplete quit.to(sp.stdIn).drain
    }
    runLogList(p.take(50)) ?= List.range(1, 51)
  }

  property("sum seq") = secure {
    val seq = spawnCmd("seq", "10000").flatMap(_.proc)
      .flatMap(_.stdOut.repeat).pipe(linesIn).map(_.toInt)
    runLogList(seq.take(100).sum) ?= List(5050)
  }

  property("seq and bc") = secure {
    val quit = Process("quit\n").pipe(linesOut).liftIO
    val seq = spawnCmd("seq", "0", "10000").flatMap(_.proc)
      .flatMap(_.stdOut.repeat).pipe(linesIn)
    val prefixSums = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      def add(i: String) =
        Process(s"x = x + $i\n", "x\n").pipe(linesOut).liftIO.to(sp.stdIn).drain ++
          sp.stdOut.repeat.once.pipe(linesIn)
      seq.flatMap(add).onComplete(quit.to(sp.stdIn).drain)
    }.map(_.toInt)

    runLogList(prefixSums.take(101)) ?= List.range(1, 101).scan(0)(_ + _)
  }

  property("yes terminates") = secure {
    spawnCmd("yes").flatMap(_.proc).run.run
    true
  }

  property("yes output") = secure {
    val p = spawnCmd("yes").flatMap(_.proc)
      .flatMap(_.stdOut.repeat.once).pipe(linesIn).take(2)
    runLogList(p) ?= List("y", "y")
  }

  property("sleep") = secure {
    val start = System.currentTimeMillis()
    spawnCmd("sleep", "0.1").flatMap(_.proc).run.run
    val end = System.currentTimeMillis()
    end - start >= 100
  }

  property("sleep terminated") = secure {
    val start = System.currentTimeMillis()
    spawnCmd("sleep", "2").flatMap(s => s.proc yip s.destroy).run.run
    val end = System.currentTimeMillis()
    end - start < 500
  }

  property("terminated lifecycle") = secure {
    val p = spawnCmd("yes").flatMap { s =>
      s.state.once ++ s.proc.flatMap(_ => s.state.once).interleave(s.destroy.drain ++ s.state.once)
    }
    val expected = List(NotRunning, Running, Destroyed)
    (runLogList(p) ?= expected) &&
      (runLogList(p) ?= expected)
  }

  property("merge 2 seq") = secure {
    def seq(i: Int): Process[Task, Int] =
      spawnCmd("seq", s"$i", "2", "2000").flatMap(_.proc).flatMap(_.stdOut.repeat)
        .pipe(linesIn).map(_.toInt).take(500)

    val merged = seq(1).merge(seq(2))
    runLogList(merged).sorted ?= List.range(1, 1001)
  }

  property("merge 3 seq") = secure {
    def seq(i: Int): Process[Task, Int] =
      spawnCmd("seq", s"$i", "3", "2000").flatMap(_.proc).flatMap(_.stdOut.repeat)
        .pipe(linesIn).map(_.toInt).take(333)

    val merged = seq(1).merge(seq(2)).merge(seq(3))
    runLogList(merged).sorted ?= List.range(1, 1000)
  }

  property("merge 4 seq") = secure {
    def seq(i: Int): Process[Task, Int] =
      spawnCmd("seq", s"$i", "4", "2000").flatMap(_.proc).flatMap(_.stdOut.repeat)
        .pipe(linesIn).map(_.toInt).take(250)

    val merged = seq(1).merge(seq(2)).merge(seq(3).merge(seq(4)))
    runLogList(merged).sorted ?= List.range(1, 1001)
  }

  property("merge 5 seq") = secure {
    def seq(i: Int): Process[Task, Int] =
      spawnCmd("seq", s"$i", "5", "2000").flatMap(_.proc).flatMap(_.stdOut.repeat)
        .pipe(linesIn).map(_.toInt).take(200)

    val merged = seq(1).merge(seq(2)).merge(seq(3).merge(seq(4))).merge(seq(5))
    runLogList(merged).sorted ?= List.range(1, 1001)
  }

  property("interleave 4 seq") = secure {
    def seq(i: Int): Process[Task, Int] =
      spawnCmd("seq", s"$i", "4", "2000").flatMap(_.proc).flatMap(_.stdOut.repeat)
        .pipe(linesIn).map(_.toInt).take(250)

    val interleaved = seq(1).interleave(seq(2)).interleave(seq(3).interleave(seq(4)))
    runLogList(interleaved).sorted ?= List.range(1, 1001)
  }
}

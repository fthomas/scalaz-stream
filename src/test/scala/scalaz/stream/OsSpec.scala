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

  property("run only") = secure {
    spawnCmd("true").flatMap(_.proc).run.run
    true
  }

  property("lifecycle") = secure {
    val lifecycle = spawnCmd("true").flatMap { s =>
      s.state.once ++ s.proc.flatMap(_ => s.state.once) ++ s.state.once
    }
    val expected = List(NotRunning, Running, Exited(0))
    lifecycle.runLog.run.toList == expected &&
      lifecycle.runLog.run.toList == expected
  }

  property("empty stdout") = secure {
    val p = spawnCmd("true").flatMap(_.proc).flatMap(_.stdOut)
    p.runLog.run.toList == List()
  }

  property("empty stderr") = secure {
    val p = spawnCmd("true").flatMap(_.proc).flatMap(_.stdErr)
    p.runLog.run.toList == List()
  }

  property("exit value of 'true'") = secure {
    val p = spawnCmd("true").flatMap(s => s.proc.drain ++ s.state.once)
    p.runLog.run.toList == List(Exited(0))
  }

  property("exit value of 'false'") = secure {
    val p = spawnCmd("false").flatMap(s => s.proc.drain ++ s.state.once)
    p.runLog.run.toList == List(Exited(1))
  }

  property("echo once") = secure {
    val p = spawnCmd("echo", "Hello World")
      .flatMap(_.proc).flatMap(_.stdOut.repeat.once).pipe(linesIn)
    p.runLog.run.toList == List("Hello World")
  }

  property("echo twice") = secure {
    val p = spawnCmd("sh", "-c", "echo Hello; echo World")
      .flatMap(_.proc).flatMap(_.stdOut.repeat.once).pipe(linesIn)
    p.runLog.run.toList == List("Hello", "World")
  }

  property("echo twice delayed") = secure {
    val p = spawnCmd("bash", "-c", "echo Hello; sleep 0.5; echo World")
      .flatMap(_.proc).flatMap(_.stdOut.repeat.take(2)).pipe(linesIn)
    p.runLog.run.toList == List("Hello", "World")
  }

  property("bc") = secure {
    val p = spawnCmd("bc").flatMap(s => s.proc.drain ++ s.state.once)
    p.runLog.run.toList == List(Exited(0))
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
    p.runLog.run.toList == List((), Exited(0))
  }

  property("bc add once") = secure {
    val add = Process("2 + 3\n").pipe(linesOut).liftIO
    val quit = Process("quit\n").pipe(linesOut).liftIO
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      add.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    p.runLog.run.toList == List("5")
  }

  property("bc syntax error") = secure {
    val calc = Process("2 +\n").pipe(linesOut).liftIO
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      calc.to(sp.stdIn).drain ++ sp.stdErr.repeat.once
    }.pipe(linesIn)
    p.runLog.run.forall(_.contains("syntax error"))
  }

  property("bc add twice, 1 pass") = secure {
    val add = Process("2 + 3\n", "3 + 5\n").pipe(linesOut).liftIO
    val quit = Process("quit\n").pipe(linesOut).liftIO
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      add.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    p.runLog.run.toList == List("5", "8")
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
    p.runLog.run.toList == List("5", "8")
  }

  property("bc nat") = secure {
    val quit = Process("quit\n").pipe(linesOut).liftIO
    val p = spawnCmd("bc").flatMap(_.proc).flatMap { sp =>
      def plus1(i: Int): Process[Task, Int] =
        Process(s"$i + 1\n").pipe(linesOut).liftIO.to(sp.stdIn).drain ++
          sp.stdOut.repeat.once.pipe(linesIn).map(_.toInt)

      def nat(start: Int): Process[Task, Int] =
        plus1(start).flatMap(n => Process.emit(n) ++ nat(n))

      nat(0) onComplete quit.to(sp.stdIn).drain
    }
    p.take(50).runLog.run.toList == List.range(1, 51)
  }

  property("yes terminates") = secure {
    spawnCmd("yes").flatMap(_.proc).run.run
    true
  }

  property("yes output") = secure {
    val p = spawnCmd("yes").flatMap(_.proc)
      .flatMap(_.stdOut.repeat.once).pipe(linesIn).take(2)
    p.runLog.run.toList == List("y", "y")
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
    p.runLog.run.toList == expected &&
      p.runLog.run.toList == expected
  }
}

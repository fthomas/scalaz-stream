package scalaz.stream

import org.scalacheck._
import org.scalacheck.Prop._
import scodec.bits.ByteVector

import os._

object OsSpec extends Properties("OsSpec") {
  val linesIn: Process1[ByteVector, String] =
    text.utf8Decode |> text.lines()

  val linesOut: Process1[String, ByteVector] =
    text.utf8Encode

  property("run only") = secure {
    spawnCmd("true").proc.run.run
    true
  }

  property("lifecycle") = secure {
    val s = spawnCmd("true")
    val lifecycle = s.state.once ++ s.proc.flatMap(_ => s.state.once) ++ s.state.once
    println(lifecycle.runLog.run.toList)
    println(lifecycle.runLog.run.toList)
    lifecycle.runLog.run.toList == List(NotRunning, Running, Exited(0))
  }

  property("empty stdout") = secure {
    val p = spawnCmd("true").proc.flatMap(_.stdOut)
    p.runLog.run.toList == List()
  }

  property("empty stderr") = secure {
    val p = spawnCmd("true").proc.flatMap(_.stdErr)
    p.runLog.run.toList == List()
  }

  property("exit value of 'true'") = secure {
    val s = spawnCmd("true")
    (s.proc.drain ++ s.state.once).runLog.run.toList == List(Exited(0))
  }

  property("exit value of 'false'") = secure {
    val s = spawnCmd("false")
    (s.proc.drain ++ s.state.once).runLog.run.toList == List(Exited(1))
  }

  property("echo once") = secure {
    val s = spawnCmd("echo", "Hello World")
      .proc.flatMap(_.stdOut.repeat.once).pipe(linesIn)
    s.runLog.run.toList == List("Hello World")
  }

  property("echo twice") = secure {
    val s = spawnCmd("sh", "-c", "echo Hello; echo World")
      .proc.flatMap(_.stdOut.repeat.once).pipe(linesIn)
    s.runLog.run.toList == List("Hello", "World")
  }

  property("echo twice delayed") = secure {
    val s = spawnCmd("bash", "-c", "echo Hello; sleep 0.5; echo World")
      .proc.flatMap(_.stdOut.repeat.take(2)).pipe(linesIn)
    s.runLog.run.toList == List("Hello", "World")
  }

  property("bc") = secure {
    val s = spawnCmd("bc")
    val p = s.proc.drain
    p.runLog.run.toList == List() &&
      s.state.once.runLog.run.toList == List(Exited(0))
  }

  property("bc terminates") = secure {
    spawnCmd("bc").proc.run.run
    true
  }

  property("bc quit") = secure {
    val quit = Process("quit\n").pipe(linesOut).toSource
    val s = spawnCmd("bc")
    val p = s.proc.flatMap(quit to _.stdIn)
    p.runLog.run.toList == List(()) &&
      s.state.once.runLog.run.toList == List(Exited(0))
  }

  property("bc add once") = secure {
    val add = Process("2 + 3\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource
    val s = spawnCmd("bc")
    val p = s.proc.flatMap { sp =>
      add.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    p.runLog.run.toList == List("5") &&
      s.state.once.runLog.run.toList == List(Exited(0))
  }

  property("bc syntax error") = secure {
    val calc = Process("2 +\n").pipe(linesOut).toSource
    val s = spawnCmd("bc")
    val p = s.proc.flatMap { sp =>
      calc.to(sp.stdIn).drain ++
        sp.stdErr.repeat.once
    }.pipe(linesIn)
    p.runLog.run.forall(_.contains("syntax error")) &&
      s.state.once.runLog.run.toList == List(Exited(0))
  }

  property("bc add twice, 1 pass") = secure {
    val add = Process("2 + 3\n", "3 + 5\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource
    val s = spawnCmd("bc")
    val p = s.proc.flatMap { sp =>
      add.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    p.runLog.run.toList == List("5", "8") &&
      s.state.once.runLog.run.toList == List(Exited(0))
  }

  property("bc add twice, 2 pass") = secure {
    val add1 = Process("2 + 3\n").pipe(linesOut).toSource
    val add2 = Process("3 + 5\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource
    val s = spawnCmd("bc")
    val p = s.proc.flatMap { sp =>
      add1.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        add2.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    p.runLog.run.toList == List("5", "8")
  }

  property("yes terminates") = secure {
    spawnCmd("yes").proc.run.run
    true
  }

  property("yes output") = secure {
    val p = spawnCmd("yes").proc.flatMap(_.stdOut.repeat.once).pipe(linesIn).once
    p.runLog.run.toList == List("y")
  }

  property("sleep") = secure {
    val start = System.currentTimeMillis()
    spawnCmd("sleep", "0.1").proc.run.run
    val end = System.currentTimeMillis()
    end - start >= 100
  }

  property("sleep terminated") = secure {
    val start = System.currentTimeMillis()
    val s = spawnCmd("sleep", "0.1")
    s.proc.flatMap(_ => s.destroy).run.run
    val end = System.currentTimeMillis()
    end - start < 100
  }
}

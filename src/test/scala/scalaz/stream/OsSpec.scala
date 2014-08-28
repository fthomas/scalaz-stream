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
    execCmd("true").flatMap(_.proc).run.run
    true
  }

  property("lifecycle") = secure {
    val lifecycle = execCmd("true").flatMap { s =>
      s.state.once ++ s.proc.flatMap(_ => s.state.once) ++ s.state.once
    }
    val expected = List(NotRunning, Running, Exited(0))
    lifecycle.runLog.run.toList == expected &&
      lifecycle.runLog.run.toList == expected
  }

  property("empty stdout") = secure {
    val p = execCmd("true").flatMap(_.proc).flatMap(_.stdOut)
    p.runLog.run.toList == List()
  }

  property("empty stderr") = secure {
    val p = execCmd("true").flatMap(_.proc).flatMap(_.stdErr)
    p.runLog.run.toList == List()
  }

  property("exit value of 'true'") = secure {
    val p = execCmd("true").flatMap(s => s.proc.drain ++ s.state.once)
    p.runLog.run.toList == List(Exited(0))
  }

  property("exit value of 'false'") = secure {
    val p = execCmd("false").flatMap(s => s.proc.drain ++ s.state.once)
    p.runLog.run.toList == List(Exited(1))
  }

  property("echo once") = secure {
    val p = execCmd("echo", "Hello World")
      .flatMap(_.proc).flatMap(_.stdOut.repeat.once).pipe(linesIn)
    p.runLog.run.toList == List("Hello World")
  }

  property("echo twice") = secure {
    val p = execCmd("sh", "-c", "echo Hello; echo World")
      .flatMap(_.proc).flatMap(_.stdOut.repeat.once).pipe(linesIn)
    p.runLog.run.toList == List("Hello", "World")
  }

  property("echo twice delayed") = secure {
    val p = execCmd("bash", "-c", "echo Hello; sleep 0.5; echo World")
      .flatMap(_.proc).flatMap(_.stdOut.repeat.take(2)).pipe(linesIn)
    p.runLog.run.toList == List("Hello", "World")
  }

  property("bc") = secure {
    val p = execCmd("bc").flatMap(s => s.proc.drain ++ s.state.once)
    p.runLog.run.toList == List(Exited(0))
  }

  property("bc terminates") = secure {
    execCmd("bc").flatMap(_.proc).run.run
    true
  }

  property("bc quit") = secure {
    val quit = Process("quit\n").pipe(linesOut).toSource
    val p = execCmd("bc").flatMap { s =>
      s.proc.flatMap(quit to _.stdIn) ++ s.state.once
    }
    p.runLog.run.toList == List((), Exited(0))
  }

  property("bc add once") = secure {
    val add = Process("2 + 3\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource
    val p = execCmd("bc").flatMap(_.proc).flatMap { sp =>
      add.to(sp.stdIn).drain ++
      sp.stdOut.repeat.once ++
      quit.to(sp.stdIn).drain
    }.pipe(linesIn)

    p.runLog.run.toList == List("5")
  }

  property("bc syntax error") = secure {
    val calc = Process("2 +\n").pipe(linesOut).toSource
    val p = execCmd("bc").flatMap(_.proc).flatMap { sp =>
      calc.to(sp.stdIn).drain ++ sp.stdErr.repeat.once
    }.pipe(linesIn)
    p.runLog.run.forall(_.contains("syntax error"))
  }

  /*
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
  */
}

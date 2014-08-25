package scalaz.stream

import org.scalacheck._
import org.scalacheck.Prop._
import scalaz.std.list._
import scalaz.syntax.equal._
import scodec.bits.ByteVector

object OsSpec extends Properties("OsSpec") {
  val linesIn: Process1[ByteVector, String] =
    text.utf8Decode |> text.lines()

  val linesOut: Process1[String, ByteVector] =
    text.utf8Encode

  property("run only") = secure {
    os.spawnCmd("true").proc.run.run
    true
  }

  property("empty stdout") = secure {
    val p = os.spawnCmd("true").proc.flatMap(_.stdOut.pipe(linesIn))
    p.runLog.run.toList === List()
  }

  property("empty stderr") = secure {
    val p = os.spawnCmd("true").proc.flatMap(_.stdErr)
    p.runLog.run == List()
  }

  property("exit value of 'true'") = secure {
    val s = os.spawnCmd("true")
    (s.proc.drain ++ s.exitValue).runLog.run == List(0)
  }

  property("exit value of 'false'") = secure {
    val s = os.spawnCmd("false")
    (s.proc.drain ++ s.exitValue).runLog.run == List(1)
  }

  property("echo once") = secure {
    val s = os.spawnCmd("echo", "Hello World")
      .proc.flatMap(_.stdOut.repeat.once).pipe(linesIn)
    s.runLog.run == List("Hello World")
  }

  property("echo twice") = secure {
    val s = os.spawnCmd("sh", "-c", "echo Hello; echo World")
      .proc.flatMap(_.stdOut.repeat.once).pipe(linesIn)
    s.runLog.run == List("Hello", "World")
  }

  property("echo twice delayed") = secure {
    val s = os.spawnCmd("sh", "-c", "echo Hello; sleep 0.5; echo World")
      .proc.flatMap(_.stdOut.pipe(linesIn).repeat.takeThrough(_ != "World"))
    s.runLog.run == List("Hello", "World")
  }

  property("bc") = secure {
    val s = os.spawnCmd("bc")
    val p = s.proc.drain
    p.runLog.run == List() && s.exitValue.runLog.run == List(0)
  }

  property("bc terminates") = secure {
    os.spawnCmd("bc").proc.run.run
    true
  }

  property("bc quit") = secure {
    val quit = Process("quit\n").pipe(linesOut).toSource
    val s = os.spawnCmd("bc")
    val p = s.proc.flatMap(quit to _.stdIn)
    p.runLog.run == List(()) && s.exitValue.runLog.run == List(0)
  }

  property("bc add once") = secure {
    val add = Process("2 + 3\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource
    val s = os.spawnCmd("bc")
    val p = s.proc.flatMap { sp =>
      add.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    p.runLog.run == List("5") && s.exitValue.runLog.run == List(0)
  }

  property("bc syntax error") = secure {
    val calc = Process("2 +\n").pipe(linesOut).toSource
    val s = os.spawnCmd("bc")
    val p = s.proc.flatMap { sp =>
      calc.to(sp.stdIn).drain ++
        sp.stdErr.repeat.once
    }.pipe(linesIn)
    p.runLog.run.forall(_.contains("syntax error")) &&
      s.exitValue.runLog.run == List(0)
  }

  property("bc add twice, 1 pass") = secure {
    val add = Process("2 + 3\n", "3 + 5\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource
    val s = os.spawnCmd("bc")
    val p = s.proc.flatMap { sp =>
      add.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    p.runLog.run.toList === List("5", "8") &&
      s.exitValue.runLog.run.toList === List(0)
  }

  property("bc add twice, 2 pass") = secure {
    val add1 = Process("2 + 3\n").pipe(linesOut).toSource
    val add2 = Process("3 + 5\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource
    val s = os.spawnCmd("bc")
    val p = s.proc.flatMap { sp =>
      add1.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        add2.to(sp.stdIn).drain ++
        sp.stdOut.repeat.once ++
        quit.to(sp.stdIn).drain
    }.pipe(linesIn)
    p.runLog.run.toList === List("5", "8")
  }

  property("yes terminates") = secure {
    os.spawnCmd("yes").proc.run.run
    true
  }

  property("yes output") = secure {
    val p = os.spawnCmd("yes").proc.flatMap(_.stdOut.repeat.once).pipe(linesIn).once
    p.runLog.run.toList === List("y")
  }
}

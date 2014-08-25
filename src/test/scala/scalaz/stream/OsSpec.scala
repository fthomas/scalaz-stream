package scalaz.stream

import org.scalacheck._
import org.scalacheck.Prop._
import scodec.bits.ByteVector

object OsSpec extends Properties("OsSpec") {
  val linesIn: Process1[ByteVector, String] =
    text.utf8Decode |> text.lines()

  val linesOut: Process1[String, ByteVector] =
    text.utf8Encode

  property("run only") = secure {
    os.spawn("true").proc.run.run
    true
  }

  property("empty stdout") = secure {
    val p = os.spawn("true").proc.flatMap(_.stdOut)
    p.runLog.run == List()
  }

  property("empty stderr") = secure {
    val p = os.spawn("true").proc.flatMap(_.stdErr)
    p.runLog.run == List()
  }

  property("exit value of 'true'") = secure {
    val s = os.spawn("true")
    (s.proc.drain ++ s.exitValue).runLog.run == List(0)
  }

  property("exit value of 'false'") = secure {
    val s = os.spawn("false")
    (s.proc.drain ++ s.exitValue).runLog.run == List(1)
  }

  property("echo once") = secure {
    val s = os.spawn("echo", "Hello World")
      .proc.flatMap(_.stdOut.repeat.once).pipe(linesIn)
    s.runLog.run == List("Hello World")
  }

  property("echo twice") = secure {
    val s = os.spawn("sh", "-c", "echo Hello; echo World")
      .proc.flatMap(_.stdOut.repeat.once).pipe(linesIn)
    s.runLog.run == List("Hello", "World")
  }

  property("echo twice delayed") = secure {
    val s = os.spawn("sh", "-c", "echo Hello; sleep 0.5; echo World")
      .proc.flatMap(_.stdOut.pipe(linesIn).repeat.takeThrough(_ != "World"))
    s.runLog.run == List("Hello", "World")
  }

  property("bc") = secure {
    val s = os.spawn("bc")
    val p = s.proc.drain
    p.runLog.run == List() && s.exitValue.runLog.run == List(0)
  }

  property("bc quit") = secure {
    val quit = Process("quit\n").pipe(linesOut).toSource
    val s = os.spawn("bc")
    val p = s.proc.flatMap(quit to _.stdIn)
    p.runLog.run == List(()) && s.exitValue.runLog.run == List(0)
  }

  property("bc add once") = secure {
    val add = Process("2 + 3\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource
    val s = os.spawn("bc")
    val p = s.proc.flatMap { cp =>
      add.to(cp.stdIn).drain ++
        cp.stdOut.repeat.once ++
        quit.to(cp.stdIn).drain
    }.pipe(linesIn)
    p.runLog.run == List("5") && s.exitValue.runLog.run == List(0)
  }

  property("bc syntax error") = secure {
    val calc = Process("2 +\n").pipe(linesOut).toSource
    val s = os.spawn("bc")
    val p = s.proc.flatMap { cp =>
      calc.to(cp.stdIn).drain ++
        cp.stdErr.repeat.once
    }.pipe(linesIn)
    p.runLog.run.forall(_.contains("syntax error")) &&
      s.exitValue.runLog.run == List(0)
  }

  property("bc add twice") = secure {
    val add = Process("2 + 3\n", "3 + 5\n").pipe(linesOut).toSource
    val quit = Process("quit\n").pipe(linesOut).toSource
    val s = os.spawn("bc")
    val p = s.proc.flatMap { cp =>
      add.to(cp.stdIn).drain ++
        cp.stdOut.repeat.once ++
        quit.to(cp.stdIn).drain
    }.pipe(linesIn)
    p.runLog.run == List("5", "8") && s.exitValue.runLog.run == List(0)
  }

  /*
  property("read-write-4") = secure {
    val calc1 = Process("2 + 3\n").pipe(text.utf8Encode).toSource
    val calc2 = Process("3 + 5\n").pipe(text.utf8Encode).toSource
    val quit = Process("quit\n").pipe(text.utf8Encode).toSource

    val p = os.popen("bc").flatMap { s =>
      calc1.to(s.write).drain ++
        s.read.repeat.once ++
        calc2.to(s.write).drain ++
        s.read.repeat.once ++
        quit.to(s.write).drain
    }.pipe(lines)
    p.runLog.run.toList == List("5", "8")
  }
  */
}

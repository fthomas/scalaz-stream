package scalaz.stream

import org.scalacheck._
import org.scalacheck.Prop._
import scodec.bits.ByteVector

import Process._
import os._

object OsSpec extends Properties("os") {
  val linesIn: Process1[ByteVector, String] =
    text.utf8Decode |> text.lines()

  property("read-only") = secure {
    val p = popen("echo", "Hello World")
      .flatMap(_.read.repeat.once).pipe(linesIn)
    p.runLog.run.toList == List("Hello World")
  }

  property("read-only-2") = secure {
    val p = os.popen("sh", "-c", "echo Hello; echo World")
      .flatMap(_.read.repeat.once).pipe(linesIn)
    p.runLog.run.toList == List("Hello", "World")
  }

  property("read-delayed") = secure {
    val p = os.popen("sh", "-c", "echo Hello; sleep 0.5; echo World")
      .flatMap(_.read.pipe(linesIn).repeat.takeThrough(_ != "World"))
    p.runLog.run.toList == List("Hello", "World")
  }

  property("write-only") = secure {
    val quit = Process("quit").pipe(text.utf8Encode).toSource
    val p = os.popen("bc").flatMap(quit to _.write)
    p.runLog.run.toList == List(())
  }

  property("read-write-2") = secure {
    val calc = Process("2 + 3\n").pipe(text.utf8Encode).toSource
    val quit = Process("quit\n").pipe(text.utf8Encode).toSource

    val p = os.popen("bc").flatMap { s =>
      calc.to(s.write).drain ++
        s.read.repeat.once ++
        quit.to(s.write).drain
    }.pipe(linesIn)
    p.runLog.run.toList == List("5")
  }

  property("read-write-3") = secure {
    val calc = Process("2 + 3\n", "3 + 5\n").pipe(text.utf8Encode).toSource
    val quit = Process("quit\n").pipe(text.utf8Encode).toSource

    val p = os.popen("bc").flatMap { s =>
      calc.to(s.write).drain ++
        s.read.repeat.once ++
        quit.to(s.write).drain
    }.pipe(linesIn)
    p.runLog.run.toList == List("5", "8")
  }

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
    }.pipe(linesIn)
    p.runLog.run.toList == List("5", "8")
  }
}

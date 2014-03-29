package scalaz.stream

import java.io.{InputStream, OutputStream}
import java.lang.{Process => SysProcess, ProcessBuilder}
import scala.io.Codec
import scalaz.concurrent.Task
import scodec.bits.ByteVector

import Process._
import process1._


/*
TODO:
- Naming: Having Process and Subprocess as types that are unrelated is
  unfortunate. Possible alternative are: Sys(tem)Exchange, ProcExchange,
  SystemProc, ChildProc, ProgramExchange?

- Expose the return value of Process.waitFor().

- Support ProcessBuilder's directory, environment, redirectErrrorStream methods.
  Possibly by adding more parameters the the create?Process methods.

- Support terminating a running Subprocess via Process.destory().
  - maybe use Process[Task, Process[Task, Subprocess[]]] for this

- Find better names for createRawProcess and createLineProcess.
  - Subprocess should not be concerned with lines or decoding.
    it just outputs Bytes
*/

case class Subprocess[+R, -W](
  input: Sink[Task, W],
  output: Process[Task, R],
  error: Process[Task, R]) {

  //def outputEx = Exchange(output, input)
  //def errorEx = Exchange(error, input)

  def contramapW[W2](f: W2 => W): Subprocess[R, W2] =
    Subprocess(input.contramap(f), output, error)

  def mapR[R2](f: R => R2): Subprocess[R2, W] =
    Subprocess(input, output.map(f), error.map(f))

  def mapSink[W2](f: Sink[Task, W] => Sink[Task, W2]): Subprocess[R, W2] =
    Subprocess(f(input), output, error)

  def mapSources[R2](f: Process[Task, R] => Process[Task, R2]): Subprocess[R2, W] =
    Subprocess(input, f(output), f(error))
}

object Subprocess {
  def createRawProcess(args: String*): Process[Task, Subprocess[Bytes, Bytes]] =
    io.resource(
      Task.delay(new ProcessBuilder(args: _*).start))(
      close2)(
      p => Task.delay(mkRawSubprocess(p))).once

  def createLineProcess(args: String*)(implicit codec: Codec): Process[Task, Subprocess[String, String]] =
    createRawProcess(args: _*).map {
      _.mapSources(_.pipe(linesIn)).mapSink(_.pipeIn(linesOut))
    }

  private def mkRawSubprocess(p: SysProcess): Subprocess[Bytes, Bytes] =
    Subprocess(
      mkSink(p.getOutputStream),
      mkSource(p.getInputStream),
      mkSource(p.getErrorStream))




  private def mkSink(os: OutputStream): Sink[Task, Bytes] =
    io.channel {
      (bytes: Bytes) => Task.delay {
        os.write(bytes.toArray)
        os.flush()
      }
    }

  private def mkSource(is: InputStream): Process[Task,ByteVector] = {
    val maxSize = 4096
    val buffer = Array.ofDim[Byte](maxSize)

    val readChunk = Task.delay {
      val size = math.min(is.available, maxSize)
      if (size > 0) {
        is.read(buffer, 0, size)
        ByteVector.view(buffer.take(size))
      } else throw End
    }
    repeatEval(readChunk)
  }

  private def closeStreams(p: SysProcess): Unit = {
    p.getOutputStream.close()
    p.getInputStream.close()
    p.getErrorStream.close()
  }

  // use Task?
  private def close(p: SysProcess): Int = {
    closeStreams(p)
    p.waitFor
  }

  def close2(p: SysProcess): Task[Unit] = Task.delay(close(p))

  private def kill(p: SysProcess): Unit = {
    closeStreams(p)
    p.destroy()
  }


  // These processes are independent of Subprocess and could be moved into process1:

  /** Converts `String` to `Bytes` using the implicitly supplied `Codec`. */
  def encode(implicit codec: Codec): Process1[String, Bytes] =
    lift(s => Bytes.unsafe(s.getBytes(codec.charSet)))

  def linesOut(implicit codec: Codec): Process1[String, Bytes] =
    lift((_: String) + "\n") |> encode

  def linesIn(implicit codec: Codec): Process1[Bytes, String] = {
    def splitLines(bytes: Bytes): Vector[Bytes] = {
      def go(bytes: Bytes, acc: Vector[Bytes]): Vector[Bytes] =
        bytes.span(_ != '\n'.toByte) match {
          case (line, Bytes.empty) => acc :+ line
          case (line, rest)        => go(rest.drop(1), acc :+ line)
        }
      go(bytes, Vector())
    }
    repartition(splitLines).map(_.decode(codec.charSet))
  }

}

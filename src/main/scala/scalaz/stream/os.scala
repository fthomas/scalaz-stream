package scalaz.stream

import java.io.{InputStream, OutputStream}
import java.lang.{Process => JavaProcess, ProcessBuilder}
import scalaz.concurrent.Task
import scalaz.syntax.bind._
import scodec.bits.ByteVector

import Process._

object os {

}

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

- Find better names for createRawProcess
*/

case class Subprocess[+R, -W](
  input: Sink[Task, W],
  output: Process[Task, R],
  error: Process[Task, R]) {

  //def outputEx = Exchange(output, input)
  //def errorEx = Exchange(error, input)
}

object Subprocess {
  def createRawProcess(args: String*): Process[Task, Subprocess[Bytes, Bytes]] =
    io.resource(
      Task.delay(new ProcessBuilder(args: _*).start))(
      p => closeProcess(p).map(()))(
      p => Task.delay(mkRawSubprocess(p))).once

  private def mkRawSubprocess(p: JavaProcess): Subprocess[Bytes, Bytes] =
    Subprocess(
      mkSink(p.getOutputStream),
      mkSource(p.getInputStream),
      mkSource(p.getErrorStream))




  private def mkSink(os: OutputStream): Sink[Task,ByteVector] =
    io.channel {
      (bytes: ByteVector) => Task.delay {
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

  private def closeStreams(p: JavaProcess): Task[Unit] =
    Task.delay {
      p.getOutputStream.close()
      p.getInputStream.close()
      p.getErrorStream.close()
    }

  private def closeProcess(p: JavaProcess): Task[Int] =
    closeStreams(p) >> Task.delay(p.waitFor())

  private def killProcess(p: JavaProcess): Task[Unit] =
    closeStreams(p) >> Task.delay(p.destroy())
}

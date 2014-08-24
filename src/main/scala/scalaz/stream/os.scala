package scalaz.stream

import java.io.{InputStream, OutputStream}
import java.lang.{Process => JavaProcess}
import scalaz.concurrent.Task
import scalaz.syntax.bind._
import scodec.bits.ByteVector

import Process._

// https://github.com/scalaz/scalaz-stream/pull/79

/*
TODO:
- Naming: Having Process and Subprocess as types that are unrelated is
  unfortunate. Possible alternative are: Sys(tem)Exchange, ProcExchange,
  SystemProc, ChildProc, ProgramExchange?

- Expose the return value of Process.waitFor().

- Support terminating a running Subprocess via Process.destory().
  - maybe use Process[Task, Process[Task, Subprocess[]]] for this

- Find better names for createRawProcess / popen

- add type alias for Exchange[ByteVector \/ ByteVector,ByteVector]
*/

object os {
  def popen(args: String*): Process[Task, Exchange[ByteVector, ByteVector]] =
    io.resource(mkJavaProcess(args: _*))(closeJavaProcessIgnore)(mkSimpleExchange).once

  private def mkJavaProcess(args: String*): Task[JavaProcess] =
    Task.delay(new ProcessBuilder(args: _*).start())

  private def mkSimpleExchange(p: JavaProcess): Task[Exchange[ByteVector, ByteVector]] =
    Task.delay(Exchange(mkSource(p.getInputStream), mkSink(p.getOutputStream)))

  private def mkSource(is: InputStream): Process[Task, ByteVector] = {
    val maxSize = 4096
    val buffer = Array.ofDim[Byte](maxSize)

    val readChunk = Task.delay {
      val size = math.min(is.available, maxSize)
      if (size > 0) {
        is.read(buffer, 0, size)
        ByteVector.view(buffer.take(size))
      } else throw Cause.Terminated(Cause.End)
    }
    repeatEval(readChunk)
  }

  private def mkSink(os: OutputStream): Sink[Task, ByteVector] =
    io.channel {
      (bytes: ByteVector) => Task.delay {
        os.write(bytes.toArray)
        os.flush()
      }
    }

  private def closeStreams(p: JavaProcess): Task[Unit] =
    Task.delay {
      p.getOutputStream.close()
      p.getInputStream.close()
      p.getErrorStream.close()
    }

  private def closeJavaProcess(p: JavaProcess): Task[Int] =
    closeStreams(p) >> Task.delay(p.waitFor())

  private def closeJavaProcessIgnore(p: JavaProcess): Task[Unit] =
    closeJavaProcess(p).as(())

  private def destroyJavaProcess(p: JavaProcess): Task[Unit] =
    closeStreams(p) >> Task.delay(p.destroy())
}

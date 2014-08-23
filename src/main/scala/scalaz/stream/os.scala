package scalaz.stream

import java.io.{File, InputStream, OutputStream}
import java.lang.{Process => JavaProcess, ProcessBuilder}
import scalaz.concurrent.Task
import scalaz.\/
import scalaz.\/._
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
  def popen(args: String*): Process[Task,Exchange[ByteVector \/ ByteVector,ByteVector]] =
    io.resource(mkJavaProcess(SubprocessArgs(args.toList)))(closeJavaProcessIgnore)(mkExchange).once



  case class SubprocessArgs(
    command: List[String],
    directory: Option[File] = None,
    mergeOutAndErr: Boolean = false)

  private def mkJavaProcess(args: SubprocessArgs): Task[JavaProcess] =
    Task.delay {
      val pb = new ProcessBuilder(args.command: _*)
      args.directory.foreach(pb.directory)
      pb.redirectErrorStream(args.mergeOutAndErr)
      pb.start()
    }

  private def mkSimpleExchange(p: JavaProcess): Task[Exchange[ByteVector,ByteVector]] =
    Task.delay(Exchange(mkSource(p.getInputStream), mkSink(p.getOutputStream)))

  private def mkExchange(p: JavaProcess): Task[Exchange[ByteVector \/ ByteVector,ByteVector]] =
    Task.delay(Exchange(mergeSources(p), mkSink(p.getOutputStream)))

  private def mergeSources(p: JavaProcess): Process[Task,ByteVector \/ ByteVector] = {
    val out = mkSource(p.getInputStream).map(right)
    val err = mkSource(p.getErrorStream).map(left)
    out merge err
  }

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
      } else throw Cause.Terminated(Cause.End)
    }
    repeatEval(readChunk)
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

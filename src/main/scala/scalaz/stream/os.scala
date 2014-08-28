package scalaz.stream

import java.io.{File, InputStream, OutputStream}
import java.lang.{Process => JavaProcess}
import scalaz.\/
import scalaz.\/._
import scalaz.concurrent.Task
import scalaz.syntax.bind._
import scodec.bits.ByteVector

import Cause._
import Process._

/*
Alternative names:
  ChildProc
  ChildProcess
  Command
  ProcExchange
  ProgramExchange
  Sys(tem)Exchange
  SystemProc
*/

object os {
  final case class Subprocess[R, W](
      stdIn: Sink[Task, W],
      stdOut: Process[Task, R],
      stdErr: Process[Task, R]) {

    def toExchange: Exchange[R \/ R, W] =
      Exchange(stdOut.map(right) merge stdErr.map(left), stdIn)

    def toStdOutExchange: Exchange[R, W] =
      Exchange(stdOut, stdIn)

    def toStdErrExchange: Exchange[R, W] =
      Exchange(stdErr, stdIn)

    def toMergedExchange: Exchange[R, W] =
      Exchange(stdOut merge stdErr, stdIn)
  }

  sealed trait SubprocessState
  case object NotRunning extends SubprocessState
  case object Running extends SubprocessState
  case object Destroyed extends SubprocessState
  case class Exited(status: Int) extends SubprocessState

  final case class SubprocessCtrl[R, W](
    proc: Process[Task, Subprocess[R, W]],
    state: Process[Task, SubprocessState],
    destroy: Process[Task, Unit])

  final case class SubprocessArgs(
    command: Seq[String],
    // TODO: environment
    directory: Option[File] = None,
    mergeOutAndErr: Boolean = false)

  type RawSubprocess = Subprocess[ByteVector, ByteVector]
  type RawSubprocessCtrl = SubprocessCtrl[ByteVector, ByteVector]

  def execCmd(command: String*): Process[Task, RawSubprocessCtrl] =
    exec(SubprocessArgs(command))

  def exec(args: SubprocessArgs): Process[Task, RawSubprocessCtrl] = {
    val state = Task.delay(async.signal[SubprocessState])
      .flatMap(s => s.set(NotRunning).as(s))

    io.resource(state)(_.close)(mkSubprocessCtrl(args, _)).once
  }

  private def mkSubprocessCtrl(args: SubprocessArgs,
      state: async.mutable.Signal[SubprocessState]): Task[RawSubprocessCtrl] =
    Task.delay {
      val destroy = async.signal[Task[Unit]]

      val acquire =
        mkJavaProcess(args).flatMap { jp =>
          state.set(Running) >>
            destroy.set(destroyJavaProcess(jp) >> state.set(Destroyed)).as(jp)
        }

      def release(jp: JavaProcess) =
        closeJavaProcess(jp)
          .flatMap(status => state.set(Exited(status)))
          .flatMap(_ => destroy.close)

      SubprocessCtrl(
        proc = io.resource(acquire)(release)(mkSubprocess).once,
        state = state.continuous,
        destroy = destroy.discrete.flatMap(eval).once)
    }

  private def mkJavaProcess(args: SubprocessArgs): Task[JavaProcess] =
    Task.delay {
      val pb = new ProcessBuilder(args.command: _*)
      args.directory.foreach(d => pb.directory(d))
      pb.redirectErrorStream(args.mergeOutAndErr)
      pb.start()
    }

  private def mkSubprocess(jp: JavaProcess): Task[Subprocess[ByteVector, ByteVector]] =
    Task.delay {
      Subprocess(
        stdIn = mkSink(jp.getOutputStream),
        stdOut = mkSource(jp.getInputStream),
        stdErr = mkSource(jp.getErrorStream))
    }

  private def mkSource(is: InputStream): Process[Task, ByteVector] = {
    val maxSize = 8 * 1024
    val buffer = Array.ofDim[Byte](maxSize)

    val readChunk = Task.delay {
      val size = math.min(is.available, maxSize)
      if (size > 0) {
        is.read(buffer, 0, size)
        ByteVector.view(buffer.take(size))
      } else throw Terminated(End)
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

  private def closeStreams(jp: JavaProcess): Task[Unit] =
    Task.delay {
      jp.getOutputStream.close()
      jp.getInputStream.close()
      jp.getErrorStream.close()
    }

  private def closeJavaProcess(jp: JavaProcess): Task[Int] =
    closeStreams(jp) >> Task.delay(jp.waitFor())

  private def destroyJavaProcess(jp: JavaProcess): Task[Unit] =
    closeStreams(jp) >> Task.delay(jp.destroy())
}

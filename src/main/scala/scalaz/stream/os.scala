package scalaz.stream

import java.io.{InputStream, OutputStream}
import java.lang.{Process => JavaProcess}
import scalaz.\/
import scalaz.\/._
import scalaz.concurrent.Task
import scalaz.syntax.bind._
import scodec.bits.ByteVector

import Cause._
import Process._

/*
TODO:
 - re-evaluate ideas from former PR:
   https://github.com/scalaz/scalaz-stream/pull/79

 - support terminating a running Subprocess via Process.destroy()

 - test that std streams and the process are closed on completion

 - complete SubprocessArgs

 - Do not pollute the package scope with our types

 - the state Signal is never closed. Problem? Yes!
   spawn: Process[Task, SubprocessCtrl]
*/

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
  command: Seq[String])

object os {
  def spawnCmd(command: String*): SubprocessCtrl[ByteVector, ByteVector] =
    spawn(SubprocessArgs(command))

  def spawn(args: SubprocessArgs): SubprocessCtrl[ByteVector, ByteVector] = {
    val state = async.signal[SubprocessState]
    val destroy = async.signal[Task[Unit]]
    state.set(NotRunning).run

    val proc = io.resource {
      for {
        jp <- mkJavaProcess(args)
        _ <- state.set(Running)
        _ <- destroy.set(destroyJavaProcess(jp) >> state.set(Destroyed))
      } yield jp
    } { jp =>
      for {
        status <- closeJavaProcess(jp)
        _ <- state.set(Exited(status))
        _ <- destroy.close
      } yield ()
    } {
      mkSubprocess
    }.once

    SubprocessCtrl(proc, state.continuous, destroy.discrete.flatMap(eval).once)
  }

  private def mkJavaProcess(args: SubprocessArgs): Task[JavaProcess] =
    Task.delay {
      val pb = new ProcessBuilder(args.command: _*)
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

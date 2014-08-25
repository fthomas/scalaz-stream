package scalaz.stream

import java.io.{InputStream, OutputStream}
import java.lang.{Process => JavaProcess}
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

 - add functions for Subprocess => Exchange

 - complete SubprocessArgs
*/

final case class Subprocess[+R, -W](
    stdIn: Sink[Task, W],
    stdOut: Process[Task, R],
    stdErr: Process[Task, R])

final case class SubprocessCtrl[+R, -W](
  proc: Process[Task, Subprocess[R, W]],
  exitValue: Process[Task, Int])

final case class SubprocessArgs(
  command: Seq[String])

object os {
  def spawn(args: String*): SubprocessCtrl[ByteVector, ByteVector] =
    spawn(SubprocessArgs(args))

  def spawn(args: SubprocessArgs): SubprocessCtrl[ByteVector, ByteVector] = {
    val exitSignal = async.signal[Int]
    val proc = io.resource {
      mkJavaProcess(args)
    } {
      closeJavaProcess(_).flatMap(exitSignal.set)
    } {
      mkSubprocess
    }.once

    SubprocessCtrl(proc, exitSignal.discrete.once)
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

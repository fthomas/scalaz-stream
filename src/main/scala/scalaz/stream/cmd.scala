package scalaz.stream

import java.io.{File, InputStream, OutputStream}
import java.lang.{Process => JavaProcess}

import scodec.bits.ByteVector

import scalaz.concurrent.Task
import scalaz.stream.Cause.{End, Terminated}
import scalaz.stream.Process._
import scalaz.syntax.bind._

object cmd {

  //

  sealed trait CmdState

  case object NotRunning extends CmdState

  case object Running extends CmdState

  case object Destroyed extends CmdState

  case class Exited(status: Int) extends CmdState

  //

  case class CmdArgs(command: Seq[String],
                     environment: Option[Map[String, String]] = None,
                     directory: Option[File] = None,
                     mergeOutAndErr: Boolean = false,
                     readBufferSize: Int = 8 * 1024)

  //

  case class Cmd(stdIn: Sink[Task, ByteVector],
                 stdOut: Process[Task, ByteVector],
                 stdErr: Process[Task, ByteVector],
                 destroy: Task[Unit])

  //

  case class CmdHandle(proc: Process[Task, Cmd],
                       state: Process[Task, CmdState])

  //

  def spawn(args: CmdArgs): Process[Task, CmdHandle] = {
    val state = Task(async.signalOf[CmdState](NotRunning))
    await(state) { signal =>
      def acquire = mkJavaProcess(args).flatMap(jp => signal.set(Running) >> Task.now(jp))
      def release(jp: JavaProcess) = closeJavaProcess(jp).flatMap(signal.set)
      def destroy(jp: JavaProcess) = destroyJavaProcess(jp).flatMap(signal.set)

      val cmd = await(acquire) { jp =>
        emit(mkCmd(jp, args.readBufferSize, destroy(jp))).onComplete(eval_(release(jp)))
      }

      emit(CmdHandle(cmd, signal.discrete)).onComplete(eval_(signal.close))
    }
  }

  //

  def spawnCmd(command: String*): Process[Task, CmdHandle] =
    spawn(CmdArgs(command))

  def shell(args: CmdArgs): Process[Task, Cmd] =
    spawn(args).flatMap(_.proc)

  def shellCmd(command: String*): Process[Task, Cmd] =
    shell(CmdArgs(command))

  //

  private def mkJavaProcess(args: CmdArgs): Task[JavaProcess] =
    Task.delay {
      val pb = new ProcessBuilder(args.command: _*)
      args.environment.foreach { env =>
        val mutableEnv = pb.environment()
        mutableEnv.clear()
        mutableEnv.putAll(scala.collection.JavaConversions.mapAsJavaMap(env))
      }
      args.directory.foreach(dir => pb.directory(dir))
      pb.redirectErrorStream(args.mergeOutAndErr)
      pb.start()
    }

  private def mkCmd(jp: JavaProcess, readBufferSize: Int, destroy: Task[Unit]): Cmd =
    Cmd(mkSink(jp.getOutputStream),
      mkSource(jp.getInputStream, readBufferSize),
      mkSource(jp.getErrorStream, readBufferSize),
      destroy)


  private def mkSource(is: InputStream, bufferSize: Int): Process[Task, ByteVector] = {
    val buffer = Array.ofDim[Byte](bufferSize)
    val readChunk = Task.delay {
      val size = math.min(is.available, bufferSize)
      if (size > 0) {
        is.read(buffer, 0, size)
        ByteVector.view(buffer.take(size))
      } else throw Terminated(End)
    }
    repeatEval(readChunk)
  }

  private def mkSink(os: OutputStream): Sink[Task, ByteVector] =
    sink.lift[Task, ByteVector] {
      bytes => Task.delay {
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

  private def closeJavaProcess(jp: JavaProcess): Task[CmdState] =
    closeStreams(jp) >> Task.delay {
      val status = jp.waitFor()
      Exited(status)
    }

  private def closeJavaProcess_(jp: JavaProcess): Task[Unit] =
    closeJavaProcess(jp).as(())

  private def destroyJavaProcess(jp: JavaProcess): Task[CmdState] =
    closeStreams(jp) >> Task.delay {
      jp.destroy()
      Destroyed
    }
}

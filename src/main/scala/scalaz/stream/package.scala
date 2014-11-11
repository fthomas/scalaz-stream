package scalaz

import java.util.concurrent.{ThreadFactory, Executors}

import scodec.bits.ByteVector

import scalaz.stream.Process.Env


/**
 * Created by pach on 11/07/14.
 */
package object stream extends WriterTypes {

  type Process0[+O] = Process[Nothing,O]

  /**
   * A single input stream transducer. Accepts input of type `I`,
   * and emits values of type `O`.
   */
  type Process1[-I,+O] = Process[Env[I,Any]#Is, O]

  /**
   * A stream transducer that can read from one of two inputs,
   * the 'left' (of type `I`) or the 'right' (of type `I2`).
   * `Process1[I,O] <: Tee[I,I2,O]`.
   */
  type Tee[-I,-I2,+O] = Process[Env[I,I2]#T, O]

  /**
   * A stream transducer that can read from one of two inputs,
   * non-deterministically.
   */
  type Wye[-I,-I2,+O] = Process[Env[I,I2]#Y, O]

  /**
   * An effectful sink, to which we can send values. Modeled
   * as a source of effectful functions.
   */
  type Sink[+F[_],-O] = Process[F, O => F[Unit]]

  /**
   * An effectful channel, to which we can send values and
   * get back responses. Modeled as a source of effectful
   * functions.
   */
  type Channel[+F[_],-I,O] = Process[F, I => F[O]]




  /**
   * Scheduler used for timing processes.
   * This thread pool shall not be used
   * for general purpose Process or Task execution
   */
  val DefaultScheduler = {
    Executors.newScheduledThreadPool(Runtime.getRuntime.availableProcessors() max 4, new ThreadFactory {
      def newThread(r: Runnable) = {
        val t = Executors.defaultThreadFactory.newThread(r)
        t.setDaemon(true)
        t.setName("scalaz-stream-default-scheduler")
        t
      }
    })
  }

  implicit val byteVectorSemigroupInstance: Semigroup[ByteVector] =
    Semigroup.instance(_ ++ _)

}

package scalaz.stream

import scalaz.\/
import scalaz.\/._

import Process._

trait WriterTypes {
  /**
   * A `Writer[F, W, O]` is a `Process[F, W \/ O]`. See
   * `Process.WriterSyntax` for convenience functions
   * for working with either the written values (the `W`)
   * or the output values (the `O`).
   *
   * This is useful for logging or other situations where we
   * want to emit some values 'on the side' while doing something
   * else with the main output of a `Process`.
   */
  type Writer[+F[_], +W, +O] = Process[F, W \/ O]

  /** A `Process0` that writes values of type `W`. */
  type Writer0[+W, +O] = Process0[W \/ O]

  /** A `Process1` that writes values of type `W`. */
  type Writer1[+W, -I, +O] = Process1[I, W \/ O]

  /** A `Tee` that writes values of type `W`. */
  type TeeW[+W, -I, -I2, +O] = Tee[I, I2, W \/ O]

  /** A `Wye` that writes values of type `W`. */
  type WyeW[+W, -I, -I2, +O] = Wye[I, I2, W \/ O]
}

object Writer {
  /** `Writer` based version of `await1`. */
  def await1W[A]: Writer1[Nothing, A, A] =
    liftW(await1[A])

  /** `Writer` based version of `awaitL`. */
  def awaitLW[I]: TeeW[Nothing, I, Any, I] =
    liftW(awaitL[I])

  /** `Writer` based version of `awaitR`. */
  def awaitRW[I2]: TeeW[Nothing, Any, I2, I2] =
    liftW(awaitR[I2])

  /** `Writer` based version of `awaitBoth`. */
  def awaitBothW[I, I2]: WyeW[Nothing, I, I2, ReceiveY[I, I2]] =
    liftW(awaitBoth[I, I2])

  /** A `Writer` which emits one value to the output. */
  def emitO[O](o: O): Writer0[Nothing, O] =
    emit(right(o))

  /** A `Writer` which writes the given value. */
  def emitW[W](w: W): Writer0[W, Nothing] =
    emit(left(w))

  /** Promotes a `Process` to a `Writer` that writes nothing. */
  def liftW[F[_], O](p: Process[F, O]): Writer[F, Nothing, O] =
    p.map(right)

  /**
   * Promotes a `Process` to a `Writer` that writes and outputs
   * all values of `p`.
   */
  def logged[F[_], O](p: Process[F, O]): Writer[F, O, O] =
    p.flatMap(o => emitAll(Vector(left(o), right(o))))

  /** Alias for `[[emitW]](w)`. */
  def tell[W](w: W): Writer0[W, Nothing] =
    emitW(w)
}

/**
 * Infix syntax for working with `Writer[F,W,O]`. We call
 * the `W` parameter the 'write' side of the `Writer` and
 * `O` the 'output' side. Many method in this class end
 * with either `W` or `O`, depending on what side they
 * operate on.
 */
final class WriterOps[F[_], W, O](val self: Writer[F, W, O]) extends AnyVal {

  import Writer._

  def flatMapO[F2[x] >: F[x], W2 >: W, B](f: O => Writer[F2, W2, B]): Writer[F2, W2, B] =
    self.flatMap(_.fold(emitW, f))

  /** Transform the write side of this `Writer`. */
  def flatMapW[F2[x] >: F[x], W2, O2 >: O](f: W => Writer[F2, W2, O2]): Writer[F2, W2, O2] =
    self.flatMap(_.fold(f, emitO))

  def stripO: Process[F, W] =
    self.flatMap(_.fold(emit, _ => halt))

  /** Remove the write side of this `Writer`. */
  def stripW: Process[F, O] =
    self.flatMap(_.fold(_ => halt, emit))

  /** Map over the output side of this `Writer`. */
  def mapO[O2](f: O => O2): Writer[F, W, O2] =
    self.map(_.map(f))

  /** Map over the write side of this `Writer`. */
  def mapW[W2](f: W => W2): Writer[F, W2, O] =
    self.map(_.leftMap(f))

  def pipeO[B](f: Process1[O, B]): Writer[F, W, B] =
    self.pipe(process1.liftR(f))

  /** pipe Write side of this `Writer` */
  def pipeW[B](f: Process1[W, B]): Writer[F, B, O] =
    self.pipe(process1.liftL(f))

  /**
   * Observe the output side of this `Writer` using the
   * given `Sink`, keeping it available for subsequent
   * processing. Also see `drainO`.
   */
  def observeO(snk: Sink[F, O]): Writer[F, W, O] =
    swap.observeW(snk).swap

  /**
   * Observe the write side of this `Writer` using the
   * given `Sink`, keeping it available for subsequent
   * processing. Also see `drainW`.
   */
  def observeW(snk: Sink[F, W]): Writer[F, W, O] =
    self.zipWith(snk)((a, f) =>
      a.fold(
        (w: W) => eval_(f(w)) ++ emitW(w),
        (o: O) => emitO(o)
      )
    ).flatMap(identity)

  /**
   * Observe the output side of this Writer` using the
   * given `Sink`, then discard it. Also see `observeO`.
   */
  def drainO(snk: Sink[F, O]): Process[F, W] =
    observeO(snk).stripO

  /**
   * Observe the write side of this `Writer` using the
   * given `Sink`, then discard it. Also see `observeW`.
   */
  def drainW(snk: Sink[F, W]): Process[F, O] =
    observeW(snk).stripW

  def swap: Writer[F, O, W] =
    self.map(_.swap)
}

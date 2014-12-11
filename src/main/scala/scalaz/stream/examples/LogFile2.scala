package examples

import scalaz.stream._

object LogFile2 extends App {

  sealed trait Loglevel
  case object Info extends Loglevel
  case object Debug extends Loglevel
  case object Warning extends Loglevel

  case class Line(level: Loglevel, line: String)

  type PartialChannel[F[_],I,O] = Process[F,PartialFunction[I,F[O]]]

  implicit class ChannelSyntax[F[_],I,O](self: Channel[F,I,O]) {
    def contramapPartial[I0](f: PartialFunction[I0,I]): PartialChannel[F,I0,O] =
      self.map(f.andThen)
  }

  def mergePartials[F[_],I,O](ps: PartialChannel[F,I,O]*): PartialChannel[F,I,O] =
    if (ps.isEmpty) Process.halt
    else ps.reduce((a1, a2) => a1.zip(a2).map { case (f, g) => f orElse g })

  val outInfo = io.stdOutLines.contramapPartial[Line] {
    case Line(Info, s) => "I: " + s
  }

  val outDebug = io.stdOutLines.contramapPartial[Line] {
    case Line(Debug, s) => "D: " + s
  }

  val outWarning = io.stdOutLines.contramapPartial[Line] {
    case Line(Warning, s) => "W: " + s
  }

  val zipped = mergePartials(outInfo, outDebug, outWarning)

  val lines = List(
    Line(Info, "Hello"),
    Line(Warning, "Oops"),
    Line(Debug, "ui ui"),
    Line(Info, "World"))

  Process.emitAll(lines).liftIO.to(zipped).run.run
}

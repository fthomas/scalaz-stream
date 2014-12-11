package examples

import scalaz.stream._

object LogFile extends App {

  sealed trait Loglevel
  case object Info extends Loglevel
  case object Debug extends Loglevel
  case object Warning extends Loglevel

  case class Line(level: Loglevel, line: String)

  val outInfo = io.stdOutLines.contramap((l: Line) => "I: " + l.line)
  val outDebug = io.stdOutLines.contramap((l: Line) => "D: " + l.line)
  val outWarning = io.stdOutLines.contramap((l: Line) => "W: " + l.line)

  val zipped = outInfo.zip(outDebug).zip(outWarning).map {
    case ((fInfo, fDebug), fWarning) =>
      (l: Line) => l.level match {
        case Info    => fInfo(l)
        case Debug   => fDebug(l)
        case Warning => fWarning(l)
      }
  }

  val lines = List(
    Line(Info, "Hello"),
    Line(Warning, "Oops"),
    Line(Debug, "ui ui"),
    Line(Info, "World"))

  Process.emitAll(lines).liftIO.to(zipped).run.run
}

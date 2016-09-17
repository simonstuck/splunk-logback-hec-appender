package io.policarp.splunk.logback

import ch.qos.logback.classic.pattern.{ ExtendedThrowableProxyConverter, _ }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.LayoutBase
import io.policarp.splunk.logback.json.{ BaseJson, FullEventJson }
import org.json4s.native.Serialization._

import scala.beans.BeanProperty
import scala.collection._

/**
 * Base Logback LayoutBase class used for formatting log events in JSON for Splunk. This supplies
 * the expected JSON format referenced here: http://dev.splunk.com/view/event-collector/SP-CAAAE6M
 */
trait BaseSplunkHttpEventCollectorJsonLayout extends LayoutBase[ILoggingEvent] {

  @BeanProperty var host: String = ""
  @BeanProperty var source: String = ""
  @BeanProperty var sourcetype: String = ""
  @BeanProperty var index: String = ""
}

package object json {

  trait EventJson

  case class BaseJson(
    time: Long,
    event: EventJson,
    host: Option[String] = None,
    source: Option[String] = None,
    sourcetype: Option[String] = None,
    index: Option[String] = None
  )

  case class FullEventJson(
    message: String,
    level: String,
    thread: String,
    logger: String,
    callingClass: Option[String],
    callingMethod: Option[String],
    callingLine: Option[String],
    callingFile: Option[String],
    exception: Option[String],
    stacktrace: Option[List[String]],
    customFields: Option[mutable.HashMap[String, String]]
  ) extends EventJson
}

object SplunkHttpEventCollectorJsonLayout {

  // ref: ch.qos.logback.classic.PatternLayout
  lazy val classOfCallerConverter = new ClassOfCallerConverter
  lazy val methodOfCallerConverter = new MethodOfCallerConverter
  lazy val lineOfCallerConverter = new LineOfCallerConverter
  lazy val fileOfCallerConverter = new FileOfCallerConverter
  lazy val extendedThrowableProxyConverter = new ExtendedThrowableProxyConverter

  implicit class FilterEmpty(s: String) {
    def filterEmptyConversion = if (s.isEmpty || s == "?") None else Some(s)
  }

  def parseStackTrace(event: ILoggingEvent, max: Int): Option[List[String]] = {
    Option(event.getThrowableProxy).flatMap(proxy => {
      Option(proxy.getStackTraceElementProxyArray).map(stacktrace => {
        val length = stacktrace.length
        val list = stacktrace.iterator.take(max).map(trace =>
          trace.getStackTraceElement.toString).toList
        list ++ (if (length > max) List("...") else Nil)
      })
    })
  }
}

/**
 * This layout provides a 'good' amount of logging data from an ILoggingEvent. It also supports
 * custom fields to append to every log message configurable via Logback.
 */
class SplunkHttpEventCollectorJsonLayout extends BaseSplunkHttpEventCollectorJsonLayout {

  import SplunkHttpEventCollectorJsonLayout._

  @BeanProperty var maxStackTrace: Int = 500

  private val customFields = new mutable.HashMap[String, String]()

  def setCustom(customField: String): Unit = {
    customField.split("=", 2) match {
      case Array(key, value) => customFields += (key -> value)
      case _ => // ignoring anything else
    }
  }

  override def doLayout(event: ILoggingEvent) = {

    implicit val format = org.json4s.DefaultFormats

    val eventJson = FullEventJson(
      event.getFormattedMessage,
      event.getLevel.levelStr,
      event.getThreadName,
      event.getLoggerName,
      classOfCallerConverter.convert(event).filterEmptyConversion,
      methodOfCallerConverter.convert(event).filterEmptyConversion,
      lineOfCallerConverter.convert(event).filterEmptyConversion,
      fileOfCallerConverter.convert(event).filterEmptyConversion,
      extendedThrowableProxyConverter.convert(event).filterEmptyConversion,
      parseStackTrace(event, maxStackTrace),
      if (customFields.isEmpty) None else Some(customFields)
    )

    val baseJson = BaseJson(
      event.getTimeStamp,
      eventJson,
      if (host.isEmpty) None else Some(host),
      if (source.isEmpty) None else Some(source),
      if (sourcetype.isEmpty) None else Some(sourcetype),
      if (index.isEmpty) None else Some(index)
    )

    write(baseJson)
  }
}

package no.ssb.xsdtoavsc

import org.apache.xerces.xni.XNIException
import org.apache.xerces.xni.parser.{XMLErrorHandler, XMLParseException}
import org.w3c.dom.{DOMError, DOMErrorHandler}

class ErrorHandler extends XMLErrorHandler with DOMErrorHandler {

  private var exception: Option[XMLParseException] = None
  private var error: Option[DOMError] = None

  @throws[XNIException]
  def warning(domain: String, key: String, exception: XMLParseException): Unit = {
    if (this.exception.isEmpty) {
      this.exception = Option(exception)
    }
  }

  @throws[XNIException]
  def error(domain: String, key: String, exception: XMLParseException): Unit = {
    if (this.exception.isEmpty) {
      this.exception = Option(exception)
    }
  }

  @throws[XNIException]
  def fatalError(domain: String, key: String, exception: XMLParseException): Unit = {
    if (this.exception.isEmpty) {
      this.exception = Option(exception)
    }
  }

  def handleError(error: DOMError): Boolean = {
    if (this.error.isEmpty) {
      this.error = Option(error)
    }
    false
  }

  def check(): Unit = {
    if (exception.isDefined) {
      throw new ConversionException(exception.get)
    }
    if (error.isDefined) {
      error.get.getRelatedException match {
        case cause: Throwable => throw new ConversionException(cause)
        case _ =>
      }
      val locator = error.get.getLocation
      val location = "at:" + locator.getUri + ", line:" + locator.getLineNumber + ", char:" + locator.getColumnNumber
      throw ConversionException(location + " " + error.get.getMessage)
    }
  }
}

case class ConversionException(message: String = null, cause: Throwable = null) extends RuntimeException(message, cause) {
  def this(cause: Throwable) = this(null, cause)
}

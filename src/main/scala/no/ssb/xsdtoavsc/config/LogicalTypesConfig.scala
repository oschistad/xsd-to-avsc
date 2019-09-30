package no.ssb.xsdtoavsc.config

import scala.beans.BeanProperty

class LogicalTypesConfig {

  @BeanProperty var xsDateTime: String = LogicalTypesConfig.STRING
  @BeanProperty var xsTime: String = LogicalTypesConfig.STRING
  @BeanProperty var xsDate: String = LogicalTypesConfig.STRING
  @BeanProperty var xsDecimal: DecimalConfig = DecimalConfig()

  def validate(): Unit = {
    xsDateTime = Option(xsDateTime) getOrElse ""
    xsDateTime match {
      case LogicalTypesConfig.LONG
           | LogicalTypesConfig.STRING
           | LogicalTypesConfig.TIMESTAMP_MILLIS
           | LogicalTypesConfig.TIMESTAMP_MICROS => /* accept */
      case _ =>
        throw new IllegalArgumentException("Invalid configuration for xs:dateTime logical type.")
    }

    xsTime = Option(xsTime) getOrElse ""
    xsTime match {
      case LogicalTypesConfig.STRING
           | LogicalTypesConfig.TIME_MILLIS
           | LogicalTypesConfig.TIME_MICROS => /* accept */
      case _ =>
        throw new IllegalArgumentException("Invalid configuration for xs:time logical type.")
    }

    xsDate = Option(xsDate) getOrElse ""
    xsDate match {
      case LogicalTypesConfig.STRING | LogicalTypesConfig.DATE => /* accept */
      case _ =>
        throw new IllegalArgumentException("Invalid configuration for xs:date logical type.")
    }

    xsDecimal = Option(xsDecimal) getOrElse new DecimalConfig
    xsDecimal.validate()
  }
}

object LogicalTypesConfig {
  /**
   * Logical type "timestamp-millis" annotating a long type.
   */
  val TIMESTAMP_MILLIS = "timestamp-millis"
  /**
   * Logical type "timestamp-micros" annotating a long type.
   */
  val TIMESTAMP_MICROS = "timestamp-micros"

  /**
   * Logical type "times-millis" annotating a long type.
   */
  val TIME_MILLIS = "time-millis"

  /**
   * Logical type "times-micros" annotating a long type.
   */
  val TIME_MICROS = "time-micros"

  /**
   * Logical type "date" annotating an int type.
   */
  val DATE = "date"

  /**
   * Dummy logical type for handling values as string without indicating a logicalType.
   */
  val STRING = "string"

  /**
   * Dummy logical type for handling values as long without indicating a logicalType.
   */
  val LONG = "long"

  def apply(): LogicalTypesConfig = new LogicalTypesConfig
}

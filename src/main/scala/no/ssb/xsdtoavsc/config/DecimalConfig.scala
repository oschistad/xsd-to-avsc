package no.ssb.xsdtoavsc.config

import scala.beans.BeanProperty

class DecimalConfig {

  @BeanProperty var avroType: String = DecimalConfig.DOUBLE
  @BeanProperty var fallbackType: String = DecimalConfig.STRING
  @BeanProperty var fallbackPrecision : Integer = null
  @BeanProperty var fallbackScale : Integer = 0

  def validate(): Unit = {

    val acceptedAvroTypes = List(
      DecimalConfig.DECIMAL,
      DecimalConfig.DOUBLE,
      DecimalConfig.STRING
    )

    if (!acceptedAvroTypes.contains(avroType)) {
      throw new IllegalArgumentException(s"Invalid configuration value '$avroType' for xsDecimal avroType.")
    }

    if (!acceptedAvroTypes.contains(fallbackType)) {
      throw new IllegalArgumentException(s"Invalid configuration value '$fallbackType' for xsDecimal fallbackType.")
    }

    if (fallbackType == DecimalConfig.DECIMAL) {
      if (Option(fallbackPrecision).isEmpty) {
        throw new IllegalArgumentException(s"Missing xsDecimal fallbackPrecision " +
          s"configuration for '$fallbackType' fallback type.")
      }
      if (Option(fallbackScale).isEmpty) {
        throw new IllegalArgumentException(s"Missing xsDecimal fallbackScale " +
          s"configuration for '$fallbackType' fallback type.")
      }
      if (fallbackPrecision <= 0) {
        throw new IllegalArgumentException(s"Invalid configuration value $fallbackPrecision for xsDecimal fallbackPrecision.")
      }
      if (fallbackScale <= 0 || fallbackScale > fallbackPrecision) {
        throw new IllegalArgumentException(s"Invalid configuration value $fallbackScale for xsDecimal fallbackScale.")
      }
    }

  }
}

object DecimalConfig {
  val DOUBLE = "double"

  val STRING = "string"

  val DECIMAL = "decimal"

  def apply(): DecimalConfig = new DecimalConfig
}

package no.ssb.xsdtoavsc.config

import javax.xml.namespace.QName

import scala.beans.BeanProperty

class Config {
  @BeanProperty var debug: Boolean = false
  @BeanProperty var namespaces: Boolean = true
  @BeanProperty var logicalTypes: LogicalTypesConfig = LogicalTypesConfig()
  @BeanProperty var rebuildChoice: Boolean = true
  @BeanProperty var stringTimestamp: Boolean = true
  @BeanProperty var ignoreHiveKeywords: Boolean = false
  @BeanProperty var rootElementQName: Option[QName] = None
  @BeanProperty var attributePrefix: String = "_"

  def validate(): Unit = {
    logicalTypes = Option(logicalTypes) getOrElse new LogicalTypesConfig
    logicalTypes.validate()
    if (stringTimestamp) {
      logicalTypes.xsDateTime = LogicalTypesConfig.STRING
    }
  }
}

object Config {
  def apply(): Config = new Config()
}

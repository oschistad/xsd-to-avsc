package no.ssb.xsdtoavsc.config

import javax.xml.namespace.QName

import scala.beans.BeanProperty
import scala.reflect.io.Path

class Config {
  var xsdFile: Path = _
  var avscFile: Path = _

  @BeanProperty var baseDir: Option[Path] = None
  @BeanProperty var debug: Boolean = false
  @BeanProperty var namespaces: Boolean = true
  @BeanProperty var logicalTypes: LogicalTypesConfig = LogicalTypesConfig()
  @BeanProperty var rebuildChoice: Boolean = true
  @BeanProperty var stringTimestamp: Boolean = true
  @BeanProperty var ignoreHiveKeywords: Boolean = false
  @BeanProperty var rootElementQName: Option[QName] = None
  @BeanProperty var attributePrefix: String = "_"

  def getXsdFile: String = xsdFile.path

  def setXsdFile(value: String): Unit = xsdFile = Path(value)

  def getAvscFile: String = avscFile.path

  def setAvscFile(value: String): Unit = avscFile = Path(value)

  def validate(): Unit = {
    if (baseDir.isDefined) {
      xsdFile = xsdFile toAbsoluteWithRoot baseDir.get
      if (Option(avscFile).isDefined)
        avscFile = avscFile toAbsoluteWithRoot baseDir.get
      else
        avscFile = xsdFile changeExtension "avsc"
    }
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

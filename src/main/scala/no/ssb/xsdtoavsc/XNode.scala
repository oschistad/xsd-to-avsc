package no.ssb.xsdtoavsc

import no.ssb.xsdtoavsc.XNode.option
import org.apache.xerces.xs.XSObject

case class XNode(name: String,
                 nsURI: String,
                 nsName: String,
                 attribute: Boolean) {
  var parentNS: String = _
  val element: Boolean = !attribute

  def sourceMatches(sourceTag: String,
                    caseSensitive: Boolean,
                    ignoreList: List[String]): Boolean = {
    val matches =
      if (caseSensitive)
        if (ignoreList contains sourceTag.toLowerCase)
          source.equalsIgnoreCase(sourceTag) || parentNSSource
            .equalsIgnoreCase(sourceTag)
        else
          source == sourceTag || parentNSSource == sourceTag
      else
        source.equalsIgnoreCase(sourceTag) || parentNSSource.equalsIgnoreCase(
          sourceTag)
    matches
  }

  def source: String =
    (if (attribute) "attribute" else "element") + s" ${fullName()}"

  def parentNSSource: String =
    (if (attribute) "attribute" else "element") + s" ${fullName(other = true)}"

  def fullName(other: Boolean = false): String =
    if (other)
      s"${if (option(parentNS).isDefined) parentNS + ":" else ""}$name"
    else
      s"${if (option(nsURI).isDefined) nsURI + ":" else ""}$name"

  override def toString: String =
    s"${if (option(nsName).isDefined) nsName + ":" else ""}$name"
}

object XNode {
  val SOURCE = "source"
  val DOCUMENT = "document"
  val WILDCARD = "others"
  val TEXT_VALUE = "text_value"
  var namespaces = true

  def apply(ele: XSObject, attribute: Boolean = false): XNode =
    new XNode(ele.getName, ele.getNamespace, null, attribute)

  def apply(parentNode: XNode,
            name: String,
            nsURI: String,
            nsName: String,
            attribute: Boolean): XNode = {
    val node = new XNode(name, nsURI, nsName, attribute)
    if (option(nsURI) isEmpty)
      if (option(parentNode.nsURI) isDefined) node.parentNS = parentNode.nsURI
      else node.parentNS = parentNode.parentNS
    node
  }

  def textNode: XNode = new XNode(TEXT_VALUE, null, null, attribute = false)

  def wildNode(attribute: Boolean): XNode =
    new XNode(WILDCARD, null, null, attribute)

  def option(text: String): Option[String] = {
    if (Option(text).isDefined) {
      if (text.trim == "") {
        None
      } else {
        Option(text)
      }
    } else {
      None
    }
  }
}

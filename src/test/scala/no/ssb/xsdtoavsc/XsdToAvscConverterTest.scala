package no.ssb.xsdtoavsc

import java.nio.file.{Files, Path, Paths}

import org.apache.xerces.dom.DOMInputImpl
import org.apache.xerces.impl.xs.{XMLSchemaLoader, XSElementDecl}
import org.apache.xerces.xs.XSConstants
import org.assertj.core.api.Assertions.assertThat
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatestplus.junit.JUnitRunner

import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class XsdToAvscConverterTest extends FunSuite {

  test("Should correctly convert schema") {

    val inputStream = Files.newInputStream(Paths.get("src", "test", "resources", "books.xsd"))

    val got = XsdToAvscConverter.createDefault().convert(inputStream).toString(true)

    inputStream.close()

    val want = new String(Files.readAllBytes(Paths.get("src", "test", "resources", "books.avsc")))

    assertThat(got).isEqualTo(want)
  }

  test("Should correctly convert a schema with documentation") {

    val inputStream = Files.newInputStream(Paths.get("src", "test", "resources", "enrichment", "books.xsd"))

    val got = XsdToAvscConverter.createDefault().convert(inputStream).toString(true)

    inputStream.close()

    val want = new String(Files.readAllBytes(Paths.get("src", "test", "resources", "enrichment", "books.avsc")))

    assertThat(got).isEqualTo(want)
  }

  def getRootElement(file: Path): XSElementDecl = {
    val inputStream = Files.newInputStream(file)

    val domInput = new DOMInputImpl()
    domInput.setByteStream(inputStream)

    val loader = new XMLSchemaLoader()
    val model = loader.load(domInput)

    for ((_, v) <- model.getComponents(XSConstants.ELEMENT_DECLARATION).asScala) {
      return v.asInstanceOf[XSElementDecl]
    }
    null
  }
}

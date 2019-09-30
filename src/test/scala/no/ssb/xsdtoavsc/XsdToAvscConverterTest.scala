package no.ssb.xsdtoavsc

import java.nio.file.{Files, Paths}

import org.assertj.core.api.Assertions.assertThat
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class XsdToAvscConverterTest extends FunSuite {

  test("Should convert schema") {

    val inputStream = Files.newInputStream(Paths.get("src", "test", "resources", "books.xsd"))

    val got = XsdToAvscConverter.createDefault().convert(inputStream).toString(true)

    inputStream.close()

    val want = new String(Files.readAllBytes(Paths.get("src", "test", "resources", "books.avsc")))

    assertThat(got).isEqualTo(want)
  }
}

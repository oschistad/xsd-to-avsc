package no.ssb.xsdtoavsc

import java.io.InputStream

import javax.xml.XMLConstants
import javax.xml.namespace.QName
import no.ssb.xsdtoavsc.config.{Config, DecimalConfig, LogicalTypesConfig}
import org.apache.avro.Schema
import org.apache.avro.Schema.Field
import org.apache.xerces.dom.DOMInputImpl
import org.apache.xerces.impl.Constants
import org.apache.xerces.impl.dv.xs.XSSimpleTypeDecl
import org.apache.xerces.impl.xs.{SchemaGrammar, XMLSchemaLoader, XSComplexTypeDecl}
import org.apache.xerces.xs.XSTypeDefinition.{COMPLEX_TYPE, SIMPLE_TYPE}
import org.apache.xerces.xs._
import org.codehaus.jackson.JsonNode
import org.codehaus.jackson.node.{IntNode, NullNode}

import scala.collection.JavaConverters._
import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.xml.XML

final class XsdToAvscConverter(config: Config) {

  XNode.namespaces = config.namespaces
  private val stringTimestamp = config.stringTimestamp
  private val rebuildChoice = config.rebuildChoice
  private val ignoreHiveKeywords = config.ignoreHiveKeywords
  private val rootElementQName = config.rootElementQName
  private val xsDateTimeMapping = config.logicalTypes.xsDateTime
  private val xsDateMapping = config.logicalTypes.xsDate
  private val xsTimeMapping = config.logicalTypes.xsTime
  private val xsDecimalMapping = config.logicalTypes.xsDecimal
  private val schemas = mutable.Map[String, Schema]()
  private var typeCount = -1
  private var typeLevel = 0

  if (stringTimestamp) {
    XsdToAvscConverter.PRIMITIVES += XSConstants.DATETIME_DT -> Schema.Type.STRING
  }

  def loadXSD(xsd: InputStream, errorHandler: ErrorHandler): XSModel = {
    val schemaInput = new DOMInputImpl()
    schemaInput.setByteStream(xsd)

    val loader = new XMLSchemaLoader
    loader.setErrorHandler(errorHandler)
    loader.setParameter(Constants.DOM_ERROR_HANDLER, errorHandler)
    loader.load(schemaInput)
  }

  private def getNamespace(qName: QName): String = {
    qName.getNamespaceURI match {
      case XMLConstants.NULL_NS_URI =>
        null;
      case ns: String =>
        ns
    }
  }

  def getRootElements(root: QName, elements: XSNamedMap): HashMap[String, XSObject] = {
    val rootElement: Option[XSObject] = Option(elements.itemByName(getNamespace(root), root.getLocalPart))

    if (rootElement.isEmpty) {
      throw new NoSuchElementException(s"The schema contains no root level element definition for QName '${rootElementQName.get}'")
    }
    HashMap("1" -> rootElement.get)
  }

  def convert(xsd: InputStream): Schema = {
    val errorHandler: ErrorHandler = new ErrorHandler

    val model: XSModel = loadXSD(xsd, errorHandler)

    errorHandler.check()

    // Generate schema for all the elements
    val schema: Schema = {

      val elements: XSNamedMap = model.getComponents(XSConstants.ELEMENT_DECLARATION) //Get all top-level elements

      var rootElements = Map[String, XSObject]()

      //If root is configured to something else
      if (rootElementQName.isDefined) {
        rootElements = getRootElements(rootElementQName.get, elements)
      } else {
        rootElements = elements.asScala.toMap.asInstanceOf[Map[String, XSObject]]
      }

      val tempSchemas: mutable.LinkedHashMap[XSObject, Schema] = mutable.LinkedHashMap[XSObject, Schema]()

      for ((_, elementDeclaration: XSElementDeclaration) <- rootElements) {
        tempSchemas += elementDeclaration -> xsTypeDefinitionToAvscSchema(elementDeclaration.getTypeDefinition, optional = false, array = false)
      }

      if (tempSchemas.isEmpty) {
        throw ConversionException("No root element declaration found")
      }

      // Create root record from the schemas generated
      if (tempSchemas.size == 1) {
        tempSchemas.valuesIterator.next()
      } else {
        val nullSchema: Schema = Schema.create(Schema.Type.NULL)
        val fields: ListBuffer[Field] = mutable.ListBuffer[Field]()

        for ((ele, record) <- tempSchemas) {
          val optionalSchema: Schema = Schema.createUnion(List[Schema](nullSchema, record).asJava)
          val field: Field = new Field(validName(ele.getName).get, optionalSchema, null, null)
          field.addProp(XNode.SOURCE, XNode(ele).source)
          fields += field
        }

        val record = Schema.createRecord(generateTypeName, null, null, false)
        record.setFields(fields.asJava)
        record.addProp(XNode.SOURCE, XNode.DOCUMENT)
        record
      }
    }
    schema
  }

  /**
   * complexType or simpleType
   */
  private def xsTypeDefinitionToAvscSchema(typeDefinition: XSTypeDefinition, optional: Boolean, array: Boolean): Schema = {
    typeLevel += 1

    var schema = typeDefinition.getTypeCategory match {
      case SIMPLE_TYPE =>
        primaryType(typeDefinition)
      case COMPLEX_TYPE =>
        val name: String = complexTypeName(typeDefinition)

        //Create a new record-schema if it doesn't already exist
        val tempSchema: Schema = schemas.getOrElse(name, createRecord(name, typeDefinition))
        tempSchema
      case others =>
        throw ConversionException(s"Unknown Element type: $others")
    }

    if (array) {
      schema = Schema.createArray(schema)
    }

    if (optional) {
      val nullSchema: Schema = Schema.create(Schema.Type.NULL)
      schema = Schema.createUnion(List[Schema](nullSchema, schema).asJava)
    }

    typeLevel -= 1
    schema
  }

  private def processGroup(term: XSTerm, innerOptional: Boolean = false, array: Boolean = false): mutable.Map[String, Field] = {
    val fields = mutable.LinkedHashMap[String, Field]()
    val group = term.asInstanceOf[XSModelGroup]
    group.getCompositor match {
      case XSModelGroup.COMPOSITOR_CHOICE =>
        if (rebuildChoice) {
          fields ++= processGroupParticle(group,
            innerOptional = true,
            innerArray = array)
        } else if (!array) {
          fields ++= processGroupParticle(group,
            innerOptional = true,
            innerArray = false)
        } else {
          val name = generateTypeName
          val groupRecord = createRecord(generateTypeName, group)
          fields += (name -> new Field(name,
            Schema.createArray(groupRecord),
            null,
            null))
        }
      case XSModelGroup.COMPOSITOR_SEQUENCE | XSModelGroup.COMPOSITOR_ALL =>
        if (!array) {
          fields ++= processGroupParticle(group,
            innerOptional,
            innerArray = false)
        } else {
          val name = generateTypeName
          val groupRecord = createRecord(generateTypeName, group)
          fields += (name -> new Field(name,
            Schema.createArray(groupRecord),
            null,
            null))
        }
    }
  }

  private def processGroupParticle(group: XSModelGroup, innerOptional: Boolean, innerArray: Boolean): mutable.Map[String, Field] = {
    val fields = mutable.LinkedHashMap[String, Field]()
    for (particle <- group.getParticles.asScala.map(_.asInstanceOf[XSParticle])) {
      val optional = innerOptional || particle.getMinOccurs == 0
      val array = innerArray || particle.getMaxOccurs > 1 || particle.getMaxOccursUnbounded
      val innerTerm = particle.getTerm

      innerTerm match {
        case elementDeclaration: XSElementDeclaration =>
          val field = xsElementToAvscField(elementDeclaration, optional, array)
          fields += (field.name() -> field)
        case wildcard: XSWildcard =>
          val field = xsWildcardToAvscField(wildcard)
          fields += (field.name() -> field)
        case _: XSModelGroup =>
          fields ++= processGroup(innerTerm, optional, array)
        case _ =>
          throw ConversionException(s"Unsupported term type ${group.getType}")
      }
    }
    fields
  }

  private def processAttributes(complexType: XSComplexTypeDefinition): mutable.Map[String, Field] = {
    val fields = mutable.LinkedHashMap[String, Field]()

    // Process normal attributes
    val attributes = complexType.getAttributeUses
    for (attribute <- attributes.asScala.map(_.asInstanceOf[XSAttributeUse])) {
      val optional = !attribute.getRequired
      val field = xsAttributeToAvscField(attribute.getAttrDeclaration, optional)
      fields += (field.name() -> field)
    }

    // Process wildcard attribute
    val wildcard = Option(complexType.getAttributeWildcard)
    if (wildcard.isDefined) {
      val field = xsWildcardToAvscField(wildcard.get)
      fields += (field.name() -> field)
    }
    fields
  }

  private def processExtension(complexType: XSComplexTypeDefinition): mutable.Map[String, Field] = {
    val fields = mutable.LinkedHashMap[String, Field]()

    if (complexType derivedFromType(SchemaGrammar.fAnySimpleType, XSConstants.DERIVATION_EXTENSION)) {
      var extnType = complexType
      while (extnType.getBaseType.getTypeCategory == XSTypeDefinition.COMPLEX_TYPE) extnType =
        extnType.getBaseType.asInstanceOf[XSComplexTypeDefinition]
      val fieldSchema = xsTypeDefinitionToAvscSchema(extnType.getBaseType, optional = true, array = false)
      val field = new Field(XNode.TEXT_VALUE, fieldSchema, null, null)
      field.addProp(XNode.SOURCE, XNode.textNode.source)
      fields += (field.name() -> field)
    }
    fields
  }

  private def processParticle(complexType: XSComplexTypeDefinition): mutable.Map[String, Field] = {
    val fields = mutable.LinkedHashMap[String, Field]()

    val particle = Option(complexType.getParticle)
    if (particle.isDefined) {
      val optional = particle.get.getMinOccurs == 0
      val array = particle.get.getMaxOccurs > 1 || particle.get.getMaxOccursUnbounded
      val innerTerm = particle.get.getTerm

      fields ++= processGroup(innerTerm, optional, array)
    }
    fields
  }

  // Create a list of avsc fields from the element (attributes, text, inner groups)
  private def createFields(typeDef: XSObject): List[Field] = {
    val fields = mutable.LinkedHashMap[String, Field]()
    typeDef match {
      case complexType: XSComplexTypeDefinition =>
        updateFields(fields, processExtension(complexType))
        updateFields(fields, processParticle(complexType))
        updateFields(fields, processAttributes(complexType))
      case complexType: XSTerm =>
        updateFields(fields, processGroup(complexType))
    }
    fields.values.toList
  }

  // Checks if the new set of fields are already existing and generates a new name for duplicate fields
  private def updateFields(originalFields: mutable.Map[String, Field], newField: mutable.Map[String, Field]): Unit = {
    //TODO make it unique
    for ((key, field) <- newField) {
      if (key != "others" && (originalFields contains key)) {
        val newKey = (field getProp "source").split(" ")(0) + "_" + key
        val tempField: Field = new Field(validName(newKey).get, field.schema(), field.doc(), field.defaultValue())
        for ((name, json) <- field.getJsonProps.asScala)
          tempField.addProp(name, json)
        //        field.addProp(XNode.SOURCE, XNode(ele, attribute).source)
        originalFields += newKey -> tempField
      } else {
        originalFields += key -> field
      }
    }
  }

  /**
   * <element> -> AVSC-field
   */
  def xsElementToAvscField(elementDeclaration: XSElementDeclaration, optional: Boolean, array: Boolean = false): Schema.Field = {

    val fieldName = validName(elementDeclaration.getName).get

    val doc = getDocumentation(elementDeclaration).orNull

    val typeDefinition = elementDeclaration.getTypeDefinition
    val fieldSchema: Schema = xsTypeDefinitionToAvscSchema(typeDefinition, optional, array)
    val defaultValue: JsonNode = if (optional) NullNode.getInstance() else null

    val field: Field = new Field(fieldName, fieldSchema, doc, defaultValue)

    field.addProp(XNode.SOURCE, XNode(elementDeclaration).source)

    if (typeDefinition.getTypeCategory == SIMPLE_TYPE) {
      val tempType = typeDefinition.asInstanceOf[XSSimpleTypeDefinition].getBuiltInKind
      if (tempType == XSConstants.DATETIME_DT && xsDateTimeMapping == LogicalTypesConfig.LONG) {
        field.addProp("comment", "timestamp")
      }
    }

    field
  }

  /**
   * <attribute> -> AVSC-field
   */
  def xsAttributeToAvscField(attributeDeclaration: XSAttributeDeclaration, optional: Boolean, array: Boolean = false): Schema.Field = {
    val fieldName = validName(config.attributePrefix + attributeDeclaration.getName).get

    val typeDefinition: XSSimpleTypeDefinition = attributeDeclaration.getTypeDefinition
    val fieldSchema: Schema = xsTypeDefinitionToAvscSchema(typeDefinition, optional, array)
    val defaultValue: JsonNode = if (optional) NullNode.getInstance() else null

    val field: Field = new Field(fieldName, fieldSchema, null, defaultValue)

    field.addProp(XNode.SOURCE, XNode(attributeDeclaration, attribute = true).source)

    val tempType = typeDefinition.getBuiltInKind
    if (tempType == XSConstants.DATETIME_DT && xsDateTimeMapping == LogicalTypesConfig.LONG) {
      field.addProp("comment", "timestamp")
    }

    field
  }

  /**
   * <any> or <anyAttribute> -> AVSC-field
   */
  def xsWildcardToAvscField(wildcard: XSWildcard): Schema.Field = {
    val map = Schema.createMap(Schema.create(Schema.Type.STRING))
    new Field(XNode.WILDCARD, map, null, null)
  }

  /**
   * Get the documentation of an <element>, if it exists. E.g:
   *
   * <element>
   * <annotation>
   * <documentation>This is the doc</documentation>
   * </annotation>
   * </element>
   *
   * Alternately, if the element had no doc, look at the type. E.g:
   *
   * <complexType>
   * <annotation>
   * <documentation>This is the alternate doc</documentation>
   * </annotation>
   * </complexType>
   */
  def getDocumentation(elementDeclaration: XSElementDeclaration): Option[String] = {
    val elementDocumentation = getDocumentation(elementDeclaration.getAnnotations)

    if (elementDocumentation.isDefined) {
      return elementDocumentation
    }

    elementDeclaration.getTypeDefinition match {
      case complexTypeDecl: XSComplexTypeDecl =>
        getDocumentation(complexTypeDecl.getAnnotations)
      case simpleTypeDecl: XSSimpleTypeDecl =>
        getDocumentation(simpleTypeDecl.getAnnotations)
      case _ =>
        None
    }
  }

  def getDocumentation(objectList: XSObjectList): Option[String] = {

    for (i <- 0 until objectList.getLength) {
      objectList.item(i) match {
        case annotation: XSAnnotation =>
          val elem = XML.loadString(annotation.getAnnotationString)
          val maybeDoc = Option((elem \ "documentation").text)
          if (maybeDoc.isDefined && !"".equals(maybeDoc.get)) {
            return maybeDoc
          }
      }
    }
    None
  }

  // Create record for an element
  private def createRecord(name: String, eleType: XSObject): Schema = {
    val schema = Schema.createRecord(name, null, null, false)
    schemas += (name -> schema)
    schema.setFields(createFields(eleType).asJava)
    schema
  }

  private def complexTypeName(eleType: XSTypeDefinition): String = {
    val name = validName(eleType.asInstanceOf[XSComplexTypeDecl].getTypeName)
    if (name.isDefined) {
      name.get
    } else {
      generateTypeName
    }
  }

  private def generateTypeName: String = {
    typeCount += 1
    "type" + typeCount
  }

  private def validName(name: String): Option[String] = {
    val sourceName = Option(name)
    val finalName: Option[String] =
      if (sourceName.isEmpty) None
      else {
        val chars = sourceName.get.toCharArray
        val result = new Array[Char](chars.length)
        var p = 0
        // Remove invalid characters, replace . or - with _
        for (c <- chars) {
          val valid = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'z' || c >= '0' && c <= '9' || c == '_'
          val separator = c == '.' || c == '-'
          if (valid) {
            result(p) = c
            p += 1
          } else if (separator) {
            result(p) = '_'
            p += 1
          }
        }
        var finalName = new String(result, 0, p)

        try {
          // handle built-in types
          Schema.Type.valueOf(finalName.toUpperCase)
          finalName = finalName + "_value"
        } catch {
          case _: IllegalArgumentException =>
        }
        // Handle hive keywords
        if (!ignoreHiveKeywords && XsdToAvscConverter.HIVE_KEYWORDS.contains(finalName.toUpperCase)) {
          finalName = finalName + "_value"
        }
        Option(finalName)
      }
    finalName
  }

  private def makeLogicalType(schemaType: Schema.Type, logicalType: String): Schema = {
    val schema = Schema.create(schemaType)
    schema.addProp("logicalType", logicalType)
    schema
  }

  /**
   * Complex or simple type definition
   */
  private def primaryType(schemaType: XSTypeDefinition): Schema = {

    val simpleType: XSSimpleTypeDefinition = schemaType.asInstanceOf[XSSimpleTypeDefinition]

    val schema: Schema = simpleType.getBuiltInKind match {

      // Mapping xs:dateTime to logical types
      case XSConstants.DATETIME_DT =>
        xsDateTimeMapping match {
          case LogicalTypesConfig.TIMESTAMP_MILLIS | LogicalTypesConfig.TIMESTAMP_MICROS =>
            makeLogicalType(Schema.Type.LONG, xsDateTimeMapping)
          case LogicalTypesConfig.LONG =>
            Schema.create(Schema.Type.LONG)
          case LogicalTypesConfig.STRING =>
            Schema.create(Schema.Type.STRING)
          case _ =>
            throw ConversionException(s"Unsupported xs:dateTime logical type mapping: ${xsDateTimeMapping}");
        }

      // Mapping xs:time to logical types
      case XSConstants.TIME_DT =>
        xsTimeMapping match {
          case LogicalTypesConfig.TIME_MILLIS | LogicalTypesConfig.TIME_MICROS =>
            makeLogicalType(Schema.Type.LONG, xsTimeMapping)
          case LogicalTypesConfig.STRING =>
            Schema.create(Schema.Type.STRING)
          case _ =>
            throw ConversionException(s"Unsupported xs:time logical type mapping: ${xsTimeMapping}");
        }

      // Mapping xs:date to logical types
      case XSConstants.DATE_DT =>
        xsDateMapping match {
          case LogicalTypesConfig.DATE =>
            makeLogicalType(Schema.Type.INT, xsDateMapping)
          case LogicalTypesConfig.STRING =>
            Schema.create(Schema.Type.STRING)
          case _ =>
            throw ConversionException(s"Unsupported xs:date logical type mapping: ${xsDateMapping}");
        }

      // Mapping xs:decimal type restricted with totalDigits and fractionDigits facets to logical decimal type
      case XSConstants.DECIMAL_DT =>
        mapDecimal(simpleType)

      // Mapping other types to non-logical types with a fallback to string
      case otherType =>
        Schema.create(XsdToAvscConverter.PRIMITIVES getOrElse(otherType, Schema.Type.STRING))
    }

    schema
  }

  private def mapDecimal(simpleType: XSSimpleTypeDefinition): Schema = {
    val totalDigitsFacet = Option(simpleType.getFacet(XSSimpleTypeDefinition.FACET_TOTALDIGITS).asInstanceOf[XSFacet])

    val factionDigitsFacet = Option(simpleType.getFacet(XSSimpleTypeDefinition.FACET_FRACTIONDIGITS).asInstanceOf[XSFacet])

    val avroTypeMapping: String = if (xsDecimalMapping.avroType != DecimalConfig.DECIMAL || totalDigitsFacet.isDefined && factionDigitsFacet.isDefined) {
      xsDecimalMapping.avroType
    } else {
      xsDecimalMapping.fallbackType
    }

    avroTypeMapping match {
      // xs:decimal to Avro double mapping or fallback to Avro double
      case DecimalConfig.DOUBLE =>
        Schema.create(Schema.Type.DOUBLE)
      // xs:decimal to Avro string mapping or fallback to Avro string
      case DecimalConfig.STRING =>
        Schema.create(Schema.Type.STRING)
      // xs:decimal to Avro decimal
      case DecimalConfig.DECIMAL =>

        val precision: Int = if (totalDigitsFacet.isDefined) {
          totalDigitsFacet.get.getIntFacetValue
        } else {
          xsDecimalMapping.fallbackPrecision.toInt
        }

        val scale = if (factionDigitsFacet.isDefined) {
          factionDigitsFacet.get.getIntFacetValue
        } else {
          xsDecimalMapping.fallbackScale.toInt
        }

        val decimalSchema = makeLogicalType(Schema.Type.BYTES, "decimal")
        decimalSchema.addProp("precision", IntNode.valueOf(precision))
        decimalSchema.addProp("scale", IntNode.valueOf(scale))
        decimalSchema
      case _ => throw new IllegalArgumentException("Illegal Avro type mapping for xs:decimal type declaration.")
    }

  }
}

object XsdToAvscConverter {
  var PRIMITIVES: Map[Short, Schema.Type] = Map(
    XSConstants.BOOLEAN_DT -> Schema.Type.BOOLEAN,
    XSConstants.INT_DT -> Schema.Type.INT,
    XSConstants.BYTE_DT -> Schema.Type.INT,
    XSConstants.SHORT_DT -> Schema.Type.INT,
    XSConstants.UNSIGNEDBYTE_DT -> Schema.Type.INT,
    XSConstants.UNSIGNEDSHORT_DT -> Schema.Type.INT,
    XSConstants.INTEGER_DT -> Schema.Type.STRING,
    XSConstants.NEGATIVEINTEGER_DT -> Schema.Type.STRING,
    XSConstants.NONNEGATIVEINTEGER_DT -> Schema.Type.STRING,
    XSConstants.POSITIVEINTEGER_DT -> Schema.Type.STRING,
    XSConstants.NONPOSITIVEINTEGER_DT -> Schema.Type.STRING,
    XSConstants.LONG_DT -> Schema.Type.LONG,
    XSConstants.UNSIGNEDINT_DT -> Schema.Type.LONG,
    XSConstants.FLOAT_DT -> Schema.Type.FLOAT,
    XSConstants.DOUBLE_DT -> Schema.Type.DOUBLE,
    XSConstants.DATETIME_DT -> Schema.Type.LONG,
    XSConstants.STRING_DT -> Schema.Type.STRING
  )

  val HIVE_KEYWORDS: List[String] =
    List(
      "ALL",
      "ALTER",
      "AND",
      "ARRAY",
      "AS",
      "AUTHORIZATION",
      "BETWEEN",
      "BIGINT",
      "BINARY",
      "BOOLEAN",
      "BOTH",
      "BY",
      "CASE",
      "CAST",
      "CHAR",
      "COLUMN",
      "COLUMNS",
      "CONF",
      "CREATE",
      "CROSS",
      "CUBE",
      "CURRENT",
      "CURRENT_DATE",
      "CURRENT_TIMESTAMP",
      "CURSOR",
      "DATABASE",
      "DATE",
      "DATETIME",
      "DECIMAL",
      "DELETE",
      "DESCRIBE",
      "DISTINCT",
      "DOUBLE",
      "DROP",
      "ELSE",
      "END",
      "EXCHANGE",
      "EXISTS",
      "EXTENDED",
      "EXTERNAL",
      "FALSE",
      "FETCH",
      "FLOAT",
      "FOLLOWING",
      "FOR",
      "FROM",
      "FULL",
      "FUNCTION",
      "GRANT",
      "GROUP",
      "GROUPING",
      "HAVING",
      "IF",
      "IMPORT",
      "IN",
      "INNER",
      "INSERT",
      "INT",
      "INTERSECT",
      "INTERVAL",
      "INTO",
      "IS",
      "JOIN",
      "LATERAL",
      "LEFT",
      "LESS",
      "LIKE",
      "LOCAL",
      "MACRO",
      "MAP",
      "MORE",
      "NONE",
      "NOT",
      "NULL",
      "OF",
      "ON",
      "OR",
      "ORDER",
      "OUT",
      "OUTER",
      "OVER",
      "PARTIALSCAN",
      "PARTITION",
      "PERCENT",
      "PRECEDING",
      "PRESERVE",
      "PROCEDURE",
      "RANGE",
      "READS",
      "REDUCE",
      "REVOKE",
      "RIGHT",
      "ROLLUP",
      "ROW",
      "ROWS",
      "SELECT",
      "SET",
      "SMALLINT",
      "TABLE",
      "TABLESAMPLE",
      "THEN",
      "TIMESTAMP",
      "TO",
      "TRANSFORM",
      "TRIGGER",
      "TRUE",
      "TRUNCATE",
      "UNBOUNDED",
      "UNION",
      "UNIQUEJOIN",
      "UPDATE",
      "USER",
      "USING",
      "UTC_TMESTAMP",
      "VALUES",
      "VARCHAR",
      "WHEN",
      "WHERE",
      "WINDOW",
      "WITH"
    )

  def apply(config: Config) = new XsdToAvscConverter(config)

  def apply(): XsdToAvscConverter = new XsdToAvscConverter(Config())

  def create(config: Config): XsdToAvscConverter = apply(config)

  def createDefault(): XsdToAvscConverter = apply()
}

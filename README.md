## Convert XML Schema definitions (xsd) into avro schemas (avsc)

Originally a fork of [xml-avro](https://github.com/GeethanadhP/xml-avro) that has been stripped down to only do conversion
of xsd to avsc.

### Example
```java
XsdToAvscConverter converter = XsdToAvscConverter.createDefault();
InputStream xsd = //...
Schema avroSchema = converter.convert(xsd);
```

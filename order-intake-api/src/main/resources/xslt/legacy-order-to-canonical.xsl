<?xml version="1.0" encoding="UTF-8"?>
<!--
  legacy-order-to-canonical.xsl  (XSLT 1.0, runs on the JDK's built-in javax.xml.transform)

  Input : legacy OMS XML - either <SubmitOrderRequest><Order>...</Order></SubmitOrderRequest>
          (namespace http://tailoredbrands.com/legacy/oms/v1) or a bare <Order> as TIBCO BW puts it
          on TB.ORDERS.OUT (with or without namespace - matching is done on local-name()).
  Output: JSON text of the canonical Order object (ARCHITECTURE §3.1 "order"), without orderId -
          order-intake-api assigns the id and wraps it in the OrderEvent envelope.

  Code mappings:
    OrderType   R=RETAIL  T=TAILORED  C=CUSTOM  X=RENTAL  E=ECOM
    FulfillType P=STORE_PICKUP  S=SHIP_TO_HOME  A=ALTERATION
    channel     E -> WEB, everything else -> STORE
-->
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

  <xsl:output method="text" encoding="UTF-8"/>
  <xsl:strip-space elements="*"/>

  <!-- ===================== entry point ===================== -->
  <xsl:template match="/">
    <xsl:variable name="order" select="(//*[local-name()='Order'])[1]"/>
    <xsl:choose>
      <xsl:when test="$order">
        <xsl:apply-templates select="$order" mode="order"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:text>{"_errors":["no Order element found in legacy XML"]}</xsl:text>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!-- ===================== Order ===================== -->
  <xsl:template match="*" mode="order">
    <xsl:variable name="typeCode" select="normalize-space(*[local-name()='OrderType'])"/>
    <xsl:text>{</xsl:text>

    <xsl:text>"orderType":</xsl:text>
    <xsl:variable name="typeName">
      <xsl:call-template name="map-order-type">
        <xsl:with-param name="code" select="$typeCode"/>
      </xsl:call-template>
    </xsl:variable>
    <xsl:call-template name="json-string-or-null">
      <xsl:with-param name="s" select="string($typeName)"/>
    </xsl:call-template>

    <xsl:text>,"channel":</xsl:text>
    <xsl:call-template name="json-string">
      <xsl:with-param name="s">
        <xsl:choose>
          <xsl:when test="$typeCode='E'">WEB</xsl:when>
          <xsl:otherwise>STORE</xsl:otherwise>
        </xsl:choose>
      </xsl:with-param>
    </xsl:call-template>

    <xsl:text>,"storeId":</xsl:text>
    <xsl:call-template name="json-string">
      <xsl:with-param name="s" select="normalize-space(*[local-name()='StoreNbr'])"/>
    </xsl:call-template>

    <xsl:if test="normalize-space(*[local-name()='CustNbr']) != ''">
      <xsl:text>,"customerId":</xsl:text>
      <xsl:call-template name="json-string">
        <xsl:with-param name="s" select="normalize-space(*[local-name()='CustNbr'])"/>
      </xsl:call-template>
    </xsl:if>

    <xsl:text>,"orderedAt":</xsl:text>
    <xsl:call-template name="json-string">
      <xsl:with-param name="s">
        <xsl:call-template name="iso-instant">
          <xsl:with-param name="d" select="normalize-space(*[local-name()='OrderDate'])"/>
        </xsl:call-template>
      </xsl:with-param>
    </xsl:call-template>

    <xsl:if test="normalize-space(*[local-name()='PromiseDate']) != ''">
      <xsl:text>,"promisedDate":</xsl:text>
      <xsl:call-template name="json-string">
        <xsl:with-param name="s" select="substring(normalize-space(*[local-name()='PromiseDate']), 1, 10)"/>
      </xsl:call-template>
    </xsl:if>

    <xsl:text>,"currency":</xsl:text>
    <xsl:call-template name="json-string">
      <xsl:with-param name="s">
        <xsl:choose>
          <xsl:when test="normalize-space(*[local-name()='Currency']) != ''">
            <xsl:value-of select="normalize-space(*[local-name()='Currency'])"/>
          </xsl:when>
          <xsl:otherwise>USD</xsl:otherwise>
        </xsl:choose>
      </xsl:with-param>
    </xsl:call-template>

    <xsl:text>,"totalAmount":</xsl:text>
    <xsl:variable name="total">
      <xsl:call-template name="sum-lines">
        <xsl:with-param name="lines" select="*[local-name()='Lines']/*[local-name()='Line']"/>
      </xsl:call-template>
    </xsl:variable>
    <xsl:value-of select="format-number(number($total), '0.00')"/>

    <xsl:text>,"lines":[</xsl:text>
    <xsl:for-each select="*[local-name()='Lines']/*[local-name()='Line']">
      <xsl:if test="position() &gt; 1">,</xsl:if>
      <xsl:apply-templates select="." mode="line"/>
    </xsl:for-each>
    <xsl:text>]</xsl:text>

    <xsl:if test="*[local-name()='Rental']">
      <xsl:text>,"rental":</xsl:text>
      <xsl:apply-templates select="*[local-name()='Rental']" mode="rental"/>
    </xsl:if>

    <xsl:if test="*[local-name()='ShipTo']">
      <xsl:text>,"shipTo":</xsl:text>
      <xsl:apply-templates select="*[local-name()='ShipTo']" mode="address"/>
    </xsl:if>

    <!-- unknown legacy codes are reported here (and the field is null) so the caller can reject
         the order with a precise message instead of a generic XSLT failure -->
    <xsl:variable name="errors">
      <xsl:if test="not($typeCode='R' or $typeCode='T' or $typeCode='C' or $typeCode='X' or $typeCode='E')">
        <xsl:text>,</xsl:text>
        <xsl:call-template name="json-string">
          <xsl:with-param name="s" select="concat('unknown OrderType code ', $typeCode)"/>
        </xsl:call-template>
      </xsl:if>
      <xsl:for-each select="*[local-name()='Lines']/*[local-name()='Line']">
        <xsl:variable name="ff" select="normalize-space(*[local-name()='FulfillType'])"/>
        <xsl:if test="not($ff='P' or $ff='S' or $ff='A')">
          <xsl:text>,</xsl:text>
          <xsl:call-template name="json-string">
            <xsl:with-param name="s" select="concat('line ', normalize-space(*[local-name()='LineNbr']), ': unknown FulfillType code ', $ff)"/>
          </xsl:call-template>
        </xsl:if>
      </xsl:for-each>
    </xsl:variable>
    <xsl:if test="string($errors) != ''">
      <xsl:text>,"_errors":[</xsl:text>
      <xsl:value-of select="substring(string($errors), 2)"/>
      <xsl:text>]</xsl:text>
    </xsl:if>

    <xsl:text>}</xsl:text>
  </xsl:template>

  <!-- ===================== Line ===================== -->
  <xsl:template match="*" mode="line">
    <xsl:variable name="fulfill" select="normalize-space(*[local-name()='FulfillType'])"/>
    <xsl:text>{"lineNumber":</xsl:text>
    <xsl:value-of select="number(*[local-name()='LineNbr'])"/>
    <xsl:text>,"sku":</xsl:text>
    <xsl:call-template name="json-string">
      <xsl:with-param name="s" select="normalize-space(*[local-name()='SKU'])"/>
    </xsl:call-template>
    <xsl:text>,"quantity":</xsl:text>
    <xsl:value-of select="number(*[local-name()='Qty'])"/>
    <xsl:text>,"unitPrice":</xsl:text>
    <xsl:value-of select="format-number(number(*[local-name()='Price']), '0.00##')"/>
    <xsl:text>,"fulfillmentType":</xsl:text>
    <xsl:variable name="fulfillName">
      <xsl:call-template name="map-fulfill-type">
        <xsl:with-param name="code" select="$fulfill"/>
      </xsl:call-template>
    </xsl:variable>
    <xsl:call-template name="json-string-or-null">
      <xsl:with-param name="s" select="string($fulfillName)"/>
    </xsl:call-template>
    <xsl:if test="*[local-name()='Alteration']">
      <xsl:text>,"alteration":</xsl:text>
      <xsl:apply-templates select="*[local-name()='Alteration']" mode="alteration"/>
    </xsl:if>
    <xsl:text>}</xsl:text>
  </xsl:template>

  <!-- ===================== Alteration ===================== -->
  <xsl:template match="*" mode="alteration">
    <xsl:text>{"type":</xsl:text>
    <xsl:call-template name="json-string">
      <xsl:with-param name="s" select="normalize-space(*[local-name()='AltType'])"/>
    </xsl:call-template>
    <xsl:if test="normalize-space(*[local-name()='Measurement']) != ''">
      <xsl:text>,"measurementInches":</xsl:text>
      <xsl:value-of select="number(*[local-name()='Measurement'])"/>
    </xsl:if>
    <xsl:if test="normalize-space(*[local-name()='TailorShop']) != ''">
      <xsl:text>,"tailorShopId":</xsl:text>
      <xsl:call-template name="json-string">
        <xsl:with-param name="s" select="normalize-space(*[local-name()='TailorShop'])"/>
      </xsl:call-template>
    </xsl:if>
    <xsl:text>}</xsl:text>
  </xsl:template>

  <!-- ===================== Rental ===================== -->
  <!-- Every field is optional: collect them with a leading comma each, then drop the first comma. -->
  <xsl:template match="*" mode="rental">
    <xsl:variable name="fields">
      <xsl:call-template name="optional-string-field">
        <xsl:with-param name="name">eventId</xsl:with-param>
        <xsl:with-param name="value" select="normalize-space(*[local-name()='EventNbr'])"/>
      </xsl:call-template>
      <xsl:call-template name="optional-string-field">
        <xsl:with-param name="name">eventDate</xsl:with-param>
        <xsl:with-param name="value" select="substring(normalize-space(*[local-name()='EventDate']), 1, 10)"/>
      </xsl:call-template>
      <xsl:call-template name="optional-string-field">
        <xsl:with-param name="name">returnDueDate</xsl:with-param>
        <xsl:with-param name="value" select="substring(normalize-space(*[local-name()='ReturnDate']), 1, 10)"/>
      </xsl:call-template>
      <xsl:call-template name="optional-string-field">
        <xsl:with-param name="name">groupId</xsl:with-param>
        <xsl:with-param name="value" select="normalize-space(*[local-name()='GroupNbr'])"/>
      </xsl:call-template>
    </xsl:variable>
    <xsl:call-template name="json-object">
      <xsl:with-param name="fields" select="string($fields)"/>
    </xsl:call-template>
  </xsl:template>

  <!-- ===================== Address ===================== -->
  <xsl:template match="*" mode="address">
    <xsl:variable name="fields">
      <xsl:call-template name="optional-string-field">
        <xsl:with-param name="name">name</xsl:with-param>
        <xsl:with-param name="value" select="normalize-space(*[local-name()='Name'])"/>
      </xsl:call-template>
      <xsl:call-template name="optional-string-field">
        <xsl:with-param name="name">line1</xsl:with-param>
        <xsl:with-param name="value" select="normalize-space(*[local-name()='Addr1'])"/>
      </xsl:call-template>
      <xsl:call-template name="optional-string-field">
        <xsl:with-param name="name">line2</xsl:with-param>
        <xsl:with-param name="value" select="normalize-space(*[local-name()='Addr2'])"/>
      </xsl:call-template>
      <xsl:call-template name="optional-string-field">
        <xsl:with-param name="name">city</xsl:with-param>
        <xsl:with-param name="value" select="normalize-space(*[local-name()='City'])"/>
      </xsl:call-template>
      <xsl:call-template name="optional-string-field">
        <xsl:with-param name="name">state</xsl:with-param>
        <xsl:with-param name="value" select="normalize-space(*[local-name()='State'])"/>
      </xsl:call-template>
      <xsl:call-template name="optional-string-field">
        <xsl:with-param name="name">postalCode</xsl:with-param>
        <xsl:with-param name="value" select="normalize-space(*[local-name()='Zip'])"/>
      </xsl:call-template>
      <xsl:call-template name="optional-string-field">
        <xsl:with-param name="name">country</xsl:with-param>
        <xsl:with-param name="value">
          <xsl:choose>
            <xsl:when test="normalize-space(*[local-name()='Country']) != ''">
              <xsl:value-of select="normalize-space(*[local-name()='Country'])"/>
            </xsl:when>
            <xsl:otherwise>US</xsl:otherwise>
          </xsl:choose>
        </xsl:with-param>
      </xsl:call-template>
    </xsl:variable>
    <xsl:call-template name="json-object">
      <xsl:with-param name="fields" select="string($fields)"/>
    </xsl:call-template>
  </xsl:template>

  <!-- Emits ,"name":"value" when value is non-empty (leading comma; see json-object). -->
  <xsl:template name="optional-string-field">
    <xsl:param name="name"/>
    <xsl:param name="value"/>
    <xsl:if test="string($value) != ''">
      <xsl:text>,"</xsl:text>
      <xsl:value-of select="$name"/>
      <xsl:text>":</xsl:text>
      <xsl:call-template name="json-string">
        <xsl:with-param name="s" select="$value"/>
      </xsl:call-template>
    </xsl:if>
  </xsl:template>

  <!-- Wraps a ",f1,f2,..." field list (as produced by optional-string-field) into {f1,f2,...}. -->
  <xsl:template name="json-object">
    <xsl:param name="fields"/>
    <xsl:text>{</xsl:text>
    <xsl:value-of select="substring($fields, 2)"/>
    <xsl:text>}</xsl:text>
  </xsl:template>

  <!-- ===================== helpers ===================== -->

  <!-- R=RETAIL T=TAILORED C=CUSTOM X=RENTAL E=ECOM; empty string for unknown codes -->
  <xsl:template name="map-order-type">
    <xsl:param name="code"/>
    <xsl:choose>
      <xsl:when test="$code='R'">RETAIL</xsl:when>
      <xsl:when test="$code='T'">TAILORED</xsl:when>
      <xsl:when test="$code='C'">CUSTOM</xsl:when>
      <xsl:when test="$code='X'">RENTAL</xsl:when>
      <xsl:when test="$code='E'">ECOM</xsl:when>
    </xsl:choose>
  </xsl:template>

  <!-- P=STORE_PICKUP S=SHIP_TO_HOME A=ALTERATION; empty string for unknown codes -->
  <xsl:template name="map-fulfill-type">
    <xsl:param name="code"/>
    <xsl:choose>
      <xsl:when test="$code='P'">STORE_PICKUP</xsl:when>
      <xsl:when test="$code='S'">SHIP_TO_HOME</xsl:when>
      <xsl:when test="$code='A'">ALTERATION</xsl:when>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="json-string-or-null">
    <xsl:param name="s"/>
    <xsl:choose>
      <xsl:when test="$s = ''">
        <xsl:text>null</xsl:text>
      </xsl:when>
      <xsl:otherwise>
        <xsl:call-template name="json-string">
          <xsl:with-param name="s" select="$s"/>
        </xsl:call-template>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!-- Sum(Qty*Price) over the line set; XSLT 1.0 has no product-sum, so recurse. -->
  <xsl:template name="sum-lines">
    <xsl:param name="lines"/>
    <xsl:param name="acc" select="0"/>
    <xsl:choose>
      <xsl:when test="count($lines) = 0">
        <xsl:value-of select="$acc"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:variable name="head" select="$lines[1]"/>
        <xsl:call-template name="sum-lines">
          <xsl:with-param name="lines" select="$lines[position() &gt; 1]"/>
          <xsl:with-param name="acc"
                          select="$acc + number($head/*[local-name()='Qty']) * number($head/*[local-name()='Price'])"/>
        </xsl:call-template>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!-- Legacy dates sometimes come without a zone; canonical orderedAt must be an RFC-3339 instant. -->
  <xsl:template name="iso-instant">
    <xsl:param name="d"/>
    <xsl:variable name="time" select="substring-after($d, 'T')"/>
    <xsl:choose>
      <xsl:when test="$time = ''">
        <!-- date only -->
        <xsl:value-of select="concat($d, 'T00:00:00Z')"/>
      </xsl:when>
      <xsl:when test="contains($time, 'Z') or contains($time, '+') or contains($time, '-')">
        <xsl:value-of select="$d"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:value-of select="concat($d, 'Z')"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!-- "escaped string" -->
  <xsl:template name="json-string">
    <xsl:param name="s"/>
    <xsl:variable name="s1">
      <xsl:call-template name="replace">
        <xsl:with-param name="text" select="string($s)"/>
        <xsl:with-param name="from">\</xsl:with-param>
        <xsl:with-param name="to">\\</xsl:with-param>
      </xsl:call-template>
    </xsl:variable>
    <xsl:variable name="s2">
      <xsl:call-template name="replace">
        <xsl:with-param name="text" select="string($s1)"/>
        <xsl:with-param name="from">"</xsl:with-param>
        <xsl:with-param name="to">\"</xsl:with-param>
      </xsl:call-template>
    </xsl:variable>
    <xsl:variable name="s3">
      <xsl:call-template name="replace">
        <xsl:with-param name="text" select="string($s2)"/>
        <xsl:with-param name="from" select="'&#10;'"/>
        <xsl:with-param name="to">\n</xsl:with-param>
      </xsl:call-template>
    </xsl:variable>
    <xsl:variable name="s4">
      <xsl:call-template name="replace">
        <xsl:with-param name="text" select="string($s3)"/>
        <xsl:with-param name="from" select="'&#13;'"/>
        <xsl:with-param name="to">\r</xsl:with-param>
      </xsl:call-template>
    </xsl:variable>
    <xsl:variable name="s5">
      <xsl:call-template name="replace">
        <xsl:with-param name="text" select="string($s4)"/>
        <xsl:with-param name="from" select="'&#9;'"/>
        <xsl:with-param name="to">\t</xsl:with-param>
      </xsl:call-template>
    </xsl:variable>
    <xsl:text>"</xsl:text>
    <xsl:value-of select="$s5"/>
    <xsl:text>"</xsl:text>
  </xsl:template>

  <xsl:template name="replace">
    <xsl:param name="text"/>
    <xsl:param name="from"/>
    <xsl:param name="to"/>
    <xsl:choose>
      <xsl:when test="contains($text, $from)">
        <xsl:value-of select="substring-before($text, $from)"/>
        <xsl:value-of select="$to"/>
        <xsl:call-template name="replace">
          <xsl:with-param name="text" select="substring-after($text, $from)"/>
          <xsl:with-param name="from" select="$from"/>
          <xsl:with-param name="to" select="$to"/>
        </xsl:call-template>
      </xsl:when>
      <xsl:otherwise>
        <xsl:value-of select="$text"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

</xsl:stylesheet>

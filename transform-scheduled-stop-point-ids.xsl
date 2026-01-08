<?xml version="1.0" encoding="UTF-8"?>
<!--
SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>

SPDX-License-Identifier: CC0-1.0
-->
<xsl:stylesheet version="3.0"
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xmlns:n="http://www.netex.org.uk/netex"
  xmlns:local="local:functions"
  xsi:schemaLocation="http://www.netex.org.uk/netex https://raw.githubusercontent.com/NeTEx-CEN/NeTEx/master/xsd/NeTEx_publication.xsd"
  exclude-result-prefixes="n"  >

  <!-- Identity template to copy every node and attribute by default -->
  <xsl:mode on-no-match="shallow-copy"/>

  <!--
    Transform the IDs of ScheduledStopPoints from this format

      IT:ITH1:ScheduledStopPoint:it-22101-7010-51-32073:

    to this

      IT:ITH10:ScheduledStopPoint:22101:7010:51:32073

    .

    This is because the SIRI feeds use the latter format, and we need to match up the two sources.
    It is a mystery to me why these IDs continue to be to badly aligned.
  -->

  <xsl:function name="local:transform-id">
    <xsl:param name="input"/>
    <xsl:value-of select="replace(replace($input, '.*ScheduledStopPoint:it-22021-(.+?):$', 'IT:ITH10:ScheduledStopPoint:$1'), '-', ':')"/>
  </xsl:function>

  <xsl:template match="//n:ScheduledStopPoint/@id">
    <xsl:attribute name="id">
      <xsl:value-of select="local:transform-id(.)"/>
    </xsl:attribute>
  </xsl:template>

  <xsl:template match="//n:RoutePointRef/@ref">
    <xsl:attribute name="ref">
        <xsl:value-of select="local:transform-id(.)"/>
    </xsl:attribute>
  </xsl:template>

  <xsl:template match="//n:ScheduledStopPointRef/@ref">
    <xsl:attribute name="ref">
        <xsl:value-of select="local:transform-id(.)"/>
    </xsl:attribute>
  </xsl:template>
</xsl:stylesheet>
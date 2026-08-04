<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:page="http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15">
    <xsl:output method="xml" indent="yes" />
    <xsl:strip-space elements="*"/>

    <!-- copy all nodes with all attributes -->
    <xsl:template match="@*|node()">
     <xsl:copy>
      <xsl:apply-templates select="@*|node()"/>
     </xsl:copy>
    </xsl:template>

    <!-- filter nodes and attributes -->

    <!-- 2016-07-15 -->
    <xsl:template match="page:Page/@primaryLanguage"/>
    <xsl:template match="page:Page/@secondaryLanguage"/>
    <xsl:template match="page:Page/@primaryScript"/>
    <xsl:template match="page:Page/@secondaryScript"/>
    <xsl:template match="page:Page/@readingDirection"/>
    <xsl:template match="page:Page/@textLineOrder"/>

    <xsl:template match="page:Metadata/@externalRef"/>

    <xsl:template match="page:Page/@textLineOrder"/>
    <xsl:template match="page:TextRegion/@textLineOrder"/>

    <xsl:template match="page:TextEquiv/@dataType"/>

    <xsl:template match="page:TextEquiv/@dataTypeDetails"/>

    <xsl:template match="page:TextEquiv/@index"/>
    <xsl:template match="page:TextEquiv/@comments"/>

    <xsl:template match="page:TextLine/@primaryScript"/>
    <xsl:template match="page:TextLine/@secondaryScript"/>
    <xsl:template match="page:Word/@primaryScript"/>
    <xsl:template match="page:Word/@secondaryScript"/>

    <xsl:template match="page:Glyph/@script"/>


    <!-- 2017-07-15 -->
    <xsl:template match="page:Graphemes"/>

    <xsl:template match="page:UserDefined"/>
    <xsl:template match="page:UserAttribute"/>

    <xsl:template match="page:TableCellRole"/>

    <xsl:template match="page:TextRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:ImageRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:LineDrawingRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:GraphicRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:TableRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:ChartRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:MapRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:SeparatorRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:MathsRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:ChemRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:MusicRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:AdvertRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:NoiseRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:UnknownRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:CustomRegion[@type= 'list-label']/@type"/>
    <xsl:template match="page:TextLine[@type= 'list-label']/@type"/>
    <xsl:template match="page:Word[@type= 'list-label']/@type"/>
    <xsl:template match="page:Glyph[@type= 'list-label']/@type"/>

    <xsl:template match="page:OrderedGroupIndexedType/@type"/>
    <xsl:template match="page:UnorderedGroupIndexedType/@type"/>
    <xsl:template match="page:RegionRefType/@type"/>
    <xsl:template match="page:OrderedGroupType/@type"/>
    <xsl:template match="page:UnorderedGroupType/@type"/>
    <xsl:template match="page:TextStyle/@textColourRgb"/>


    <!-- 2018-07-15 -->
    <xsl:template match="page:MapRegion"/>
    <xsl:template match="page:CustomRegion"/>

    <xsl:template match="page:Page/@conf"/>
    <xsl:template match="page:Coords/@conf"/>
    <xsl:template match="page:BaseLine/@conf"/>
    <xsl:template match="page:ReadingOrder/@conf"/>
    <xsl:template match="page:AlternativeImage/@conf"/>

    <xsl:template match="page:TableCellRole/@header"/>

    <xsl:template match="page:TextLine/@index"/>

    <xsl:template match="page:Page/@imageXResolution"/>
    <xsl:template match="page:Page/@imageYResolution"/>
    <xsl:template match="page:Page/@imageResolutionUnit"/>

    <xsl:template match="page:MetadataItem"/>

    <xsl:template match="page:Grid"/>

    <xsl:template match="page:Relation/page:SourceRegionRef">
      <xsl:element name="RegionRef" namespace="http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15">
        <xsl:apply-templates select="@*"/>
      </xsl:element>
    </xsl:template>

    <xsl:template match="page:Relation/page:TargetRegionRef">
      <xsl:element name="RegionRef" namespace="http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15">
        <xsl:apply-templates select="@*"/>
      </xsl:element>
    </xsl:template>

    <xsl:template match="page:Labels" />

    <xsl:template match="page:TextRegion/page:AlternativeImage"/>
    <xsl:template match="page:ImageRegion/page:AlternativeImage"/>
    <xsl:template match="page:LineDrawingRegion/page:AlternativeImage"/>
    <xsl:template match="page:GraphicRegion/page:AlternativeImage"/>
    <xsl:template match="page:TableRegion/page:AlternativeImage"/>
    <xsl:template match="page:ChartRegion/page:AlternativeImage"/>
    <xsl:template match="page:MapRegion/page:AlternativeImage"/>
    <xsl:template match="page:SeparatorRegion/page:AlternativeImage"/>
    <xsl:template match="page:MathsRegion/page:AlternativeImage"/>
    <xsl:template match="page:ChemRegion/page:AlternativeImage"/>
    <xsl:template match="page:MusicRegion/page:AlternativeImage"/>
    <xsl:template match="page:AdvertRegion/page:AlternativeImage"/>
    <xsl:template match="page:NoiseRegion/page:AlternativeImage"/>
    <xsl:template match="page:UnknownRegion/page:AlternativeImage"/>
    <xsl:template match="page:CustomRegion/page:AlternativeImage"/>
    <xsl:template match="page:TextLine/page:AlternativeImage"/>
    <xsl:template match="page:Word/page:AlternativeImage"/>
    <xsl:template match="page:Glyph/page:AlternativeImage"/>


    <!-- 2019-07-15 -->
    <xsl:template match="page:Page/page:TextStyle"/>
    <xsl:template match="page:Page/@orientation"/>
    <xsl:template match="page:TextStyle/@underlineStyle"/>
    
</xsl:stylesheet>

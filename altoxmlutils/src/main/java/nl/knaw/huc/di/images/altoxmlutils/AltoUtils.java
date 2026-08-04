package nl.knaw.huc.di.images.altoxmlutils;

import nl.knaw.huc.di.images.layoutds.models.Alto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.bind.DatatypeConverter;
import java.util.ArrayList;

import static nl.knaw.huc.di.images.stringtools.StringTools.convertStringToXMLDocument;

public class AltoUtils {

    private static final Logger LOG = LoggerFactory.getLogger(AltoUtils.class);

    public static AltoDocument readAltoDocumentFromString(String pageXmlString) {
        Document document = convertStringToXMLDocument(pageXmlString);
        if (document == null) {
            return null;
        }
        Node documentElement = document.getFirstChild();

        AltoDocument altoDocument = new AltoDocument();

        for (int i = 0; i < documentElement.getChildNodes().getLength(); i++) {
            Node node = documentElement.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "Description":
                    altoDocument.setDescription(getDescription(node));
                    break;
                case "Styles":
                    altoDocument.setStyles(getStyles(node));
                    break;
                case "Layout":
                    altoDocument.setLayout(getLayout(node));
                    break;
                default:
                    LOG.warn("Unexpected node {} - {}", documentElement.getNodeName(), node.getNodeName());
                    break;
            }
        }
        return altoDocument;
    }

    private static Layout getLayout(Node parent) {
        Layout layout = new Layout();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "Page":
                    layout.setPage(getPage(node));
                    break;
//                case "sourceImageInformation":
//                    description.setSourceImageInformation(getSourceImageInformation(node));
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return layout;
    }

    private static Page getPage(Node parent) {
        Page page = new Page();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "TopMargin":
                    page.setTopMargin(getMargin(node));
                    break;
                case "LeftMargin":
                    page.setLeftMargin(getMargin(node));
                    break;
                case "RightMargin":
                    page.setRightMargin(getMargin(node));
                    break;
                case "BottomMargin":
                    page.setBottomMargin(getMargin(node));
                    break;
                case "PrintSpace":
                    page.setPrintSpace(getPrintSpace(node));
                    break;
//                case "sourceImageInformation":
//                    description.setSourceImageInformation(getSourceImageInformation(node));
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                case "ID":
                    page.setId(attribute.getTextContent());
                    break;
                case "PAGECLASS":
                    page.setPageClass(attribute.getTextContent());
                    break;
//                case "STYLEREFS":
//                    page.setStyleRefs(attribute.getTextContent());
//                    break;
//                case "PROCESSINGREFS":
//                    page.setProcessingRefs(attribute.getTextContent());
//                    break;
                case "HEIGHT":
                    page.setHeight(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "WIDTH":
                    page.setWidth(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "PHYSICAL_IMG_NR":
                    page.setPhysicalImageNumber(Float.parseFloat(attribute.getTextContent()));
                    break;
                case "PRINTED_IMG_NR":
                    page.setPrintedImgNr(attribute.getTextContent());
                    break;
                case "QUALITY":
                    page.setQuality(getQuality(attribute.getTextContent()));
                    break;
                case "QUALITY_DETAIL":
                    page.setQualityDetail(attribute.getTextContent());
                    break;
                case "POSITION":
                    page.setPosition(getPosition(attribute.getTextContent()));
                    break;
                case "PROCESSING":
                    page.setProcessing(attribute.getTextContent());
                    break;
                case "ACCURACY":
                    page.setAccuracy(Float.parseFloat(attribute.getTextContent()));
                    break;
                case "PC":
                    page.setPageConfidence(Float.parseFloat(attribute.getTextContent()));
                    break;
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return page;
    }

    private static QualityType getQuality(String textContent) {
        switch (textContent) {
            case "OK":
                return QualityType.OK;
            case "Missing":
                return QualityType.Missing;
            case "Missing in original":
                return QualityType.MissingInOriginal;
            case "Damaged":
                return QualityType.Damaged;
            case "Retained":
                return QualityType.Retained;
            case "Target":
                return QualityType.Target;
            case "As in original":
                return QualityType.AsInOriginal;
            default:
                LOG.warn("unknown PositionType: {}", textContent);
                return null;
        }
    }

    private static PositionType getPosition(String textContent) {
        switch (textContent) {
            case "Left":
                return PositionType.Left;
            case "Right":
                return PositionType.Right;
            case "Foldout":
                return PositionType.Foldout;
            case "Single":
                return PositionType.Single;
            case "Cover":
                return PositionType.Cover;
            default:
                LOG.warn("unknown PositionType: {}", textContent);
                return null;
        }
    }

    private static Margin getMargin(Node parent) {
        Margin margin = new Margin();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                case "ID":
                    margin.setId(attribute.getTextContent());
                    break;
                case "HPOS":
                    margin.setHpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "VPOS":
                    margin.setVpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "WIDTH":
                    margin.setWidth(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "HEIGHT":
                    margin.setHeight(Integer.parseInt(attribute.getTextContent()));
                    break;
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }

        return margin;
    }

    private static PrintSpace getPrintSpace(Node parent) {
        PrintSpace printSpace = new PrintSpace();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "TextBlock":
                    printSpace.getPrintSpaceBlocks().add(getTextBlock(node));
                    break;
                case "ComposedBlock":
                    printSpace.getPrintSpaceBlocks().add(getComposedBlock(node));
                    break;
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                case "ID":
                    printSpace.setId(attribute.getTextContent());
                    break;
                case "HPOS":
                    printSpace.setHpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "VPOS":
                    printSpace.setVpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "WIDTH":
                    printSpace.setWidth(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "HEIGHT":
                    printSpace.setHeight(Integer.parseInt(attribute.getTextContent()));
                    break;
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return printSpace;
    }

    private static PrintSpaceBlock getComposedBlock(Node parent) {
        ComposedBlock block = new ComposedBlock();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "TextLine":
                    if (block.getTextLines() == null) {
                        block.setTextLines(new ArrayList<>());
                    }
                    block.getTextLines().add(getTextLine(node));
                    break;
                case "GraphicalElement":
                    if (block.getBlocks() == null) {
                        block.setBlocks(new ArrayList<>());
                    }
                    block.getBlocks().add(getGraphicalElement(node));
                    break;
                case "TextBlock":
                    if (block.getBlocks() == null) {
                        block.setBlocks(new ArrayList<>());
                    }
                    block.getBlocks().add(getTextBlock(node));
                    break;
                case "Shape":
                    block.setShape(getShape(node));
                    break;
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                case "ID":
                    block.setId(attribute.getTextContent());
                    break;
                case "HPOS":
                    block.setHpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "VPOS":
                    block.setVpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "WIDTH":
                    block.setWidth(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "HEIGHT":
                    block.setHeight(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "STYLEREFS":
                    block.setStyleRefs(attribute.getTextContent());
                    break;
                case "TYPE":
                    block.setType(attribute.getTextContent());
                    break;
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return block;
    }

    private static PrintSpaceBlock getGraphicalElement(Node parent) {
        PrintSpaceBlock block = new GraphicalElement();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "Shape":
                    block.setShape(getShape(node));
                    break;
                case "type":
                    block.setType(node.getTextContent());
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                case "ID":
                    block.setId(attribute.getTextContent());
                    break;
                case "HEIGHT":
                    block.setHeight(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "WIDTH":
                    block.setWidth(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "HPOS":
                    block.setHpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "VPOS":
                    block.setVpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "ROTATION":
                    block.setRotation(Float.parseFloat(attribute.getTextContent()));
                    break;
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return block;
    }

    private static PrintSpaceBlock getTextBlock(Node parent) {
        PrintSpaceBlock block = new TextBlock();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "TextLine":
                    if (block.getTextLines() == null) {
                        block.setTextLines(new ArrayList<>());
                    }
                    block.getTextLines().add(getTextLine(node));
                    break;
                case "Shape":
                    block.setShape(getShape(node));
                    break;
                case "type":
                    block.setType(node.getTextContent());
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                case "ID":
                    block.setId(attribute.getTextContent());
                    break;
                case "STYLEREFS":
                    block.setStyleRefs(attribute.getTextContent());
                    break;
//                case "TAGREFS":
//                    block.setTagRefs(attribute.getTextContent());
//                    break;
//                case "PROCESSINGREFS":
//                    block.setTagRefs(attribute.getTextContent());
//                    break;
                case "HEIGHT":
                    block.setHeight(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "WIDTH":
                    block.setWidth(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "HPOS":
                    block.setHpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "VPOS":
                    block.setVpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "ROTATION":
                    block.setRotation(Float.parseFloat(attribute.getTextContent()));
                    break;
                case "IDNEXT":
                    block.setIdNext(attribute.getTextContent());
                    break;
                case "CS":
                    block.setCS(Boolean.parseBoolean(attribute.getTextContent()));
                    break;
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return block;
    }

    private static Shape getShape(Node parent) {
        Shape shape = new Shape();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "Polygon":
                    shape.setPolygon(getPolygon(node));
                    break;
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return shape;

    }

    private static Polygon getPolygon(Node parent) {
        Polygon polygon = new Polygon();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                case "POINTS":
                    polygon.setPoints(attribute.getTextContent());
                    break;
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return polygon;
    }

    private static TextLine getTextLine(Node parent) {
        TextLine textLine = new TextLine();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "String":
                    textLine.getTextLineElements().add(getString(node));
                    break;
                case "SP":
                    textLine.getTextLineElements().add(getSP(node));
                    break;
                case "HYP":
                    textLine.getTextLineElements().add(getHYP(node));
                    break;
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                case "ID":
                    textLine.setId(attribute.getTextContent());
                    break;
                case "HPOS":
                    textLine.setHpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "VPOS":
                    textLine.setVpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "WIDTH":
                    textLine.setWidth(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "HEIGHT":
                    textLine.setHeight(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "CS":
                    textLine.setCS(Boolean.parseBoolean(attribute.getTextContent()));
                    break;
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return textLine;
    }

    private static SP getSP(Node parent) {
        SP sp = new SP();
        sp.setType("SP");
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                case "ID":
                    sp.setId(attribute.getTextContent());
                    break;
                case "HPOS":
                    sp.setHpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "VPOS":
                    sp.setVpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "WIDTH":
                    sp.setWidth(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "HEIGHT":
                    sp.setHeight(Integer.parseInt(attribute.getTextContent()));
                    break;
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return sp;
    }

    private static Hypenation getHYP(Node parent) {
        Hypenation hypenation = new Hypenation();
        hypenation.setType("HYP");
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
//                case "String":
//                    textLine.getContents().add(getString(node));
//                case "SP":
//                    textLine.getContents().add(getSP(node));
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                case "ID":
                    hypenation.setId(attribute.getTextContent());
                    break;
                case "HPOS":
                    hypenation.setHpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "VPOS":
                    hypenation.setVpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "WIDTH":
                    hypenation.setWidth(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "HEIGHT":
                    hypenation.setHeight(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "CONTENT":
                    hypenation.setContent(attribute.getTextContent());
                    break;
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return hypenation;
    }

    private static AltoString getString(Node parent) {
        AltoString altoString = new AltoString();
        altoString.setType("String");
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                case "ID":
                    altoString.setId(attribute.getTextContent());
                    break;
                case "HPOS":
                    altoString.setHpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "VPOS":
                    altoString.setVpos(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "WIDTH":
                    altoString.setWidth(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "HEIGHT":
                    altoString.setHeight(Integer.parseInt(attribute.getTextContent()));
                    break;
                case "CC":
                    altoString.setCharacterConfidence(attribute.getTextContent());
                    break;
                case "WC":
                    altoString.setWordConfidence(Float.parseFloat(attribute.getTextContent()));
                    break;
                case "CONTENT":
                    altoString.setContent(attribute.getTextContent());
                    break;
                case "STYLEREFS":
                    altoString.setStyleRefs(attribute.getTextContent());
                    break;
                case "SUBS_CONTENT":
                    altoString.setSubsContent(attribute.getTextContent());
                    break;
                case "SUBS_TYPE":
                    altoString.setSubsType(getSubsType(attribute.getTextContent()));
                    break;
                case "STYLE":
                    altoString.setStyle(getFontStylesType(attribute.getTextContent()));
                    break;
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return altoString;
    }

    private static FontStylesType getFontStylesType(String textContent) {
        switch (textContent) {
            case "bold":
                return FontStylesType.bold;
            case "italics":
                return FontStylesType.italics;
            case "smallcaps":
                return FontStylesType.smallcaps;
            case "strikethrough":
                return FontStylesType.strikethrough;
            case "subscript":
                return FontStylesType.subscript;
            case "superscript":
                return FontStylesType.superscript;
            case "underline":
                return FontStylesType.underline;
            default:
                LOG.warn("unknown SubsType: {}", textContent);
                return null;
        }
    }

    private static SubsType getSubsType(String textContent) {
        switch (textContent) {
            case "HypPart1":
                return SubsType.HypPart1;
            case "HypPart2":
                return SubsType.HypPart2;
            case "Abbreviation":
                return SubsType.Abbreviation;
            default:
                LOG.warn("unknown SubsType: {}", textContent);
                return null;
        }
    }

    private static ArrayList<Style> getStyles(Node parent) {
        ArrayList<Style> styles = new ArrayList<>();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "TextStyle":
//                    styles.add(getTextStyle(node));
                    break;
                case "ParagraphStyle":
//                    styles.add(getTextStyle(node));
                    break;
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        if (styles.size() == 0) {
            return null;
        }
        return styles;

    }

    private static Description getDescription(Node parent) {
        Description description = new Description();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "MeasurementUnit":
                    description.setMeasurementUnit(getMeasurementUnit(node));
                    break;
                case "sourceImageInformation":
                    description.setSourceImageInformation(getSourceImageInformation(node));
                    break;
                case "OCRProcessing":
                    description.setOcrProcessing(getOCRProcessing(node));
                    break;
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return description;
    }

    private static OCRProcessing getOCRProcessing(Node parent) {
        OCRProcessing ocrProcessing = new OCRProcessing();

        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "preProcessingStep":
                    ocrProcessing.getPreProcessingStep().add(getProcessingStep(node));
                    break;
                case "ocrProcessingStep":
                    ocrProcessing.getOcrProcessingStep().add(getProcessingStep(node));
                    break;
                case "postProcessingStep":
                    ocrProcessing.getPostProcessingStep().add(getProcessingStep(node));
                    break;
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                case "ID":
                    ocrProcessing.setId(attribute.getTextContent());
                    break;

                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return ocrProcessing;
    }

    private static ProcessingStep getProcessingStep(Node parent) {
        ProcessingStep processingStep = new ProcessingStep();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "processingCategory":
                    processingStep.setProcessingCategory(getProcessingCategory(node));
                    break;
                case "processingDateTime":
//                    DateFormat dateFormat = new SimpleDateFormat("[-]CCYY-MM-DDThh:mm:ss[Z|(+|-)hh:mm]");
                    // OPT-04: java.time replaces joda-time
                    java.time.ZonedDateTime processingDateTime = DatatypeConverter
                            .parseDateTime(node.getTextContent())
                            .toInstant()
                            .atZone(java.time.ZoneId.systemDefault());
                    processingStep.setProcessingDate(processingDateTime.toString());
                    break;
                case "processingAgency":
                    processingStep.setProcessingAgency(node.getTextContent());
                    break;
                case "processingStepDescription":
                    processingStep.setProcessingStepDescription(node.getTextContent());
                    break;
                case "processingStepSettings":
                    processingStep.setProcessingStepSettings(node.getTextContent());
                    break;
                case "processingSoftware":
                    processingStep.setProcessingSoftware(getProcessingSoftware(node));
                    break;
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return processingStep;
    }

    private static OCRProcessingSoftware getProcessingSoftware(Node parent) {
        OCRProcessingSoftware ocrProcessingSoftware = new OCRProcessingSoftware();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "softwareCreator":
                    ocrProcessingSoftware.setSoftwareCreator(node.getTextContent());
                    break;
                case "softwareName":
                    ocrProcessingSoftware.setSoftwareName(node.getTextContent());
                    break;
                case "softwareVersion":
                    ocrProcessingSoftware.setSoftwareVersion(node.getTextContent());
                    break;
                case "applicationDescription":
                    ocrProcessingSoftware.setApplicationDescription(node.getTextContent());
                    break;
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return ocrProcessingSoftware;
    }

    private static ProcessingCategory getProcessingCategory(Node parent) {
        ProcessingCategory processingCategory = new ProcessingCategory();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return processingCategory;
    }

    private static SourceImageInformation getSourceImageInformation(Node parent) {
        SourceImageInformation sourceImageInformation = new SourceImageInformation();
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            Node node = parent.getChildNodes().item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getNodeName()) {
                case "fileName":
                    sourceImageInformation.setFileName(node.getTextContent());
                    break;
                default:
                    LOG.warn("Unexpected child node {} - {} - {}", parent.getNodeName(), node.getNodeName(), node.getNodeValue());
                    break;
            }
        }
        for (int j = 0; j < parent.getAttributes().getLength(); j++) {
            Node attribute = parent.getAttributes().item(j);
            switch (attribute.getNodeName()) {
                default:
                    LOG.warn("Unexpected attribute {} - {} - {}", parent.getNodeName(), attribute.getNodeName(), attribute.getNodeValue());
                    break;
            }
        }
        return sourceImageInformation;
    }

    private static MeasurementUnit getMeasurementUnit(Node node) {
        switch (node.getTextContent()) {
            case "pixel":
                return MeasurementUnit.pixel;
            case "cm":
                return MeasurementUnit.cm;
            case "inch":
                return MeasurementUnit.inch;
            default:
                LOG.warn("Unexpected MeasurementUnit {} - {}", node.getNodeName(), node.getNodeValue());
                return MeasurementUnit.pixel;
        }

    }
}

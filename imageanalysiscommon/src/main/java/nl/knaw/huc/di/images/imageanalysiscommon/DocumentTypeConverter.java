package nl.knaw.huc.di.images.imageanalysiscommon;

import com.google.common.base.Strings;
import nl.knaw.huc.di.images.layoutds.models.Alto.*;
import nl.knaw.huc.di.images.layoutds.models.Alto.Page;
import nl.knaw.huc.di.images.layoutds.models.Alto.PrintSpace;
import nl.knaw.huc.di.images.layoutds.models.Alto.TextLine;
import nl.knaw.huc.di.images.layoutds.models.*;
import nl.knaw.huc.di.images.layoutds.models.Page.*;
import nl.knaw.huc.di.images.layoutds.models.hocr.*;
import org.apache.commons.io.FilenameUtils;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DocumentTypeConverter {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentTypeConverter.class);


    // TODO RUTGERCHECK: implement all below and add unit tests
    // everything to documentImage and later documentImage to everyThing
    public static DocumentPage pageToDocumentPage(PcGts page) {
        if (page == null) {
            return null;
        }
        String id = page.getPage().getImageFilename();
        if (Strings.isNullOrEmpty(id)) {
            id = UUID.randomUUID().toString();
        }
        String imageFilename = page.getPage().getImageFilename();
//        remove extension
        imageFilename = FilenameUtils.removeExtension(imageFilename);
//        get only filename
        imageFilename = FilenameUtils.getName(imageFilename);
//        split on underscore and take last part
        String[] parts = imageFilename.split("_");
        String scanId = "";
        if (parts.length > 1) {
            scanId = parts[parts.length - 1];
        }

        float scanIdNumber;
        try{
            scanIdNumber = Float.parseFloat(scanId);

        } catch (NumberFormatException e) {
            if (parts.length >2) {
                scanId = parts[parts.length -2] + "." + parts[parts.length -1];
                try {
                    scanIdNumber = Float.parseFloat(scanId);
                } catch (NumberFormatException ex) {
                    scanIdNumber = 0;
                }
            }else {
                scanIdNumber = 0;
            }
        }

        DocumentPage documentPage = new DocumentPage(id, page.getMetadata().getCreated(), page.getPage().getImageHeight(), page.getPage().getImageWidth());
        documentPage.setPhysicalImageNumber(scanIdNumber);

        if (page.getPage().getPrintSpace() != null) {
            PrintSpace printSpace = new PrintSpace();
            printSpace.setId(page.getPage().getPrintSpace().getId());
            ArrayList<Point> points = StringConverter.stringToPoint(page.getPage().getPrintSpace().getCoords().getPoints());
            printSpace.setHpos((int) points.get(0).x);
            printSpace.setVpos((int) points.get(0).y);
            printSpace.setWidth((int) (points.get(1).x - points.get(0).x));
            printSpace.setHeight((int) (points.get(3).y - points.get(0).y));
            documentPage.setPrintSpaceId(printSpace.getId());
            documentPage.setPrintSpaceHpos(printSpace.getHpos());
            documentPage.setPrintSpaceVpos(printSpace.getVpos());
            documentPage.setPrintSpaceHeight(printSpace.getHeight());
            documentPage.setPrintSpaceWidth(printSpace.getWidth());
        }
        for (TextRegion textRegion : page.getPage().getTextRegions()) {
            DocumentTextBlock documentTextBlock = new DocumentTextBlock();
            documentPage.getDocumentTextBlocks().add(documentTextBlock);
            DocumentParagraph documentParagraph = new DocumentParagraph();
            documentTextBlock.getDocumentParagraphs().add(documentParagraph);
            Rect regionBoundingBox = getBoundingBox(textRegion);
            documentTextBlock.setHeight(regionBoundingBox.height);
            documentTextBlock.setWidth(regionBoundingBox.width);
            documentTextBlock.setXStart(regionBoundingBox.x);
            documentTextBlock.setYStart(regionBoundingBox.y);
            documentTextBlock.setIdString(textRegion.getId());
            for (nl.knaw.huc.di.images.layoutds.models.Page.TextLine textLine : textRegion.getTextLines()) {
                Rect textLineBoundingBox = getBoundingBox(textLine);
                DocumentTextLine documentTextLine = new DocumentTextLine(textLineBoundingBox.y, textLineBoundingBox.x, textLineBoundingBox.height, textLineBoundingBox.width);
                TextEquiv textEquiv = textLine.getTextEquiv();
                if (textEquiv != null) {
                    documentTextLine.setText(textEquiv.getUnicode());
                    documentTextLine.setConfidence(textEquiv.getConf());
                }
                if (textLine.getCoords() != null) {
                    documentTextLine.setCoordinates(textLine.getCoords().getPoints());
                }
                if (textLine.getBaseline() != null) {
                    documentTextLine.setBaseLine(StringConverter.stringToPoint(textLine.getBaseline().getPoints()));
                }
                documentTextLine.setCustom(textLine.getCustom());
                documentTextLine.setPrimaryLanguage(textLine.getPrimaryLanguage());
                TextStyle textStyle = textLine.getTextStyle();
                if (textStyle != null) {
                    documentTextLine.setBold(textStyle.getBold());
                    documentTextLine.setFontFamily(textStyle.getFontFamily());
                    documentTextLine.setFontSize(textStyle.getFontSize());
                    documentTextLine.setItalic(textStyle.getItalic());
                    documentTextLine.setStrikethrough(textStyle.getStrikethrough());
                    documentTextLine.setSubscript(textStyle.getSubscript());
                    documentTextLine.setSuperscript(textStyle.getSuperscript());
                    documentTextLine.setUnderlined(textStyle.getUnderlined());
                }
                documentTextLine.setIdString(textLine.getId());
                documentTextBlock.getDocumentParagraphs().get(0).getDocumentTextLines().add(documentTextLine);

                if (textLine.getWords() != null && textLine.getWords().size() == 0 && !Strings.isNullOrEmpty(documentTextLine.getText())) {
                    String[] splitted = documentTextLine.getText().split(" ");
                    for (int i = 0; i < splitted.length; i++) {
                        DocumentWord newWord = new DocumentWord();
                        documentTextLine.getWords().add(newWord);
                        newWord.setContent(splitted[i]);
                        newWord.setIdString(UUID.randomUUID().toString());
                    }
                }
                if (textLine.getWords() != null) {
                    for (Word word : textLine.getWords()) {
                        if (word.getTextEquiv() != null) {
                            DocumentWord newWord = new DocumentWord();
                            Rect newWordBoundingBox = getBoundingBox(word);
                            newWord.setHeight(newWordBoundingBox.height);
                            newWord.setWidth(newWordBoundingBox.width);
                            newWord.setXStart(newWordBoundingBox.x);
                            newWord.setYStart(newWordBoundingBox.y);


                            if (documentTextLine.getWords() == null) {
                                documentTextLine.setWords(new ArrayList<>());
                            }
                            documentTextLine.getWords().add(newWord);
                            newWord.setIdString(word.getId());
                            TextEquiv wordTextEquiv = word.getTextEquiv();
                            if (wordTextEquiv != null) {
                                newWord.setContent(wordTextEquiv.getUnicode());
                            }
                        }
                    }
                }
            }
        }
        return documentPage;
    }

    private static Rect getBoundingBox(TextRegion textRegion) {
        return getBoundingBox(textRegion.getCoords().getPoints());
    }

    private static Rect getBoundingBox(String coordPoints) {
        ArrayList<Point> points = StringConverter.stringToPoint(coordPoints);
        int xMin = Integer.MAX_VALUE;
        int xMax = Integer.MIN_VALUE;
        int yMin = Integer.MAX_VALUE;
        int yMax = Integer.MIN_VALUE;
        for (Point point : points) {
            if (point.x < xMin) {
                xMin = (int) point.x;
            }
            if (point.x > xMax) {
                xMax = (int) point.x;
            }
            if (point.y < yMin) {
                yMin = (int) point.y;
            }
            if (point.y > yMax) {
                yMax = (int) point.y;
            }
        }
        return new Rect(xMin, yMin, xMax - xMin, yMax - yMin);
    }

    private static Rect getBoundingBox(nl.knaw.huc.di.images.layoutds.models.Page.TextLine textLine){
        return getBoundingBox(textLine.getCoords().getPoints());
    }

    private static Rect getBoundingBox(Word word){
        return getBoundingBox(word.getCoords().getPoints());
    }


    private static TextRegion documentTextBlockToTextRegion(DocumentTextBlock documentTextBlock) {
        TextRegion textRegion = new TextRegion();

        if (documentTextBlock.getXStart() != null) {
            Coords coords = new Coords();
            coords.setPoints(StringConverter.pointToString(documentTextBlock.getBoundingBox()));
            textRegion.setCoords(coords);
        }
        if (documentTextBlock.getDocumentParagraphs().size() > 0) {
            for (DocumentTextLine documentTextLine : documentTextBlock.getDocumentParagraphs().get(0).getDocumentTextLines()) {
                nl.knaw.huc.di.images.layoutds.models.Page.TextLine textLine = new nl.knaw.huc.di.images.layoutds.models.Page.TextLine();
                if (!Strings.isNullOrEmpty(documentTextLine.getText())) {
                    TextEquiv textEquiv = new TextEquiv(null, documentTextLine.getText());
                    textLine.setTextEquiv(textEquiv);
                }
                Baseline baseLine = getBaseline(documentTextLine);
                textLine.setBaseline(baseLine);
                if (documentTextLine.getXStart() != null) {
                    Coords coords = new Coords();

                    coords.setPoints(StringConverter.pointToString(documentTextLine.getBoundingBox()));
                    textLine.setCoords(coords);
                }
                if (documentTextLine.getXHeight() != null) {
                    if (textLine.getTextStyle() == null) {
                        textLine.setTextStyle(new TextStyle());
                        textLine.getTextStyle().setxHeight(documentTextLine.getXHeight());
                    }
                }
                textRegion.getTextLines().add(textLine);
                textLine.setId(documentTextLine.getIdString());
                if (documentTextLine.getWords() != null) {
                    for (DocumentWord word : documentTextLine.getWords()) {
                        Word newWord = new Word();
                        if (textLine.getWords() == null) {
                            textLine.setWords(new ArrayList<>());
                        }

                        if (word.getXStart() != null && word.getYStart() != null) {
                            String points = StringConverter.boundingBoxToPoints(
                                    new Rect(word.getXStart(), word.getYStart(), word.getWidth(), word.getHeight()));
                            if (!Strings.isNullOrEmpty(points)) {
                                newWord.setCoords(new Coords());
                                newWord.getCoords().setPoints(points);
                            }
                        }
                        textLine.getWords().add(newWord);

                        newWord.setId(word.getIdString());
                        TextEquiv textEquiv = new TextEquiv(null, word.getContent());
                        newWord.setTextEquiv(textEquiv);
                    }
                }
            }
        }

        if (documentTextBlock.getDocumentTextBlocks() != null) {
            for (DocumentTextBlock subBlock : documentTextBlock.getDocumentTextBlocks()) {
                TextRegion subRegion = documentTextBlockToTextRegion(subBlock);
                textRegion.getTextRegions().add(subRegion);
            }
        }
        return textRegion;

    }

    private static Baseline getBaseline(DocumentTextLine documentTextLine) {
        if (documentTextLine.getCompressedBaseLine() != null && documentTextLine.getCompressedBaseLine().size() > 0) {
            Baseline baseLine = new Baseline();
            baseLine.setPoints(StringConverter.pointToString(documentTextLine.getCompressedBaseLine()));
            return baseLine;
        }
        return null;
    }

    public static PcGts documentPageToPage(DocumentPage documentPage) {
        if (documentPage == null) {
            return null;
        }
        PcGts pcGts = new PcGts();

        if (documentPage.getPrintSpaceHpos() != null) {
            nl.knaw.huc.di.images.layoutds.models.Page.PrintSpace printSpace = new nl.knaw.huc.di.images.layoutds.models.Page.PrintSpace();
            Coords printSpaceCoords = new Coords();
            List<Point> points = new ArrayList<Point>();
            points.add(new Point(documentPage.getPrintSpaceHpos(), documentPage.getPrintSpaceVpos()));
            points.add(new Point(documentPage.getPrintSpaceHpos() + documentPage.getPrintSpaceWidth(), documentPage.getPrintSpaceVpos()));
            points.add(new Point(documentPage.getPrintSpaceHpos() + documentPage.getPrintSpaceWidth(), documentPage.getPrintSpaceVpos() + documentPage.getPrintSpaceHeight()));
            points.add(new Point(documentPage.getPrintSpaceHpos(), documentPage.getPrintSpaceVpos() + documentPage.getPrintSpaceHeight()));
            printSpaceCoords.setPoints(StringConverter.pointToString(points));
            printSpace.setCoords(printSpaceCoords);
            printSpace.setId(documentPage.getPrintSpaceId());
            pcGts.getPage().setPrintSpace(printSpace);
        }
        for (DocumentTextBlock documentTextBlock : documentPage.getDocumentTextBlocks()) {
            TextRegion textRegion = documentTextBlockToTextRegion(documentTextBlock);
            pcGts.getPage().getTextRegions().add(textRegion);
        }
        pcGts.getPage().setImageFilename(documentPage.getFileName());
        pcGts.getPage().setImageHeight(documentPage.getHeight());
        pcGts.getPage().setImageWidth(documentPage.getWidth());
        pcGts.getMetadata().setCreated(documentPage.getCreated());
        pcGts.getMetadata().setLastChange(documentPage.getCreated());
        pcGts.getMetadata().setCreator(documentPage.getCreator());
        return pcGts;
    }

    public static AltoDocument documentPageToAlto(DocumentPage documentPage) {
        AltoDocument altoDocument = new AltoDocument();
        altoDocument.getLayout().getPage().setPosition(documentPage.getPosition());
        altoDocument.getLayout().getPage().setPageConfidence(documentPage.getPageConfidence());
        altoDocument.getLayout().getPage().setQuality(documentPage.getQuality());
        altoDocument.getLayout().getPage().setQualityDetail(documentPage.getQualityDetail());
        altoDocument.getLayout().getPage().setProcessing(documentPage.getProcessing());
        altoDocument.getLayout().getPage().setId(documentPage.getIdString());
        float psysicalImageNumber = documentPage.getPhysicalImageNumber() != null ? documentPage.getPhysicalImageNumber() : 0;
        altoDocument.getLayout().getPage().setPhysicalImageNumber(psysicalImageNumber);
        Description description = new Description();
        if (documentPage.getOcrProcessing() != null) {
            if (documentPage.getOcrProcessing().getOcrProcessingStep() != null) {
                for (ProcessingStep processingStep : documentPage.getOcrProcessing().getOcrProcessingStep()) {
                    ProcessingStep newProcessingStep = new ProcessingStep();
                    newProcessingStep.setProcessingDate(processingStep.getProcessingDate());
                    newProcessingStep.setProcessingSoftware(processingStep.getProcessingSoftware());
                    newProcessingStep.setProcessingAgency(processingStep.getProcessingAgency());
                    newProcessingStep.setProcessingStepDescription(processingStep.getProcessingStepDescription());
                    newProcessingStep.setProcessingCategory(processingStep.getProcessingCategory());
                    newProcessingStep.setProcessingStepSettings(processingStep.getProcessingStepSettings());
                    description.getOcrProcessing().getOcrProcessingStep().add(newProcessingStep);
                }
            }
            if (documentPage.getOcrProcessing().getPreProcessingStep() != null) {
                for (ProcessingStep processingStep : documentPage.getOcrProcessing().getPreProcessingStep()) {
                    ProcessingStep newProcessingStep = new ProcessingStep();
                    newProcessingStep.setProcessingDate(processingStep.getProcessingDate());
                    newProcessingStep.setProcessingSoftware(processingStep.getProcessingSoftware());
                    newProcessingStep.setProcessingAgency(processingStep.getProcessingAgency());
                    newProcessingStep.setProcessingStepDescription(processingStep.getProcessingStepDescription());
                    newProcessingStep.setProcessingCategory(processingStep.getProcessingCategory());
                    newProcessingStep.setProcessingStepSettings(processingStep.getProcessingStepSettings());
                    description.getOcrProcessing().getPreProcessingStep().add(newProcessingStep);
                }
            }
            if (documentPage.getOcrProcessing().getPostProcessingStep() != null) {
                for (ProcessingStep processingStep : documentPage.getOcrProcessing().getPostProcessingStep()) {
                    ProcessingStep newProcessingStep = new ProcessingStep();
                    newProcessingStep.setProcessingDate(processingStep.getProcessingDate());
                    newProcessingStep.setProcessingSoftware(processingStep.getProcessingSoftware());
                    newProcessingStep.setProcessingAgency(processingStep.getProcessingAgency());
                    newProcessingStep.setProcessingStepDescription(processingStep.getProcessingStepDescription());
                    newProcessingStep.setProcessingCategory(processingStep.getProcessingCategory());
                    newProcessingStep.setProcessingStepSettings(processingStep.getProcessingStepSettings());
                    description.getOcrProcessing().getPostProcessingStep().add(newProcessingStep);
                }
            }

            description.getOcrProcessing().setId(documentPage.getOcrProcessing().getId());
        }
//        if (documentPage.getOcrProcessing().getPreProcessingStep() !=null) {
//            ProcessingStep processingStep = new ProcessingStep();
//            processingStep.setDate(documentPage.getOcrProcessing().getOcrProcessingStep().getDate());
//            processingStep.setSoftware(documentPage.getOcrProcessing().getOcrProcessingStep().getSoftware());
//        }

        if (documentPage.getFileName() != null) {
            SourceImageInformation sourceImageInformation = new SourceImageInformation();
            sourceImageInformation.setFileName(documentPage.getFileName());
            description.setSourceImageInformation(sourceImageInformation);
        }

        altoDocument.setDescription(description);
        altoDocument.setSchemaLocation(documentPage.getAltoSchemaLocation());
        final Page page = altoDocument.getLayout().getPage();
        page.setId(documentPage.getIdString());
        page.setHeight(documentPage.getHeight());
        page.setWidth(documentPage.getWidth());

        if (documentPage.getPrintSpaceHeight() != null || documentPage.getHeight() != null) {
            page.setPrintSpace(new PrintSpace());
        }
        if (documentPage.getPrintSpaceHeight() != null) {
            page.getPrintSpace().setHeight(documentPage.getPrintSpaceHeight());
        } else if (documentPage.getHeight() != null) {
            page.getPrintSpace().setHeight(documentPage.getHeight());
        }
        if (documentPage.getPrintSpaceWidth() != null) {
            page.getPrintSpace().setWidth(documentPage.getPrintSpaceWidth());
        } else if (documentPage.getWidth() != null) {
            page.getPrintSpace().setWidth(documentPage.getWidth());
        }
        if (documentPage.getPrintSpaceVpos() != null) {
            page.getPrintSpace().setVpos(documentPage.getPrintSpaceVpos());
        }
        if (documentPage.getPrintSpaceHpos() != null) {
            page.getPrintSpace().setHpos(documentPage.getPrintSpaceHpos());
        }
        if (documentPage.getPrintSpaceId() != null) {
            page.getPrintSpace().setId(documentPage.getPrintSpaceId());
        }

        if (documentPage.getTopMarginHeight() != null) {
            page.setTopMargin(new Margin(documentPage.getTopMarginId(), documentPage.getTopMarginHeight(),
                    documentPage.getTopMarginWidth(), documentPage.getTopMarginVpos(), documentPage.getTopMarginHpos()));
        }
        if (documentPage.getBottomMarginHeight() != null) {
            page.setBottomMargin(new Margin(documentPage.getBottomMarginId(), documentPage.getBottomMarginHeight(),
                    documentPage.getBottomMarginWidth(), documentPage.getBottomMarginVpos(), documentPage.getBottomMarginHpos()));
        }
        if (documentPage.getLeftMarginHeight() != null) {
            page.setLeftMargin(new Margin(documentPage.getLeftMarginId(), documentPage.getLeftMarginHeight(),
                    documentPage.getLeftMarginWidth(), documentPage.getLeftMarginVpos(), documentPage.getLeftMarginHpos()));
        }
        if (documentPage.getRightMarginHeight() != null) {
            page.setRightMargin(new Margin(documentPage.getRightMarginId(), documentPage.getRightMarginHeight(),
                    documentPage.getRightMarginWidth(), documentPage.getRightMarginVpos(), documentPage.getRightMarginHpos()));
        }
        page.setPhysicalImageNumber(documentPage.getPhysicalImageNumber());
        page.setPrintedImgNr(documentPage.getPrintedImgNumber());

        for (DocumentTextBlock documentTextBlock : documentPage.getDocumentTextBlocks()) {
            TextBlock textBlock = getTextBlockFromDocumentPage(documentTextBlock);
            page.getPrintSpace().getPrintSpaceBlocks().add(textBlock);
        }
        return altoDocument;
    }

    private static TextBlock getTextBlockFromDocumentPage(DocumentTextBlock documentTextBlock) {
        TextBlock textBlock = new TextBlock();
        textBlock.setId(documentTextBlock.getIdString());
        textBlock.setHeight(documentTextBlock.getHeight());
        textBlock.setWidth(documentTextBlock.getWidth());
        textBlock.setVpos(documentTextBlock.getYStart());
        textBlock.setHpos(documentTextBlock.getXStart());
        if (documentTextBlock.getPolygon() != null) {
            textBlock.setShape(new Shape());
            textBlock.getShape().getPolygon().setPoints(documentTextBlock.getPolygon());
        }
        textBlock.setStyleRefs(documentTextBlock.getStyleRefs());
        textBlock.setRotation(documentTextBlock.getRotation());
        textBlock.setType(documentTextBlock.getType());
        if (documentTextBlock.getDocumentTextBlocks() != null) {
            for (DocumentTextBlock documentTextBlock1 : documentTextBlock.getDocumentTextBlocks()) {
                TextBlock subDocumentTextBlock = getTextBlockFromDocumentPage(documentTextBlock1);
                if (textBlock.getBlocks() == null) {
                    textBlock.setBlocks(new ArrayList<>());
                }
                textBlock.getBlocks().add(subDocumentTextBlock);
            }
        }
        for (DocumentTextLine documentTextLine : documentTextBlock.getDocumentParagraphs().get(0).getDocumentTextLines()) {
            TextLine altoTextLine = new TextLine(documentTextLine.getHeight(), documentTextLine.getWidth(), documentTextLine.getYStart(), documentTextLine.getXStart());
            if (Strings.isNullOrEmpty(documentTextLine.getText())){
                continue;
            }
            altoTextLine.setId(documentTextLine.getIdString());
            if (documentTextLine.getBaseLineY() != null) {
                altoTextLine.setBASELINE(documentTextLine.getBaseLineY().toString());
            }
            altoTextLine.setCS(documentTextLine.getCS());
            if (textBlock.getTextLines() == null) {
                textBlock.setTextLines(new ArrayList<>());
            }
            textBlock.getTextLines().add(altoTextLine);
            if (documentTextLine.getWords() != null) {
                for (DocumentWord word : documentTextLine.getWords()) {
                    TextLineElement textLineElement = null;
                    if ("SP".equals(word.getType())) {
//                        System.out.println("SP");
                        textLineElement = new SP();
                    } else if ("HYP".equals(word.getType())) {
//                        System.out.println("HYP");
                        Hypenation hypenation = new Hypenation();
                        hypenation.setContent(word.getContent());
                        textLineElement = hypenation;

                    } else {
                        AltoString altoString = new AltoString(word.getHeight(), word.getWidth(), word.getYStart(), word.getXStart());
                        altoString.setContent(word.getContent());
                        altoString.setWordConfidence(word.getWordConfidence());
                        altoString.setCharacterConfidence(word.getCC());
                        altoString.setStyle(word.getStyle());
                        altoString.setStyleRefs(word.getStyleRefs());
                        altoString.setSubsType(word.getSubsType());
                        altoString.setSubsContent(word.getSubsContent());
                        textLineElement = altoString;
                    }
                    textLineElement.setId(word.getIdString());
                    textLineElement.setHpos(word.getXStart());
                    textLineElement.setVpos(word.getYStart());
                    textLineElement.setWidth(word.getWidth());
                    if (word.getType() == null) {
                        textLineElement.setType("String");
                    } else {
                        textLineElement.setType(word.getType());
                    }
                    altoTextLine.getTextLineElements().add(textLineElement);
                }
            }
        }
        return textBlock;
    }

    public static DocumentPage altoDocumentToDocumentPage(AltoDocument altoDocument) {
        DocumentPage documentPage = new DocumentPage(altoDocument.getLayout().getPage().getId(),null, altoDocument.getLayout().getPage().getHeight(), altoDocument.getLayout().getPage().getWidth());
        documentPage.setIdString(altoDocument.getLayout().getPage().getId());
        documentPage.setAltoSchemaLocation(altoDocument.getSchemaLocation());
        documentPage.setPosition(altoDocument.getLayout().getPage().getPosition());
        documentPage.setPageConfidence(altoDocument.getLayout().getPage().getPageConfidence());
        documentPage.setPhysicalImageNumber(altoDocument.getLayout().getPage().getPhysicalImageNumber());
        documentPage.setPrintedImgNumber(altoDocument.getLayout().getPage().getPrintedImgNr());
        documentPage.setQuality(altoDocument.getLayout().getPage().getQuality());
        documentPage.setQualityDetail(altoDocument.getLayout().getPage().getQualityDetail());
        documentPage.setProcessing(altoDocument.getLayout().getPage().getProcessing());
        if (altoDocument.getDescription() != null) {
            if (altoDocument.getDescription().getSourceImageInformation() != null) {
                documentPage.setFileName(altoDocument.getDescription().getSourceImageInformation().getFileName());
            }
            documentPage.setOcrProcessing(altoDocument.getDescription().getOcrProcessing());
        }
        if (altoDocument.getLayout() != null) {
            if (altoDocument.getLayout().getPage().getTopMargin() != null) {
                documentPage.setTopMarginId(altoDocument.getLayout().getPage().getTopMargin().getId());
                documentPage.setTopMarginHeight(altoDocument.getLayout().getPage().getTopMargin().getHeight());
                documentPage.setTopMarginWidth(altoDocument.getLayout().getPage().getTopMargin().getWidth());
                documentPage.setTopMarginHpos(altoDocument.getLayout().getPage().getTopMargin().getHpos());
                documentPage.setTopMarginVpos(altoDocument.getLayout().getPage().getTopMargin().getVpos());
            }
            if (altoDocument.getLayout().getPage().getBottomMargin() != null) {
                documentPage.setBottomMarginId(altoDocument.getLayout().getPage().getBottomMargin().getId());
                documentPage.setBottomMarginHeight(altoDocument.getLayout().getPage().getBottomMargin().getHeight());
                documentPage.setBottomMarginWidth(altoDocument.getLayout().getPage().getBottomMargin().getWidth());
                documentPage.setBottomMarginHpos(altoDocument.getLayout().getPage().getBottomMargin().getHpos());
                documentPage.setBottomMarginVpos(altoDocument.getLayout().getPage().getBottomMargin().getVpos());
            }
            if (altoDocument.getLayout().getPage().getLeftMargin() != null) {
                documentPage.setLeftMarginId(altoDocument.getLayout().getPage().getLeftMargin().getId());
                documentPage.setLeftMarginHeight(altoDocument.getLayout().getPage().getLeftMargin().getHeight());
                documentPage.setLeftMarginWidth(altoDocument.getLayout().getPage().getLeftMargin().getWidth());
                documentPage.setLeftMarginHpos(altoDocument.getLayout().getPage().getLeftMargin().getHpos());
                documentPage.setLeftMarginVpos(altoDocument.getLayout().getPage().getLeftMargin().getVpos());
            }
            if (altoDocument.getLayout().getPage().getRightMargin() != null) {
                documentPage.setRightMarginId(altoDocument.getLayout().getPage().getRightMargin().getId());
                documentPage.setRightMarginHeight(altoDocument.getLayout().getPage().getRightMargin().getHeight());
                documentPage.setRightMarginWidth(altoDocument.getLayout().getPage().getRightMargin().getWidth());
                documentPage.setRightMarginHpos(altoDocument.getLayout().getPage().getRightMargin().getHpos());
                documentPage.setRightMarginVpos(altoDocument.getLayout().getPage().getRightMargin().getVpos());
            }
            if (altoDocument.getLayout().getPage().getPrintSpace() != null) {
                documentPage.setPrintSpaceHeight(altoDocument.getLayout().getPage().getPrintSpace().getHeight());
                documentPage.setPrintSpaceHpos(altoDocument.getLayout().getPage().getPrintSpace().getHpos());
                documentPage.setPrintSpaceVpos(altoDocument.getLayout().getPage().getPrintSpace().getVpos());
                documentPage.setPrintSpaceWidth(altoDocument.getLayout().getPage().getPrintSpace().getWidth());
                documentPage.setPrintSpaceId(altoDocument.getLayout().getPage().getPrintSpace().getId());
            }
        }


        if (altoDocument.getLayout().getPage().getPrintSpace() != null) {
            for (PrintSpaceBlock textBlock : altoDocument.getLayout().getPage().getPrintSpace().getPrintSpaceBlocks()) {
                DocumentTextBlock documentTextBlock = getDocumentTextBlockFromAltoTextBlock(textBlock);
                documentPage.getDocumentTextBlocks().add(documentTextBlock);
            }
        }

        return documentPage;
    }

    private static DocumentTextBlock getDocumentTextBlockFromAltoTextBlock(PrintSpaceBlock textBlock) {
        DocumentTextBlock documentTextBlock = new DocumentTextBlock();
        documentTextBlock.setIdString(textBlock.getId());
        documentTextBlock.setHeight(textBlock.getHeight());
        documentTextBlock.setWidth(textBlock.getWidth());
        documentTextBlock.setYStart(textBlock.getVpos());
        documentTextBlock.setXStart(textBlock.getHpos());
        if (textBlock.getShape() != null) {
            documentTextBlock.setPolygon(textBlock.getShape().getPolygon().getPoints());
        }
        documentTextBlock.setStyleRefs(textBlock.getStyleRefs());
        documentTextBlock.setRotation(textBlock.getRotation());
        documentTextBlock.setType(textBlock.getType());

        DocumentParagraph documentParagraph = new DocumentParagraph();
        documentTextBlock.getDocumentParagraphs().add(documentParagraph);
        if (textBlock.getBlocks() != null) {
            for (PrintSpaceBlock printSpaceBlock : textBlock.getBlocks()) {
                DocumentTextBlock subDocumentTextBlock = getDocumentTextBlockFromAltoTextBlock(printSpaceBlock);
                if (documentTextBlock.getDocumentTextBlocks() == null) {
                    documentTextBlock.setDocumentTextBlocks(new ArrayList<>());
                }
                documentTextBlock.getDocumentTextBlocks().add(subDocumentTextBlock);
            }
        }
        if (textBlock.getTextLines() != null) {
            for (TextLine textLine : textBlock.getTextLines()) {
                DocumentTextLine documentTextLine = new DocumentTextLine(textLine.getVpos(), textLine.getHpos(), textLine.getHeight(), textLine.getWidth());
                documentTextLine.setIdString(textLine.getId());
                if (!Strings.isNullOrEmpty(textLine.getBASELINE())) {
                    documentTextLine.setBaseLineY(Integer.parseInt(textLine.getBASELINE()));
                }
                documentTextLine.setCS(textLine.getCS());
                ArrayList<DocumentWord> words = new ArrayList<>();

                StringBuilder documentTextLineText = new StringBuilder();
                for (TextLineElement textLineElement : textLine.getTextLineElements()) {
                    DocumentWord documentWord = new DocumentWord();
                    documentWord.setIdString(textLineElement.getId());
                    documentWord.setYStart(textLineElement.getVpos());
                    documentWord.setXStart(textLineElement.getHpos());
                    documentWord.setWidth(textLineElement.getWidth());
                    if (textLineElement.getHeight() != null) {
                        documentWord.setHeight(textLineElement.getHeight());
                    }
                    if (textLineElement instanceof AltoString) {
                        AltoString altoString = (AltoString) textLineElement;
                        documentWord.setContent(altoString.getContent());
                        documentTextLineText.append(altoString.getContent());
                        documentWord.setWordConfidence(altoString.getWordConfidence());
                        documentWord.setCC(altoString.getCharacterConfidence());
                        documentWord.setType(textLineElement.getType());
                        documentWord.setStyle(altoString.getStyle());
                        documentWord.setStyleRefs(altoString.getStyleRefs());
                        documentWord.setSubsType(altoString.getSubsType());
                        documentWord.setSubsContent(altoString.getSubsContent());
                    }
                    if (textLineElement instanceof Hypenation) {
                        Hypenation hypenation = (Hypenation) textLineElement;
                        documentWord.setContent(hypenation.getContent());
                        documentTextLineText.append(hypenation.getContent());
                        documentWord.setType(textLineElement.getType());
                    }
                    if (textLineElement instanceof SP) {
                        documentTextLineText.append(" ");
                        documentWord.setContent(" ");
                        documentWord.setType(textLineElement.getType());
                    }
                    documentWord.setType(textLineElement.getType());
                    words.add(documentWord);
                }
                if (words.size() > 0) {
                    documentTextLine.setWords(words);
                }
                documentTextLine.setText(documentTextLineText.toString().trim());
                documentTextBlock.getDocumentParagraphs().get(0).getDocumentTextLines().add(documentTextLine);
            }
        }
        return documentTextBlock;
    }

    public static HocrDocument documentPageToHocr(DocumentPage documentPage) {
        HocrDocument hocrDocument = new HocrDocument();
//        hocrDocument.getHocrHtml().getHocrHead().setTitle(documentPage.getTitle());
        for (DocumentMeta documentMeta : documentPage.getDocumentMetas()) {
            hocrDocument
                    .getHocrHtml()
                    .getHocrHead()
                    .addMeta(new HocrMeta(documentMeta.getName(), documentMeta.getContent(), documentMeta.getEquiv()));
        }

        hocrDocument.getHocrHtml().getHocrBody().getHocrPage().setIdString(documentPage.getIdString());
        hocrDocument.getHocrHtml().getHocrBody().getHocrPage().setTitle(documentPage.getTitle());
        for (DocumentTextBlock documentTextBlock : documentPage.getDocumentTextBlocks()) {
            HocrCArea hocrCarea = new HocrCArea();
            hocrCarea.setIdString(documentTextBlock.getIdString());
            hocrCarea.setTitle(documentTextBlock.getTitle());

            hocrDocument.getHocrHtml().getHocrBody().getHocrPage().getHocrCAreaList().add(hocrCarea);
            for (DocumentParagraph documentParagraph : documentTextBlock.getDocumentParagraphs()) {
                HocrParagraph hocrParagraph = new HocrParagraph();
                hocrParagraph.setId(documentParagraph.getId());
                hocrParagraph.setTitle(documentParagraph.getTitle());
                hocrParagraph.setLang(documentParagraph.getLang());

                hocrCarea.getParagraphList().add(hocrParagraph);
                for (DocumentTextLine documentTextLine : documentParagraph.getDocumentTextLines()) {
                    HocrLine hocrLine = new HocrLine();
                    hocrParagraph.getTextLines().add(hocrLine);
                    hocrLine.setXsize(documentTextLine.getXsize());
                    hocrLine.setIdString(documentTextLine.getIdString());
                    hocrLine.setTitle(documentTextLine.getTitle());
                    if (documentTextLine.getXStart() != null) {
                        hocrLine.setBoundingBox(StringConverter.pointToString(documentTextLine.getBoundingBox()));
                    }
                    hocrLine.setDescenders(documentTextLine.getDescenders());
                    hocrLine.setAscenders(documentTextLine.getAscenders());
                    for (DocumentWord documentWord : documentTextLine.getWords()) {
                        HocrWord hocrWord = new HocrWord();
                        hocrLine.getWords().add(hocrWord);
                        hocrWord.setBoundingBox(documentWord.getBoundingBox());
                        hocrWord.setId(documentWord.getIdString());
                        hocrWord.setTitle(documentWord.getTitle());
                        hocrWord.setContent(documentWord.getContent());
                    }
                }
            }
        }
        return hocrDocument;
    }

    public static DocumentPage hocrToDocumentPage(HocrDocument hocrDocument) {
        String title = hocrDocument.getHocrHtml().getHocrBody().getHocrPage().getTitle();
        Integer height = null;
        Integer width = null;
        Rect fullimageBoundingBox = getBoundingBoxFromHocrString(title);
        if (fullimageBoundingBox != null) {
            height = fullimageBoundingBox.height;
            width = fullimageBoundingBox.width;
        }
        DocumentPage documentPage = new DocumentPage(UUID.randomUUID().toString(),null, height, width);
//        documentPage.setTitle(hocrDocument.getHocrHtml().getHocrHead().getTitle());
        for (HocrMeta hocrMeta : hocrDocument.getHocrHtml().getHocrHead().getHocrMetaList()) {
            documentPage.addMeta(new DocumentMeta(hocrMeta.getName(), hocrMeta.getContent(), hocrMeta.getEquiv()));
        }
        documentPage.setIdString(hocrDocument.getHocrHtml().getHocrBody().getHocrPage().getIdString());
        documentPage.setTitle(hocrDocument.getHocrHtml().getHocrBody().getHocrPage().getTitle());
        for (HocrCArea hocrCArea : hocrDocument.getHocrHtml().getHocrBody().getHocrPage().getHocrCAreaList()) {
            DocumentTextBlock documentTextBlock = new DocumentTextBlock();
            documentTextBlock.setIdString(hocrCArea.getIdString());
            documentTextBlock.setTitle(hocrCArea.getTitle());
            documentPage.getDocumentTextBlocks().add(documentTextBlock);
            for (HocrParagraph hocrParagraph : hocrCArea.getParagraphList()) {
                DocumentParagraph documentParagraph = new DocumentParagraph();
                documentParagraph.setId(hocrParagraph.getId());
                documentParagraph.setTitle(hocrParagraph.getTitle());
                documentParagraph.setLang(hocrParagraph.getLang());
                documentTextBlock.getDocumentParagraphs().add(documentParagraph);
                for (HocrLine hocrLine : hocrParagraph.getTextLines()) {
                    Rect boundingBox = getBoundingBoxFromHocrString(hocrLine.getTitle());
                    DocumentTextLine documentTextLine;
                    if (boundingBox != null) {
                        documentTextLine = new DocumentTextLine(boundingBox.y, boundingBox.x, boundingBox.height, boundingBox.width);
                    } else {
                        documentTextLine = new DocumentTextLine();
                    }
//                    documentTextLine.setBoundingBox();
                    documentTextLine.setTitle(hocrLine.getTitle());
                    for (HocrWord hocrWord : hocrLine.getWords()) {
                        DocumentWord documentWord = new DocumentWord();
                        documentWord.setBoundingBox(hocrWord.getBoundingBox(0, 0));
                        documentWord.setIdString(hocrWord.getId());
                        documentWord.setTitle(hocrWord.getTitle());
                        documentWord.setContent(hocrWord.getContent());
                        if (documentTextLine.getWords() == null) {
                            documentTextLine.setWords(new ArrayList<>());
                        }
                        documentTextLine.getWords().add(documentWord);
                    }
                    documentTextLine.setAscenders(hocrLine.getAscenders());
                    documentTextLine.setDescenders(hocrLine.getDescenders());
                    documentTextLine.setXsize(hocrLine.getXsize());
                    documentTextLine.setIdString(hocrLine.getIdString());
                    documentParagraph.getDocumentTextLines().add(documentTextLine);

                }
            }
        }
        return documentPage;
    }

    private static Rect getBoundingBoxFromHocrString(String boundingBox) {
        if (boundingBox != null) {
            String[] splitted = boundingBox.split(";");
            if (splitted[0].startsWith("bbox")) {
                String bbox = splitted[0];
                splitted = bbox.split(" ");
                int xStart = Integer.parseInt(splitted[1]);//x-start
                int yStart = Integer.parseInt(splitted[2]);//y-start
                int xStop = Integer.parseInt(splitted[3]);//x-stop
                int yStop = Integer.parseInt(splitted[4]);//y-stop
                Rect boundingRect = new Rect(new Point(xStart, yStart), new Point(xStop, yStop));
                return boundingRect;
            }
        }
        return null;
    }

}
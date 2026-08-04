package nl.knaw.huc.di.images.minions;


import nl.knaw.huc.di.images.layoutds.models.Page.Baseline;
import nl.knaw.huc.di.images.layoutds.models.Page.TextLine;
import org.junit.jupiter.api.Test;
import org.opencv.core.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class BaselinesMapperTest {

    @Test
    public void returnsMappingWithOverlappingLinesWhenLinesAreOverlapping100Percent() {
        final List<TextLine> newLines = new ArrayList<>();
        newLines.add(createTextLineWithIdAndBaselineCoords("newId", "3244,342 5224,372"));
        final List<TextLine> oldLines = new ArrayList<>();
        oldLines.add(createTextLineWithIdAndBaselineCoords("oldId", "3244,342 5224,372"));

        final Map<String, String> newLinesToOldLinesMap = BaselinesMapper.mapNewLinesToOldLines(newLines, oldLines, new Size(5418, 3808));

        assertThat(newLinesToOldLinesMap, hasEntry(equalTo("newId"), equalTo("oldId")));
    }

    @Test
    public void returnsMappingWithOverlappingLinesWhenLinesAreOverlappingMoreThan50Percent() {
        final List<TextLine> newLines = new ArrayList<>();
        newLines.add(createTextLineWithIdAndBaselineCoords("newId", "3244,342 5224,342"));
        final List<TextLine> oldLines = new ArrayList<>();
        oldLines.add(createTextLineWithIdAndBaselineCoords("oldId", "4000,342 5224,342"));

        final Map<String, String> newLinesToOldLinesMap = BaselinesMapper.mapNewLinesToOldLines(newLines, oldLines, new Size(5418, 3808));

        assertThat(newLinesToOldLinesMap, hasEntry(equalTo("newId"), equalTo("oldId")));
    }

    @Test
    public void returnsEmptyMappingWhenNoLinesAreOverlapping() {
        final List<TextLine> newLines = new ArrayList<>();
        newLines.add(createTextLineWithIdAndBaselineCoords("newId", "3244,342 5224,372"));
        final List<TextLine> oldLines = new ArrayList<>();
        oldLines.add(createTextLineWithIdAndBaselineCoords("oldId", "3244,500 5224,550"));

        final Map<String, String> newLinesToOldLinesMap = BaselinesMapper.mapNewLinesToOldLines(newLines, oldLines, new Size(5418, 3808));

        assertThat(newLinesToOldLinesMap.entrySet(), is(empty()));
    }

    @Test
    public void returnsMappingWithOverlappingLinesWhenLinesAreOverlappingLessThan50Percent() {
        final List<TextLine> newLines = new ArrayList<>();
        newLines.add(createTextLineWithIdAndBaselineCoords("newId", "3244,342 5224,342"));
        final List<TextLine> oldLines = new ArrayList<>();
        oldLines.add(createTextLineWithIdAndBaselineCoords("oldId", "5000,342 5224,342"));

        final Map<String, String> newLinesToOldLinesMap = BaselinesMapper.mapNewLinesToOldLines(newLines, oldLines, new Size(5418, 3808));

        assertThat(newLinesToOldLinesMap.entrySet(), is(empty()));
    }

    @Test
    public void returnsMappingWithoutNewLinesThatMatchMultipleOldLines() {
        final List<TextLine> newLines = new ArrayList<>();
        newLines.add(createTextLineWithIdAndBaselineCoords("newId", "0,20 400,20"));
        final List<TextLine> oldLines = List.of(createTextLineWithIdAndBaselineCoords("oldId1", "0,20 200,20"), createTextLineWithIdAndBaselineCoords("oldId2", "200,20 400,20"));

        final Map<String, String> newLinesToOldLinesMap = BaselinesMapper.mapNewLinesToOldLines(newLines, oldLines, new Size(500, 500));

        assertThat(newLinesToOldLinesMap.entrySet(), is(empty()));
    }

    @Test
    public void returnsMappingWithoutNewLinesThatMatchTheSameOldLine() {
        final List<TextLine> newLines = List.of(createTextLineWithIdAndBaselineCoords("newId1", "0,20 200,20"), createTextLineWithIdAndBaselineCoords("newId2", "200,20 400,20"));
        final List<TextLine> oldLines = List.of(createTextLineWithIdAndBaselineCoords("oldId", "0,20 400,20"));

        final Map<String, String> newLinesToOldLinesMap = BaselinesMapper.mapNewLinesToOldLines(newLines, oldLines, new Size(500, 500));

        assertThat(newLinesToOldLinesMap.entrySet(), is(empty()));
    }



    private TextLine createTextLineWithIdAndBaselineCoords(String id, String points) {
        final TextLine textLine = new TextLine();
        textLine.setId(id);
        final Baseline baseline = new Baseline();
        baseline.setPoints(points);
        textLine.setBaseline(baseline);

        return textLine;
    }


}

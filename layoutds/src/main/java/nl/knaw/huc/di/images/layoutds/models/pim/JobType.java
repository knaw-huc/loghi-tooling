package nl.knaw.huc.di.images.layoutds.models.pim;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/*
 * This enum is append only, because the ordinal value is used in the database.
 * When a JobType is no longer supported add it to the UNUSABLE_JOB_TYPES Set.
 */
public enum JobType {
    None,
    SendToElasticSearch,
    HarvestIIIF,
    OldGenerateManifests,
    SetSentToElasticSearchNullForDataSet,
    BuildEnvironments,
    CalculateConfidence,
    CalculateSkew,
    ConvertToTiledTiff,
    DetectLanguage,
    SetBrokenNull,
    FrogNer,
    HarvestFromLocalDisk,
    SplitPDF,
    ElasticSearchRemoveIndex,
    ElasticSearchCleanIndex,
    CalculateHash,
    AttachDocumentImageSet,
    Tesseract4Hocr,
    DeleteJobsThatAreDone,
    RestartUnfinishedJobs,
    AttachXmlDocumentToImageSet,
    AttachXMLDocument2DocumentImage,
    SiameseConfusionMatrix,
    SiameseNetwork,
    PipelineKB,
    UploadToTranskribus,
    KrakenHocr,
    TestEmailNotification,
    P2PaLA,
    ScriptCompare,
    HarvestTrpCollection,
    HarvestTrpDocument,
    HarvestTrpPage,
    RedoTesseract4ExistingPageXML,
    PipelineKB2,
    RecalculateReadingOrderNew,
    Epubify,
    CreateSetForSiameseNetwork;

    //    LayoutAnalysis
    private static final Set<JobType> UNUSABLE_JOB_TYPES = Set.of(None, SetSentToElasticSearchNullForDataSet);
    private static final Logger LOG = LoggerFactory.getLogger(JobType.class);
    public static List<JobType> JOB_TYPES = List.of(JobType.values()).stream()
            .filter(type -> !UNUSABLE_JOB_TYPES.contains(type))
            .collect(Collectors.toList());


    private static class Test {
        public static void main(String[] args) {
            LOG.info("{}", JOB_TYPES.stream().filter(type -> type == HarvestTrpCollection || type == HarvestTrpDocument || type == HarvestTrpPage).map(type -> type + ": " + type.ordinal()).collect(Collectors.toSet()));
        }
    }
}

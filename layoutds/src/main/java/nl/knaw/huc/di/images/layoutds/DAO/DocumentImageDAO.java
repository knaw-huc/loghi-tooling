package nl.knaw.huc.di.images.layoutds.DAO;


import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import nl.knaw.huc.di.images.layoutds.SessionFactorySingleton;
import nl.knaw.huc.di.images.layoutds.models.*;
import nl.knaw.huc.di.images.layoutds.models.Vector;
import nl.knaw.huc.di.images.layoutds.models.pim.PimFieldSet;
import nl.knaw.huc.di.images.layoutds.models.pim.PimUser;
import nl.knaw.huc.di.images.layoutds.models.pim.Swipe;
import org.apache.commons.lang3.tuple.Pair;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DocumentImageDAO extends GenericDAO<DocumentImage> {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentImageDAO.class);
    public DocumentImageDAO() {
        super(DocumentImage.class);
    }

    private Long getLayoutAnalyzedCount() {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {

            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.MONTH, -1);
            Date date = cal.getTime();

            CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

            CriteriaQuery<Long> cq = criteriaBuilder.createQuery(Long.class);
            Root<DocumentImage> documentImageRoot = cq.from(DocumentImage.class);
            cq.select(criteriaBuilder.count(documentImageRoot));
            cq.where(
                    criteriaBuilder.and(
                            criteriaBuilder.isNotNull(documentImageRoot.get("layoutXMLAnalyzed")),
                            criteriaBuilder.greaterThan(documentImageRoot.get("layoutXMLAnalyzed"), date)
                    )
            );

            TypedQuery<Long> query = session.createQuery(cq);
            Long result = query.getSingleResult();
            session.close();
            return result;
        }
    }

    private Long getNullCountForColumn(String column) {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {

            CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

            CriteriaQuery<Long> cq = criteriaBuilder.createQuery(Long.class);
            Root<DocumentImage> documentImageRoot = cq.from(DocumentImage.class);
            Path path = documentImageRoot.get(column);
            cq.select(criteriaBuilder.count(documentImageRoot));
            cq.where(
                    criteriaBuilder.and(
                            criteriaBuilder.isNull(path),
                            criteriaBuilder.isNotNull(documentImageRoot.get("tesseract4BestHOCRAnalyzed")),
                            criteriaBuilder.or(
                                    criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                    criteriaBuilder.isFalse(documentImageRoot.get("broken"))
                            ),

                            criteriaBuilder.like(documentImageRoot.get("remoteuri"), "https://images.huygens.knaw.nl/%")
                    )
            );

            TypedQuery<Long> query = session.createQuery(cq);
            Long result = query.getSingleResult();
            session.close();
            return result;
        }
    }

    private Long getAnalysisCountForColumn(String column) {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {

            CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

            CriteriaQuery<Long> cq = criteriaBuilder.createQuery(Long.class);
            Root<DocumentImage> documentImageRoot = cq.from(DocumentImage.class);
            Path path = documentImageRoot.get(column);
            cq.select(criteriaBuilder.count(documentImageRoot));
            cq.where(
                    criteriaBuilder.isNotNull(path)
            );

            TypedQuery<Long> query = session.createQuery(cq);
            Long result = query.getSingleResult();
            session.close();
            return result;
        }
    }

    private ArrayList<DataItem> getStatsPerDay(String column) {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.MONTH, -1);
            Date date = cal.getTime();

            CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

            CriteriaQuery<Tuple> criteriaQuery = criteriaBuilder.createQuery(Tuple.class);
            Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
            Path path = documentImageRoot.get(column);
            criteriaQuery.multiselect(criteriaBuilder.function("day", Integer.class, path), criteriaBuilder.count(documentImageRoot));
            criteriaQuery.groupBy(criteriaBuilder.function("day", Integer.class, path));
            criteriaQuery.where(
                    criteriaBuilder.and(
                            criteriaBuilder.isNotNull(path),
                            criteriaBuilder.greaterThan(path, date)
                    )
            );
            criteriaQuery.orderBy(criteriaBuilder.asc(criteriaBuilder.function("day", Integer.class, path)));
            List<Tuple> tupleResult = session.createQuery(criteriaQuery).getResultList();

            ArrayList<DataItem> dataItems = new ArrayList<>();
            for (Tuple t : tupleResult) {
                dataItems.add(new DataItem(t.get(0).toString(), (double) ((Long) t.get(1)).intValue()));
            }

            session.close();
            return dataItems;
        }
    }

    private ArrayList<DataItem> getLayoutStatsPerDay() {
        return getStatsPerDay("layoutXMLAnalyzed");
    }

    private ArrayList<DataItem> getTesseract4BestHocrStatsPerDay() {
        return getStatsPerDay("tesseract4BestHOCRAnalyzed");
    }

    private ArrayList<DataItem> getFrogNerBestAnalyzedStatsPerDay() {
        return getStatsPerDay("frogNerBestAnalyzed");
    }

    public DocumentStats getStats() {

        DocumentStats stats = new DocumentStats();
        stats.setTotal(super.getCount()); // fast, but not completely accurate
        stats.setLayoutAnalyzed(getLayoutAnalyzedCount()); // fast
        stats.setTesseract4BestHocrAnalyzed(getAnalysisCountForColumn("tesseract4BestHOCRAnalyzed")); //slow
        stats.setToBeSentToElasticSearch(getNullCountForColumn("sentToElasticSearch"));//slow
        stats.setFrogNerBestAnalyzed(getNullCountForColumn("frogNerBestAnalyzed"));//slow


        ArrayList<DataItem> data = new ArrayList<>();

        data.add(new DataItem("LayoutAnalyzed", (double) stats.getLayoutAnalyzed() / stats.getTotal().longValue()));
        data.add(new DataItem("Tesseract4BestHocrAnalyzed", (double) stats.getTesseract4BestHocrAnalyzed() / stats.getTotal().longValue()));
        data.add(new DataItem("frogNerBestAnalyzed", (double) stats.getFrogNerBestAnalyzed() / stats.getTotal().longValue()));
        data.add(new DataItem("ToBeSentToElasticSearch", (double) stats.getToBeSentToElasticSearch() / stats.getTotal().longValue()));
        stats.setData(data);
        stats.setLayoutDayData(getLayoutStatsPerDay());
        stats.setTesseract4BestHocrDayData(getTesseract4BestHocrStatsPerDay());//fast
        stats.setfrogNerBestAnalyzedDayData(getFrogNerBestAnalyzedStatsPerDay());//slow

        return stats;
    }

    public DocumentImage getByUri(Session session, String uri, boolean endsWith) throws DuplicateDataException {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);

        if (endsWith) {
            criteriaQuery.where(criteriaBuilder.like(documentImageRoot.get("uri"), "%" + uri));
        } else {
            criteriaQuery.where(criteriaBuilder.equal(documentImageRoot.get("uri"), uri));
        }
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> documentImages = query.getResultList();

        if (documentImages.size() == 1) {
            return documentImages.get(0);
        } else if (documentImages.size() > 1) {
            throw new DuplicateDataException("duplicate data");
        } else {
            return null;
        }
    }

    public DocumentImage getByUri(Session session, String uri) throws DuplicateDataException {
        return getByUri(session, uri, false);
    }

    public DocumentImage getByUri(String uri, boolean endsWith) throws DuplicateDataException {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            return getByUri(session, uri, endsWith);
        }
    }

     public DocumentImage getByRemoteUri(Session session, String remoteUri, boolean stripThumbUrl) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        if (stripThumbUrl && remoteUri.endsWith("/full/200,/0/native.jpg")) {
            remoteUri = remoteUri.substring(0, remoteUri.length() - 24);
        }

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        ParameterExpression<String> p = criteriaBuilder.parameter(String.class);
        criteriaQuery.where(criteriaBuilder.equal(documentImageRoot.get("remoteuri"), p));
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        query.setParameter(p, remoteUri);
        List<DocumentImage> documentImages = query.getResultList();

        if (documentImages.size() == 1) {
            return documentImages.get(0);
        } else {
            return null;
        }
    }

    public DocumentImage getByRemoteUri(String remoteUri) {
        return getByRemoteUri(remoteUri, false);
    }

    public DocumentImage getByRemoteUri(String remoteUri, boolean stripThumbUrl) {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            return getByRemoteUri(session, remoteUri, stripThumbUrl);
        }
    }

    public DocumentImage getRandomDocumentToAnalyzeLayout(Session session, int maxRandom) {

        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("remoteuri")),
                                criteriaBuilder.equal(documentImageRoot.get("remoteuri"), "")
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.greaterThan(documentImageRoot.get("height"), 100),
                                criteriaBuilder.isNull(documentImageRoot.get("height"))
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.greaterThan(documentImageRoot.get("width"), 100),
                                criteriaBuilder.isNull(documentImageRoot.get("width"))
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("layoutXMLAnalyzed"))
                )
        );
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        int skip = getRandom().nextInt(maxRandom);
        List<DocumentImage> result = query.setFirstResult(skip).setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        } else {
            query = session.createQuery(criteriaQuery);
            result = query.setMaxResults(1).getResultList();
            if (result.size() > 0) {
                return result.get(0);
            }
        }
        return null;
    }

    public DocumentImage getRandomHocrJanusEmpty(Session session) {

        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("sentToJanus")),
                        criteriaBuilder.isNotNull(documentImageRoot.get("tesseract4BestHOCRAnalyzed")),
//                        criteriaBuilder.like(documentImageRoot.get("uri"), "%originelen%"),
                        criteriaBuilder.like(documentImageRoot.get("remoteuri"), "https://images.huygens.knaw.nl/%")


                )
        );
//        criteriaQuery.orderBy(criteriaBuilder.asc(documentImageRoot.get("uri")));
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> results = query.setMaxResults(1).getResultList();

        if (results.size() > 0) {
            return results.get(0);
        } else {
            return null;
        }
    }

    public DocumentImage getRandomHocrNoNerEmpty(Session session) {
        return getRandomWithNullColumn(session, "frogNerBestAnalyzed");
    }

    public DocumentImage getRandomPageNumberNotDetected(Session session) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("pagenumber")),
                                criteriaBuilder.equal(documentImageRoot.get("pagenumber"), 0)
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("pagenumberDetected")),
                        criteriaBuilder.isNotNull(documentImageRoot.get("tesseract4BestHOCRAnalyzed"))

                )
        );
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> results = query.setMaxResults(1).getResultList();

        if (results.size() > 0) {
            return results.get(0);
        } else {
            return null;
        }
    }

    public DocumentImage getRandomHocrNotDateChecked(Session session, int maxRandom) {
        return getRandomWithNullColumn(session, "dateDetectedDate");
    }

    public List<DocumentImage> getRandomHocrNerFromIndex(Session session) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.isNotNull(documentImageRoot.get("frogNerBestAnalyzed")),
                        criteriaBuilder.isNotNull(documentImageRoot.get("tesseract4BestHOCRAnalyzed")),
                        criteriaBuilder.greaterThanOrEqualTo(documentImageRoot.get("pagenumber"), 741),
                        criteriaBuilder.lessThanOrEqualTo(documentImageRoot.get("pagenumber"), 836),
                        criteriaBuilder.like(documentImageRoot.get("uri"), "%staten-generaalnr-7-gs187_%")

                )
        );
        criteriaQuery.orderBy(criteriaBuilder.asc(documentImageRoot.get("pagenumber")));
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        return query.getResultList();
    }

    public Iterator<DocumentImage> getAllDocs(Session session) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalc/nib/%"),
                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalc/europa/%"),
                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalb/98_2_Ontwikkelingssamenwerking/%")
                        ),
                        criteriaBuilder.isNotNull(documentImageRoot.get("tesseract4BestHOCRAnalyzed"))

                )
        );
        criteriaQuery.orderBy(criteriaBuilder.asc(documentImageRoot.get("pagenumber")));

        Query<DocumentImage> query = session.createQuery(criteriaQuery);
        return query.iterate();
    }


//    @Deprecated
//    public List<DocumentImage> getWithWidthHeightRemoteUriByImageSet(Session session, DocumentSeries series, String imageset, boolean getByPageOrder, Boolean publishable) throws Exception {
//
//
//        DocumentImageSet documentImageSet = new DocumentImageSetDAO().getDocumentImageSet(session, series, imageset, publishable);
//        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
//        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
//
//        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
//        criteriaQuery.select(documentImageRoot).where(
//                criteriaBuilder.and(
//                        criteriaBuilder.or(
//                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
//                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
//                        ),
//                        criteriaBuilder.equal(documentImageRoot.get("primaryDocumentImageSet"), documentImageSet),
////                        criteriaBuilder.equal(documentImageRoot.get("documentImageSet.imageset"), imageset),
//                        criteriaBuilder.isNotNull(documentImageRoot.get("remoteuri")),
//                        criteriaBuilder.isNotNull(documentImageRoot.get("height")),
//                        criteriaBuilder.isNotNull(documentImageRoot.get("width"))//,
////                        criteriaBuilder.like(documentImageRoot.get("remoteuri"), "%B00677%")
//                )
//        );
//
//
//        if (getByPageOrder) {
//            criteriaQuery.orderBy(criteriaBuilder.asc(documentImageRoot.get("pageorder")));
//        } else {
//            criteriaQuery.orderBy(criteriaBuilder.asc(documentImageRoot.get("uri")));
//        }
//
//        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
//        List<DocumentImage> results = query.getResultList();
//        if (results.size() > 0) {
//            return results;
//        }
//        return null;
//    }

    public List<DocumentImageSet> getManifests() {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {

            CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

            CriteriaQuery<DocumentImageSet> criteriaQuery = criteriaBuilder.createQuery(DocumentImageSet.class);
            Root<DocumentImageSet> documentImageSetRoot = criteriaQuery.from(DocumentImageSet.class);
            criteriaQuery
                    .where(
                            criteriaBuilder.isTrue(documentImageSetRoot.get("publish"))
                    );

            TypedQuery<DocumentImageSet> query = session.createQuery(criteriaQuery);
            return query.getResultList();
        }
    }

    public Stream<DocumentImage> getByImageSetStreaming(Session session, DocumentImageSet documentImageSet, boolean getByPageOrder) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);

        Root<DocumentImageSet> documentImageSetRoot = criteriaQuery.from(DocumentImageSet.class);
//        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
//        SetJoin<DocumentImageSet, DocumentImage> documentImages = documentImageSetRoot.join(DocumentImageSet_.documentImages);

        criteriaQuery.where(
                criteriaBuilder.and(
                        criteriaBuilder.equal(documentImageSetRoot, documentImageSet) //,
//                        criteriaBuilder.or(
//                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
//                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
//                        )
                )
        );
        criteriaQuery.select(documentImageSetRoot.get("documentImages"));
//        documentImageSetRoot.join("documentImages");
//        if (getByPageOrder) {
//            criteriaQuery.orderBy(criteriaBuilder.asc(documentImageSetRoot.get("documentImages").get("pageorder")));
//        } else {
//            criteriaQuery.orderBy(criteriaBuilder.asc(documentImageSetRoot.get("documentImages").get("uri")));
//        }

        TypedQuery<DocumentImage> typedQuery = session.createQuery(criteriaQuery);
        return typedQuery.getResultStream();
    }

    public Stream<DocumentImage> getWithWidthHeightRemoteUriByImageSet(Session session, DocumentImageSet documentImageSet, boolean getByPageOrder) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);

        Root<DocumentImageSet> documentImageSetRoot = criteriaQuery.from(DocumentImageSet.class);
        SetJoin<DocumentImageSet, DocumentImage> documentImages = documentImageSetRoot.join(DocumentImageSet_.documentImages);

        criteriaQuery.where(
                criteriaBuilder.and(
                        criteriaBuilder.equal(documentImageSetRoot, documentImageSet),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImages.get("broken")),
                                criteriaBuilder.equal(documentImages.get("broken"), false)
                        ),
                        criteriaBuilder.isNotNull(documentImages.get("remoteuri")),
                        criteriaBuilder.isNotNull(documentImages.get("height")),
                        criteriaBuilder.isNotNull(documentImages.get("width"))
                )
        );
        criteriaQuery.select(documentImages);

        if (getByPageOrder) {
            criteriaQuery.orderBy(criteriaBuilder.asc(documentImages.get("pageorder")));
        } else {
            criteriaQuery.orderBy(criteriaBuilder.asc(documentImages.get("uri")));
        }

        TypedQuery<DocumentImage> typedQuery = session.createQuery(criteriaQuery);
        return typedQuery.getResultStream();

    }

    public Stream<DocumentImage> getWithWidthHeightRemoteUriByImageSetPublishable(Session session, DocumentImageSet documentImageSet, boolean getByPageOrder) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);

        Root<DocumentImageSet> documentImageSetRoot = criteriaQuery.from(DocumentImageSet.class);
        SetJoin<DocumentImageSet, DocumentImage> documentImages = documentImageSetRoot.join(DocumentImageSet_.documentImages);

        criteriaQuery.where(
                criteriaBuilder.and(
                        criteriaBuilder.equal(documentImageSetRoot, documentImageSet),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImages.get("broken")),
                                criteriaBuilder.equal(documentImages.get("broken"), false)
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImages.get("publish")),
                                criteriaBuilder.equal(documentImages.get("publish"), true)
                        ),
                        criteriaBuilder.isNotNull(documentImages.get("remoteuri")),
                        criteriaBuilder.isNotNull(documentImages.get("height")),
                        criteriaBuilder.isNotNull(documentImages.get("width"))
                )
        );
        criteriaQuery.select(documentImages);

        if (getByPageOrder) {
            criteriaQuery.orderBy(criteriaBuilder.asc(documentImages.get("pageorder")));
        } else {
            criteriaQuery.orderBy(criteriaBuilder.asc(documentImages.get("uri")));
        }

        TypedQuery<DocumentImage> typedQuery = session.createQuery(criteriaQuery);
        return typedQuery.getResultStream();

    }

    public DocumentImage getRandomDocumentWithoutRemoteUri(Session session) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("publish")),
                                criteriaBuilder.equal(documentImageRoot.get("publish"), true)
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("remoteuri")),
                        criteriaBuilder.isNotNull(documentImageRoot.get("uri"))//,
//                        criteriaBuilder.or(
////                                criteriaBuilder.like(documentImageRoot.get("uri"), "/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/statengeneraal/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/home/rutger/republic/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/resourcesfiles/%/originelen/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/resourcesfiles/%/tif/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/resourcesfiles/%/TIF/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/resourcesfiles/%/png/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/resourcesfiles/%/jpg/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/images/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/98_1_Staten-Generaal_1626-1651/%%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/home/rutger/data/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/test/%")
////                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/images/bosscheprotocollen/2/Bosch' Protocol/JPG/1279 met index/%")
////                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalb/98_2_Ontwikkelingssamenwerking/%"),
////                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalc/%")
////                                ,criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externala/1.04.02%%")
//                        )//,
//                        criteriaBuilder.notLike(documentImageRoot.get("uri"), "/mnt/externalc/bosscheprotocollen/%")
                )
        );
//        criteriaQuery.orderBy(criteriaBuilder.asc(documentImageRoot.get("uri")));
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> result = query.setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        }
        return null;

    }

    public DocumentImage getRandomDocumentWithoutRemoteUriImageSet(Session session, DocumentImageSet documentImageSet) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);

        Root<DocumentImageSet> documentImageSetRoot = criteriaQuery.from(DocumentImageSet.class);
        SetJoin<DocumentImageSet, DocumentImage> documentImages = documentImageSetRoot.join(DocumentImageSet_.documentImages);

        criteriaQuery.where(
                criteriaBuilder.and(
                        criteriaBuilder.equal(documentImageSetRoot, documentImageSet),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImages.get("broken")),
                                criteriaBuilder.equal(documentImages.get("broken"), false)
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImages.get("publish")),
                                criteriaBuilder.equal(documentImages.get("publish"), true)
                        ),
                        criteriaBuilder.isNull(documentImages.get("remoteuri")),
                        criteriaBuilder.isNotNull(documentImages.get("uri"))
                )
        );
        criteriaQuery.select(documentImages);

        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> result = query.setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        }
        return null;
    }

    public DocumentImage getRandomTesseract4BestHOCRConfidenceEmpty(Session session) {
        return getRandomWithNullColumn(session, "tesseract4BestHOCRConfidence");
    }

    public DocumentImage getRandomLanguageEmpty(Session session) {
        return getRandomWithNullColumn(session, "languageBest");
    }

    public DocumentImage getRandomSkewEmpty(Session session, int maxRandom) {

        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.greaterThan(documentImageRoot.get("height"), 200),
                        criteriaBuilder.greaterThan(documentImageRoot.get("width"), 200),
//                        criteriaBuilder.isNotNull(documentImageRoot.get("tesseract4HOCRAnalyzed")),
                        criteriaBuilder.isNull(documentImageRoot.get("deskewAngle")),
                        criteriaBuilder.like(documentImageRoot.get("uri"), "/%")//,
//                        criteriaBuilder.like(documentImageRoot.get("uri"), "%originelen%")//,
//                        criteriaBuilder.greaterThan(documentImageRoot.get("tesseract4HOCRConfidence"), 1)//,
//                        criteriaBuilder.like(documentImageRoot.get("uri"), "%gs187%")

                )
        );
//        criteriaQuery.orderBy(criteriaBuilder.asc(documentImageRoot.get("tesseract4HOCRConfidence")));
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        int skip = getRandom().nextInt(maxRandom);
        List<DocumentImage> result = query.setFirstResult(skip).setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        } else {
            query = session.createQuery(criteriaQuery);
            result = query.setMaxResults(1).getResultList();
            if (result.size() > 0) {
                return result.get(0);
            }
        }
        return null;


    }

    public DocumentImage getRandomHocrNotSectionNumberCheck(Session session) {
        return getRandomWithNullColumn(session, "dateSectionNumberChecked");
    }

    public DocumentImage getRandomDocumentCountColumns(Session session, int maxRandom) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.greaterThan(documentImageRoot.get("height"), 200),
                        criteriaBuilder.greaterThan(documentImageRoot.get("width"), 200),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("columnCount")),
                        criteriaBuilder.like(documentImageRoot.get("uri"), "/%")//,
//                        criteriaBuilder.like(documentImageRoot.get("uri"), "%originelen%")

                )
        );
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        int skip = getRandom().nextInt(maxRandom);
        List<DocumentImage> result = query.setFirstResult(skip).setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        } else {
            query = session.createQuery(criteriaQuery);
            result = query.setMaxResults(1).getResultList();
            if (result.size() > 0) {
                return result.get(0);
            }
        }
        return null;

    }

//    public void setPrettyNameForSeries(String series, String prettyName) {
//        Session session = SessionFactorySingleton.getSessionFactory().openSession();
//
//        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
//
//        CriteriaQuery<DocumentSeries> cq = criteriaBuilder.createQuery(DocumentSeries.class);
//        Root<DocumentSeries> documentSeriesRoot = cq.from(DocumentSeries.class);
//        cq.where(
//                criteriaBuilder.and(
//                        criteriaBuilder.equal(documentSeriesRoot.get("series"), series)
//                )
//        );
//
//        TypedQuery<DocumentSeries> query = session.createQuery(cq);
//        Transaction transaction = session.beginTransaction();
//        DocumentSeries result = query.getSingleResult();
//        result.setPrettyName(prettyName);
//        session.update(result);
//        transaction.commit();
//        session.close();
//    }

    public DocumentImage getRandomSplittableImage(Session session) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("splitDate")),
                        criteriaBuilder.isNotNull(documentImageRoot.get("remoteuri")),
                        criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalb/Afgeleiden HING01/%")

                )
        );
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);

        List<DocumentImage> result = query.setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        }
        return null;
    }

    public DocumentImage getRandomHocrPersonEmpty(Session session) {

        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("personsExtracted")),
                        criteriaBuilder.isNotNull(documentImageRoot.get("frogNerBestAnalyzed"))
                )
        );
//        criteriaQuery.orderBy(criteriaBuilder.asc(documentImageRoot.get("uri")));
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> results = query.setMaxResults(1).getResultList();

        if (results.size() > 0) {
            return results.get(0);
        } else {
            return null;
        }
    }

    public DocumentImage getRandomHocrPlaceEmpty(Session session) {

        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("placesExtracted")),
                        criteriaBuilder.isNotNull(documentImageRoot.get("frogNerBestAnalyzed"))
                )
        );
//        criteriaQuery.orderBy(criteriaBuilder.asc(documentImageRoot.get("uri")));
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> results = query.setMaxResults(1).getResultList();

        if (results.size() > 0) {
            return results.get(0);
        } else {
            return null;
        }
    }

    public List<DocumentImage> getManyRandomTesseractV4HOCRBestEmpty(int number) {
        Session session = SessionFactorySingleton.getSessionFactory().openSession();
        Transaction transaction = session.beginTransaction();
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.isNotNull(documentImageRoot.get("uri")),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.and(
                                        criteriaBuilder.notLike(documentImageRoot.get("uri"), "http://%"),
                                        criteriaBuilder.greaterThan(documentImageRoot.get("height"), 200),
                                        criteriaBuilder.greaterThan(documentImageRoot.get("width"), 200),
                                        criteriaBuilder.lessThan(documentImageRoot.get("height"), 8000),
                                        criteriaBuilder.lessThan(documentImageRoot.get("width"), 8000)
                                ),
                                criteriaBuilder.and(
                                        criteriaBuilder.like(documentImageRoot.get("uri"), "http://%"),
                                        criteriaBuilder.equal(documentImageRoot.get("documentImageType"), "Document"),
                                        criteriaBuilder.lessThan(documentImageRoot.get("height"), 8000),
                                        criteriaBuilder.lessThan(documentImageRoot.get("width"), 8000)
                                ),
                                criteriaBuilder.and(
                                        criteriaBuilder.like(documentImageRoot.get("uri"), "http://%"),
                                        criteriaBuilder.equal(documentImageRoot.get("documentImageType"), "Document"),
                                        criteriaBuilder.isNull(documentImageRoot.get("height")),
                                        criteriaBuilder.isNull(documentImageRoot.get("width"))
                                )

                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("tesseract4BestHOCRAnalyzed")),
                        criteriaBuilder.or(
                                criteriaBuilder.like(documentImageRoot.get("uri"), "/%"),
                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/%"),
                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/statengeneraal/%")
                        ),
                        criteriaBuilder.notLike(documentImageRoot.get("uri"), "https://stacks.stanford.edu/%")

//                        criteriaBuilder.notLike(documentImageRoot.get("uri"), "/mnt/externalc/bosscheprotocollen%")

                )
        );

        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> result = query.setMaxResults(number).getResultList();
        transaction.commit();
        session.close();
        return result;

    }

    public DocumentImage getRandomTesseractV4HOCRBestEmpty(Session session, int maxRandom) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
//                        criteriaBuilder.or(
                        //criteriaBuilder.isNull(documentImageRoot.get("remoteuri"))
//                        ),
                        criteriaBuilder.isNotNull(documentImageRoot.get("uri")),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.and(
                                        criteriaBuilder.notLike(documentImageRoot.get("uri"), "http://%"),
                                        criteriaBuilder.greaterThan(documentImageRoot.get("height"), 200),
                                        criteriaBuilder.greaterThan(documentImageRoot.get("width"), 200),
                                        criteriaBuilder.lessThan(documentImageRoot.get("height"), 8000),
                                        criteriaBuilder.lessThan(documentImageRoot.get("width"), 8000)
                                ),
                                criteriaBuilder.and(
                                        criteriaBuilder.like(documentImageRoot.get("uri"), "http://%"),
                                        criteriaBuilder.equal(documentImageRoot.get("documentImageType"), "Document"),
                                        criteriaBuilder.lessThan(documentImageRoot.get("height"), 8000),
                                        criteriaBuilder.lessThan(documentImageRoot.get("width"), 8000)
                                ),
                                criteriaBuilder.and(
                                        criteriaBuilder.like(documentImageRoot.get("uri"), "http://%"),
                                        criteriaBuilder.equal(documentImageRoot.get("documentImageType"), "Document"),
                                        criteriaBuilder.isNull(documentImageRoot.get("height")),
                                        criteriaBuilder.isNull(documentImageRoot.get("width"))
                                )

                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.like(documentImageRoot.get("uri"), "/home/rutger/republic/%"),
                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/%"),
                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externald/%")
                        ),
//                        criteriaBuilder.like(documentImageRoot.get("uri"), "/home/rutger/republic/%"),
//                        criteriaBuilder.like(documentImageRoot.get("uri"), "/home/rutger/republic/%"),
                        criteriaBuilder.notLike(documentImageRoot.get("uri"), "https://stacks.stanford.edu/%"),
//                        criteriaBuilder.or(
////                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalb/98_2_Ontwikkelingssamenwerking/%"),
////                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalc/cid/%"),
////                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalc/nib/%"),
////                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalc/europa/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/98_1_Staten-Generaal_1626-1651/00%")
//                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("tesseract4BestHOCRAnalyzed"))
//                        ,criteriaBuilder.like(documentImageRoot.get("uri"), "/data/resourcesfiles/ohz/OHZ5/jpg/%")
//                        criteriaBuilder.or(
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "%MMHING01%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/resources%"),
//                        ),
//                        ,criteriaBuilder.notLike(documentImageRoot.get("uri"), "%_bookservice%")
//                        ,criteriaBuilder.or(
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/resourcesfiles/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/%")
//                        )
//                        , criteriaBuilder.notLike(documentImageRoot.get("uri"), "/mnt/externalc/bosscheprotocollen%")
//                        , criteriaBuilder.notLike(documentImageRoot.get("uri"), "http%")

//                        criteriaBuilder.greaterThanOrEqualTo(documentImageRoot.get("pagenumber"), 741),
//                        criteriaBuilder.lessThanOrEqualTo(documentImageRoot.get("pagenumber"), 836),
//                        criteriaBuilder.like(documentImageRoot.get("uri"), "%staten-generaalnr-7-gs187_%")

//,                        criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalb/98_2_Ontwikkelingssamenwerking/%")//,
//                        criteriaBuilder.or(
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "%MMHING01%"),
//                        )
//                        criteriaBuilder.like(documentImageRoot.get("uri"), "%gs%")

//                        criteriaBuilder.greaterThan(documentImageRoot.get("height"), 3500),
//                        criteriaBuilder.lessThan(documentImageRoot.get("height"), 3700),
//                        criteriaBuilder.greaterThan(documentImageRoot.get("width"), 2500),
//                        criteriaBuilder.lessThan(documentImageRoot.get("width"), 2700)

                )
        );
//        criteriaQuery.orderBy(criteriaBuilder.asc(documentImageRoot.get("imageSet")));

        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        int skip = getRandom().nextInt(maxRandom);
        List<DocumentImage> result = query.setFirstResult(skip).setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        } else {
            query = session.createQuery(criteriaQuery);
            result = query.setMaxResults(1).getResultList();
            if (result.size() > 0) {
                return result.get(0);
            }
        }
        return null;

    }

    public DocumentImage getRandomOcrEvaluation(Session session, int maxRandom) {

        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("wer")),
                        criteriaBuilder.isNull(documentImageRoot.get("cer")),
                        criteriaBuilder.isNull(documentImageRoot.get("werIndependant")),
                        criteriaBuilder.isNotNull(documentImageRoot.get("groundtruth"))//,
                        //criteriaBuilder.like(documentImageRoot.get("uri"),"/data/resourcesfiles/%")//,
                        //criteriaBuilder.like(documentImageRoot.get("uri"), "%_gs%")

                )
        );
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);

        int skip = getRandom().nextInt(maxRandom);
        List<DocumentImage> result = query.setFirstResult(skip).setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        } else {
            query = session.createQuery(criteriaQuery);
            result = query.setMaxResults(1).getResultList();
            if (result.size() > 0) {
                return result.get(0);
            }
        }
        return null;

    }

    public DocumentImage getRandomWordsEmpty(Session session) {
        return getRandomWithNullColumn(session, "tesseract4BestWords");
    }

    public List<DocumentImage> getRandomDocumentWithoutImageSet(Session session, int limit, String prefix) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        Predicate andQuery = criteriaBuilder.and(
                criteriaBuilder.or(
                        criteriaBuilder.isNull(documentImageRoot.get("broken")),
                        criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                ),
                criteriaBuilder.isEmpty(documentImageRoot.get("documentImageSets")),
                criteriaBuilder.isNotNull(documentImageRoot.get("uri")),
                criteriaBuilder.notLike(documentImageRoot.get("uri"), "http%"),
                criteriaBuilder.notLike(documentImageRoot.get("uri"), "/home/rutger/data/kb/images/%")
        );
        if (!Strings.isNullOrEmpty(prefix)){
            Predicate newPart = criteriaBuilder.like(documentImageRoot.get("uri"), prefix + "%");
            andQuery = criteriaBuilder.and(
                    andQuery,
                    newPart
            );
        }
        criteriaQuery.select(documentImageRoot).where(
                andQuery
        );
//        criteriaQuery.orderBy(criteriaBuilder.asc(documentImageRoot.get("uri")));
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
//        int skip = (int) (Math.random() * maxRandom);
//        List<DocumentImage> result = query.setFirstResult(skip).setMaxResults(1).getResultList();
        List<DocumentImage> results = query.setMaxResults(limit).getResultList();
        return results;
    }

    public DocumentImage getRandomNoAlto(Session session) {

        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("tesseractAltoText")),
                        criteriaBuilder.isNotNull(documentImageRoot.get("tesseract4BestHOCRAnalyzed"))
                )
        );
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> result = query.setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        } else {
            result = query.setMaxResults(1).getResultList();
            if (result.size() > 0) {
                return result.get(0);
            }
        }
        return null;
    }

    private TypedQuery<DocumentImage> getQuery(Session session, String column) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
//                        criteriaBuilder.or(
//                                criteriaBuilder.like(documentImageRoot.get("remoteuri"), "https://images.huygens.knaw.nl/iiif/%")
//                        ),
                        criteriaBuilder.isNull(documentImageRoot.get(column)),
                        criteriaBuilder.isNotNull(documentImageRoot.get("tesseract4BestHOCRAnalyzed"))
                )
        );
        return session.createQuery(criteriaQuery);
    }

    private DocumentImage getRandomWithNullColumn(Session session, String column) {
        TypedQuery<DocumentImage> query = getQuery(session, column);
        List<DocumentImage> result = query.setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        } else {
            result = getQuery(session, column).setMaxResults(1).getResultList();
            if (result.size() > 0) {
                return result.get(0);
            }
        }
        return null;

    }

    public DocumentImage getRandomDocumentToBeSentToElastic(Session session) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("sentToElasticSearch")),
                        criteriaBuilder.isNotNull(documentImageRoot.get("remoteuri")),
                        criteriaBuilder.isNotNull(documentImageRoot.get("tesseract4BestHOCRAnalyzed"))
                )
        );
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> result = query.setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        } else {
            query = session.createQuery(criteriaQuery);
            result = query.setMaxResults(1).getResultList();
            if (result.size() > 0) {
                return result.get(0);
            }
        }
        return null;
    }


//    public DocumentImage getRandomTinder(Session session) {
//        int maxRandom = 1000;
//        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
//
//        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
//        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
//        criteriaQuery.select(documentImageRoot).where(
//                criteriaBuilder.and(
//                        criteriaBuilder.or(
//                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
//                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
//                        ),
////                        criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externala/openn.library.upenn.edu/Data/%"),
////                        criteriaBuilder.like(documentImageRoot.get("uri"), "%/sap/%"),
//                        criteriaBuilder.like(documentImageRoot.get("uri"), "/%"),
//                        criteriaBuilder.or(
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "%.png"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "%.jpg"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "%.jpeg")
//                        ),
//                        criteriaBuilder.or(
//                                criteriaBuilder.greaterThan(documentImageRoot.get("height"), 400),
//                                criteriaBuilder.isNull(documentImageRoot.get("height"))
//                        ),
//                        criteriaBuilder.or(
//                                criteriaBuilder.greaterThan(documentImageRoot.get("width"), 300),
//                                criteriaBuilder.isNull(documentImageRoot.get("width"))
//                        )
//                )
//        );
//        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
//        int skip = (int) (Math.random() * maxRandom);
//        List<DocumentImage> result = query.setFirstResult(skip).setMaxResults(1).getResultList();
////        List<DocumentImage> result = query.setMaxResults(1).getResultList();
//        if (result.size() > 0) {
//            return result.get(0);
//        } else {
//            result = query.setMaxResults(1).getResultList();
//            if (result.size() > 0) {
//                return result.get(0);
//            }
//        }
//        return null;
//    }


    //    public DocumentOCRResult getRandomNoAlto(Session session, int maxRandom) throws ParseException {
//
//        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
//        SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");
//        String dateInString = "01/06/2018";
//        Date date = formatter.parse(dateInString);
//
//        CriteriaQuery<DocumentOCRResult> criteriaQuery = criteriaBuilder.createQuery(DocumentOCRResult.class);
//        Root<DocumentOCRResult> documentImageRoot = criteriaQuery.from(DocumentOCRResult.class);
//        criteriaQuery.select(documentImageRoot).where(
//                criteriaBuilder.and(
////                        criteriaBuilder.or(
////                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
////                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
////                        ),
//                        criteriaBuilder.equal(documentImageRoot.get("ocrType"), "tesseract"),
//                        criteriaBuilder.greaterThan(documentImageRoot.get("analyzed"), date),
//                        criteriaBuilder.isNotNull(documentImageRoot.get("result"))
//                )
//        );
//        TypedQuery<DocumentOCRResult> query = session.createQuery(criteriaQuery);
//        int skip = (int) (Math.random() * maxRandom);
////        List<DocumentImage> result = query.setFirstResult(skip).setMaxResults(1).getResultList();
//        List<DocumentOCRResult> result = query.setMaxResults(1).getResultList();
//        if (result.size() > 0) {
//            return result.get(0);
//        } else {
//            result = query.setMaxResults(1).getResultList();
//            if (result.size() > 0) {
//                return result.get(0);
//            }
//        }
//        return null;
//    }

    public DocumentImage getRandomTinder(Session session, Long pimUserId) {


        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        CriteriaQuery<DocumentImage> query = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = query.from(DocumentImage.class);
//        query.select(documentImageRoot).where(
//                        criteriaBuilder.or(
//                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
//                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
//                        ),
////                        criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externala/openn.library.upenn.edu/Data/%"),
////                        criteriaBuilder.like(documentImageRoot.get("uri"), "%/sap/%"),
//                        criteriaBuilder.like(documentImageRoot.get("uri"), "/%"),
//                        criteriaBuilder.or(
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "%.png"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "%.jpg"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "%.jpeg")
//                        ),
//                        criteriaBuilder.or(
//                                criteriaBuilder.greaterThan(documentImageRoot.get("height"), 400),
//                                criteriaBuilder.isNull(documentImageRoot.get("height"))
//                        ),
//                        criteriaBuilder.or(
//                                criteriaBuilder.greaterThan(documentImageRoot.get("width"), 300),
//                                criteriaBuilder.isNull(documentImageRoot.get("width"))
//                        )
//                )
//        );

        Subquery<Swipe> subquery = query.subquery(Swipe.class);
        Root<Swipe> subRoot = subquery.from(Swipe.class);
        subquery.select(subRoot);

        Predicate p = criteriaBuilder.equal(subRoot.get("documentImage"), documentImageRoot);
        subquery.where(p);
        query.where(
                criteriaBuilder.and(
                        criteriaBuilder.not(criteriaBuilder.exists(subquery)),
                        criteriaBuilder.like(documentImageRoot.get("uri"), "/data/tmp/tinder/%"),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.like(documentImageRoot.get("uri"), "/%"),
                        criteriaBuilder.or(
                                criteriaBuilder.like(documentImageRoot.get("uri"), "%.png"),
                                criteriaBuilder.like(documentImageRoot.get("uri"), "%.jpg"),
                                criteriaBuilder.like(documentImageRoot.get("uri"), "%.jpeg")
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.greaterThan(documentImageRoot.get("height"), 400),
                                criteriaBuilder.isNull(documentImageRoot.get("height"))
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.greaterThan(documentImageRoot.get("width"), 300),
                                criteriaBuilder.isNull(documentImageRoot.get("width"))
                        )));

        TypedQuery<DocumentImage> typedQuery = session.createQuery(query);


        int maxRandom = 1000;
        int skip = getRandom().nextInt(maxRandom);
        List<DocumentImage> result = typedQuery.setFirstResult(skip).setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        } else {
            result = typedQuery.setMaxResults(1).getResultList();
            if (result.size() > 0) {
                return result.get(0);
            }
        }
        return null;
    }

    public DocumentImage getRandomDocumentToExtractTextlines(Session session, int maxRandom) {

        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
//                        criteriaBuilder.isNotNull(documentImageRoot.get("remoteuri")),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("publish")),
                                criteriaBuilder.equal(documentImageRoot.get("publish"), true)
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.greaterThan(documentImageRoot.get("height"), 100),
                                criteriaBuilder.isNull(documentImageRoot.get("height"))
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.greaterThan(documentImageRoot.get("width"), 100),
                                criteriaBuilder.isNull(documentImageRoot.get("width"))
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("textLinesExtracted")),
                        criteriaBuilder.or(
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalc/wvo/%"),
                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/statengeneraal/%")//,
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/mnt/externalb/Afgeleiden%"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/98_1_Staten-Generaal_1626-1651/00 Vervolg RSG/Pilot HRT Transkribus/Afbeeldingen/162%")
                        )
                )
        );
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
//        int skip = (int) (Math.random() * maxRandom);
        List<DocumentImage> result = query.setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        } else {
            query = session.createQuery(criteriaQuery);
            result = query.setMaxResults(1).getResultList();
            if (result.size() > 0) {
                return result.get(0);
            }
        }
        return null;
    }

    public DocumentImage getRandomDocumentToExtractTextlinesDifor(Session session, int maxRandom) {

        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.isNotNull(documentImageRoot.get("remoteuri")),
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.greaterThan(documentImageRoot.get("height"), 100),
                                criteriaBuilder.isNull(documentImageRoot.get("height"))
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.greaterThan(documentImageRoot.get("width"), 100),
                                criteriaBuilder.isNull(documentImageRoot.get("width"))
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("textLinesExtracted")),
                        criteriaBuilder.or(
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/gv751fq0828/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/th953kw1763/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/wr832wy7248/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://www.e-codices.unifr.ch/metadata/iiif/ubb-AN-IV-0018/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://www.e-codices.unifr.ch/metadata/iiif/ubb-B-II-0011/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://www.e-codices.unifr.ch/metadata/iiif/ubb-B-V-0013/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://www.e-codices.unifr.ch/en/list/one/ubb/B-V-0014"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://www.e-codices.unifr.ch/metadata/iiif/ubb-B-V-0016/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://www.e-codices.unifr.ch/metadata/iiif/ubb-F-III-0015/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://www.e-codices.unifr.ch/metadata/iiif/bbb-0004/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/nm455rd6672/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/xb003nj3345/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/rg420yp8320/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/wk321dt4676/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/bh709gt4292/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/kj930dj0645/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/qc358nk7737/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/nt577mx7842/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/jh145wk0599/manifest.json"),
                                criteriaBuilder.equal(documentImageRoot.get("iiifManifestUri"), "https://dms-data.stanford.edu/data/manifests/Parker/rk497rj5204/manifest.json")
                        )

                )
        );
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        int skip = getRandom().nextInt(maxRandom);
        List<DocumentImage> result = query.setFirstResult(skip).setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        } else {
            query = session.createQuery(criteriaQuery);
            result = query.setMaxResults(1).getResultList();
            if (result.size() > 0) {
                return result.get(0);
            }
        }
        return null;
    }


//    // linking is done via many-to-one relation instead of many-to-many
//    @Deprecated
//    public List<Tuple> getRemoteUrisForSeries(Session session, DocumentSeries documentSeries) {
//        //series = > imagesets => documentImages
//
//        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
//
//        CriteriaQuery<DocumentImageSet> criteriaSubQuery = criteriaBuilder.createQuery(DocumentImageSet.class);
//        Root<DocumentImageSet> documentImageSetRoot = criteriaSubQuery.from(DocumentImageSet.class);
//        criteriaSubQuery
//                .where(
//                        criteriaBuilder.equal(documentImageSetRoot.get("documentSeries"), documentSeries)
//                );
//
//        TypedQuery<DocumentImageSet> subQuery = session.createQuery(criteriaSubQuery);
//
//        List<DocumentImageSet> subResult = subQuery.getResultList();
//
//        CriteriaQuery<Tuple> criteriaQuery = criteriaBuilder.createQuery(Tuple.class);
//        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
//        Expression<DocumentImageSet> exp = documentImageRoot.get("documentImageSet");
//
//        criteriaQuery
//                .where(
//                        criteriaBuilder.and(
//                                exp.in(subResult),
//                                criteriaBuilder.isNotNull(documentImageRoot.get("tesseract4BestHOCRAnalyzed"))
//                        )
//
//                )
//                .orderBy(criteriaBuilder.asc(documentImageRoot.get("remoteuri")));
//        criteriaQuery.multiselect(documentImageRoot.get("remoteuri"), documentImageRoot.get("uuid"));
//        TypedQuery<Tuple> query = session.createQuery(criteriaQuery);
//
//        return query.getResultList();
//    }

    public DocumentImage getRandomDocumentToExtractTextlinesRepublic(Session session, String startsWith) {

        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.greaterThan(documentImageRoot.get("height"), 100),
                                criteriaBuilder.isNull(documentImageRoot.get("height"))
                        ),
                        criteriaBuilder.or(
                                criteriaBuilder.greaterThan(documentImageRoot.get("width"), 100),
                                criteriaBuilder.isNull(documentImageRoot.get("width"))
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("textLinesExtracted"))
                        , criteriaBuilder.or(
                                criteriaBuilder.like(documentImageRoot.get("uri"), startsWith + "%") //,
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/statengeneraal/%NL-HaNA_1.01.02/37%/NL-HaNA_1.01.02_%.jpg"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/statengeneraal/%NL-HaNA_1.01.02/38%/NL-HaNA_1.01.02_%.jpg"),
//                                criteriaBuilder.like(documentImageRoot.get("uri"), "/data/statengeneraal/%1.10.94/44%/NL-HaNA_1.10.%.jpg")
                        )
                )
        );
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> result = query.setMaxResults(1).getResultList();
        if (result.size() > 0) {
            return result.get(0);
        } else {
            query = session.createQuery(criteriaQuery);
            result = query.setMaxResults(1).getResultList();
            if (result.size() > 0) {
                return result.get(0);
            }
        }
        return null;
    }

    public long getUploadAmountOfUser(PimUser uploader) {
        Session session = SessionFactorySingleton.getSessionFactory().openSession();
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        CriteriaQuery<Long> criteriaQuery = criteriaBuilder.createQuery(Long.class);
        Root<DocumentImage> root = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(criteriaBuilder.sum(root.get("size")));

        criteriaQuery.where(criteriaBuilder.equal(root.get("uploader"), uploader));
//        criteriaQuery.groupBy(root.get("uploader"));

        Long uploadedAmount = session.createQuery(criteriaQuery).getSingleResult();
        if (uploadedAmount == null) {
            uploadedAmount = 0L;
        }
        session.close();
        return uploadedAmount;
    }

    public void setAllDocumentImagesNotBroken() {
        boolean remainingDocuments = true;
        while (remainingDocuments) {
            Session session = SessionFactorySingleton.getSessionFactory().openSession();
            Transaction transaction = session.beginTransaction();
            List<DocumentImage> documentImages = this.getRandomBrokenDocumentImage(session, 100);
            if (documentImages.size() == 0) {
                remainingDocuments = false;
            }
            for (DocumentImage documentImage : documentImages) {
                documentImage.setBroken(null);
                this.save(session, documentImage);
            }
            transaction.commit();
            session.close();
        }
    }

//    public void setDocumentImagesNotBroken(DocumentSeries series) {
//        DocumentSeriesDAO documentSeriesDAO = new DocumentSeriesDAO();
//        DocumentSeries documentSeries = documentSeriesDAO.get(series.getId());
//        for (DocumentImageSet documentImageSet : documentSeries.getDocumentImageSets()) {
//            setDocumentImagesNotBroken(documentImageSet);
//        }
//    }

    public void setDocumentImagesNotBroken(ElasticSearchIndex elasticSearchIndex) {
        List<DocumentImageSet> documentImageSets = getManifests();

        for (DocumentImageSet documentImageSet : documentImageSets) {
            if (elasticSearchIndex.equals(documentImageSet.getElasticSearchIndex())) {
                setDocumentImagesNotBroken(documentImageSet);
            }
        }
    }

    public void setDocumentImagesNotBroken(DocumentImageSet documentImageSet) {
        DocumentImageSetDAO documentImageSetDAO = new DocumentImageSetDAO();
        Session session = SessionFactorySingleton.getSessionFactory().openSession();
        Transaction transaction = session.beginTransaction();
        DocumentImageSet imageSet = documentImageSetDAO.get(session, documentImageSet.getId());
        for (DocumentImage documentImage : imageSet.getDocumentImages()) {
            if (documentImage.getBroken() != null) {
                documentImage.setBroken(null);
                save(session, documentImage);
            }
        }
        transaction.commit();
        session.close();
    }

    private List<DocumentImage> getRandomBrokenDocumentImage(Session session, int maxResults) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.equal(documentImageRoot.get("broken"), true)
        );
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        return query.setMaxResults(maxResults).getResultList();
    }

    public DocumentImage getRandomNoSha512(Session session) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.and(
                        criteriaBuilder.or(
                                criteriaBuilder.isNull(documentImageRoot.get("broken")),
                                criteriaBuilder.equal(documentImageRoot.get("broken"), false)
                        ),
                        criteriaBuilder.isNull(documentImageRoot.get("sha512")),
                        criteriaBuilder.like(documentImageRoot.get("uri"), "/%")
                )
        );
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> results = query.setMaxResults(1).getResultList();
        if (results.size() > 0) {
            return results.get(0);
        } else {
            return null;
        }
    }

    public Stream<DocumentImage> getDocsWithoutOriginalFileName(Session session) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        criteriaQuery.select(documentImageRoot).where(
                criteriaBuilder.isNull(documentImageRoot.get("originalFileName"))
        );

        TypedQuery<DocumentImage> typedQuery = session.createQuery(criteriaQuery);
        return typedQuery.getResultStream();
    }

    public DocumentImage getByFileName(Session session, String fileName, boolean ignoreCase) throws DuplicateDataException {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);

        if (ignoreCase) {
            criteriaQuery.where(criteriaBuilder.like(criteriaBuilder.upper(documentImageRoot.get("originalFileName")), fileName.toUpperCase()));
        } else {
            criteriaQuery.where(criteriaBuilder.like(documentImageRoot.get("originalFileName"), fileName));
        }
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> documentImages = query.getResultList();

        if (documentImages.size() == 1) {
            return documentImages.get(0);
        } else if (documentImages.size() > 1) {
            throw new DuplicateDataException("duplicate data");
        } else {
            return null;
        }
    }

    public DocumentImage getByTranskribusPageId(Session session, Long transkribusPageId) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);

        criteriaQuery.where(criteriaBuilder.equal(documentImageRoot.get("transkribusPageId"), transkribusPageId));
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> documentImages = query.getResultList();

        if (documentImages.size() == 1) {
            return documentImages.get(0);
        } else {
            return null;
        }
    }

    public Stream<Pair<DocumentImage, String>> getImagesBySetAndMetadataLabel(Session session, DocumentImageSet documentImageSet, String label) {
        final CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        final CriteriaQuery<Tuple> criteriaQuery = criteriaBuilder.createTupleQuery();
        final Root<DocumentImage> root = criteriaQuery.from(DocumentImage.class);

        final Join<DocumentImage, MetaData> metadata = root.join("metaData", JoinType.INNER);
        criteriaQuery.where(criteriaBuilder.and(
                criteriaBuilder.equal(metadata.get("label"), label),
                criteriaBuilder.isMember(documentImageSet, root.get("documentImageSets"))
        ));

        criteriaQuery.select(criteriaBuilder.tuple(root, metadata.get("value")));
        Query<Tuple> query = session.createQuery(criteriaQuery);

        return query.getResultStream().map(tuple -> Pair.of((DocumentImage) tuple.get(0), (String) tuple.get(1)));
    }

    public Stream<DocumentImage> getByPimFieldSet(Session session, PimFieldSet pimFieldSet) {

        final NativeQuery<DocumentImage> nativeQuery = session.createNativeQuery("select documentimage.*\n" +
                "from (\n" +
                "\tselect pimfieldvalue.pimrecordid,\n" +
                "\tstring_agg(case when  pimfield.name = 'include' then pimfieldvalue.value end, ',') as include\n" +
                "\tfrom pimfieldvalue \n" +
                "\tinner join pimfield on pimfieldvalue.pimfieldid=pimfield.id\n" +
                "\twhere pimfield.pimfieldsetid=" + pimFieldSet.getId() + "\n" +
                "\tgroup by pimfieldvalue.pimrecordid) as raw\n" +
                "inner join pimrecord on pimrecordid=pimrecord.id\n" +
                "inner join documentimage on pimrecord.parent=documentimage.remoteuri\n" +
                "where raw.include is null OR include not like '%false%';", DocumentImage.class);

        return nativeQuery.stream();
    }

    public Stream<DocumentImage> getByPimFieldSetAndImageSet(Session session, PimFieldSet pimFieldSet, DocumentImageSet imageSet) {
        final NativeQuery<DocumentImage> nativeQuery = session.createNativeQuery("select documentimage.*\n" +
                "from (\n" +
                "\tselect pimfieldvalue.pimrecordid,\n" +
                "\tstring_agg(case when  pimfield.name = 'include' then pimfieldvalue.value end, ',') as include\n" +
                "\tfrom pimfieldvalue \n" +
                "\tinner join pimfield on pimfieldvalue.pimfieldid=pimfield.id\n" +
                "\twhere pimfield.pimfieldsetid=" + pimFieldSet.getId() + "\n" +
                "\tgroup by pimfieldvalue.pimrecordid) as raw\n" +
                "inner join pimrecord on pimrecordid=pimrecord.id\n" +
                "inner join documentimage on pimrecord.parent=documentimage.remoteuri\n" +
                "inner join documentimagedataset on documentimagedataset.documentimageid=documentimage.id\n" +
                "where (raw.include is null OR include not like '%false%') " +
                "and documentimagedataset.documentimagesetid=" + imageSet.getId() + ";", DocumentImage.class);

        return nativeQuery.stream();
    }

    public Stream<DocumentImage> getAutocomplete(Session session, UUID imageSetUuid, String filter, int limit, int skip) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);

        Root<DocumentImageSet> documentImageSetRoot = criteriaQuery.from(DocumentImageSet.class);
        final Join<DocumentImageSet, DocumentImage> documentImage = documentImageSetRoot.join("documentImages");

        criteriaQuery.where(
                criteriaBuilder.and(
                        criteriaBuilder.equal(documentImageSetRoot.get("uuid"), imageSetUuid),
                        criteriaBuilder.like(
                                criteriaBuilder.lower(documentImage.get("remoteuri")),
                                "%" + filter.toLowerCase() + "%"
                        )
                )
        );
        criteriaQuery.select(documentImageSetRoot.get("documentImages"));

        TypedQuery<DocumentImage> typedQuery = session.createQuery(criteriaQuery);
        typedQuery.setMaxResults(limit);
        typedQuery.setFirstResult(skip);
        return typedQuery.getResultStream();
    }

    public Stream<DocumentImage> getAutocomplete(Session session, String filter, int limit, int skip) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);

        final Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);

        criteriaQuery.where(
                criteriaBuilder.like(
                        criteriaBuilder.lower(documentImageRoot.get("remoteuri")),
                        "%" + filter.toLowerCase() + "%"
                )
        );

        TypedQuery<DocumentImage> typedQuery = session.createQuery(criteriaQuery);
        typedQuery.setMaxResults(limit);
        typedQuery.setFirstResult(skip);
        return typedQuery.getResultStream();
    }

    public Stream<DocumentImage> getAutocompleteForPrimaryGroupOfUser(Session session, String filter, int limit, int skip, List<Long> imageSetIds) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);

        Root<DocumentImageSet> documentImageSetRoot = criteriaQuery.from(DocumentImageSet.class);
        final Join<DocumentImageSet, DocumentImage> documentImage = documentImageSetRoot.join("documentImages");

        criteriaQuery.where(
                criteriaBuilder.and(
                        documentImageSetRoot.get("id").in(imageSetIds),
                        criteriaBuilder.like(documentImage.get("remoteuri"), "%" + filter + "%")
                )
        );
        criteriaQuery.select(documentImageSetRoot.get("documentImages"));

        TypedQuery<DocumentImage> typedQuery = session.createQuery(criteriaQuery);
        typedQuery.setMaxResults(limit);
        typedQuery.setFirstResult(skip);
        return typedQuery.getResultStream();
    }

    public Stream<DocumentImage> getImagesWithoutVectorOfModelWithPageXml(Session session, String model, int maxResult) {
        final CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        final CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        final Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        documentImageRoot.join("documentOCRResults");

        final Subquery<Vector> subquery = criteriaQuery.subquery(Vector.class);
        final Root<Vector> vectorRoot = subquery.from(Vector.class);
        subquery.where(criteriaBuilder.equal(vectorRoot.get("model"), model));
        subquery.select(vectorRoot.get("documentImageId"));

        criteriaQuery.where(criteriaBuilder.not(criteriaBuilder.in(documentImageRoot.get("id")).value(subquery)));
        criteriaQuery.distinct(true);

        final Query<DocumentImage> query = session.createQuery(criteriaQuery);
        query.setMaxResults(maxResult);

        return query.stream();
    }

    public List<UUID> getImageUuidsWithoutVectorOfModelWithPageXml(Session session, String modelName, int maxResult, List<String> allowedSearchIndices) {
        final Stopwatch started = Stopwatch.createStarted();
        final CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        final CriteriaQuery<UUID> criteriaQuery = criteriaBuilder.createQuery(UUID.class);
        final Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);
        final Join<DocumentImage, DocumentOCRResult> documentOCRResult = documentImageRoot.join("documentOCRResults");

        final Join<DocumentImage, DocumentImageSet> documentImageSets = documentImageRoot.join("documentImageSets");
        final Join<DocumentImageSet, ElasticSearchIndex> elasticSearchIndex = documentImageSets.join("elasticSearchIndex");

        criteriaQuery.where(criteriaBuilder.and(
                criteriaBuilder.isFalse(documentImageRoot.get("sentForSiamese")),
//                criteriaBuilder.isNotMember(modelName, documentImageRoot.get("siameseAnalyzedByModels")),
                criteriaBuilder.greaterThan(criteriaBuilder.length(documentOCRResult.get("result")), 1024),
                criteriaBuilder.equal(documentOCRResult.get("format"), TranscriptionFormat.Page),
                elasticSearchIndex.get("name").in(allowedSearchIndices)
        ));
        criteriaQuery.distinct(true);
        criteriaQuery.select(documentImageRoot.get("uuid"));

        final Query<UUID> query = session.createQuery(criteriaQuery);
        query.setMaxResults(maxResult);

        final List<UUID> resultList = query.getResultList();
        LOG.info("getImageUuidsWithoutVectorOfModelWithPageXml took: {}", started.stop());
        return resultList;
    }

    public List<UUID> getImageIdsWithoutOcrResult(int maxResults, List<String> allowedSearchIndices) {
        LOG.info("getImageIdsWithoutOcrResult");
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Query<String> query = session.createNativeQuery("select cast(documentimage.uuid as varchar) " +
                    "from documentimage  " +
                    "inner join documentimagedataset on documentimage.id=documentimagedataset.documentimageId " +
                    "inner join documentimageset on documentimagedataset.documentimagesetid=documentimageset.id " +
                    "inner join elasticsearchindex  on documentimageset.elasticsearchindexid=elasticsearchindex.id " +
                    "WHERE NOT EXISTS(SELECT 1 from DocumentOCRResult documentoc1_ where documentoc1_.documentimageId=documentimage.id and (documentimage.broken is null or documentimage.broken=false)) " +
                    "AND elasticsearchindex.name in ('" + String.join("','", allowedSearchIndices) + "') " +
                    "limit " + maxResults + ";"
            );

            return query.stream().map(val -> UUID.fromString(val)).collect(Collectors.toList());
//            final CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
//            final CriteriaQuery<UUID> criteriaQuery = criteriaBuilder.createQuery(UUID.class);
//            final Root<DocumentImage> image = criteriaQuery.from(DocumentImage.class);
//
//            final Join<DocumentImage, DocumentOCRResult> documentOCRResults = image.join("documentOCRResults", JoinType.LEFT);
//
//            final Join<DocumentImage, DocumentImageSet> documentImageSets = image.join("documentImageSets");
//            final Join<DocumentImageSet, ElasticSearchIndex> elasticSearchIndex = documentImageSets.join("elasticSearchIndex");
//
//            criteriaQuery.where(criteriaBuilder.and(
//                    criteriaBuilder.isNull(documentOCRResults.get("id")),
//                    criteriaBuilder.or(
//                            criteriaBuilder.isNull(image.get("broken")),
//                            criteriaBuilder.isFalse(image.get("broken"))
//                    ),
//                    elasticSearchIndex.get("name").in(allowedSearchIndices)
//            ));
//
//
//
//            criteriaQuery.select(image.get("uuid"));
//
//            final Query<UUID> query = session.createQuery(criteriaQuery);
//            query.setMaxResults(maxResults);
//
//            return query.getResultList();
        }
    }


    public DocumentImage getByOriginalFileName(Session session, String fileName) throws DuplicateDataException {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        Root<DocumentImage> documentImageRoot = criteriaQuery.from(DocumentImage.class);

        criteriaQuery.where(criteriaBuilder.equal(documentImageRoot.get("originalFileName"), fileName));
        TypedQuery<DocumentImage> query = session.createQuery(criteriaQuery);
        List<DocumentImage> documentImages = query.getResultList();

        if (documentImages.size() == 1) {
            return documentImages.get(0);
        } else if (documentImages.size() > 1) {
            throw new DuplicateDataException("duplicate data");
        } else {
            return null;
        }

    }
}


package nl.knaw.huc.di.images.layoutds.DAO;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Stopwatch;
import nl.knaw.huc.di.images.layoutds.SessionFactorySingleton;
import nl.knaw.huc.di.images.layoutds.models.DocumentImage;
import nl.knaw.huc.di.images.layoutds.models.DocumentImageSet;
import nl.knaw.huc.di.images.layoutds.models.Vector;
import nl.knaw.huc.di.images.layoutds.models.VectorModel;
import org.apache.logging.log4j.util.Strings;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.TypedQuery;
import javax.persistence.criteria.*;
import java.util.*;
import java.util.stream.Stream;

public class VectorDAO extends GenericDAO<Vector> {

    public static final Logger LOG = LoggerFactory.getLogger(VectorDAO.class);

    public VectorDAO() {
        super(Vector.class);
    }

    public Vector getByLocation(String location) throws DuplicateDataException {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {

            CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

            CriteriaQuery<Vector> criteriaQuery = criteriaBuilder.createQuery(Vector.class);
            Root<Vector> documentImageRoot = criteriaQuery.from(Vector.class);

            criteriaQuery.where(criteriaBuilder.equal(documentImageRoot.get("imageLocation"), location));
            TypedQuery<Vector> query = session.createQuery(criteriaQuery);
            List<Vector> documentImages = query.getResultList();

            if (documentImages.size() == 1) {
                return documentImages.get(0);
            } else if (documentImages.size() > 1) {
                throw new DuplicateDataException("duplicate data");
            } else {
                return null;
            }
        }
    }

    public List<Vector> getByExample(String location, int limit, int skip) throws DuplicateDataException {
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            List<Vector> vectors = new ArrayList<>();
            Vector targetVector = getByLocation(location);
            if (targetVector == null) {
                return vectors;
            }
            String arrayString = "(select vector from vectors where id = " + targetVector.getId() + ")";
            NativeQuery query = session.createSQLQuery("select id, documentocrresultline_id, cast (uuid as varchar), documentImageId, cube(" + arrayString + ") <#> vector as distance " +
//                    "from vectors where cube(" + arrayString + ") <#> vector < 0.5 " +
//                    "order by cube(" + arrayString + ") <#> vector asc");
                    "from vectors " +
                    "order by cube(" + arrayString + ") <#> vector asc");
            query.setMaxResults(limit);
            List<Object[]> rows = query.list();
            for (Object[] row : rows) {
                Vector vector = new Vector();
                vector.setId(Integer.parseInt(row[0].toString()));
                vector.setDocumentOCRResultLineId(Integer.parseInt(row[1].toString()));
                vector.setUuid(UUID.fromString(row[2].toString()));
                if (row[3] != null) {
                    vector.setDocumentImageId(Integer.parseInt(row[3].toString()));
                }
                vector.setDistance(Float.parseFloat(row[4].toString()));
                vectors.add(vector);
            }

            return vectors;
        }
    }

    public List<Vector> getByExample(UUID uuid, int limit, int skip) throws DuplicateDataException {
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            List<Vector> vectors = new ArrayList<>();
            Vector targetVector = getByUUID(uuid);
            if (targetVector == null) {
                return vectors;
            }
            String arrayString = "(select vector from vectors where id = " + targetVector.getId() + ")";
            NativeQuery query = session.createSQLQuery("select vectors.id, documentocrresultline_id,cast (documentocrresultline.uuid as varchar) as documentocrresultlineuuid, cast (vectors.uuid as varchar), documentImageId, cube(" + arrayString + ") <#> vector as distance " +
                    "from vectors " +
                    "inner join documentocrresultline on vectors.documentocrresultline_id=documentocrresultline.id " +
//                    "where cube(" + arrayString + ") <#> vector < 0.5 " +
                    "order by cube(" + arrayString + ") <#> vector asc");
            query.setMaxResults(limit);
            List<Object[]> rows = query.list();
            for (Object[] row : rows) {
                Vector vector = new Vector();
                vector.setId(Integer.parseInt(row[0].toString()));
                vector.setDocumentOCRResultLineId(Integer.parseInt(row[1].toString()));
                vector.setDocumentOCRResultLineUUID(row[2].toString());
                vector.setUuid(UUID.fromString(row[3].toString()));
                if (row[4] != null) {
                    vector.setDocumentImageId(Integer.parseInt(row[4].toString()));
                }
                vector.setDistance(Float.parseFloat(row[5].toString()));
                vectors.add(vector);
            }

            return vectors;
        }
    }

    public Stream<Map<String, ?>> streamByModel(Session session, FindByModelQuery findByModelQuery, int limit, int skip) {
        final Optional<Integer> modelIdOpt = getModelIdByName(session, findByModelQuery.model);
        if (modelIdOpt.isEmpty()) {
            return Stream.empty();
        }
        final Integer vectorModelId = modelIdOpt.get();
        String imageFilter = null;
        if (findByModelQuery.pimField != null) {
            if (findByModelQuery.imageUuid != null) {
                final Query query = session.createNativeQuery("select documentimage.id from documentimage " +
                        "inner join pimrecord on pimrecord.parent=documentimage.remoteuri " +
                        "inner join pimfieldvalue on pimfieldvalue.pimrecordid=pimrecord.id " +
                        "inner join pimfield on pimfieldvalue.pimfieldid=pimfield.id " +
                        "where documentimage.uuid=:imageUuid " +
                        "and pimfield.name=:pimField " +
                        (!Strings.isBlank(findByModelQuery.pimFieldValue) ? "and pimfieldValue.value=:pimFieldValue " : "")
                );
                query.setParameter("imageUuid", findByModelQuery.imageUuid);
                query.setParameter("pimField", findByModelQuery.pimField);
                if (!Strings.isBlank(findByModelQuery.pimFieldValue)) {
                    query.setParameter("pimFieldValue", findByModelQuery.pimFieldValue);
                }

                final List resultList = query.getResultList();
                if (!resultList.isEmpty()) {
                    imageFilter = "and vectors.documentimageid = " + resultList.get(0);
                }
            } else if (findByModelQuery.imageSetUuid != null) {
                final Query query = session.createNativeQuery("select dids.documentimageid from documentimageset dis " +
                        "inner join documentimagedataset dids on dids.documentimagesetid=dis.id " +
                        "inner join documentimage on documentimage.id = dids.documentimageid " +
                        "inner join pimrecord on pimrecord.parent=documentimage.remoteuri " +
                        "inner join pimfieldvalue on pimfieldvalue.pimrecordid=pimrecord.id " +
                        "inner join pimfield on pimfieldvalue.pimfieldid=pimfield.id " +
                        "where dis.uuid=:imageSetUuid " +
                        "and pimfield.name=:pimField " +
                        (!Strings.isBlank(findByModelQuery.pimFieldValue) ? "and pimfieldValue.value=:pimFieldValue " : "")
                );
                query.setParameter("imageSetUuid", findByModelQuery.imageSetUuid);
                query.setParameter("pimField", findByModelQuery.pimField);
                if (!Strings.isBlank(findByModelQuery.pimFieldValue)) {
                    query.setParameter("pimFieldValue", findByModelQuery.pimFieldValue);
                }
                final List resultList = query.getResultList();
                if (!resultList.isEmpty()) {
                    imageFilter = "and vectors.documentimageid in " + resultList;
                    imageFilter = imageFilter.replace('[', '(').replace(']', ')');
                }
            } else {
                final Query query = session.createNativeQuery("select documentimage.id from documentimage " +
                        "inner join pimrecord on pimrecord.parent=documentimage.remoteuri " +
                        "inner join pimfieldvalue on pimfieldvalue.pimrecordid=pimrecord.id " +
                        "inner join pimfield on pimfieldvalue.pimfieldid=pimfield.id " +
                        "and pimfield.name=:pimField " +
                        (!Strings.isBlank(findByModelQuery.pimFieldValue) ? "and pimfieldValue.value=:pimFieldValue " : "")
                );
                query.setParameter("pimField", findByModelQuery.pimField);
                if (!Strings.isBlank(findByModelQuery.pimFieldValue)) {
                    query.setParameter("pimFieldValue", findByModelQuery.pimFieldValue);
                }

                final List resultList = query.getResultList();
                if (!resultList.isEmpty()) {
                    imageFilter = "and vectors.documentimageid in " + resultList;
                    imageFilter = imageFilter.replace('[', '(').replace(']', ')');
                }
            }
        } else {
            if (findByModelQuery.imageUuid != null) {
                final Query query = session.createNativeQuery("select id from documentimage where uuid=:imageUuid");
                query.setParameter("imageUuid", findByModelQuery.imageUuid);

                final List resultList = query.getResultList();
                if (!resultList.isEmpty()) {
                    imageFilter = "and vectors.documentimageid = " + resultList.get(0);
                }
            } else if (findByModelQuery.imageSetUuid != null) {
                final Query query = session.createNativeQuery("select dids.documentimageid from documentimageset dis inner join documentimagedataset dids on dids.documentimagesetid=dis.id where dis.uuid=:imageSetUuid");
                query.setParameter("imageSetUuid", findByModelQuery.imageSetUuid);
                final List resultList = query.getResultList();
                if (!resultList.isEmpty()) {
                    imageFilter = "and vectors.documentimageid in " + resultList;
                    imageFilter = imageFilter.replace('[', '(').replace(']', ')');
                }
            }
        }

        final String queryString = "select cast(vectors.uuid as varchar), cast(documentocrresultline.uuid as varchar) as documentocrresultlineuuid, cast(documentimage.uuid as varchar) as documentimageid " +
// tablesample seems to slow it down here. Maybe because of the joins? or because of random number assignment
//                (findByModelQuery.imageUuid != null ? "from vectors " : "from vectors tablesample system(1) ") +
                "from vectors " +
                "inner join documentimage on vectors.documentimageid=documentimage.id " +
                "inner join documentocrresultline on vectors.documentocrresultline_id=documentocrresultline.id " +
                "where vectormodel_id= " + vectorModelId + " " +
                (!Strings.isBlank(imageFilter) ? imageFilter + " " : "") +
                "order by vectors.id";

        LOG.info("find by model query: {}", queryString);

        final NativeQuery query = session.createNativeQuery(queryString);
        query.setMaxResults(limit);
        query.setFirstResult(skip);

        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> {
            final HashMap<String, Object> vector = new HashMap<>();
            vector.put("uuid", UUID.fromString(row[0].toString()));
            vector.put("documentOCRResultLineUUID", row[1].toString());
            if (row[2] != null) {
                vector.put("documentImageId", UUID.fromString(row[2].toString()));
            }

            return vector;
        });
    }

    public Stream<Map<String, ?>> streamByQuery(Session session, SiameseQuery siameseQuery, int limit, int skip) {
        Vector targetVector = getByUUID(session, siameseQuery.exampleUuid);
        if (targetVector == null) {
            return Stream.empty();
        }

        Optional<Integer> modelIdOpt = getModelIdByName(session, siameseQuery.model);
        if (modelIdOpt.isEmpty()) {
            return Stream.empty();
        }

        String imageFilter = "";
        if (siameseQuery.pimField != null) {
            if (siameseQuery.imageUuid != null) {
                final Query query = session.createNativeQuery("select documentimage.id from documentimage " +
                        "inner join pimrecord on pimrecord.parent=documentimage.remoteuri " +
                        "inner join pimfieldvalue on pimfieldvalue.pimrecordid=pimrecord.id " +
                        "inner join pimfield on pimfieldvalue.pimfieldid=pimfield.id " +
                        "where documentimage.uuid=:imageUuid " +
                        "and pimfield.name=:pimField " +
                        (!Strings.isBlank(siameseQuery.pimFieldValue) ? "and pimfieldValue.value=:pimFieldValue " : ""));
                query.setParameter("imageUuid", siameseQuery.imageUuid);
                query.setParameter("pimField", siameseQuery.pimField);
                if (!Strings.isBlank(siameseQuery.pimFieldValue)) {
                    query.setParameter("pimFieldValue", siameseQuery.pimFieldValue);
                }

                final List resultList = query.getResultList();
                if (!resultList.isEmpty()) {
                    imageFilter = "and vectors.documentimageid = " + resultList.get(0);
                }
            } else if (siameseQuery.imageSetUuid != null) {
                final Query query = session.createNativeQuery("select dids.documentimageid from documentimageset dis " +
                        "inner join documentimagedataset dids on dids.documentimagesetid=dis.id " +
                        "inner join documentimage on documentimage.id = dids.documentimageid " +
                        "inner join pimrecord on pimrecord.parent=documentimage.remoteuri " +
                        "inner join pimfieldvalue on pimfieldvalue.pimrecordid=pimrecord.id " +
                        "inner join pimfield on pimfieldvalue.pimfieldid=pimfield.id " +
                        "where dis.uuid=:imageSetUuid " +
                        "and pimfield.name=:pimField " +
                        (siameseQuery.onlyDifferentImages ? "and dids.documentimageid <> " + targetVector.getDocumentImageId() + " " : "") +
                        (!Strings.isBlank(siameseQuery.pimFieldValue) ? "and pimfieldValue.value=:pimFieldValue " : ""));
                query.setParameter("imageSetUuid", siameseQuery.imageSetUuid);
                query.setParameter("pimField", siameseQuery.pimField);
                if (!Strings.isBlank(siameseQuery.pimFieldValue)) {
                    query.setParameter("pimFieldValue", siameseQuery.pimFieldValue);
                }
                final List resultList = query.getResultList();
                if (!resultList.isEmpty()) {
                    imageFilter = "and vectors.documentimageid in " + resultList + " ";
                    imageFilter = imageFilter.replace('[', '(').replace(']', ')');
                }
            } else {
                final String queryString = "select documentimage.id from documentimage " +
                        (siameseQuery.onlyDifferentImageSets ? "inner join documentimagedataset on documentimagedataset.documentimageid=documentimage.id " : "") +
                        "inner join pimrecord on pimrecord.parent=documentimage.remoteuri " +
                        "inner join pimfieldvalue on pimfieldvalue.pimrecordid=pimrecord.id " +
                        "inner join pimfield on pimfieldvalue.pimfieldid=pimfield.id " +
                        "and pimfield.name=:pimField " +
                        (siameseQuery.onlyDifferentImages ? "and documentimage.id <> " + targetVector.getDocumentImageId() + " " : "") +
                        (siameseQuery.onlyDifferentImageSets ? "and documentimagedataset.documentimagesetid not in (select documentimagedataset.documentimagesetid from documentimage inner join documentimagedataset on documentimage.id = documentimagedataset.documentimageid  where id=" + targetVector.getDocumentImageId() + ") " : "") +
                        (!Strings.isBlank(siameseQuery.pimFieldValue) ? "and pimfieldValue.value=:pimFieldValue " : "");
                final Query query = session.createNativeQuery(queryString);
                LOG.info("documentimage filter: {}", queryString);
                query.setParameter("pimField", siameseQuery.pimField);
                if (!Strings.isBlank(siameseQuery.pimFieldValue)) {
                    query.setParameter("pimFieldValue", siameseQuery.pimFieldValue);
                }


                final List resultList = query.getResultList();
                if (!resultList.isEmpty()) {
                    imageFilter = "and vectors.documentimageid in " + resultList + " ";
                    imageFilter = imageFilter.replace('[', '(').replace(']', ')');
                } else {
                    imageFilter = "and true = false ";
                }

            }
        } else {
            if (siameseQuery.imageUuid != null) {
                final Query query = session.createNativeQuery("select id from documentimage where uuid=:imageUuid");
                query.setParameter("imageUuid", siameseQuery.imageUuid);

                final List resultList = query.getResultList();
                if (!resultList.isEmpty()) {
                    imageFilter = "and vectors.documentimageid = " + resultList.get(0);
                }
            } else if (siameseQuery.imageSetUuid != null) {
                final Query query = session.createNativeQuery("select dids.documentimageid from documentimageset dis " +
                        "inner join documentimagedataset dids on dids.documentimagesetid=dis.id " +
                        "where dis.uuid=:imageSetUuid " +
                        (siameseQuery.onlyDifferentImages ? "and dids.documentimageid <> " + targetVector.getDocumentImageId() + " " : ""));
                query.setParameter("imageSetUuid", siameseQuery.imageSetUuid);
                final List resultList = query.getResultList();
                if (!resultList.isEmpty()) {
                    imageFilter = "and vectors.documentimageid in " + resultList + " ";
                    imageFilter = imageFilter.replace('[', '(').replace(']', ')');
                } else {
                    imageFilter = "and true = false ";
                }
            } else {
                if (siameseQuery.onlyDifferentImageSets) {
                    final NativeQuery query = session.createNativeQuery("select distinct documentimagedataset.documentimageid from documentimagedataset  " +
                            "where documentimagedataset.documentimagesetid in " +
                            "(select documentimagedataset.documentimagesetid from documentimage inner join documentimagedataset on documentimage.id = documentimagedataset.documentimageid  where id=" + targetVector.getDocumentImageId() + ");");
                    final List resultList = query.getResultList();
                    if (!resultList.isEmpty()) {
                        imageFilter = "and vectors.documentimageid not in" + resultList + " ";
                        imageFilter = imageFilter.replace('[', '(').replace(']', ')');
                    }
                }

                if (siameseQuery.onlyDifferentImages) {
                    imageFilter += "and vectors.documentimageid <> " + targetVector.getDocumentImageId() + " ";
                }
            }
        }

        String arrayString = "(select vector from vectors where id = " + targetVector.getId() + ")";
//        final String vectorQuery = "select vectors.id, vectors.uuid, documentimageid, documentocrresultline_id, cube(" + arrayString + ") <#> vector as distance " +
//                "from vectors " +
//                "where cube(" + arrayString + ") <#> vector < 0.5 " +
//                "and vectors.vectormodel_id = '" + modelIdOpt.get() + "' " +
//                imageFilter +
//                "order by distance asc limit " + limit;
//        String queryString = "select vectemp.id, cast(vectemp.uuid as varchar), cast(documentocrresultline.uuid as varchar) as documentocrresultlineuuid, cast(documentimage.uuid as varchar) as documentimageid, vectemp.distance \n" +
//                "from (" + vectorQuery + ") as vectemp \n" +
//                "inner join documentimage on documentimage.id=vectemp.documentimageid\n" +
//                "inner join documentocrresultline on vectemp.documentocrresultline_id=documentocrresultline.id;";

        String queryString = "select vectors.id,array_to_string(array_agg(distinct cast(documentocrresultline.uuid as varchar)), ',')as documentocrresultlineuuid, cast (vectors.uuid as varchar), array_to_string(array_agg(distinct cast(documentimage.uuid as varchar)), ',') as documentimageid, cube(" + arrayString + ") " + siameseQuery.distanceAlgorithm.getValue() + " vector as distance " +
                "from vectors " +
                "inner join documentocrresultline on vectors.documentocrresultline_id=documentocrresultline.id " +
                "inner join documentimage on vectors.documentimageid = documentimage.id " +
                "where cube(" + arrayString + ") " + siameseQuery.distanceAlgorithm.getValue() + " vector < 0.5 " +
                "and vectors.vectormodel_id = '" + modelIdOpt.get() + "' " +
//                "where vectors.vectormodel_id = '" + modelIdOpt.get() + "' " +
                imageFilter +
                "group by vectors.id, vectors.uuid, distance " +
                "order by distance asc";
        NativeQuery<Object[]> query = session.<Object[]>createNativeQuery(queryString);
        LOG.info("Vector query used: {}", queryString);
        query.setMaxResults(limit);
        query.setFirstResult(skip);
        Stopwatch stopwatch = Stopwatch.createStarted();
        Stream<Object[]> rows = query.stream();
        LOG.debug("duration vectorquery: {}", stopwatch.stop());
        stopwatch = Stopwatch.createStarted();
        Stream<Map<String, ?>> result = rows.map(row -> {
            final HashMap<String, Object> vector = new HashMap<>();
            vector.put("id", Integer.parseInt(row[0].toString()));
            vector.put("documentOCRResultLineUUID", row[1].toString());
            vector.put("uuid", UUID.fromString(row[2].toString()));
            if (row[3] != null) {
                vector.put("documentImageId", UUID.fromString(row[3].toString()));
            }

            vector.put("distance", Float.parseFloat(row[4].toString()));

            return vector;
        });
        LOG.debug("duration map: {}", stopwatch.stop());
        return result;
    }

    public Stream<Map<String, ?>> compareImageSnippets(Session session, SiameseQuery siameseQuery, int limit, int i) {
        Vector targetVector = getByUUID(session, siameseQuery.exampleUuid);
        if (targetVector == null) {
            return Stream.empty();
        }
        final String queryString = "select vectors.id as vectorid, cast(documentocrresultline.uuid as varchar) as documentocrresultlineuuid, "
                + "cast(vectors.uuid as varchar) as vectoruuid, cast(documentimage.uuid as varchar) as documentimageid, "
                + "cube((select vector from vectors where id = " + targetVector.getId() + ")) "
                + siameseQuery.distanceAlgorithm.getValue() + " vector as distance from documentImage \n "
                + "inner join vectors on documentimage.id=vectors.documentimageid \n"
                + "inner join documentocrresultline on vectors.documentocrresultline_id=documentocrresultline.id\n"
                + "where documentimage.uuid=:imageUuid \n"
                + "order by distance";
        final NativeQuery<Object[]> query = session.<Object[]>createNativeQuery(queryString);
        query.setParameter("imageUuid", siameseQuery.imageUuid);
        query.setMaxResults(limit);


        return query.stream().map(row -> {
            final HashMap<String, Object> vector = new HashMap<>();
            vector.put("id", Integer.parseInt(row[0].toString()));
            vector.put("documentOCRResultLineUUID", row[1].toString());
            vector.put("uuid", UUID.fromString(row[2].toString()));
            if (row[3] != null) {
                vector.put("documentImageId", UUID.fromString(row[3].toString()));
            }

            vector.put("distance", Float.parseFloat(row[4].toString()));

            return vector;
        });
    }

    public Stream<Map<String, ?>> compareWithImageset(Session session, SiameseQuery siameseQuery, int limit, int i) {
        Vector targetVector = getByUUID(session, siameseQuery.exampleUuid);
        if (targetVector == null) {
            return Stream.empty();
        }
        final String queryString = "select vectors.id as vectorid, cast(documentocrresultline.uuid as varchar) as documentocrresultlineuuid, "
                + "cast(vectors.uuid as varchar) as vectoruuid, cast(documentimage.uuid as varchar) as documentimageid, "
                + "cube((select vector from vectors where id = " + targetVector.getId() + ")) "
                + siameseQuery.distanceAlgorithm.getValue() + " vector as distance from documentImage \n"
                + "inner join vectors on documentimage.id=vectors.documentimageid \n"
                + "inner join documentimagedataset on documentimage.id = documentimagedataset.documentimageid \n"
                + "inner join documentimageset on documentimageset.id = documentimagedataset.documentimagesetid \n"
                + "inner join documentocrresultline on vectors.documentocrresultline_id=documentocrresultline.id\n"
                + "where documentimageset.uuid=:imageSetUuid \n "
                + (siameseQuery.imageUuid != null ? "and documentimage.uuid=:imageUuid \n" : "")
                + "order by distance";
        final NativeQuery<Object[]> query = session.<Object[]>createNativeQuery(queryString);
        query.setParameter("imageSetUuid", siameseQuery.imageSetUuid);
        if (siameseQuery.imageUuid != null) {
            query.setParameter("imageUuid", siameseQuery.imageUuid);
        }
        query.setMaxResults(limit);


        return query.stream().map(row -> {
            final HashMap<String, Object> vector = new HashMap<>();
            vector.put("id", Integer.parseInt(row[0].toString()));
            vector.put("documentOCRResultLineUUID", row[1].toString());
            vector.put("uuid", UUID.fromString(row[2].toString()));
            if (row[3] != null) {
                vector.put("documentImageId", UUID.fromString(row[3].toString()));
            }

            vector.put("distance", Float.parseFloat(row[4].toString()));

            return vector;
        });
    }

    private Optional<Integer> getModelIdByName(Session session, String model) {
        final CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        final CriteriaQuery<Integer> criteriaQuery = criteriaBuilder.createQuery(Integer.class);
        final Root<VectorModel> from = criteriaQuery.from(VectorModel.class);
        criteriaQuery.where(criteriaBuilder.equal(from.get("model"), model));

        criteriaQuery.select(from.get("id"));
        final Query<Integer> query = session.createQuery(criteriaQuery);

        return query.stream().findAny();
    }

    public List<Vector> getByDocumentImageId(Integer documentImageId) {
        Session session = SessionFactorySingleton.getSessionFactory().openSession();

        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<Vector> criteriaQuery = criteriaBuilder.createQuery(Vector.class);
        Root<Vector> documentImageRoot = criteriaQuery.from(Vector.class);

        criteriaQuery.where(criteriaBuilder.equal(documentImageRoot.get("documentImageId"), documentImageId));
        TypedQuery<Vector> query = session.createQuery(criteriaQuery);
        List<Vector> documentImages = query.getResultList();

        session.close();
        return documentImages;
    }

    public List<String> getDistinctModels() {
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
            final CriteriaQuery<String> criteriaQuery = criteriaBuilder.createQuery(String.class);
            final Root<VectorModel> root = criteriaQuery.from(VectorModel.class);

            criteriaQuery.select(root.get("model")).distinct(true);
            criteriaQuery.orderBy(criteriaBuilder.asc(root.get("model")));
            criteriaQuery.where(criteriaBuilder.isNotNull(root.get("model")));

            final Query<String> query = session.createQuery(criteriaQuery);

            return query.list();
        }
    }

    public Stream<DocumentImageSet> streamDistinctImageSets(Session session, String modelName) {
        final String queryString = "select distinct cast(documentimageset.uuid as varchar), documentimageset.imageset from vectormodel " +
                "inner join documentimagevectormodel on vectormodel.id=documentimagevectormodel.vectormodelid " +
                "inner join documentimagedataset on documentimagevectormodel.documentimageid=documentimagedataset.documentimageid " +
                "inner join documentimageset on documentimageset.id=documentimagedataset.documentimagesetid " +
                "where model= :modelName " +
                "order by documentimageset.imageset;";
        final NativeQuery<Object[]> query = session.createNativeQuery(queryString);
        query.setParameter("modelName", modelName);

        LOG.info("streamDistinctImageSets query: {}", queryString);

        return query.stream().map(data -> {
            final DocumentImageSet documentImageSet = new DocumentImageSet();
            documentImageSet.setUuid(UUID.fromString((String) data[0]));
            documentImageSet.setImageset((String) data[1]);
            return documentImageSet;
        });
    }

    public Stream<DocumentImage> streamDistinctImages(Session session, String modelName, UUID imageSetUuid) {
        LOG.info("distinctinct images model: {} imageset: {}", modelName, imageSetUuid);
        final CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        final CriteriaQuery<DocumentImage> criteriaQuery = criteriaBuilder.createQuery(DocumentImage.class);
        final Root<DocumentImage> from = criteriaQuery.from(DocumentImage.class);
        final Join<DocumentImage, DocumentImageSet> documentImageSets = from.join("documentImageSets", JoinType.INNER);
        final Join<DocumentImage, VectorModel> analyzedByVectorModel = from.join("analyzedByVectorModel", JoinType.INNER);
        criteriaQuery.where(criteriaBuilder.and(
                criteriaBuilder.equal(documentImageSets.get("uuid"), imageSetUuid),
                criteriaBuilder.equal(analyzedByVectorModel.get("model"), modelName)
        ));

        return session.createQuery(criteriaQuery).stream();
    }

    public Stream<String> getMetadataFields(Session session, String model) {
        final NativeQuery<String> sqlQuery = session.<String>createNativeQuery("select pimfield.name " +
                "from documentimage " +
                "inner join documentimage_siameseanalyzedbymodels on documentimage_siameseanalyzedbymodels.documentimage_id = documentimage.id " +
                "inner join pimrecord on pimrecord.parent = documentimage.remoteuri " +
                "inner join pimfieldvalue on pimfieldvalue.pimrecordid = pimrecord.id " +
                "inner join pimfield on pimfieldvalue.pimfieldid = pimfield.id " +
                "where documentimage_siameseanalyzedbymodels.siamesemodel = :model " +
                "group by pimfield.name;"
        );

        sqlQuery.setParameter("model", model);

        return sqlQuery.stream();
    }

    public Stream<String> getFieldValues(Session session, String model, String fieldName) {
        final NativeQuery<String> sqlQuery = session.<String>createNativeQuery("select pimfieldvalue.value " +
                "from documentimage " +
                "inner join documentimage_siameseanalyzedbymodels on documentimage_siameseanalyzedbymodels.documentimage_id = documentimage.id " +
                "inner join pimrecord on pimrecord.parent = documentimage.remoteuri " +
                "inner join pimfieldvalue on pimfieldvalue.pimrecordid = pimrecord.id " +
                "inner join pimfield on pimfieldvalue.pimfieldid = pimfield.id " +
                "where documentimage_siameseanalyzedbymodels.siamesemodel = :model " +
                "and pimfield.name = :fieldName " +
                "and pimfieldvalue.value is not null " +
                "group by pimfieldvalue.value;"
        );
        sqlQuery.setParameter("model", model);
        sqlQuery.setParameter("fieldName", fieldName);

        return sqlQuery.stream();
    }


    public enum DistanceAlgorithm {
        Euclidean("<->"),
        Taxicab("<#>"),
        Chebyshev("<=>");

        private final String value;

        DistanceAlgorithm(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public static class SiameseQuery {
        @JsonProperty
        String model;
        @JsonProperty
        UUID exampleUuid;
        @JsonProperty
        UUID imageSetUuid;
        @JsonProperty
        UUID imageUuid;
        @JsonProperty
        boolean onlyDifferentImageSets;
        @JsonProperty
        boolean onlyDifferentImages;
        @JsonProperty
        String pimField;
        @JsonProperty
        String pimFieldValue;
        @JsonProperty
        DistanceAlgorithm distanceAlgorithm;
    }

    public static class FindByModelQuery {
        @JsonProperty
        String model;
        @JsonProperty
        UUID imageSetUuid;
        @JsonProperty
        UUID imageUuid;
        @JsonProperty
        String pimField;
        @JsonProperty
        String pimFieldValue;
    }
}

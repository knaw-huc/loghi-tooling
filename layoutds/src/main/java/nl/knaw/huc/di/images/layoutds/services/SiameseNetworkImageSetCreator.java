package nl.knaw.huc.di.images.layoutds.services;

import nl.knaw.huc.di.images.layoutds.DAO.DocumentImageDAO;
import nl.knaw.huc.di.images.layoutds.DAO.DocumentImageSetDAO;
import nl.knaw.huc.di.images.layoutds.DAO.PimRecordDAO;
import nl.knaw.huc.di.images.layoutds.SessionFactorySingleton;
import nl.knaw.huc.di.images.layoutds.models.DocumentImage;
import nl.knaw.huc.di.images.layoutds.models.DocumentImageSet;
import nl.knaw.huc.di.images.layoutds.models.pim.PimFieldDefinition;
import nl.knaw.huc.di.images.layoutds.models.pim.PimFieldValue;
import nl.knaw.huc.di.images.layoutds.models.pim.PimUser;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.springframework.data.util.Pair;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;

public class SiameseNetworkImageSetCreator {

    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd");

    public static void create(PimUser pimUser, DocumentImageSet fromSet, PimFieldDefinition pimField, Function<UUID, String> createRemoteUri) {
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Transaction transaction = session.beginTransaction();
            try {
                final PimRecordDAO pimRecordDAO = new PimRecordDAO();
                final DocumentImageDAO documentImageDAO = new DocumentImageDAO();
                Map<String, DocumentImageSet> subSets = new HashMap<>();

                pimRecordDAO.getRecordsByDataset(session, fromSet.getRemoteUri(), false, null)
                        .map(record -> {
                            final Optional<PimFieldValue> any = record.getFieldValues().stream().filter(pimFieldValue -> pimFieldValue.getField().getId().equals(pimField.getId())).findAny();
                            return Pair.of(record.getParent(), any.map(PimFieldValue::getValue).orElse(null));
                        })
                        .filter(imageValue -> imageValue.getSecond() != null)
                        .forEach(imageValue -> {
                            final DocumentImageSet subSet = subSets.computeIfAbsent(imageValue.getSecond(), value -> {
                                final DocumentImageSet documentImageSet = new DocumentImageSet();
                                documentImageSet.setImageset(subset(fromSet, value, documentImageSet.getCreated()));
                                documentImageSet.setRemoteUri(createRemoteUri.apply(documentImageSet.getUuid()));
                                documentImageSet.setUri(documentImageSet.getImageset() + "_" + documentImageSet.getUuid());
                                documentImageSet.setOwner(pimUser);
                                return documentImageSet;
                            });
                            final DocumentImage image = documentImageDAO.getByRemoteUri(imageValue.getFirst());
                            subSet.getDocumentImages().add(image);
                        });

                final DocumentImageSetDAO documentImageSetDAO = new DocumentImageSetDAO();

                for (DocumentImageSet value : subSets.values()) {
                    documentImageSetDAO.save(session, value);
                }

                final DocumentImageSet documentImageSet = new DocumentImageSet();
                documentImageSet.setUri(documentImageSet.getImageset() + "_" + documentImageSet.getUuid());
                documentImageSet.setRemoteUri(createRemoteUri.apply(documentImageSet.getUuid()));
                documentImageSet.setImageset(rootName(fromSet.getImageset(), pimField.getName(), documentImageSet.getCreated()));
                documentImageSet.setOwner(pimUser);
                documentImageSet.setSubSets(new HashSet<>(subSets.values()));

                documentImageSetDAO.save(session, documentImageSet);

                transaction.commit();
            } catch (RuntimeException e) {
                transaction.rollback();
                throw e;
            }
        }
    }

    private static String subset(DocumentImageSet fromSet, String fieldValue, Date created) {
        return String.format("%s_siameseset_%s_%s", fromSet.getImageset(), fieldValue, DATE_FORMAT.format(created));
    }

    private static String rootName(String fromSetImageSet, String fieldName, Date created) {
        return String.format("%s_siameseset_%s_%s", fromSetImageSet, fieldName, DATE_FORMAT.format(created));
    }

    public static String getValueOfSetName(String imageSetName) {
        return imageSetName.substring(imageSetName.lastIndexOf("siameseset_"), imageSetName.length() - 9)
                .replace("siameseset_", "")
                .replace(" ", "_");
    }
}

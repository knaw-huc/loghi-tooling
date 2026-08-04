package nl.knaw.huc.di.images.layoutds.services;

import nl.knaw.huc.di.images.layoutds.DAO.*;
import nl.knaw.huc.di.images.layoutds.SessionFactorySingleton;
import nl.knaw.huc.di.images.layoutds.StudentJpaConfig;
import nl.knaw.huc.di.images.layoutds.models.DocumentImage;
import nl.knaw.huc.di.images.layoutds.models.DocumentImageSet;
import nl.knaw.huc.di.images.layoutds.models.pim.PimFieldDefinition;
import nl.knaw.huc.di.images.layoutds.models.pim.PimFieldValue;
import nl.knaw.huc.di.images.layoutds.models.pim.PimRecord;
import nl.knaw.huc.di.images.layoutds.models.pim.PimUser;
import org.hamcrest.Matcher;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {StudentJpaConfig.class}, loader = AnnotationConfigContextLoader.class)
public class SiameseNetworkImageSetCreatorTest {

    public static final String FROM_SET_REMOTE_URI = "remoteUri";
    public static final String FIELD_NAME = "name";
    public static final String IMAGE_URI_1 = "imageUri1";
    public static final String IMAGE_URI_2 = "imageUri2";
    public static final String IMAGE_URI_3 = "imageUri3";
    private final Function<UUID, String> createRemoteUri = (uuid) -> "uri";
    private PimUser pimUser;
    private DocumentImageSetDAO documentImageSetDAO;
    private DocumentImageSet fromSet;
    private PimFieldDefinition pimField;
    private PimFieldDefinition datasetDefinition;

    @Before
    public void setUp() {
        documentImageSetDAO = new DocumentImageSetDAO();
        createUser();
        createFromSet();
        createPimFields();
        // Ensure pimField and datasetDefinition are persisted by re-fetching them
        final PimFieldDefinitionDAO pimFieldDefinitionDAO = new PimFieldDefinitionDAO();
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            Transaction transaction = session.beginTransaction();
            pimField = pimFieldDefinitionDAO.getByUUID(session, pimField.getUuid());
            datasetDefinition = pimFieldDefinitionDAO.getByUUID(session, datasetDefinition.getUuid());
            // Force initialization of any lazy collections
            if (pimField.getPossibleValues() != null) {
                pimField.getPossibleValues().size();
            }
            transaction.commit();
        }
    }

    @After
    public void tearDown() {
        // Start with a clean database with each test.
        SessionFactorySingleton.closeSessionFactory();
    }

    private void createPimFields() {
        final PimFieldDefinitionDAO pimFieldDefinitionDAO = new PimFieldDefinitionDAO();
        pimField = new PimFieldDefinition();
        pimField.setName(FIELD_NAME);
        pimFieldDefinitionDAO.save(pimField);

        datasetDefinition = new PimFieldDefinition();
        datasetDefinition.setName("datasetUri");
        pimFieldDefinitionDAO.save(datasetDefinition);
    }

    private void createFromSet() {
        fromSet = new DocumentImageSet();
        fromSet.setImageset("fromset");
        fromSet.setRemoteUri(FROM_SET_REMOTE_URI);
        fromSet.setUri("uri");
        documentImageSetDAO.save(fromSet);

        final DocumentImageDAO documentImageDAO = new DocumentImageDAO();
        final DocumentImage documentImage1 = new DocumentImage();
        documentImage1.setRemoteuri(IMAGE_URI_1);
        documentImage1.setUri(IMAGE_URI_1);
        documentImage1.setDocumentImageSets(Set.of(fromSet));
        documentImageDAO.save(documentImage1);

        final DocumentImage documentImage2 = new DocumentImage();
        documentImage2.setRemoteuri(IMAGE_URI_2);
        documentImage2.setUri(IMAGE_URI_2);
        documentImage2.setDocumentImageSets(Set.of(fromSet));
        documentImageDAO.save(documentImage2);

        final DocumentImage documentImage3 = new DocumentImage();
        documentImage3.setRemoteuri(IMAGE_URI_3);
        documentImage3.setUri(IMAGE_URI_3);
        documentImage3.setDocumentImageSets(Set.of(fromSet));
        documentImageDAO.save(documentImage3);
    }

    private void createUser() {
        PimUser pimUser = new PimUser();
        pimUser.setName("user");
        this.pimUser = new PimUserDAO().save(pimUser);
    }

    @Test
    public void createCreatesANewDatasetWithANameAnUri() {
        SiameseNetworkImageSetCreator.create(pimUser, fromSet, pimField, createRemoteUri);

        final List<DocumentImageSet> all = documentImageSetDAO.getAll();
        assertThat(all, is(not(empty())));
        assertThat(all, hasItem(hasProperty("imageset", startsWith("fromset_siameseset_" + FIELD_NAME))));
    }

    @Test
    public void createCreatesAnImageSetWithSubsetsFilledWithDataBaseOnAnAnnotation() {
        createPimRecord(IMAGE_URI_1, FROM_SET_REMOTE_URI, "value1");
        createPimRecord(IMAGE_URI_2, FROM_SET_REMOTE_URI, "value2");
        createPimRecord(IMAGE_URI_3, FROM_SET_REMOTE_URI, "value3");

        SiameseNetworkImageSetCreator.create(pimUser, fromSet, pimField, createRemoteUri);

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final List<DocumentImageSet> all = documentImageSetDAO.getAll(session);
            assertThat(all, hasSize(5)); // fromset, new imageset and 3 subsets
            final Optional<DocumentImageSet> siameseSet = all.stream()
                    .filter(imageSet -> imageSet.getSubSets().size() > 0)
                    .findAny();
            assertThat(siameseSet.isPresent(), is(true));
            assertThat(siameseSet.get(), hasProperty("subSets", containsInAnyOrder(
                    imageSetWithImage("fromset_siameseset_value1", IMAGE_URI_1),
                    imageSetWithImage("fromset_siameseset_value2", IMAGE_URI_2),
                    imageSetWithImage("fromset_siameseset_value3", IMAGE_URI_3)
            )));
        }


    }

    private Matcher<Object> imageSetWithImage(String imageSet, String image) {
        return allOf(
                hasProperty("imageset", startsWith(imageSet)),
                hasProperty("documentImages", contains(hasProperty("remoteuri", equalTo(image))))
        );
    }

    private void createPimRecord(String imageUri, String fromSetRemoteUri, String value) {

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Transaction transaction = session.beginTransaction();

            final PimFieldValueDAO pimFieldValueDAO = new PimFieldValueDAO();
            final PimFieldValue pimFieldValue = new PimFieldValue();
            pimFieldValue.setValue(value);
            pimFieldValue.setField(pimField);
            pimFieldValueDAO.save(pimFieldValue);

            final PimFieldValue datasetvalue = new PimFieldValue();
            datasetvalue.setField(datasetDefinition);
            datasetvalue.setValue(fromSetRemoteUri);
            pimFieldValueDAO.save(datasetvalue);

            final PimRecord pimRecord = new PimRecord();
            pimRecord.setParent(imageUri);
            final PimRecordDAO pimRecordDAO = new PimRecordDAO();
            pimRecord.setFieldValues(List.of(pimFieldValue, datasetvalue));
            pimRecordDAO.save(session, pimRecord);

            transaction.commit();
        }
    }

    @Test
    public void getValueFieldValueReplacesSpacesWithUnderscores() {
        String imageSetName = "fromset_siameseset_value  1_20210219";

        final String value = SiameseNetworkImageSetCreator.getValueOfSetName(imageSetName);

        assertThat(value, is("value__1"));
    }

    @Test
    public void getValueFieldValueAllowsUnderscoresInValue() {
        String imageSetName = "fromset_siameseset_value_1_20210219";

        final String value = SiameseNetworkImageSetCreator.getValueOfSetName(imageSetName);

        assertThat(value, is("value_1"));
    }

}
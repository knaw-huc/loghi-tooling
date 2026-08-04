package nl.knaw.huc.di.images.layoutds.services;


import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import nl.knaw.huc.di.images.layoutds.DAO.DocumentImageDAO;
import nl.knaw.huc.di.images.layoutds.DAO.DocumentImageSetDAO;
import nl.knaw.huc.di.images.layoutds.DAO.DocumentOCRResultDAO;
import nl.knaw.huc.di.images.layoutds.exceptions.NotFoundException;
import nl.knaw.huc.di.images.layoutds.exceptions.PimSecurityException;
import nl.knaw.huc.di.images.layoutds.exceptions.ValidationException;
import nl.knaw.huc.di.images.layoutds.models.DocumentImage;
import nl.knaw.huc.di.images.layoutds.models.DocumentImageSet;
import nl.knaw.huc.di.images.layoutds.models.ElasticSearchIndex;
import nl.knaw.huc.di.images.layoutds.models.pim.PimUser;
import nl.knaw.huc.di.images.layoutds.models.pim.Role;
import nl.knaw.huc.di.images.layoutds.security.PermissionHandler;
import org.apache.commons.lang3.tuple.Pair;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.NoResultException;
import java.io.IOException;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DocumentImageSetService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentImageSetService.class);
    private final DocumentImageSetDAO documentImageSetDAO;
    private final BiFunction<Session, UUID, String> remoteUriCreator;
    private final PermissionHandler permissionHandler;
    private final DocumentImageDAO documentImageDAO = new DocumentImageDAO();
    private final DocumentOCRResultDAO documentOCRResultDAO;

    public DocumentImageSetService(BiFunction<Session, UUID, String> remoteUriCreator) {
        this.remoteUriCreator = remoteUriCreator;
        documentImageSetDAO = new DocumentImageSetDAO();
        permissionHandler = new PermissionHandler();
        documentOCRResultDAO = new DocumentOCRResultDAO();
    }

    public void save(Session session, DocumentImageSet documentImageSet, PimUser pimUser) throws PimSecurityException, ValidationException {
        if (!permissionHandler.isAllowedToCreate(session, pimUser)) {
            throw new PimSecurityException("User does not have enough permissions to create entity");
        }

        if (Strings.isNullOrEmpty(documentImageSet.getUri())) {
            documentImageSet.setUri(documentImageSet.getImageset() + "_" + UUID.randomUUID());
        }
        documentImageSet.setRemoteUri(remoteUriCreator.apply(session, documentImageSet.getUuid()));

        if (!documentImageSet.validate()) {
            throw new ValidationException("DocumentImageSet is not valid.");
        }

        final DocumentImageSet byUUID = documentImageSetDAO.getByUUID(session, documentImageSet.getUuid());
        if (byUUID != null) {
            throw new IllegalArgumentException("DocumentImageSet already exists");
        }

        final DocumentImageSet setByImagesetForOwner = documentImageSetDAO.getSetByImagesetForOwner(session, documentImageSet.getImageset(), pimUser);
        if (setByImagesetForOwner != null) {
            throw new ValidationException("You already own a DocumentImageSet with the same imageset.");
        }

        documentImageSet.setOwner(pimUser);
        documentImageSet.setPublicDocumentImageSet(true);
        documentImageSet.setCreated(new Date());
        documentImageSetDAO.save(session, documentImageSet);

        permissionHandler.addAcls(session, documentImageSet.getUuid(), pimUser);
    }

    public Stream<DocumentImageSet> streamAllForUser(Session session, PimUser pimUser, boolean onlyOwnData) {
        if (pimUser != null && pimUser.getDisabled()) {
            return Stream.empty();
        }

        if ((pimUser != null && pimUser.isAdmin()) || !permissionHandler.useGroups()) {
            return documentImageSetDAO.getAllStreaming(session, pimUser, onlyOwnData);
        }
        return documentImageSetDAO.streamAllForPrimaryGroupOfUser(session, pimUser, onlyOwnData);
    }

    // FIXME TI-351: create complete fix
    public Optional<DocumentImageSet> getByUuid(Session session, UUID uuid, PimUser pimUser) {
        if (pimUser != null && pimUser.getDisabled()) {
            throw new NoResultException();
        }
        if (pimUser != null && (pimUser.isAdmin() || pimUser.getRoles().contains(Role.SIAMESENETWORK_MINION))) {
            return Optional.ofNullable(documentImageSetDAO.getByUUID(session, uuid));
        }
        return documentImageSetDAO.getByUUID(session, uuid, pimUser, permissionHandler.useGroups());
    }

    public void update(Session session, DocumentImageSet documentImageSet, PimUser pimUser) throws PimSecurityException {
        if (!userIsAllowedToEdit(session, documentImageSet, pimUser)) {
            throw new PimSecurityException("User does not have enough permissions to update entity");
        }

        documentImageSet.setUpdated(new Date());
        documentImageSetDAO.save(session, documentImageSet);
    }

    public boolean userIsAllowedToRead(Session session, UUID imageSetUuid, PimUser pimUser) {
        return permissionHandler.isAllowedToRead(session, imageSetUuid, pimUser);
    }

    public boolean userIsAllowedToEdit(Session session, DocumentImageSet documentImageSet, PimUser pimUser) {
        if (pimUser.getDisabled()) {
            return false;
        }
        final boolean allowedToUpdate = permissionHandler.isAllowedToUpdate(session, pimUser, documentImageSet.getUuid());
        if (permissionHandler.useGroups()) {
            return allowedToUpdate;
        }
        return pimUser.isAdmin() || (
                documentImageSet.getOwner().equals(pimUser) ||
                        documentImageSet.isPublicDocumentImageSet()
                                && allowedToUpdate);
    }


    public boolean userIsAllowedToDelete(Session session, DocumentImageSet documentImageSet, PimUser pimUser) {
        if (pimUser.getDisabled()) {
            return false;
        }
        final boolean allowedToDelete = permissionHandler.isAllowedToDelete(session, pimUser, documentImageSet.getUuid());
        if (permissionHandler.useGroups()) {
            return allowedToDelete;
        }
        return pimUser.isAdmin() || (documentImageSet.getOwner().equals(pimUser) && allowedToDelete);
    }


    public void delete(Session session, UUID uuid, PimUser pimUser) throws PimSecurityException {
        final DocumentImageSet setToDelete = documentImageSetDAO.getByUUID(session, uuid);

        if (setToDelete == null) {
            throw new IllegalArgumentException("DocumentImageSet does not exist");
        }

        if (!userIsAllowedToDelete(session, setToDelete, pimUser)) {
            throw new PimSecurityException();
        }
        documentImageSetDAO.delete(session, setToDelete);

        permissionHandler.removeAllAclsForSubject(session, uuid);
    }

    public Stream<DocumentImage> streamImagesOfDocumentImageSet(Session session, UUID imageSetUuid, boolean byPageOrder, PimUser pimUser) {
        if (!pimUser.getDisabled()) {
            final DocumentImageSet documentImageSet = documentImageSetDAO.getByUUID(session, imageSetUuid);
            if (documentImageSet != null) {
                if (documentImageSet.isPublicDocumentImageSet() || userIsAllowedToRead(session, imageSetUuid, pimUser)) {
                    return documentImageDAO.getByImageSetStreaming(session, documentImageSet, byPageOrder);
                }
            }
        }

        return Stream.empty();
    }

    public Stream<Pair<DocumentImage, String>> getImagesByMetadataLabel(Session session, UUID imageSetUuid, String label, PimUser pimUser) {
        if (!pimUser.getDisabled()) {
            final DocumentImageSet documentImageSet = documentImageSetDAO.getByUUID(session, imageSetUuid);
            if (documentImageSet != null) {
                if (documentImageSet.isPublicDocumentImageSet() || userIsAllowedToRead(session, imageSetUuid, pimUser)) {

                    return this.documentImageDAO.getImagesBySetAndMetadataLabel(session, documentImageSet, label);
                }
            }
        }

        return Stream.empty();
    }

    public Stream<DocumentImageSet> getAutocomplete(Session session, PimUser pimUser, boolean onlyOwnData, String filter, int limit, int skip) {
        if (pimUser.getDisabled()) {
            return Stream.empty();
        }
        if (pimUser.isAdmin() || !permissionHandler.useGroups()) {
            return documentImageSetDAO.getAutocomplete(session, pimUser, onlyOwnData, filter, limit, skip);
        }
        return documentImageSetDAO.getAutocompleteForPrimaryGroupOfUser(session, pimUser, onlyOwnData, filter, limit, skip);
    }

    public Stream<DocumentImage> getImageAutoComplete(Session session, UUID imageSetUuid, PimUser pimUser, String filter, int limit, int skip) {
        final DocumentImageSet documentImageSet = documentImageSetDAO.getByUUID(session, imageSetUuid);
        if (documentImageSet != null) {
            if (documentImageSet.isPublicDocumentImageSet() || userIsAllowedToRead(session, imageSetUuid, pimUser)) {
                return documentImageDAO.getAutocomplete(session, documentImageSet.getUuid(), filter, limit, skip);
            }
        }

        return Stream.empty();
    }

    public void addSubSet(Session session, UUID superSetUuid, UUID subSetUuid, PimUser pimUser) throws NotFoundException, ValidationException, PimSecurityException {
        DocumentImageSet documentImageSet = documentImageSetDAO.getByUUID(session, superSetUuid);
        if (documentImageSet == null) {
            throw new NotFoundException();
        }

        if (!userIsAllowedToEdit(session, documentImageSet, pimUser)) {
            throw new PimSecurityException();
        }

        DocumentImageSet subset = documentImageSetDAO.getByUUID(session, subSetUuid);

        if (subset == null) {
            throw new ValidationException("Subset with uuid '" + superSetUuid + "' does not exist.");
        }

        if (!userIsAllowedToRead(session, subSetUuid, pimUser)) {
            throw new ValidationException("Subset with uuid '" + superSetUuid + "' does not exist.");
        }

        documentImageSet.addSubSet(subset);
        documentImageSetDAO.save(session, documentImageSet);
    }

    public List<DocumentImageSet> getByElasticSearchIndex(Session session, ElasticSearchIndex elasticSearchIndex, PimUser pimUser) {
        if (pimUser.getDisabled()) {
            return new ArrayList<>();
        }
        return documentImageSetDAO.getByElasticSearchIndex(session, elasticSearchIndex, pimUser, permissionHandler.useGroups());
    }

    public void writePageFilesToZip(Session session, Long imageSetId, boolean excludeEmptyPage, ZipOutputStream out, Function<String, String> pageNameCreator, PimUser pimUser) {
        final DocumentImageSet documentImageSet = documentImageSetDAO.get(imageSetId);

        if (documentImageSet == null) {
            return;
        }

        if (!documentImageSet.isPublicDocumentImageSet() && !userIsAllowedToRead(session, UUID.randomUUID(), pimUser)) {
            return;
        }

        Map<Object, String> ocrIdImageUriMap = documentOCRResultDAO.getImageUriWithLatestOcr(session, imageSetId, excludeEmptyPage);

        for (Map.Entry<Object, String> objectStringEntry : ocrIdImageUriMap.entrySet()) {
            String ocrResult = documentOCRResultDAO.getOcrResult(session, objectStringEntry.getKey());
            if (ocrResult.length() > 0) {
                ZipEntry zipEntry = new ZipEntry(pageNameCreator.apply(objectStringEntry.getValue()));
                try {
                    out.putNextEntry(zipEntry);
                    byte[] data = ocrResult.getBytes(Charsets.UTF_8);
                    out.write(data, 0, data.length);
                    out.closeEntry();
                } catch (IOException e) {
                    LOG.error("Unexpected error", e);
                }
            }
        }
    }

}

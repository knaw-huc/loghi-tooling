package nl.knaw.huc.di.images.layoutds.DAO;


import nl.knaw.huc.di.images.layoutds.SessionFactorySingleton;
import nl.knaw.huc.di.images.layoutds.models.pim.Acl;
import nl.knaw.huc.di.images.layoutds.models.pim.IPimObject;
import nl.knaw.huc.di.images.layoutds.models.pim.PimGroup;
import nl.knaw.huc.di.images.layoutds.models.pim.PimUser;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.TypedQuery;
import javax.persistence.criteria.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

public abstract class GenericDAO<T extends IPimObject> {
    private static final Logger LOG = LoggerFactory.getLogger(GenericDAO.class);
    final Class<T> typeParameterClass;

    public GenericDAO(Class<T> typeParameterClass) {
        this.typeParameterClass = typeParameterClass;
    }

    public static Random getRandom() {
        return ThreadLocalRandom.current();
    }

    public List<T> getAll(Session session) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<T> criteriaQuery = criteriaBuilder.createQuery(typeParameterClass);
        Root<T> documentImageRoot = criteriaQuery.from(typeParameterClass);
        criteriaQuery.select(documentImageRoot);

        TypedQuery<T> query = session.createQuery(criteriaQuery);

        return query.getResultList();
    }

    public List<T> getAll(Session session, int limit) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<T> criteriaQuery = criteriaBuilder.createQuery(typeParameterClass);
        Root<T> documentImageRoot = criteriaQuery.from(typeParameterClass);
        criteriaQuery.select(documentImageRoot);

        TypedQuery<T> query = session.createQuery(criteriaQuery);
        query.setMaxResults(limit);

        return query.getResultList();
    }


    public List<T> getAll() {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            List<T> results = getAll(session);
            return results;
        }
    }

    public List<T> getAll(int limit) {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            List<T> results = getAll(session, limit);

            return results;
        }
    }

    public Stream<T> getAllStreaming(Session session) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
        CriteriaQuery<T> criteriaQuery = criteriaBuilder.createQuery(typeParameterClass);
        // criteriaQuery.from is needed so the criteriaQuery can determine the root class
        Root<T> documentImageSetRoot = criteriaQuery.from(typeParameterClass);
        TypedQuery<T> query = session.createQuery(criteriaQuery);
        return query.getResultStream();
    }

    public T save(T objectToSave) {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            Transaction transaction = session.beginTransaction();
            T result = this.save(session, objectToSave);

            session.flush();
            transaction.commit();


            return result;
        }
    }

    public T save(Session session, T objectToSave) {
        boolean valid = true;
        for (Method method : objectToSave.getClass().getMethods()) {
            if (method.getName().equals("validate")) {
                try {
                    Method validateMethod = objectToSave.getClass().getMethod("validate");
                    Object result = validateMethod.invoke(objectToSave);
                    valid = (boolean) result;

                } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                    LOG.error("Unexpected error", e);
                }
                break;
            }
        }
        if (!valid) {
            new Exception("validation of object failed").printStackTrace();
            return null;
        }
        session.saveOrUpdate(objectToSave);

        return objectToSave;
    }


    public T getByUUID(UUID uuid) {

        try(Session session = SessionFactorySingleton.getSessionFactory().openSession()) {

            return getByUUID(session, uuid);
        }
    }

    public T getByUUID(Session session, UUID uuid) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<T> criteriaQuery = criteriaBuilder.createQuery(typeParameterClass);
        Root<T> objectRoot = criteriaQuery.from(typeParameterClass);

        criteriaQuery.where(criteriaBuilder.equal(objectRoot.get("uuid"), uuid));
        TypedQuery<T> query = session.createQuery(criteriaQuery);
        List<T> documentImages = query.getResultList();

        if (documentImages.size() == 1) {
            return documentImages.get(0);
        } else {
            return null;
        }

    }

    public T get(long id) {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession() ) {
            Transaction transaction = session.beginTransaction();
            T result = session.get(typeParameterClass, id);
            transaction.commit();

            return result;
        }

    }

    public T get(Session session, long id) {
        return session.get(typeParameterClass, id);
    }

    public BigInteger getCount() {

        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
//        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

            //CriteriaQuery<Long> cq = criteriaBuilder.createQuery(Long.class);
            //cq.select(criteriaBuilder.count(cq.from(typeParameterClass)));
            //        TypedQuery<Long> query = session.createQuery(cq);

            TypedQuery<BigInteger> query = session.createNativeQuery(" SELECT n_live_tup " +
                    " FROM pg_stat_all_tables " +
                    " WHERE relname = 'documentimage'");

            return query.getSingleResult();
        }
    }

    public void delete(T objectToDelete) {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            Transaction transaction = session.beginTransaction();
            this.delete(session, objectToDelete);
            transaction.commit();
        }
    }

    public void delete(Session session, T objectToDelete) {
        session.delete(objectToDelete);
    }

    public void update(T entity) {
        try (Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            Transaction transaction = session.beginTransaction();
            update(session, entity);
            transaction.commit();
        }
    }

    public void update(Session session, T entity) {
        save(session, entity);
//        session.update(entity);
//        session.flush();
    }

    public Stream<T> getSimpleAutocomplete(Session session, String fieldName, String filter, int limit) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<T> criteriaQuery = criteriaBuilder.createQuery(typeParameterClass);
        Root<T> siameseNetworkRoot = criteriaQuery.from(typeParameterClass);

        criteriaQuery.where(
                criteriaBuilder.like(
                        criteriaBuilder.lower(siameseNetworkRoot.get(fieldName)),
                        "%" + filter.toLowerCase() + "%"
                )
        );
        TypedQuery<T> query = session.createQuery(criteriaQuery);
        query.setMaxResults(limit);

        return query.getResultStream();
    }

    public Stream<T> getSimpleAutocomplete(Session session, String fieldName, String filter, Integer limit, int skip) {
        CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();

        CriteriaQuery<T> criteriaQuery = criteriaBuilder.createQuery(typeParameterClass);
        Root<T> siameseNetworkRoot = criteriaQuery.from(typeParameterClass);

        criteriaQuery.where(
                criteriaBuilder.like(
                        criteriaBuilder.lower(siameseNetworkRoot.get(fieldName)),
                        "%" + filter.toLowerCase() + "%"
                )
        );
        TypedQuery<T> query = session.createQuery(criteriaQuery);
        query.setMaxResults(limit);
        query.setFirstResult(skip);

        return query.getResultStream();
    }

    protected final Predicate createAclFilter(PimUser pimUser, CriteriaBuilder criteriaBuilder, CriteriaQuery<? extends IPimObject> criteriaQuery, Root<? extends IPimObject> pimObjectRoot) {
        final Subquery<UUID> aclSubquery = criteriaQuery.subquery(UUID.class);
        final Root<Acl> aclRoot = aclSubquery.from(Acl.class);
        aclSubquery.where(criteriaBuilder.and(criteriaBuilder.isNull(aclRoot.get("deleted")), aclRoot.get("group").in(getGroupsOfUser(pimUser))));
        aclSubquery.select(aclRoot.get("subjectUuid"));
        aclSubquery.distinct(true);

        return pimObjectRoot.get("uuid").in(aclSubquery);
    }

    protected Set<PimGroup> getGroupsOfUser(PimUser pimUser) {
        return pimUser != null ? pimUser.getSuperGroupsInHierarchyPrimaryGroup() : new HashSet<>();
    }
}

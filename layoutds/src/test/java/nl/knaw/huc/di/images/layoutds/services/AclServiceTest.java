package nl.knaw.huc.di.images.layoutds.services;

import nl.knaw.huc.di.images.layoutds.DAO.AclDAO;
import nl.knaw.huc.di.images.layoutds.DAO.PimGroupDAO;
import nl.knaw.huc.di.images.layoutds.DAO.PimUserDAO;
import nl.knaw.huc.di.images.layoutds.SessionFactorySingleton;
import nl.knaw.huc.di.images.layoutds.StudentJpaConfig;
import nl.knaw.huc.di.images.layoutds.exceptions.PimSecurityException;
import nl.knaw.huc.di.images.layoutds.models.pim.Acl;
import nl.knaw.huc.di.images.layoutds.models.pim.PimGroup;
import nl.knaw.huc.di.images.layoutds.models.pim.PimUser;
import nl.knaw.huc.di.images.layoutds.models.pim.Role;
import org.hamcrest.Matchers;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;


import static nl.knaw.huc.di.images.layoutds.services.AclTestHelpers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;


@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {StudentJpaConfig.class}, loader = AnnotationConfigContextLoader.class)
public class AclServiceTest {

    private PimGroupDAO pimGroupDAO;
    private AclDAO aclDao;
    private PimUserDAO pimUserDao;
    private AclService aclService;

    @After
    public void tearDown() {
        // Start with a clean database with each test.
        SessionFactorySingleton.closeSessionFactory();
    }

    @Before
    public void setUp() throws Exception {
        pimGroupDAO = new PimGroupDAO();
        aclDao = new AclDAO();
        pimUserDao = new PimUserDAO();
        aclService = new AclService();
    }

    @Test
    public void getAclsOfEnitityReturnsTheAclsForAdmins() throws PimSecurityException {
        final PimUser pimUser = adminUser();
        pimUserDao.save(pimUser);
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);


        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {

            final Set<Acl> aclsOfEnitity = aclService.getAclsOfEnitity(session, subjectUuid, pimUser);

            assertThat(aclsOfEnitity, Matchers.hasSize(1));
        }


    }

    @Test
    public void getAclsOfEnitityReturnsTheAclsForPIsGroup() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimGroup otherGroup = new PimGroup();
        pimGroupDAO.save(otherGroup);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.PI);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);
        final Acl otherAcl = Acl.readPermission(subjectUuid, otherGroup, Role.ASSISTANT);
        aclDao.save(otherAcl);


        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Set<Acl> aclsOfEnitity = aclService.getAclsOfEnitity(session, subjectUuid, pimUser);

            // Only contains
            assertThat(aclsOfEnitity, contains(hasProperty("uuid", equalTo(acl.getUuid()))));
        }
    }

    @Test
    public void getAclsOfEntityReturnsOnlyAclOfPIsPrimaryGroup() throws PimSecurityException {
        final PimGroup primaryGroup = new PimGroup();
        pimGroupDAO.save(primaryGroup);
        final PimGroup otherGroup = new PimGroup();
        pimGroupDAO.save(otherGroup);
        PimUser pimUser = userWithMembershipAndPrimaryGroup(primaryGroup, Role.PI);
        pimUserDao.save(pimUser);
        // Add membership and save within the same transaction
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            Transaction transaction = session.beginTransaction();
            pimUser = pimUserDao.getByUUID(session, pimUser.getUuid());
            pimUser.addMembership(otherGroup, Role.PI);
            pimUserDao.save(session, pimUser);
            transaction.commit();
        }
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, primaryGroup, Role.ASSISTANT);
        aclDao.save(acl);
        final Acl otherAcl = Acl.readPermission(subjectUuid, otherGroup, Role.ASSISTANT);
        aclDao.save(otherAcl);

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            Transaction transaction = session.beginTransaction();
            pimUser = pimUserDao.getByUUID(session, pimUser.getUuid());
            // Force initialization of memberships and primaryGroup's supergroups
            pimUser.getMemberships().size();
            pimUser.getPrimaryGroup().getSupergroups().size();
            transaction.commit();
        }

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Set<Acl> aclsOfEnitity = aclService.getAclsOfEnitity(session, subjectUuid, pimUser);

            // Only contains
            assertThat(aclsOfEnitity, contains(hasProperty("uuid", equalTo(acl.getUuid()))));
        }
    }

    @Test
    public void getAclsOfEntityReturnsAclOfPIsPrimaryGroupsHierarchy() throws PimSecurityException {
        final PimGroup primaryGroup = new PimGroup();
        primaryGroup.setName("primaryGroup");
        pimGroupDAO.save(primaryGroup);
        final PimGroup superGroup = new PimGroup();
        superGroup.setName("superGroup");
        superGroup.addSubgroup(primaryGroup);
        pimGroupDAO.save(superGroup);
        PimUser pimUser = userWithMembershipAndPrimaryGroup(primaryGroup, Role.PI);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, primaryGroup, Role.ASSISTANT);
        aclDao.save(acl);
        final Acl otherAcl = Acl.readPermission(subjectUuid, superGroup, Role.ASSISTANT);
        aclDao.save(otherAcl);

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            Transaction transaction = session.beginTransaction();
            pimUser = pimUserDao.getByUUID(session, pimUser.getUuid());
            // Force initialization of memberships
            pimUser.getMemberships().size();
            transaction.commit();
        }

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            pimUser = pimUserDao.getByUUID(session, pimUser.getUuid());
            // Force initialization of primaryGroup's supergroups in the SAME session where we call getAclsOfEnitity
            pimUser.getPrimaryGroup().getSupergroups().size();
            final Set<Acl> aclsOfEnitity = aclService.getAclsOfEnitity(session, subjectUuid, pimUser);

            // Only contains
            assertThat(aclsOfEnitity, containsInAnyOrder(
                    hasProperty("uuid", equalTo(acl.getUuid())),
                    hasProperty("uuid", equalTo(otherAcl.getUuid()))
            ));
        }
    }

    @Test(expected = PimSecurityException.class)
    public void getAclsOfEnitityThrowsAPimSecurityExceptionForResearchers() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.RESEARCHER);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);


        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            aclService.getAclsOfEnitity(session, subjectUuid, pimUser);
        }
    }

    @Test(expected = PimSecurityException.class)
    public void getAclsOfEnitityThrowsAPimSecurityExceptionForAssistants() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.ASSISTANT);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            aclService.getAclsOfEnitity(session, subjectUuid, pimUser);
        }
    }

    @Test(expected = PimSecurityException.class)
    public void getAclsOfEnitityThrowsAPimSecurityExceptionForDisabledUsers() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.PI);
        pimUser.setDisabled(true);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.PI);
        aclDao.save(acl);

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            aclService.getAclsOfEnitity(session, subjectUuid, pimUser);
        }
    }

    @Test
    public void removeAclIsAllowedForAdmins() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser pimUser = adminUser();
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            aclService.removeAcl(session, acl.getUuid(), pimUser);
        }

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            assertThat(aclDao.getByUUID(session, acl.getUuid()), Matchers.is(nullValue()));
        }
    }

    @Test
    public void removeAclIsAllowedForPIsForTheirPrimaryGroup() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.PI);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            aclService.removeAcl(session, acl.getUuid(), pimUser);
        }

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            assertThat(aclDao.getByUUID(session, acl.getUuid()), Matchers.is(nullValue()));
        }
    }

    @Test
    public void removeAclIsAllowedForPIsForASuperGroupOfTheirPrimaryGroup() throws PimSecurityException {
        final PimGroup primaryGroup = new PimGroup();
        primaryGroup.setName("primaryGroup");
        pimGroupDAO.save(primaryGroup);
        final PimGroup superGroup = new PimGroup();
        superGroup.setName("superGroup");
        superGroup.addSubgroup(primaryGroup);
        pimGroupDAO.save(superGroup);
        PimUser pimUser = userWithMembershipAndPrimaryGroup(primaryGroup, Role.PI);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, superGroup, Role.ASSISTANT);
        aclDao.save(acl);

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            pimUser = pimUserDao.getByUUID(session, pimUser.getUuid());
            aclService.removeAcl(session, acl.getUuid(), pimUser);
        }

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            assertThat(aclDao.getByUUID(session, acl.getUuid()), Matchers.is(nullValue()));
        }
    }

    @Test(expected = PimSecurityException.class)
    public void removeAclIsNotAllowedForPIsForOtherGroups() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimGroup otherGroup = new PimGroup();
        pimGroupDAO.save(otherGroup);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.PI);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, otherGroup, Role.ASSISTANT);
        aclDao.save(acl);

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            aclService.removeAcl(session, acl.getUuid(), pimUser);
        }
    }

    @Test(expected = PimSecurityException.class)
    public void removeAclIsNotAllowedForResearchers() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.RESEARCHER);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            aclService.removeAcl(session, acl.getUuid(), pimUser);
        }
    }

    @Test(expected = PimSecurityException.class)
    public void removeAclIsNotAllowedForAssistants() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.ASSISTANT);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            aclService.removeAcl(session, acl.getUuid(), pimUser);
        }
    }

    @Test(expected = PimSecurityException.class)
    public void removeAclIsNotAllowedForDisabledUsers() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser pimUser = adminUser();
        pimUser.setDisabled(true);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            aclService.removeAcl(session, acl.getUuid(), pimUser);
        }
    }

    @Test
    public void addAclIsAllowedForSubjectsWithoutAclsForAdmins() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser admin = adminUser();
        pimUserDao.save(admin);
        final UUID subjectUuid = UUID.randomUUID();

        final AclService.AclToAdd aclToAdd = new AclService.AclToAdd(group.getUuid(), Role.ASSISTANT, Acl.Permission.UPDATE);
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Transaction transaction = session.beginTransaction();
            aclService.addAcl(session, subjectUuid, aclToAdd, admin);
            transaction.commit();
        }

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            assertThat(aclService.getAclsOfEnitity(session, subjectUuid, admin), contains(
                    allOf(
                            hasProperty("group", equalTo(group)),
                            hasProperty("role", equalTo(Role.ASSISTANT)),
                            hasProperty("permission", equalTo(Acl.Permission.UPDATE))
                    )
            ));
        }
    }

    @Test
    public void addAclIsAllowedForAdmins() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser admin = adminUser();
        pimUserDao.save(admin);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);

        final AclService.AclToAdd aclToAdd = new AclService.AclToAdd(group.getUuid(), Role.ASSISTANT, Acl.Permission.UPDATE);
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Transaction transaction = session.beginTransaction();
            aclService.addAcl(session, subjectUuid, aclToAdd, admin);
            transaction.commit();
        }

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            assertThat(aclService.getAclsOfEnitity(session, subjectUuid, admin), containsInAnyOrder(
                    allOf(
                            hasProperty("group", equalTo(group)),
                            hasProperty("role", equalTo(Role.ASSISTANT)),
                            hasProperty("permission", equalTo(Acl.Permission.READ))
                    ),
                    allOf(
                            hasProperty("group", equalTo(group)),
                            hasProperty("role", equalTo(Role.ASSISTANT)),
                            hasProperty("permission", equalTo(Acl.Permission.UPDATE))
                    )
            ));
        }
    }

    @Test
    public void addAclIsAllowedForUsersThatHavePermissionToAddAcls() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.PI);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.createPermission(subjectUuid, group, Role.PI);
        aclDao.save(acl);

        final AclService.AclToAdd aclToAdd = new AclService.AclToAdd(group.getUuid(), Role.ASSISTANT, Acl.Permission.UPDATE);
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Transaction transaction = session.beginTransaction();
            aclService.addAcl(session, subjectUuid, aclToAdd, pimUser);
            transaction.commit();
        }

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            assertThat(aclService.getAclsOfEnitity(session, subjectUuid, pimUser), containsInAnyOrder(
                    allOf(
                            hasProperty("group", equalTo(group)),
                            hasProperty("role", equalTo(Role.PI)),
                            hasProperty("permission", equalTo(Acl.Permission.CREATE))
                    ),
                    allOf(
                            hasProperty("group", equalTo(group)),
                            hasProperty("role", equalTo(Role.ASSISTANT)),
                            hasProperty("permission", equalTo(Acl.Permission.UPDATE))
                    )
            ));
        }
    }

    @Test
    public void addAclAllowsToAddAclsToAnyGroup() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        group.setName("group");
        pimGroupDAO.save(group);
        final PimGroup otherGroup = new PimGroup();
        otherGroup.setName("otherGroup");
        pimGroupDAO.save(otherGroup);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.PI);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.createPermission(subjectUuid, group, Role.PI);
        aclDao.save(acl);

        final AclService.AclToAdd aclToAdd = new AclService.AclToAdd(otherGroup.getUuid(), Role.ASSISTANT, Acl.Permission.UPDATE);
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Transaction transaction = session.beginTransaction();
            aclService.addAcl(session, subjectUuid, aclToAdd, pimUser);
            transaction.commit();
        }

        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Set<Acl> acls = aclDao.getBySubjectUuid(session, subjectUuid).collect(Collectors.toSet());
            assertThat(acls, containsInAnyOrder(
                    allOf(
                            hasProperty("group", equalTo(group)),
                            hasProperty("role", equalTo(Role.PI)),
                            hasProperty("permission", equalTo(Acl.Permission.CREATE))
                    ),
                    allOf(
                            hasProperty("group", equalTo(otherGroup)),
                            hasProperty("role", equalTo(Role.ASSISTANT)),
                            hasProperty("permission", equalTo(Acl.Permission.UPDATE))
                    )
            ));
        }
    }

    @Test(expected = PimSecurityException.class)
    public void addAclThrowsAPimSecurityExceptionIfThereAreNoAclsForSubjectId() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.PI);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();

        final AclService.AclToAdd aclToAdd = new AclService.AclToAdd(group.getUuid(), Role.ASSISTANT, Acl.Permission.UPDATE);
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Transaction transaction = session.beginTransaction();
            aclService.addAcl(session, subjectUuid, aclToAdd, pimUser);
            transaction.commit();
        }
    }

    @Test(expected = PimSecurityException.class)
    public void addAclThrowsPimSecurityExceptionForResearchers() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.RESEARCHER);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);


        final AclService.AclToAdd aclToAdd = new AclService.AclToAdd(group.getUuid(), Role.ASSISTANT, Acl.Permission.UPDATE);
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Transaction transaction = session.beginTransaction();
            aclService.addAcl(session, subjectUuid, aclToAdd, pimUser);
            transaction.commit();
        }
    }

    @Test(expected = PimSecurityException.class)
    public void addAclThrowsPimSecurityExceptionForAssistants() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser pimUser = userWithMembershipAndPrimaryGroup(group, Role.ASSISTANT);
        pimUserDao.save(pimUser);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);

        final AclService.AclToAdd aclToAdd = new AclService.AclToAdd(group.getUuid(), Role.ASSISTANT, Acl.Permission.UPDATE);
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Transaction transaction = session.beginTransaction();
            aclService.addAcl(session, subjectUuid, aclToAdd, pimUser);
            transaction.commit();
        }
    }

    @Test(expected = PimSecurityException.class)
    public void addAclThrowsPimSecurityExceptionForDisabledUsers() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser admin = adminUser();
        admin.setDisabled(true);
        pimUserDao.save(admin);
        final UUID subjectUuid = UUID.randomUUID();

        final AclService.AclToAdd aclToAdd = new AclService.AclToAdd(group.getUuid(), Role.ASSISTANT, Acl.Permission.UPDATE);
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Transaction transaction = session.beginTransaction();
            aclService.addAcl(session, subjectUuid, aclToAdd, admin);
            transaction.commit();
        }

    }

    @Test(expected = IllegalArgumentException.class)
    public void addAclThrowsIllegalArgumentExceptionWhenTheGroupDoesNotExist() throws PimSecurityException {
        final PimGroup group = new PimGroup();
        pimGroupDAO.save(group);
        final PimUser admin = adminUser();
        pimUserDao.save(admin);
        final UUID subjectUuid = UUID.randomUUID();
        final Acl acl = Acl.readPermission(subjectUuid, group, Role.ASSISTANT);
        aclDao.save(acl);
        final UUID bogusGroupId = UUID.randomUUID();

        final AclService.AclToAdd aclToAdd = new AclService.AclToAdd(bogusGroupId, Role.ASSISTANT, Acl.Permission.UPDATE);
        try (final Session session = SessionFactorySingleton.getSessionFactory().openSession()) {
            final Transaction transaction = session.beginTransaction();
            aclService.addAcl(session, subjectUuid, aclToAdd, admin);
            transaction.commit();
        }

    }

}
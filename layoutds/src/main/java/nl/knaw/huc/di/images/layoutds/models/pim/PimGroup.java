package nl.knaw.huc.di.images.layoutds.models.pim;


import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.Type;
import org.hibernate.validator.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.*;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.*;
import java.util.stream.Collectors;

@Entity
@XmlRootElement
public class PimGroup implements IPimObject {
    private static final Logger LOG = LoggerFactory.getLogger(PimGroup.class);
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Type(type = "text")
    @NotBlank
    @Column(unique = true)
    private String name;

    private Date created;
    private Date updated;
    private Date deleted;

    @Column(nullable = false, unique = true)
    private UUID uuid;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY, mappedBy = "pimGroup")
    @JsonIgnore
    private Set<Membership> memberships;

    @ManyToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinTable(
            name = "pimSubgroups",
            joinColumns = {@JoinColumn(name = "parent")},
            inverseJoinColumns = {@JoinColumn(name = "child")}
    )
    @JsonIgnore
    private Set<PimGroup> subgroups;

    @ManyToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinTable(
            name = "pimSubgroups",
            joinColumns = {@JoinColumn(name = "child")},
            inverseJoinColumns = {@JoinColumn(name = "parent")}
    )
    @JsonIgnore
    private Set<PimGroup> supergroups;

    public PimGroup() {
        this.created = new Date();
        this.uuid = UUID.randomUUID();
        this.subgroups = new HashSet<>();
        this.supergroups = new HashSet<>();
        this.memberships = new HashSet<>();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }


    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public Date getUpdated() {
        return updated;
    }

    public void setUpdated(Date updated) {
        this.updated = updated;
    }

    public Date getDeleted() {
        return deleted;
    }

    public void setDeleted(Date deleted) {
        this.deleted = deleted;
    }

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public Set<PimGroup> getSubgroups() {
        return subgroups;
    }

    public void setSubgroups(Set<PimGroup> subgroups) {
        this.subgroups = subgroups;
    }

    public void removeSubgroup(PimGroup subgroup) {
        subgroups.remove(subgroup);
    }

    public void addSubgroup(PimGroup subgroup) {
        final int subgroupToAddDepth = subgroup.countSubgroups(0);
        if (subgroupToAddDepth >= 3) {
            throw new IllegalArgumentException("Subgroup to has too many layers of subgroups");
        }

        final int supergroupDepth = this.countDepthSupergroups(0);
        if (supergroupDepth >= 3) {
            throw new IllegalArgumentException("This group to has too many layers of supergroup");
        }

        if ((subgroupToAddDepth + supergroupDepth) > 3) {
            throw new IllegalArgumentException("There will be too many layers when subgroup is added");
        }

        final HashSet<PimGroup> checkedGroups = new HashSet<>();
        checkedGroups.add(this);
        if (isSomewhereInTheFamilyTree(supergroups, subgroups, subgroup, checkedGroups)) {
            throw new IllegalArgumentException("The subgroup is already in the tree.");
        }

        subgroups.add(subgroup);
    }

    private static boolean isSomewhereInTheFamilyTree(Set<PimGroup> supergroups, Set<PimGroup> subgroups, PimGroup subgroupToAdd, Set<PimGroup> checkedGroups) {
        for (PimGroup supergroup : supergroups.stream().filter(pimGroup -> !checkedGroups.contains(pimGroup)).collect(Collectors.toSet())) {
            if (supergroup.uuid.equals(subgroupToAdd.uuid)) {
                return true;
            }
            checkedGroups.add(supergroup);
            if (isSomewhereInTheFamilyTree(supergroup.supergroups, supergroup.subgroups, subgroupToAdd, checkedGroups)) {
                return true;
            }
        }

        for (PimGroup subgroup : subgroups.stream().filter(pimGroup -> !checkedGroups.contains(pimGroup)).collect(Collectors.toSet())) {
            if (subgroup.uuid.equals(subgroupToAdd.uuid)) {
                return true;
            }

            checkedGroups.add(subgroup);
            if (isSomewhereInTheFamilyTree(subgroup.supergroups, subgroup.subgroups, subgroupToAdd, checkedGroups)) {
                return true;
            }
        }


        return false;
    }

    private int countDepthSupergroups(int depth) {
        if (depth >= 3) {
            return depth;
        }
        final Optional<Integer> highestDepth = supergroups.stream()
                .map(superGroup -> superGroup.countDepthSupergroups(depth + 1))
                .max(Comparator.naturalOrder());
        return highestDepth.orElse(depth + 1);
    }

    private int countSubgroups(int depth) {
        if (depth >= 3) {
            return depth;
        }
        final Optional<Integer> highestDepth = getSubgroups().stream()
                .map(subgroup -> subgroup.countSubgroups(depth + 1))
                .max(Comparator.naturalOrder());
        return highestDepth.orElse(depth + 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PimGroup pimGroup = (PimGroup) o;
        return Objects.equals(uuid, pimGroup.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }

    @Override
    public String toString() {
        return "PimGroup{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", uuid=" + uuid +
                '}';
    }

    public Set<Membership> getMemberships() {
        return memberships;
    }

    public void setMemberships(Set<Membership> memberships) {
        this.memberships = memberships;
    }

    public void addMembership(Membership membership) {
        memberships.add(membership);
    }

    public void removeMembership(Membership membership) {
        final boolean remove = memberships.remove(membership);
        LOG.info("removed: {}", remove);
    }

    public void addSupergroup(PimGroup superGroup) {
        this.supergroups.add(superGroup);
    }

    public Set<PimGroup> getSupergroups() {
        return this.supergroups;
    }

    @JsonIgnore
    public Set<PimGroup> getSuperGroupsInHierarchy() {
        final HashSet<PimGroup> pimGroups = new HashSet<>();
        pimGroups.add(this);
        for (PimGroup supergroup : this.supergroups) {
            pimGroups.addAll(supergroup.getSuperGroupsInHierarchy());
        }

        return pimGroups;
    }
}
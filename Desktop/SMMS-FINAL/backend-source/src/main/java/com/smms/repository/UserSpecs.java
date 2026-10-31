package com.smms.repository;

import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.security.AdminScopePolicy.Kind;
import com.smms.security.AdminScopePolicy.UserListScope;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Specifications for the admin user list. The scope predicate is always ANDed with the filters. */
public final class UserSpecs {

    private UserSpecs() {}

    /**
     * @param scope    what the actor may see (NONE matches nothing)
     * @param tenantId tenant of the request context, null for the platform admin
     * @param centerId optional caller filter, only narrows
     * @param role     optional caller filter, only narrows
     */
    public static Specification<User> listFor(UserListScope scope, UUID tenantId, UUID centerId, UserRole role) {
        return (root, query, cb) -> {
            if (scope.kind() == Kind.NONE) return cb.disjunction();
            List<Predicate> and = new ArrayList<>();
            and.add(cb.isNull(root.get("deletedAt")));
            if (tenantId != null) and.add(cb.equal(root.get("tenantId"), tenantId));
            switch (scope.kind()) {
                case REGION -> {
                    var ownRegion = root.join("region", JoinType.LEFT);
                    var centerRegion = root.join("center", JoinType.LEFT).join("region", JoinType.LEFT);
                    and.add(cb.or(
                        cb.equal(ownRegion.get("id"), scope.regionId()),
                        cb.equal(centerRegion.get("id"), scope.regionId())));
                }
                case CENTER -> and.add(cb.equal(root.join("center", JoinType.LEFT).get("id"), scope.centerId()));
                default -> { /* ALL */ }
            }
            if (!scope.excludedRoles().isEmpty()) {
                and.add(cb.not(root.get("role").in(scope.excludedRoles())));
            }
            if (centerId != null) and.add(cb.equal(root.join("center", JoinType.LEFT).get("id"), centerId));
            if (role != null) and.add(cb.equal(root.get("role"), role));
            return cb.and(and.toArray(new Predicate[0]));
        };
    }
}

package com.premisave.wallet.commercial.onboarding;

import com.premisave.wallet.commercial.config.CommercialOnboardingProperties;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePolicyTest {

    private static RolePolicy policy(CommercialOnboardingProperties.Roles roles) {
        return new RolePolicy(new CommercialOnboardingProperties(null, null, null, null, null, null, roles, null));
    }

    private static Actor staff(String role) {
        return Actor.staff("u1", "Sam", role);
    }

    @Test
    void financeApprovesButOperationsDoesNot() {
        RolePolicy p = policy(null);
        p.require(staff("FINANCE"), Permission.APPROVE);
        p.require(staff("OPERATIONS"), Permission.REVIEW);
        p.require(staff("OPERATIONS"), Permission.RETURN);
        CommercialException e = assertThrows(CommercialException.class, () -> p.require(staff("OPERATIONS"), Permission.APPROVE));
        assertEquals(ErrorCode.ROLE_NOT_PERMITTED, e.code());
    }

    @Test
    void onlyAdminsReopenByDefault() {
        RolePolicy p = policy(null);
        p.require(staff("admin"), Permission.REOPEN);
        assertThrows(CommercialException.class, () -> p.require(staff("FINANCE"), Permission.REOPEN));
    }

    @Test
    void anApplicantOrMissingActorIsNotStaff() {
        RolePolicy p = policy(null);
        CommercialException e = assertThrows(CommercialException.class, () -> p.require(Actor.applicant("u9"), Permission.REVIEW));
        assertEquals(ErrorCode.UNAUTHENTICATED, e.code());
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(CommercialException.class,
                () -> p.require(null, Permission.REVIEW)).code());
    }

    @Test
    void anUnknownRoleCanDoNothing() {
        RolePolicy p = policy(null);
        for (Permission perm : Permission.values()) {
            assertThrows(CommercialException.class, () -> p.require(staff("INTERN"), perm));
        }
        assertThrows(CommercialException.class, () -> p.require(staff(" "), Permission.REVIEW));
    }

    @Test
    void rolesComeFromConfiguration() {
        CommercialOnboardingProperties.Roles custom = new CommercialOnboardingProperties.Roles(
                List.of("COMPLIANCE"), List.of("COMPLIANCE"), List.of("CFO"), List.of("CFO"), List.of("CFO"));
        RolePolicy p = policy(custom);
        p.require(staff("compliance"), Permission.REVIEW);
        p.require(staff("cfo"), Permission.APPROVE);
        assertThrows(CommercialException.class, () -> p.require(staff("ADMIN"), Permission.REVIEW));
        assertTrue(RolePolicy.allows(custom, "CFO", Permission.REOPEN));
        assertFalse(RolePolicy.allows(custom, "COMPLIANCE", Permission.APPROVE));
    }
}

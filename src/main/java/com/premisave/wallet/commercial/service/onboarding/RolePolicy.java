package com.premisave.wallet.commercial.service.onboarding;

import com.premisave.wallet.commercial.config.properties.CommercialOnboardingProperties;
import com.premisave.wallet.commercial.config.properties.CommercialOnboardingProperties.Roles;
import com.premisave.wallet.commercial.entity.onboarding.Actor;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.onboarding.ActorType;
import com.premisave.wallet.commercial.enums.onboarding.Permission;
import com.premisave.wallet.commercial.exception.CommercialException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Decides which staff role may do what, from commercial.onboarding.roles. */
@Component("commercialOnboardingRolePolicy")
public class RolePolicy {

    private final CommercialOnboardingProperties props;

    public RolePolicy(CommercialOnboardingProperties props) {
        this.props = props;
    }

    public void require(Actor actor, Permission permission) {
        if (actor == null || actor.type() != ActorType.STAFF) {
            throw new CommercialException(ErrorCode.UNAUTHENTICATED, "A staff member is required for this step");
        }
        if (!allows(props.roles(), actor.role(), permission)) {
            throw new CommercialException(ErrorCode.ROLE_NOT_PERMITTED,
                    "The role " + (actor.role() == null ? "(none)" : actor.role().toUpperCase(Locale.ROOT))
                            + " may not " + describe(permission) + " an application");
        }
    }

    public static boolean allows(Roles roles, String role, Permission permission) {
        if (role == null || role.isBlank()) return false;
        String r = role.trim().toUpperCase(Locale.ROOT);
        List<String> allowed = switch (permission) {
            case REVIEW -> roles.review();
            case RETURN -> roles.returnForCorrection();
            case APPROVE -> roles.approve();
            case REJECT -> roles.reject();
            case REOPEN -> roles.reopen();
        };
        return allowed.contains(r);
    }

    private static String describe(Permission p) {
        return switch (p) {
            case REVIEW -> "review";
            case RETURN -> "return";
            case APPROVE -> "approve";
            case REJECT -> "reject";
            case REOPEN -> "reopen";
        };
    }
}

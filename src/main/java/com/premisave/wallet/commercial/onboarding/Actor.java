package com.premisave.wallet.commercial.onboarding;

/** Who is doing something. Staff come from the developer service's own login, the applicant is the merchant owner. */
public record Actor(ActorType type, String id, String name, String role) {

    public static Actor applicant(String id) {
        return new Actor(ActorType.APPLICANT, blankTo(id, "applicant"), null, null);
    }

    public static Actor system() {
        return new Actor(ActorType.SYSTEM, "system", "System", null);
    }

    public static Actor staff(String id, String name, String role) {
        return new Actor(ActorType.STAFF, id, name, role);
    }

    /** Name shown to the applicant in the timeline and emails. Staff identities stay internal. */
    public String publicLabel() {
        return type == ActorType.STAFF ? "Premisave review team" : type == ActorType.APPLICANT ? "You" : "Premisave";
    }

    static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}

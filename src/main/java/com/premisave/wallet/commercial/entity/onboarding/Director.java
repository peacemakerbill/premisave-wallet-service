package com.premisave.wallet.commercial.entity.onboarding;

/** A director or beneficial owner named on the application. */
public class Director {

    private String fullName;
    private String idNumber;
    /** Director, shareholder, owner and so on. */
    private String role;
    private Integer ownershipPercent;

    public Director() {}

    public String getFullName() { return fullName; }
    public void setFullName(String v) { this.fullName = v; }
    public String getIdNumber() { return idNumber; }
    public void setIdNumber(String v) { this.idNumber = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public Integer getOwnershipPercent() { return ownershipPercent; }
    public void setOwnershipPercent(Integer v) { this.ownershipPercent = v; }
}

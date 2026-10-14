package com.premisave.wallet.commercial.entity;

import com.premisave.wallet.commercial.enums.MethodType;

import java.util.LinkedHashMap;
import java.util.Map;

/** A place a merchant's balance may be settled to. Fixed per merchant; payouts can never name one. */
public class SettlementDestination {

    private String id;
    private MethodType type;
    private String label;
    private String country;
    private Map<String, String> details = new LinkedHashMap<>();

    public SettlementDestination() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public MethodType getType() { return type; }
    public void setType(MethodType type) { this.type = type; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public Map<String, String> getDetails() { return details; }
    public void setDetails(Map<String, String> details) { this.details = details; }
}

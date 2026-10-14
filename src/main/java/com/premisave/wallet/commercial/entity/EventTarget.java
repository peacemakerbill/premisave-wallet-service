package com.premisave.wallet.commercial.entity;

/** Where one event is to be delivered. source is REQUEST (a per-request callbackUrl) or REGISTERED. */
public class EventTarget {

    private String url;
    private String source;
    private String registrationId;

    public EventTarget() {}

    public EventTarget(String url, String source, String registrationId) {
        this.url = url;
        this.source = source;
        this.registrationId = registrationId;
    }

    public String getUrl() { return url; }
    public String getSource() { return source; }
    public String getRegistrationId() { return registrationId; }
}

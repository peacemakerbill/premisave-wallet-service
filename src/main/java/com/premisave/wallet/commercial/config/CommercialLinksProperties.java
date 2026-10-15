package com.premisave.wallet.commercial.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where customers open a payment link. The commercial API does not serve the
 * checkout page itself; the developer service does, so the link url is built from
 * its public address. When no address is set the url is the relative path /pay/{id}.
 *
 * checkoutBaseUrl: for live links, for example https://pay.example.com
 * sandboxCheckoutBaseUrl: for sandbox links; falls back to checkoutBaseUrl
 */
@ConfigurationProperties(prefix = "commercial.links")
public record CommercialLinksProperties(String checkoutBaseUrl, String sandboxCheckoutBaseUrl) {

    public String urlFor(boolean livemode, String linkId) {
        String base = livemode ? checkoutBaseUrl : (isBlank(sandboxCheckoutBaseUrl) ? checkoutBaseUrl : sandboxCheckoutBaseUrl);
        if (isBlank(base)) return "/pay/" + linkId;
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed + "/pay/" + linkId;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

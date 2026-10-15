package com.premisave.wallet.commercial.rail.mpesa.c2b;

import com.premisave.wallet.commercial.config.CommercialC2bProperties;
import com.premisave.wallet.commercial.rail.mpesa.MpesaCallbackSigner;
import com.premisave.wallet.commercial.rail.mpesa.MpesaDarajaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tells Safaricom where to send the commercial paybill's validation and confirmation requests. Done by hand
 * through the internal endpoint, or once at start up when commercial.c2b.register-on-startup is true. Safaricom
 * keeps one pair of addresses per paybill and will not replace it, so an already registered paybill is reported,
 * not treated as a failure.
 */
@Component("commercialC2bUrlRegistrar")
@ConditionalOnProperty(name = {"commercial.c2b.enabled", "commercial.mpesa.enabled"}, havingValue = "true")
public class C2bUrlRegistrar {

    private static final Logger log = LoggerFactory.getLogger(C2bUrlRegistrar.class);

    private final CommercialC2bProperties props;
    private final MpesaCallbackSigner signer;
    private final MpesaDarajaClient daraja;

    public C2bUrlRegistrar(CommercialC2bProperties props, MpesaCallbackSigner signer, MpesaDarajaClient daraja) {
        this.props = props;
        this.signer = signer;
        this.daraja = daraja;
    }

    public String validationUrl() {
        return signer.urlFor(MpesaCallbackSigner.C2B_VALIDATION, props.shortcode());
    }

    public String confirmationUrl() {
        return signer.urlFor(MpesaCallbackSigner.C2B_CONFIRMATION, props.shortcode());
    }

    public Map<String, Object> register() {
        MpesaDarajaClient.C2bRegistration r = daraja.registerC2bUrls(props.shortcode(), props.responseType(),
                confirmationUrl(), validationUrl());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shortcode", props.shortcode());
        out.put("responseType", props.responseType());
        out.put("registered", r.registered());
        out.put("alreadyRegistered", r.alreadyRegistered());
        out.put("message", r.message());
        return out;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerOnStartup() {
        if (!props.registerOnStartup()) return;
        try {
            Map<String, Object> r = register();
            log.info("Paybill address registration for {}: {}", props.shortcode(), r);
        } catch (RuntimeException e) {
            log.error("Paybill address registration for {} failed: {}", props.shortcode(), e.getMessage());
        }
    }
}

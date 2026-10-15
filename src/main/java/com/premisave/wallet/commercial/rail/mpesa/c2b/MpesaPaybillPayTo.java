package com.premisave.wallet.commercial.rail.mpesa.c2b;

import com.premisave.wallet.commercial.config.CommercialC2bProperties;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.link.PayToProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Live account references point at the commercial paybill. Present only when paybill payments are
 * enabled, so a live reference is refused (METHOD_UNAVAILABLE) until they are.
 */
@Component("commercialMpesaPaybillPayTo")
@ConditionalOnProperty(name = {"commercial.c2b.enabled", "commercial.mpesa.enabled"}, havingValue = "true")
public class MpesaPaybillPayTo implements PayToProvider {

    private final CommercialC2bProperties props;

    public MpesaPaybillPayTo(CommercialC2bProperties props) {
        String problem = props.problem();
        if (problem != null) throw new IllegalStateException("Paybill payments are enabled but " + problem);
        this.props = props;
    }

    @Override
    public boolean supports(Environment env, MethodType method) {
        return env == Environment.LIVE && method == MethodType.MOBILE_MONEY;
    }

    @Override
    public PayTo payTo(Environment env, MethodType method) {
        return new PayTo("MPESA", props.shortcode());
    }
}

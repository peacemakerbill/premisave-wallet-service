package com.premisave.wallet.commercial.link;

import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;

/**
 * Says where a customer pays when given an account reference: which provider and which shared
 * paybill or account. A rail that can match incoming payments to references supplies one of these;
 * a method with no provider cannot issue references (422 METHOD_UNAVAILABLE).
 */
public interface PayToProvider {

    record PayTo(String provider, String shortcode) {}

    boolean supports(Environment env, MethodType method);

    PayTo payTo(Environment env, MethodType method);
}

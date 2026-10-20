package com.premisave.wallet.commercial.service.billing;

import com.premisave.wallet.commercial.entity.billing.CustomerPaymentMethod;

/**
 * Told when a customer finishes (or fails) the step that authorises a saved payment method. Subscriptions
 * waiting for the method use it to start. A listener must be safe to call more than once.
 */
public interface PaymentMethodListener {
    void onActivated(CustomerPaymentMethod method);

    void onFailed(CustomerPaymentMethod method);
}

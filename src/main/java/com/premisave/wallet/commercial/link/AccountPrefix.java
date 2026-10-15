package com.premisave.wallet.commercial.link;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** The short code (PS plus digits) that starts every account reference of one merchant in one environment. */
@Document(collection = "commercial_account_prefixes")
public class AccountPrefix {

    @Id
    private String key;

    @Indexed(unique = true)
    private String prefix;

    public AccountPrefix() {}

    public AccountPrefix(String key, String prefix) {
        this.key = key;
        this.prefix = prefix;
    }

    public String getKey() { return key; }
    public String getPrefix() { return prefix; }

    public static String keyFor(String merchantId, boolean livemode) {
        return merchantId + (livemode ? "|live" : "|sandbox");
    }
}

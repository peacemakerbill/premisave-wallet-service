package com.premisave.wallet.commercial.entity;

import com.premisave.wallet.commercial.enums.ResultCode;

/** Final outcome of a transaction: the normalised code plus whatever the provider said. */
public class TxResult {

    private ResultCode code;
    private String description;
    private String providerCode;
    private String providerDescription;

    public TxResult() {}

    public TxResult(ResultCode code, String description, String providerCode, String providerDescription) {
        this.code = code;
        this.description = description;
        this.providerCode = providerCode;
        this.providerDescription = providerDescription;
    }

    public ResultCode getCode() { return code; }
    public String getDescription() { return description; }
    public String getProviderCode() { return providerCode; }
    public String getProviderDescription() { return providerDescription; }
}

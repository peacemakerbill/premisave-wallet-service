package com.premisave.wallet.dto;

import lombok.Data;

@Data
public class DisbursementCallbackRequest {
    private String conversationId;
    private String status;
    private String resultDesc;
}
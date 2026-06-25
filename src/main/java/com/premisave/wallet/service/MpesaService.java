package com.premisave.wallet.service;

import com.premisave.wallet.dto.MpesaB2CResponse;
import com.premisave.wallet.dto.MpesaStkPushRequest;
import org.springframework.stereotype.Service;

@Service
public class MpesaService {

    public MpesaB2CResponse sendB2C(String phone, java.math.BigDecimal amount) {
        // TODO: Implement real Daraja B2C call
        return new MpesaB2CResponse(true, "Simulated B2C success", "conv123", "orig123");
    }

    public String initiateStkPush(MpesaStkPushRequest request) {
        // TODO: Implement real STK Push
        return "STK_PUSH_INITIATED";
    }
}
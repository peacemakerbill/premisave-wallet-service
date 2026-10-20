package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.beneficiary.LookupAccountRequest;
import com.premisave.wallet.commercial.dto.beneficiary.VerifyBeneficiaryRequest;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.service.beneficiary.BeneficiaryService;
import com.premisave.wallet.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Sections 7.1 to 7.3: check who owns a destination before sending money to it. */
@RestController("commercialBeneficiaryController")
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}/beneficiaries")
public class CommercialBeneficiaryController {

    private final BeneficiaryService beneficiaries;

    public CommercialBeneficiaryController(BeneficiaryService beneficiaries) {
        this.beneficiaries = beneficiaries;
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(@Valid @RequestBody VerifyBeneficiaryRequest req) {
        return Responses.ok("Beneficiary checked", beneficiaries.verify(CommercialContextHolder.require(), req));
    }

    @PostMapping("/lookup-account")
    public ResponseEntity<ApiResponse<Map<String, Object>>> lookup(@Valid @RequestBody LookupAccountRequest req) {
        return Responses.ok("Account resolved", beneficiaries.lookupAccount(CommercialContextHolder.require(), req));
    }

    @GetMapping("/checks/{checkId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String checkId) {
        return Responses.ok("Beneficiary check retrieved", beneficiaries.get(CommercialContextHolder.require(), checkId));
    }
}

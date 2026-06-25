package com.premisave.wallet.service;

import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DisbursementService {

    private final WalletRepository walletRepository;
    private final DisbursementRepository disbursementRepository;
    private final MpesaService mpesaService;

    public DisbursementResponse processDisbursement(String userId, DisbursementRequest request) {
        var wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Wallet not found"));

        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new RuntimeException("Insufficient funds");
        }

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(userId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(request.getAmount());
        disbursement.setDestination(request.getDestination());
        disbursement.setProvider(request.getProvider());

        // For now, simulate M-Pesa B2C
        var mpesaResponse = mpesaService.sendB2C(request.getDestination(), request.getAmount());

        disbursement.setStatus(mpesaResponse.isSuccess() ? 
                com.premisave.wallet.enums.DisbursementStatus.SUCCESS : 
                com.premisave.wallet.enums.DisbursementStatus.FAILED);

        disbursementRepository.save(disbursement);

        return new DisbursementResponse(disbursement.getId(), 
                disbursement.getStatus().name(), mpesaResponse.getMessage());
    }
}
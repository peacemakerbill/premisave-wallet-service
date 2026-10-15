package com.premisave.wallet.commercial.beneficiary;

import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.transfer.CommercialTransferService;
import com.premisave.wallet.commercial.transfer.PremisaveWalletCredit;
import com.premisave.wallet.service.UserNameResolver;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Premisave wallets in the live environment. The real name is used to compare, but the merchant is
 * only shown a masked form ("S***** O**"), the same as on a transfer, so the check cannot be used to
 * read people's names out of the system. A frozen wallet counts as not found because it cannot receive money.
 */
@Component("commercialPremisaveWalletBeneficiaryResolver")
public class PremisaveWalletBeneficiaryResolver implements BeneficiaryResolver {

    static final String SOURCE = "PREMISAVE_WALLET";

    private final PremisaveWalletCredit wallets;
    private final UserNameResolver names;

    public PremisaveWalletBeneficiaryResolver(PremisaveWalletCredit wallets, UserNameResolver names) {
        this.wallets = wallets;
        this.names = names;
    }

    @Override
    public boolean supports(Environment env, DestinationType type) {
        return env == Environment.LIVE && type == DestinationType.PREMISAVE_WALLET;
    }

    @Override
    public Resolution resolve(Environment env, DestinationType type, Map<String, Object> destination) {
        Optional<PremisaveWalletCredit.Recipient> found = wallets.find(String.valueOf(destination.get("accountRef")));
        if (found.isEmpty() || found.get().frozen()) return Resolution.notFound(SOURCE);
        String name = names.resolveNameSafely(found.get().accountNumber());
        return new Resolution(true, name, CommercialTransferService.maskName(name), SOURCE);
    }
}

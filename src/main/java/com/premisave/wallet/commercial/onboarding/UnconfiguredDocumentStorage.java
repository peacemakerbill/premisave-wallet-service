package com.premisave.wallet.commercial.onboarding;

import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;

/** Stands in until the document folder is set, so uploads fail with a clear message instead of silently dropping files. */
public class UnconfiguredDocumentStorage implements DocumentStorage {

    private static CommercialException unavailable() {
        return new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE,
                "Document storage is not configured. Set commercial.onboarding.storage.path and try again");
    }

    @Override public StoredFile upload(String fileName, String contentType, byte[] bytes) { throw unavailable(); }
    @Override public byte[] download(String fileId) { throw unavailable(); }
    @Override public void delete(String fileId) { throw unavailable(); }
    @Override public boolean available() { return false; }
}
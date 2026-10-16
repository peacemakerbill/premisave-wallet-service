package com.premisave.wallet.commercial.onboarding;

/** Where onboarding documents are kept. The only implementation that stores anything is Google Drive. */
public interface DocumentStorage {

    StoredFile upload(String fileName, String contentType, byte[] bytes);

    byte[] download(String fileId);

    /** Removes the file. A file that is already gone is not an error. */
    void delete(String fileId);

    boolean available();

    record StoredFile(String fileId, String viewLink) {}
}

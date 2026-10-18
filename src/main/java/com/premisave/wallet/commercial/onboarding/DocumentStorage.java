package com.premisave.wallet.commercial.onboarding;

/** Where onboarding documents are kept. The only implementation that stores anything is the disk folder. */
public interface DocumentStorage {

    StoredFile upload(String fileName, String contentType, byte[] bytes);

    byte[] download(String fileId);

    /** Removes the file. A file that is already gone is not an error. */
    void delete(String fileId);

    /** True when files can be written right now. A network folder that dropped off the server reports false. */
    boolean available();

    /** fileId is what is kept on the application and handed back to download and delete. */
    record StoredFile(String fileId) {}
}
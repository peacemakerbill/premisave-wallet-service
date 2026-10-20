package com.premisave.wallet.commercial.service.onboarding.storage;

import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a document store that lives on a network share and refuses every call while the share is not mounted.
 * This is what keeps a dropped VPN or NAS from sending documents to the local disk.
 */
public class MountCheckedStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(MountCheckedStorage.class);

    private final DocumentStorage delegate;
    private final NetworkMount mount;

    public MountCheckedStorage(DocumentStorage delegate, NetworkMount mount) {
        this.delegate = delegate;
        this.mount = mount;
    }

    @Override
    public StoredFile upload(String fileName, String contentType, byte[] bytes) {
        requireMounted();
        return delegate.upload(fileName, contentType, bytes);
    }

    @Override
    public byte[] download(String fileId) {
        requireMounted();
        return delegate.download(fileId);
    }

    @Override
    public void delete(String fileId) {
        requireMounted();
        delegate.delete(fileId);
    }

    @Override
    public boolean exists(String fileId) {
        requireMounted();
        return delegate.exists(fileId);
    }

    @Override
    public boolean available() {
        return mount.mounted() && delegate.available();
    }

    private void requireMounted() {
        String problem = mount.problem();
        if (problem != null) {
            log.warn("Document storage refused: {}", problem);
            throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE,
                    "Document storage is not reachable right now. Please try again shortly");
        }
    }
}
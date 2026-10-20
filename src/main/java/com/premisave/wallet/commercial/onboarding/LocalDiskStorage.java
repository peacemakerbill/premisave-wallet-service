package com.premisave.wallet.commercial.onboarding;

import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Keeps onboarding documents in a folder on disk. The folder can be a local directory or a network share
 * (a Synology or any NAS) that the operating system has mounted at a path, so the service only ever sees a path.
 * For a share, NETWORK mode wraps this class in MountCheckedStorage, which refuses calls while the share is not mounted.
 *
 * Files are grouped by month, for example 2026-10/onb_x_TAX_COMPLIANCE_doc_x_pin.pdf. The file id kept on
 * the application is that relative path. Ids are checked against a strict pattern before they touch the disk, so
 * a stored or supplied id can never point outside the folder.
 *
 * The folder itself is never created here. If a network share is not mounted, creating the folder would quietly
 * store documents on the local disk instead, so the folder must exist and be writable or uploads are refused.
 */
public class LocalDiskStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskStorage.class);
    private static final Pattern ID = Pattern.compile("^[0-9]{4}-[0-9]{2}/[A-Za-z0-9_][A-Za-z0-9._-]{0,199}$");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final Path root;
    private final Clock clock;

    public LocalDiskStorage(Path root) {
        this(root, Clock.systemUTC());
    }

    LocalDiskStorage(Path root, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
    }

    public Path root() {
        return root;
    }

    @Override
    public boolean available() {
        return Files.isDirectory(root) && Files.isWritable(root);
    }

    @Override
    public boolean exists(String fileId) {
        if (!Files.isDirectory(root)) {
            log.warn("Document folder {} is missing", root);
            throw unreachable();
        }
        if (fileId == null || !ID.matcher(fileId).matches() || fileId.contains("..")) return false;
        return Files.isRegularFile(root.resolve(fileId).normalize());
    }

    @Override
    public StoredFile upload(String fileName, String contentType, byte[] bytes) {
        requireAvailable();
        String name = diskName(fileName);
        String id = MONTH.format(clock.instant().atZone(ZoneOffset.UTC)) + "/" + name;
        Path target = resolve(id);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, "A stored document with this name already exists");
            }
            temp = Files.createTempFile(target.getParent(), ".upload-", ".part");
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target);
            }
            return new StoredFile(id);
        } catch (IOException e) {
            log.warn("Could not write document to {}: {}", root, e.getMessage());
            throw unreachable();
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("Could not remove temporary file {}: {}", temp, e.getMessage());
                }
            }
        }
    }

    @Override
    public byte[] download(String fileId) {
        Path file = resolve(fileId);
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw CommercialException.notFound("Stored document");
        } catch (IOException e) {
            log.warn("Could not read document {} from {}: {}", fileId, root, e.getMessage());
            throw unreachable();
        }
    }

    @Override
    public void delete(String fileId) {
        Path file = resolve(fileId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete document {} from {}: {}", fileId, root, e.getMessage());
            throw unreachable();
        }
    }

    /** Keeps letters, digits, dot, dash and underscore. Cut from the end so the merchant number and document id stay. */
    static String diskName(String original) {
        String name = original == null ? "" : original.trim().replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("\\.{2,}", ".");
        if (name.isEmpty() || name.startsWith(".") || name.startsWith("-")) name = "_" + name;
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    private Path resolve(String fileId) {
        if (fileId == null || !ID.matcher(fileId).matches() || fileId.contains("..")) {
            throw CommercialException.notFound("Stored document");
        }
        Path file = root.resolve(fileId).normalize();
        if (!file.startsWith(root)) {
            throw CommercialException.notFound("Stored document");
        }
        return file;
    }

    private void requireAvailable() {
        if (!available()) {
            log.warn("Document folder {} is missing or not writable", root);
            throw unreachable();
        }
    }

    private static CommercialException unreachable() {
        return new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE,
                "Document storage is not reachable right now. Please try again shortly");
    }
}
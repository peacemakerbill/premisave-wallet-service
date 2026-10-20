package com.premisave.wallet.commercial.config.properties;

import com.premisave.wallet.commercial.enums.onboarding.DocumentType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Merchant onboarding (commercial.onboarding.*).
 *
 * requiredDocuments: document types that must be uploaded before an application can be submitted. Names must be
 * DocumentType values, anything else stops the service at startup so a typo cannot lock every applicant out.
 * staffNotifyEmails: addresses told when an application is submitted or resubmitted. Leave empty to skip.
 * mailFrom: sender address of onboarding emails. Empty uses the mail account's own address.
 * roles: which staff roles may do what. Role names come from the X-Actor-Role header, compared without case.
 * storage: where uploaded documents are stored, on a local folder or a mounted network share.
 */
@ConfigurationProperties(prefix = "commercial.onboarding")
public record CommercialOnboardingProperties(
        Boolean enabled,
        List<String> requiredDocuments,
        Long maxDocumentBytes,
        Integer maxDocuments,
        List<String> staffNotifyEmails,
        String mailFrom,
        Roles roles,
        Storage storage) {

    /** Documents travel as base64 in a JSON body capped at 12 MB by the request filter, which allows files up to 8 MB. */
    public static final long MAX_DOCUMENT_BYTES_LIMIT = 8L * 1024 * 1024;

    public CommercialOnboardingProperties {
        if (enabled == null) enabled = Boolean.TRUE;
        requiredDocuments = requiredDocuments == null
                ? List.of("BUSINESS_REGISTRATION", "TAX_COMPLIANCE", "DIRECTOR_ID")
                : requiredDocuments.stream().filter(s -> s != null && !s.isBlank())
                        .map(s -> s.trim().toUpperCase(Locale.ROOT)).toList();
        for (String type : requiredDocuments) {
            if (java.util.Arrays.stream(DocumentType.values()).noneMatch(t -> t.name().equals(type))) {
                throw new IllegalArgumentException("commercial.onboarding.required-documents has an unknown type '" + type
                        + "'. Use any of " + java.util.Arrays.toString(DocumentType.values()));
            }
        }
        if (maxDocumentBytes == null || maxDocumentBytes < 1024) maxDocumentBytes = 5L * 1024 * 1024;
        if (maxDocumentBytes > MAX_DOCUMENT_BYTES_LIMIT) maxDocumentBytes = MAX_DOCUMENT_BYTES_LIMIT;
        if (maxDocuments == null || maxDocuments < 1) maxDocuments = 20;
        staffNotifyEmails = staffNotifyEmails == null ? List.of()
                : staffNotifyEmails.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        mailFrom = mailFrom == null || mailFrom.isBlank() ? null : mailFrom.trim();
        if (roles == null) roles = new Roles(null, null, null, null, null);
        if (storage == null) storage = new Storage(null, null, null);
    }

    /**
     * review: start a review, add notes, record a verdict on a document.
     * returnForCorrection: send an application back with items to fix.
     * approve: approve an application, which switches production on.
     * reject: reject an application.
     * reopen: bring a rejected application back into review.
     */
    public record Roles(List<String> review, List<String> returnForCorrection, List<String> approve,
                        List<String> reject, List<String> reopen) {
        public Roles {
            review = normalise(review, List.of("FINANCE", "OPERATIONS", "ADMIN"));
            returnForCorrection = normalise(returnForCorrection, List.of("FINANCE", "OPERATIONS", "ADMIN"));
            approve = normalise(approve, List.of("FINANCE", "ADMIN"));
            reject = normalise(reject, List.of("FINANCE", "OPERATIONS", "ADMIN"));
            reopen = normalise(reopen, List.of("ADMIN"));
        }

        private static List<String> normalise(List<String> in, List<String> fallback) {
            if (in == null) return fallback;
            return in.stream().filter(s -> s != null && !s.isBlank()).map(s -> s.trim().toUpperCase(Locale.ROOT)).toList();
        }
    }

    /**
     * Where documents are stored (STORAGE_* in .env). The service writes to a folder and never opens a network
     * connection itself, so a network location is used by mounting it on the server first (the operating system
     * handles the address, user name, password and VPN).
     *
     * mode: LOCAL keeps documents in the folder given by path (STORAGE_ROOT). NETWORK keeps them in a folder on a
     * share that is mounted at network.mountPath. Default LOCAL. Any other value stops the service at startup.
     * path: the folder for LOCAL mode. The old COMMERCIAL_ONBOARDING_STORAGE_PATH still works as a fallback.
     * network: used only when mode is NETWORK.
     */
    public record Storage(String mode, String path, Network network) {
        public static final String LOCAL = "LOCAL";
        public static final String NETWORK = "NETWORK";

        public Storage {
            mode = mode == null || mode.isBlank() ? LOCAL : mode.trim().toUpperCase(Locale.ROOT);
            if (!LOCAL.equals(mode) && !NETWORK.equals(mode)) {
                throw new IllegalArgumentException("commercial.onboarding.storage.mode (STORAGE_MODE) must be LOCAL or NETWORK, not '"
                        + mode + "'");
            }
            path = path == null || path.isBlank() ? null : path.trim();
            if (network == null) network = new Network(null, null, null, null, null, null);
        }

        public boolean networkMode() {
            return NETWORK.equals(mode);
        }

        /** The folder documents go into, or null when the chosen mode has not been given a folder yet. */
        public java.nio.file.Path folder() {
            if (!networkMode()) return path == null ? null : java.nio.file.Path.of(path);
            if (network.mountPath() == null) return null;
            java.nio.file.Path mount = java.nio.file.Path.of(network.mountPath());
            return network.directory() == null ? mount : mount.resolve(network.directory());
        }

        public boolean configured() {
            return folder() != null;
        }
    }

    /**
     * A share that the operating system has mounted on this server.
     *
     * protocol: SMB (Windows share, Synology, Samba), NFS, SFTP (sshfs), WEBDAV or OTHER. Used to confirm the mount is
     * the right kind of mount. Default SMB.
     * host, share: optional. When set, the mount's source must contain both, so a different server or share mounted
     * at the same place is refused. Use the same host name or address that /etc/fstab uses.
     * mountPath: the local folder the share is mounted on, for example /mnt/nas. Required in NETWORK mode.
     * directory: sub folder on the share where documents go, for example premisave-wallet. Relative, no "..".
     * requireMount: true (default) refuses every read and write unless mountPath is really a mount, so a dropped VPN
     * or share never makes documents land on the local disk. Set false only where mounts cannot be checked.
     */
    public record Network(String protocol, String host, String share, String mountPath, String directory,
                          Boolean requireMount) {
        private static final java.util.Set<String> PROTOCOLS = java.util.Set.of("SMB", "NFS", "SFTP", "WEBDAV", "OTHER");

        public Network {
            protocol = protocol == null || protocol.isBlank() ? "SMB" : protocol.trim().toUpperCase(Locale.ROOT);
            if (!PROTOCOLS.contains(protocol)) {
                throw new IllegalArgumentException("commercial.onboarding.storage.network.protocol (STORAGE_NETWORK_PROTOCOL) must be one of "
                        + new java.util.TreeSet<>(PROTOCOLS) + ", not '" + protocol + "'");
            }
            host = blankToNull(host);
            share = blankToNull(share);
            mountPath = blankToNull(mountPath);
            directory = cleanDirectory(directory);
            if (requireMount == null) requireMount = Boolean.TRUE;
        }

        private static String blankToNull(String v) {
            return v == null || v.isBlank() ? null : v.trim();
        }

        private static String cleanDirectory(String raw) {
            String d = blankToNull(raw);
            if (d == null) return null;
            d = d.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
            if (d.isEmpty()) return null;
            for (String part : d.split("/")) {
                if (part.equals("..") || part.equals(".")) {
                    throw new IllegalArgumentException("commercial.onboarding.storage.network.directory (STORAGE_NETWORK_DIRECTORY) "
                            + "must be a plain sub folder name such as premisave-wallet, not '" + raw + "'");
                }
            }
            return d;
        }
    }

    public boolean allows(List<String> allowedRoles, String role) {
        return role != null && allowedRoles.contains(role.trim().toUpperCase(Locale.ROOT));
    }
}
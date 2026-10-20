package com.premisave.wallet.commercial.onboarding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks that a folder really is a mounted network share (Linux). The service does not mount anything itself: the
 * operating system mounts the share (fstab, systemd, a VPN script) and this class only confirms the mount is there.
 *
 * Without the check, a share that dropped off (VPN down, NAS rebooting) leaves an empty ordinary folder at the mount
 * path, and documents would quietly be written to the local disk. With it, the service refuses instead.
 *
 * The answer comes from /proc/self/mountinfo, so it cannot be fooled by a folder that merely exists. When that file
 * cannot be read (macOS, Windows), the mount cannot be confirmed and the caller is told so.
 */
public class NetworkMount {

    private static final Path MOUNTINFO = Path.of("/proc/self/mountinfo");

    private final Path mountPoint;
    private final String protocol;
    private final String host;
    private final String share;
    private final Path mountInfo;

    public NetworkMount(Path mountPoint, String protocol, String host, String share) {
        this(mountPoint, protocol, host, share, MOUNTINFO);
    }

    NetworkMount(Path mountPoint, String protocol, String host, String share, Path mountInfo) {
        this.mountPoint = mountPoint.toAbsolutePath().normalize();
        this.protocol = protocol == null ? "OTHER" : protocol;
        this.host = host;
        this.share = share;
        this.mountInfo = mountInfo;
    }

    public Path mountPoint() {
        return mountPoint;
    }

    /** Null when the share is mounted as configured, otherwise a sentence saying what is wrong. */
    public String problem() {
        List<String> lines;
        try {
            lines = Files.readAllLines(mountInfo);
        } catch (IOException | RuntimeException e) {
            return "Cannot read " + mountInfo + " to confirm the share is mounted. "
                    + "Set STORAGE_NETWORK_REQUIRE_MOUNT=false on systems that cannot report mounts";
        }
        String[] found = find(lines);
        if (found != null && found[0].equals("autofs")) {
            // x-systemd.automount only mounts the share when something looks inside the folder. Look, then check again.
            touch();
            try {
                found = find(Files.readAllLines(mountInfo));
            } catch (IOException | RuntimeException e) {
                return "Cannot read " + mountInfo + " to confirm the share is mounted";
            }
        }
        if (found == null) {
            return mountPoint + " is not a mount point, so the share is not mounted";
        }
        String fsType = found[0];
        String source = found[1];
        if (!typeMatches(fsType)) {
            return mountPoint + " is mounted as " + fsType + ", which is not a " + protocol + " share";
        }
        if (host != null && !contains(source, host)) {
            return mountPoint + " is mounted from " + source + ", not from host " + host;
        }
        if (share != null && !contains(source, share)) {
            return mountPoint + " is mounted from " + source + ", not from share " + share;
        }
        return null;
    }

    /** The file system type and source of the last mount at the mount point, or null when nothing is mounted there. */
    private String[] find(List<String> lines) {
        Path wanted = real(mountPoint);
        String[] found = null;
        for (String line : lines) {
            String[] t = line.trim().split("\\s+");
            int dash = indexOf(t, "-");
            if (dash < 0 || t.length < dash + 3 || t.length < 6) continue;
            if (real(Path.of(unescape(t[4]))).equals(wanted)) {
                found = new String[] {t[dash + 1], unescape(t[dash + 2])};   // a later line over the same place wins
            }
        }
        return found;
    }

    private void touch() {
        try (var ignored = Files.list(mountPoint)) {
            ignored.findFirst();
        } catch (IOException | RuntimeException e) {
            // nothing to do: the check that follows reports what is mounted
        }
    }

    public boolean mounted() {
        return problem() == null;
    }

    private boolean typeMatches(String fsType) {
        String t = fsType.toLowerCase(Locale.ROOT);
        return switch (protocol) {
            case "SMB" -> Set.of("cifs", "smb3", "smbfs").contains(t);
            case "NFS" -> t.startsWith("nfs");
            case "SFTP" -> t.equals("fuse.sshfs");
            case "WEBDAV" -> t.startsWith("davfs") || t.startsWith("fuse");
            default -> true;
        };
    }

    private static boolean contains(String source, String part) {
        return source.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private static int indexOf(String[] t, String v) {
        for (int i = 0; i < t.length; i++) if (t[i].equals(v)) return i;
        return -1;
    }

    /** mountinfo writes a space as \040, a tab as \011 and so on. */
    static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 3 < s.length() && s.substring(i + 1, i + 4).matches("[0-7]{3}")) {
                out.append((char) Integer.parseInt(s.substring(i + 1, i + 4), 8));
                i += 3;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
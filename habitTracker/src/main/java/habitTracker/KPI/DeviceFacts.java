package habitTracker.KPI;

import java.util.List;

/**
 * What the hub knows about one target device when a collector is written for it (runner info + credentials).
 * os is Go runtime.GOOS (windows, linux, darwin), null when an older runner did not report it.
 * managers = package managers found on that device's PATH (relay, winget, brew, flatpak, pip).
 */
public record DeviceFacts(String deviceId, String name, String os, List<String> managers, List<CredentialRef> credentials) {
    public DeviceFacts {
        managers = managers == null ? List.of() : List.copyOf(managers);
        credentials = credentials == null ? List.of() : List.copyOf(credentials);
    }
}

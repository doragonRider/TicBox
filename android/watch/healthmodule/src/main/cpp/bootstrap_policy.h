#pragma once
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

namespace healthmodule {
constexpr const char *kTarget = "com.mobvoi.wear.health.at";
constexpr const char *kAlertTarget = "com.mobvoi.wear.refsysui";
constexpr size_t kMaxDexBytes = 2 * 1024 * 1024;

// SystemUI gets the extension unless it was turned off, which leaves a marker. Anything under that name counts, and so
// does a look at it that fails for any reason but its absence, so a damaged marker never turns the extension back on.
inline bool alert_opted_out(int stat_result, int error) { return stat_result == 0 || error != ENOENT; }

// nice_name alone is insufficient: require the actual per-user private app directory and UID.
inline bool package_allowed(const char *target, const char *process, const char *directory, int uid,
                            bool denylisted, bool child_zygote) {
    if (!process || !directory || uid < 10000 || uid % 100000 >= 99000 ||
        uid % 100000 < 10000 || denylisted || child_zygote || strcmp(process, target)) return false;
    constexpr const char *legacy = "/data/data/";
    if (uid < 100000 && !strncmp(directory, legacy, strlen(legacy)) && !strcmp(directory + strlen(legacy), target)) return true;
    unsigned user = 0; int consumed = 0;
    // An app that defaults to device-protected storage, as system UIs do, has its directory in user_de.
    bool credential = sscanf(directory, "/data/user/%u/%n", &user, &consumed) == 1 && consumed > 0;
    if (!credential) { consumed = 0; if (sscanf(directory, "/data/user_de/%u/%n", &user, &consumed) != 1 || consumed <= 0) return false; }
    return user == static_cast<unsigned>(uid / 100000) && !strcmp(directory + consumed, target);
}
inline bool target_allowed(const char *process, const char *directory, int uid,
                           bool denylisted, bool child_zygote) {
    return package_allowed(kTarget, process, directory, uid, denylisted, child_zygote);
}
inline bool alert_target_allowed(const char *process, const char *directory, int uid,
                                 bool denylisted, bool child_zygote) {
    // The experimental socket/control is deliberately scoped to the primary watch user.
    return uid < 100000 && package_allowed(kAlertTarget, process, directory, uid, denylisted, child_zygote);
}

// Only for naming a refusal in the log: every other process in the system is refused silently.
inline bool is_target_name(const char *process) {
    return process && (!strcmp(process, kTarget) || !strcmp(process, kAlertTarget));
}

inline bool dex_header_valid(const unsigned char *data, size_t length) {
    return data && length >= 112 && length <= kMaxDexBytes &&
        !memcmp(data, "dex\n", 4) && data[4] >= '0' && data[4] <= '9' &&
        data[5] >= '0' && data[5] <= '9' && data[6] >= '0' && data[6] <= '9' && data[7] == 0;
}
} // namespace healthmodule

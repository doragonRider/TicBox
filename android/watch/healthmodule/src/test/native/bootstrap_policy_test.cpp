#include <assert.h>
#include <string.h>
#include "bootstrap_policy.h"
int main() {
    using namespace healthmodule;
    assert(target_allowed(kTarget, "/data/user/0/com.mobvoi.wear.health.at", 10067, false, false));
    assert(target_allowed(kTarget, "/data/data/com.mobvoi.wear.health.at", 10067, false, false));
    assert(target_allowed(kTarget, "/data/user/10/com.mobvoi.wear.health.at", 1010067, false, false));
    assert(!target_allowed(kTarget, "/data/user/0/com.mobvoi.wear.health.at", 1010067, false, false));
    assert(target_allowed(kTarget, "/data/user_de/0/com.mobvoi.wear.health.at", 10067, false, false));
    assert(!target_allowed(kTarget, "/data/user_de/0/com.mobvoi.wear.health.at", 1010067, false, false));
    assert(!target_allowed(kTarget, "/data/user_de/0/com.mobvoi.wear.health.at.fake", 10067, false, false));
    assert(!target_allowed(kTarget, "/data/user_ce/0/com.mobvoi.wear.health.at", 10067, false, false));
    assert(!target_allowed("com.mobvoi.wear.health.at:remote", "/data/user/0/com.mobvoi.wear.health.at", 10067, false, false));
    assert(!target_allowed(kTarget, "/data/user/0/com.mobvoi.wear.health.at.fake", 10067, false, false));
    assert(!target_allowed(kTarget, "/data/user/0/com.mobvoi.wear.health.at", 0, false, false));
    assert(!target_allowed(kTarget, "/data/user/0/com.mobvoi.wear.health.at", 99067, false, false));
    assert(!target_allowed(kTarget, "/data/user/0/com.mobvoi.wear.health.at", 10067, true, false));
    assert(!target_allowed(kTarget, "/data/user/0/com.mobvoi.wear.health.at", 10067, false, true));
    assert(!target_allowed(nullptr, nullptr, 10067, false, false));
    assert(!target_allowed(kAlertTarget, "/data/user/0/com.mobvoi.wear.refsysui", 10068, false, false));
    assert(alert_target_allowed(kAlertTarget, "/data/user/0/com.mobvoi.wear.refsysui", 10068, false, false));
    assert(alert_target_allowed(kAlertTarget, "/data/data/com.mobvoi.wear.refsysui", 10068, false, false));
    assert(alert_target_allowed(kAlertTarget, "/data/user_de/0/com.mobvoi.wear.refsysui", 10068, false, false));
    assert(!alert_target_allowed(kAlertTarget, "/data/user_de/10/com.mobvoi.wear.refsysui", 1010068, false, false));
    assert(!alert_target_allowed(kTarget, "/data/user/0/com.mobvoi.wear.health.at", 10067, false, false));
    assert(is_target_name(kTarget) && is_target_name(kAlertTarget));
    assert(!is_target_name("com.mobvoi.wear.health.at:remote") && !is_target_name(nullptr));
    assert(!alert_target_allowed("com.mobvoi.wear.refsysui:remote", "/data/user/0/com.mobvoi.wear.refsysui", 10068, false, false));
    assert(!alert_target_allowed(kAlertTarget, "/data/user/0/com.mobvoi.wear.refsysui", 1000, false, false));
    assert(!alert_target_allowed(kAlertTarget, "/data/user/0/com.mobvoi.wear.refsysui", 99068, false, false));
    assert(!alert_target_allowed(kAlertTarget, "/data/user/0/com.mobvoi.wear.refsysui", 10068, true, false));
    assert(!alert_target_allowed(kAlertTarget, "/data/user/0/com.mobvoi.wear.refsysui", 10068, false, true));
    assert(!alert_target_allowed(kAlertTarget, "/data/user/10/com.mobvoi.wear.refsysui", 1010068, false, false));
    assert(!alert_opted_out(-1, ENOENT));
    assert(alert_opted_out(0, 0));
    assert(alert_opted_out(-1, EACCES)); assert(alert_opted_out(-1, ELOOP)); assert(alert_opted_out(-1, EIO));
    unsigned char dex[112]{}; memcpy(dex, "dex\n039", 8);
    assert(dex_header_valid(dex, sizeof(dex)));
    assert(!dex_header_valid(dex, 111)); assert(!dex_header_valid(dex, kMaxDexBytes + 1));
    dex[7] = 'a'; assert(!dex_header_valid(dex, sizeof(dex)));
}

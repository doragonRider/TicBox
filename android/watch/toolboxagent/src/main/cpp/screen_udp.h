#ifndef TICBOX_SCREEN_UDP_H
#define TICBOX_SCREEN_UDP_H
#include <stdint.h>
/* Datagrams per send: one FEC block of 16 data and 4 parity shards. */
#define TW_SCREEN_MAX_BATCH 20
int64_t tw_screen_open(int fd);
int tw_screen_send(int64_t id, const uint8_t *bytes, const int *lengths, int count,
    const uint8_t *address, int address_length, int scope, int port);
typedef struct { int gso_calls, gso_datagrams, gso_disabled; } TwScreenSendStats;
int tw_screen_send_measured(int64_t id, const uint8_t *bytes, const int *lengths, int count,
    const uint8_t *address, int address_length, int scope, int port, TwScreenSendStats *stats);
void tw_screen_close(int64_t id);
#endif

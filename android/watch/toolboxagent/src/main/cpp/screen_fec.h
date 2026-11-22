#ifndef TICBOX_SCREEN_FEC_H
#define TICBOX_SCREEN_FEC_H
#include <stdint.h>
int tw_fec_mode(void);
void tw_fec_encode(const uint8_t *data, int count, int length, uint8_t *parity, int simd);
#endif

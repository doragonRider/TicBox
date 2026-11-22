#include <stdint.h>
#include <arm_neon.h>
/** Original GF(256) multiply-add with two nibble lookup tables. No third-party SIMD code. */
void tw_fec_neon(const uint8_t *in, uint8_t *out, int length, const uint8_t *table) {
    uint8_t hi[16]; for (int i = 0; i < 16; ++i) hi[i] = table[i << 4];
    int i = 0;
#if defined(__aarch64__)
    uint8x16_t low = vld1q_u8(table), high = vld1q_u8(hi), mask = vdupq_n_u8(15);
    for (; i + 16 <= length; i += 16) {
        uint8x16_t v = vld1q_u8(in + i);
        uint8x16_t p = veorq_u8(vqtbl1q_u8(low, vandq_u8(v, mask)), vqtbl1q_u8(high, vshrq_n_u8(v, 4)));
        vst1q_u8(out + i, veorq_u8(vld1q_u8(out + i), p));
    }
#else
    uint8x8x2_t low = {{vld1_u8(table), vld1_u8(table + 8)}}, high = {{vld1_u8(hi), vld1_u8(hi + 8)}};
    uint8x8_t mask = vdup_n_u8(15);
    for (; i + 8 <= length; i += 8) {
        uint8x8_t v = vld1_u8(in + i);
        uint8x8_t p = veor_u8(vtbl2_u8(low, vand_u8(v, mask)), vtbl2_u8(high, vshr_n_u8(v, 4)));
        vst1_u8(out + i, veor_u8(vld1_u8(out + i), p));
    }
#endif
    for (; i < length; ++i) out[i] ^= table[in[i]];
}

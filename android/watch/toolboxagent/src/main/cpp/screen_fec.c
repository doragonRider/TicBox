#include "screen_fec.h"
#include <pthread.h>
#include <string.h>
#if defined(__aarch64__) || defined(__arm__)
#include <sys/auxv.h>
#if defined(__arm__)
#include <asm/hwcap.h>
#endif
void tw_fec_neon(const uint8_t *, uint8_t *, int, const uint8_t *);
#elif defined(__i386__) || defined(__x86_64__)
#include <tmmintrin.h>
#endif

static uint8_t products[256][256], coefficients[4][16];
static pthread_once_t initialized = PTHREAD_ONCE_INIT;
static uint8_t multiply(unsigned a, unsigned b) {
    unsigned result = 0;
    while (b) { if (b & 1) result ^= a; a <<= 1; if (a & 256) a ^= 0x11d; b >>= 1; }
    return (uint8_t)result;
}
static void initialize(void) {
    uint8_t inverse[256] = {0};
    for (int a = 0; a < 256; ++a) for (int b = 0; b < 256; ++b) {
        products[a][b] = multiply((unsigned)a, (unsigned)b);
        if (products[a][b] == 1) inverse[a] = (uint8_t)b;
    }
    for (int r = 0; r < 4; ++r) for (int c = 0; c < 16; ++c) coefficients[r][c] = inverse[r ^ (c + 4)];
}
#if defined(__i386__) || defined(__x86_64__)
__attribute__((target("ssse3")))
static void add_ssse3(const uint8_t *in, uint8_t *out, int length, const uint8_t *table) {
    uint8_t hi[16]; for (int i = 0; i < 16; ++i) hi[i] = table[i << 4];
    __m128i low = _mm_loadu_si128((const __m128i *)table), high = _mm_loadu_si128((const __m128i *)hi);
    __m128i mask = _mm_set1_epi8(15); int i = 0;
    for (; i + 16 <= length; i += 16) {
        __m128i value = _mm_loadu_si128((const __m128i *)(in + i));
        __m128i product = _mm_xor_si128(_mm_shuffle_epi8(low, _mm_and_si128(value, mask)),
            _mm_shuffle_epi8(high, _mm_and_si128(_mm_srli_epi16(value, 4), mask)));
        _mm_storeu_si128((__m128i *)(out + i), _mm_xor_si128(_mm_loadu_si128((const __m128i *)(out + i)), product));
    }
    for (; i < length; ++i) out[i] ^= table[in[i]];
}
#endif
int tw_fec_mode(void) {
    pthread_once(&initialized, initialize); // Prewarm before advertising the socket.
#if defined(__aarch64__)
    return 1;
#elif defined(__arm__)
    return (getauxval(AT_HWCAP) & HWCAP_NEON) ? 1 : 0;
#elif defined(__i386__) || defined(__x86_64__)
    __builtin_cpu_init();
    return __builtin_cpu_supports("ssse3") ? 2 : 0;
#else
    return 0;
#endif
}
void tw_fec_encode(const uint8_t *data, int count, int length, uint8_t *parity, int simd) {
    pthread_once(&initialized, initialize);
    int rows = (count + 3) / 4, mode = simd ? tw_fec_mode() : 0;
    memset(parity, 0, (size_t)rows * length);
    for (int r = 0; r < rows; ++r) for (int c = 0; c < count; ++c) {
        const uint8_t *in = data + c * length, *table = products[coefficients[r][c]];
        uint8_t *out = parity + r * length;
#if defined(__aarch64__) || defined(__arm__)
        if (mode == 1) { tw_fec_neon(in, out, length, table); continue; }
#elif defined(__i386__) || defined(__x86_64__)
        if (mode == 2) { add_ssse3(in, out, length, table); continue; }
#else
        (void)mode;
#endif
        for (int i = 0; i < length; ++i) out[i] ^= table[in[i]];
    }
}

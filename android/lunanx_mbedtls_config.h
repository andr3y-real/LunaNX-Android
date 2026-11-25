#pragma once

/* The bundled config was tuned for the Switch. Use Android's entropy source. */
#undef MBEDTLS_TEST_NULL_ENTROPY
#undef MBEDTLS_NO_PLATFORM_ENTROPY
#define MBEDTLS_TIMING_C
#define MBEDTLS_NET_C
#ifndef MBEDTLS_SSL_DTLS_SRTP
#define MBEDTLS_SSL_DTLS_SRTP
#endif

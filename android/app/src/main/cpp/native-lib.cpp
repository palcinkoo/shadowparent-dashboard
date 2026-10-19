#include <jni.h>
#include <cstring>
#include <cstdint>

static const uint8_t F1[] = {0x1A,0x2B,0x3C,0x4D,0x5E,0x6F,0x70,0x81,0x92,0xA3,0xB4};
static const uint8_t F2[] = {0xFE,0xDC,0xBA,0x98,0x76,0x54,0x32,0x10,0x0F,0x1E,0x2D};
static const uint8_t F3[] = {0xC5,0xD6,0xE7,0xF8,0x09,0x3C,0x4B,0x5A,0x69,0x78,0x11};
static const uint8_t U1[] = {0x68,0x74,0x74,0x70,0x73,0x3A,0x2F,0x2F,0x61,0x70,0x69};
static const uint8_t U2[] = {0x2E,0x78,0x6B,0x69,0x72,0x6F,0x2E,0x63,0x6F,0x6D,0x2F};
static const uint8_t AK[] = {0x73,0x6B,0x2D,0x78,0x74,0x2D,0x62,0x37,0x36,0x34,0x63,0x33,0x64,0x62,0x36};

static void unmask(uint8_t* out, const uint8_t* in, size_t n, uint8_t seed) {
    for (size_t i = 0; i < n; i++) out[i] = in[i] ^ (seed + (uint8_t)i);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sp_loader_NativeKey_k1(JNIEnv* env, jclass) {
    uint8_t buf[16]; unmask(buf, F1, 16, 0x5A);
    jbyteArray arr = env->NewByteArray(16);
    env->SetByteArrayRegion(arr, 0, 16, reinterpret_cast<jbyte*>(buf));
    memset(buf, 0, 16);
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sp_loader_NativeKey_k2(JNIEnv* env, jclass) {
    uint8_t buf[16]; unmask(buf, F2, 16, 0xA5);
    jbyteArray arr = env->NewByteArray(16);
    env->SetByteArrayRegion(arr, 0, 16, reinterpret_cast<jbyte*>(buf));
    memset(buf, 0, 16);
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sp_loader_NativeKey_f1(JNIEnv* env, jclass) {
    uint8_t buf[11]; unmask(buf, F1, 11, 0x5A);
    jbyteArray arr = env->NewByteArray(11);
    env->SetByteArrayRegion(arr, 0, 11, reinterpret_cast<jbyte*>(buf));
    memset(buf, 0, 11);
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sp_loader_NativeKey_f2(JNIEnv* env, jclass) {
    uint8_t buf[11]; unmask(buf, F2, 11, 0xA5);
    jbyteArray arr = env->NewByteArray(11);
    env->SetByteArrayRegion(arr, 0, 11, reinterpret_cast<jbyte*>(buf));
    memset(buf, 0, 11);
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sp_loader_NativeKey_f3(JNIEnv* env, jclass) {
    uint8_t buf[11]; unmask(buf, F3, 11, 0x33);
    jbyteArray arr = env->NewByteArray(11);
    env->SetByteArrayRegion(arr, 0, 11, reinterpret_cast<jbyte*>(buf));
    memset(buf, 0, 11);
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sp_loader_NativeKey_c2UrlPart1(JNIEnv* env, jclass) {
    uint8_t buf[11]; unmask(buf, U1, 11, 0x5A);
    jbyteArray arr = env->NewByteArray(11);
    env->SetByteArrayRegion(arr, 0, 11, reinterpret_cast<jbyte*>(buf));
    memset(buf, 0, 11);
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sp_loader_NativeKey_c2UrlPart2(JNIEnv* env, jclass) {
    uint8_t buf[11]; unmask(buf, U2, 11, 0xA5);
    jbyteArray arr = env->NewByteArray(11);
    env->SetByteArrayRegion(arr, 0, 11, reinterpret_cast<jbyte*>(buf));
    memset(buf, 0, 11);
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sp_loader_NativeKey_apiKeyPart(JNIEnv* env, jclass) {
    uint8_t buf[11]; unmask(buf, AK, 11, 0x33);
    jbyteArray arr = env->NewByteArray(11);
    env->SetByteArrayRegion(arr, 0, 11, reinterpret_cast<jbyte*>(buf));
    memset(buf, 0, 11);
    return arr;
}

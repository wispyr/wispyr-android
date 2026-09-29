#include <jni.h>
#include <cstdint>
#include <climits>
#include <vector>
#include <openssl/aead.h>
#include <openssl/mem.h>

namespace {

struct Bytes {
    JNIEnv *env;
    jbyteArray array;
    jbyte *data;
    jsize size;

    Bytes(JNIEnv *env, jbyteArray array) : env(env), array(array), data(nullptr), size(0) {
        if (array != nullptr) {
            size = env->GetArrayLength(array);
            data = env->GetByteArrayElements(array, nullptr);
        }
    }

    ~Bytes() {
        if (data != nullptr) {
            env->ReleaseByteArrayElements(array, data, JNI_ABORT);
        }
    }

    bool valid() const {
        return array != nullptr && data != nullptr;
    }
};

jbyteArray crypt(JNIEnv *env, bool encrypt, jbyteArray keyArray, jbyteArray nonceArray,
                 jbyteArray aadArray, jbyteArray inputArray) {
    Bytes key(env, keyArray);
    Bytes nonce(env, nonceArray);
    Bytes aad(env, aadArray);
    Bytes input(env, inputArray);
    if (!key.valid() || !nonce.valid() || !input.valid() || key.size != 32 || nonce.size != 12) {
        return nullptr;
    }
    if (aadArray != nullptr && !aad.valid()) {
        return nullptr;
    }

    EVP_AEAD_CTX ctx;
    if (!EVP_AEAD_CTX_init(&ctx, EVP_aead_aes_256_gcm_siv(),
                           reinterpret_cast<const uint8_t *>(key.data), key.size,
                           EVP_AEAD_DEFAULT_TAG_LENGTH, nullptr)) {
        return nullptr;
    }

    size_t capacity = encrypt
            ? static_cast<size_t>(input.size) + EVP_AEAD_max_overhead(EVP_aead_aes_256_gcm_siv())
            : static_cast<size_t>(input.size);
    std::vector<uint8_t> output(capacity);
    size_t outputLength = 0;
    const uint8_t *aadData = aad.valid() ? reinterpret_cast<const uint8_t *>(aad.data) : nullptr;
    size_t aadLength = aad.valid() ? static_cast<size_t>(aad.size) : 0;

    int ok;
    if (encrypt) {
        ok = EVP_AEAD_CTX_seal(&ctx, output.data(), &outputLength, output.size(),
                               reinterpret_cast<const uint8_t *>(nonce.data), nonce.size,
                               reinterpret_cast<const uint8_t *>(input.data), input.size,
                               aadData, aadLength);
    } else {
        ok = EVP_AEAD_CTX_open(&ctx, output.data(), &outputLength, output.size(),
                               reinterpret_cast<const uint8_t *>(nonce.data), nonce.size,
                               reinterpret_cast<const uint8_t *>(input.data), input.size,
                               aadData, aadLength);
    }
    EVP_AEAD_CTX_cleanup(&ctx);
    if (!ok || outputLength > static_cast<size_t>(INT32_MAX)) {
        OPENSSL_cleanse(output.data(), output.size());
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(outputLength));
    if (result != nullptr && outputLength != 0) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(outputLength),
                                reinterpret_cast<const jbyte *>(output.data()));
    }
    OPENSSL_cleanse(output.data(), output.size());
    return result;
}

} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_wispyr_messenger_security_MediaAead_seal(
        JNIEnv *env, jclass, jbyteArray key, jbyteArray nonce, jbyteArray aad, jbyteArray plaintext) {
    return crypt(env, true, key, nonce, aad, plaintext);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_wispyr_messenger_security_MediaAead_open(
        JNIEnv *env, jclass, jbyteArray key, jbyteArray nonce, jbyteArray aad, jbyteArray ciphertext) {
    return crypt(env, false, key, nonce, aad, ciphertext);
}

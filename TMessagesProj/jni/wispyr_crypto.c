#include <jni.h>
#include <string.h>
#include "argon2.h"

JNIEXPORT jbyteArray Java_org_wispyr_messenger_security_PasscodeHasher_argon2id(JNIEnv *env, jclass clazz, jbyteArray password, jbyteArray salt, jint tCost, jint mCostKiB, jint parallelism, jint hashLength) {
    if (password == NULL || salt == NULL || hashLength <= 0 || hashLength > 64) {
        return NULL;
    }
    jsize passwordLength = (*env)->GetArrayLength(env, password);
    jsize saltLength = (*env)->GetArrayLength(env, salt);
    jbyte *passwordBytes = (*env)->GetByteArrayElements(env, password, NULL);
    jbyte *saltBytes = (*env)->GetByteArrayElements(env, salt, NULL);
    if (passwordBytes == NULL || saltBytes == NULL) {
        if (passwordBytes != NULL) {
            (*env)->ReleaseByteArrayElements(env, password, passwordBytes, JNI_ABORT);
        }
        if (saltBytes != NULL) {
            (*env)->ReleaseByteArrayElements(env, salt, saltBytes, JNI_ABORT);
        }
        return NULL;
    }

    uint8_t hash[64];
    int result = argon2id_hash_raw((uint32_t) tCost, (uint32_t) mCostKiB, (uint32_t) parallelism, passwordBytes, (size_t) passwordLength, saltBytes, (size_t) saltLength, hash, (size_t) hashLength);

    memset(passwordBytes, 0, (size_t) passwordLength);
    (*env)->ReleaseByteArrayElements(env, password, passwordBytes, 0);
    (*env)->ReleaseByteArrayElements(env, salt, saltBytes, JNI_ABORT);

    if (result != ARGON2_OK) {
        memset(hash, 0, sizeof(hash));
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, hashLength);
    if (out != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, hashLength, (const jbyte *) hash);
    }
    memset(hash, 0, sizeof(hash));
    return out;
}

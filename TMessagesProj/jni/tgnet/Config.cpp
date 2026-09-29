/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#include <sys/stat.h>
#include <unistd.h>
#include <errno.h>
#include <cstring>
#include <vector>
#include <openssl/aead.h>
#include <openssl/mem.h>
#include <openssl/rand.h>
#include "Config.h"
#include "ConnectionsManager.h"
#include "FileLog.h"
#include "BuffersStorage.h"

static const uint8_t CONFIG_MAGIC[4] = {'W', 'Y', 'C', '1'};
static const size_t CONFIG_NONCE_LENGTH = 12;
static const size_t CONFIG_TAG_LENGTH = 16;
static const size_t CONFIG_HEADER_LENGTH = sizeof(CONFIG_MAGIC) + CONFIG_NONCE_LENGTH;

static uint8_t encryptionKey[32];
static bool encryptionKeySet = false;

void Config::setEncryptionKey(const uint8_t *key, size_t length) {
    if (length != sizeof(encryptionKey)) {
        return;
    }
    memcpy(encryptionKey, key, sizeof(encryptionKey));
    encryptionKeySet = true;
}

Config::Config(int32_t instance, std::string fileName) {
    instanceNum = instance;
    configName = fileName;
    configPath = ConnectionsManager::getInstance(instanceNum).currentConfigPath + fileName;
    backupPath = configPath + ".bak";
    FILE *backup = fopen(backupPath.c_str(), "rb");
    if (backup != nullptr) {
        if (LOGS_ENABLED) DEBUG_D("Config(%p, %s) backup file found %s", this, configPath.c_str(), backupPath.c_str());
        fclose(backup);
        remove(configPath.c_str());
        rename(backupPath.c_str(), configPath.c_str());
    }
}

NativeByteBuffer *Config::decryptFile(FILE *file, long fileSize) {
    if (fileSize < (long) (CONFIG_HEADER_LENGTH + CONFIG_TAG_LENGTH + 1)) {
        return nullptr;
    }
    std::vector<uint8_t> data((size_t) fileSize);
    if (fread(data.data(), sizeof(uint8_t), data.size(), file) != data.size() || memcmp(data.data(), CONFIG_MAGIC, sizeof(CONFIG_MAGIC)) != 0) {
        return nullptr;
    }
    const uint8_t *nonce = data.data() + sizeof(CONFIG_MAGIC);
    const uint8_t *cipherText = data.data() + CONFIG_HEADER_LENGTH;
    size_t cipherLength = data.size() - CONFIG_HEADER_LENGTH;
    size_t plainLength = cipherLength - CONFIG_TAG_LENGTH;

    EVP_AEAD_CTX ctx;
    if (!EVP_AEAD_CTX_init(&ctx, EVP_aead_aes_256_gcm(), encryptionKey, sizeof(encryptionKey), CONFIG_TAG_LENGTH, nullptr)) {
        return nullptr;
    }
    NativeByteBuffer *buffer = BuffersStorage::getInstance().getFreeBuffer((uint32_t) plainLength);
    size_t outLength = 0;
    int ok = EVP_AEAD_CTX_open(&ctx, buffer->bytes(), &outLength, plainLength, nonce, CONFIG_NONCE_LENGTH, cipherText, cipherLength, (const uint8_t *) configName.data(), configName.size());
    EVP_AEAD_CTX_cleanup(&ctx);
    OPENSSL_cleanse(data.data(), data.size());
    if (!ok || outLength != plainLength) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) decryption failed", this, configPath.c_str());
        buffer->reuse();
        return nullptr;
    }
    return buffer;
}

NativeByteBuffer *Config::readConfig() {
    if (!encryptionKeySet) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) encryption key is not set", this, configPath.c_str());
        return nullptr;
    }
    NativeByteBuffer *buffer = nullptr;
    FILE *file = fopen(configPath.c_str(), "rb");
    if (file != nullptr) {
        fseek(file, 0, SEEK_END);
        long fileSize = ftell(file);
        if (fseek(file, 0, SEEK_SET)) {
            if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) failed fseek to begin, reopen it", this, configPath.c_str());
            fclose(file);
            file = fopen(configPath.c_str(), "rb");
        }
        if (file != nullptr) {
            buffer = decryptFile(file, fileSize);
            if (LOGS_ENABLED) DEBUG_D("Config(%p, %s) load, fileSize = %u, ok = %d", this, configPath.c_str(), (uint32_t) fileSize, buffer != nullptr);
            fclose(file);
        }
    }
    return buffer;
}

void Config::writeConfig(NativeByteBuffer *buffer) {
    if (LOGS_ENABLED) DEBUG_D("Config(%p, %s) start write config", this, configPath.c_str());
    if (!encryptionKeySet) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) encryption key is not set, refusing to write", this, configPath.c_str());
        return;
    }
    uint32_t size = buffer->position();
    std::vector<uint8_t> encrypted(CONFIG_HEADER_LENGTH + size + CONFIG_TAG_LENGTH);
    memcpy(encrypted.data(), CONFIG_MAGIC, sizeof(CONFIG_MAGIC));
    uint8_t *nonce = encrypted.data() + sizeof(CONFIG_MAGIC);
    size_t encryptedLength = 0;
    EVP_AEAD_CTX ctx;
    bool sealed = false;
    if (RAND_bytes(nonce, CONFIG_NONCE_LENGTH) == 1 && EVP_AEAD_CTX_init(&ctx, EVP_aead_aes_256_gcm(), encryptionKey, sizeof(encryptionKey), CONFIG_TAG_LENGTH, nullptr)) {
        sealed = EVP_AEAD_CTX_seal(&ctx, encrypted.data() + CONFIG_HEADER_LENGTH, &encryptedLength, size + CONFIG_TAG_LENGTH, nonce, CONFIG_NONCE_LENGTH, buffer->bytes(), size, (const uint8_t *) configName.data(), configName.size()) == 1;
        EVP_AEAD_CTX_cleanup(&ctx);
    }
    if (!sealed) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) encryption failed", this, configPath.c_str());
        return;
    }
    size_t totalLength = CONFIG_HEADER_LENGTH + encryptedLength;

    FILE *file = fopen(configPath.c_str(), "rb");
    FILE *backup = fopen(backupPath.c_str(), "rb");
    bool error = false;
    bool hasBackupFile = false;
    if (file != nullptr) {
        if (backup == nullptr) {
            fclose(file);
            if (rename(configPath.c_str(), backupPath.c_str()) != 0) {
                if (LOGS_ENABLED) DEBUG_E("Config(%p) unable to rename file %s to backup file %s", this, configPath.c_str(), backupPath.c_str());
                error = true;
            } else {
                hasBackupFile = true;
            }
        } else {
            fclose(file);
            fclose(backup);
            remove(configPath.c_str());
        }
    } else if (backup != nullptr) {
        fclose(backup);
    }
    if (error) {
        return;
    }
    file = fopen(configPath.c_str(), "wb");
    if (file == nullptr) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) unable to open file for writing", this, configPath.c_str());
        return;
    }
    if (chmod(configPath.c_str(), 0600)) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) chmod failed", this, configPath.c_str());
    }
    if (fwrite(encrypted.data(), sizeof(uint8_t), totalLength, file) != totalLength) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) failed to write config data to file", this, configPath.c_str());
        error = true;
    }
    if (fflush(file)) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) fflush failed", this, configPath.c_str());
        error = true;
    }
    int fd = fileno(file);
    if (fd == -1) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) fileno failed", this, configPath.c_str());
        error = true;
    } else {
        if (LOGS_ENABLED) DEBUG_D("Config(%p, %s) fileno = %d", this, configPath.c_str(), fd);
    }
    if (fd != -1 && fsync(fd) == -1) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) fsync failed", this, configPath.c_str());
        error = true;
    }
    if (fclose(file)) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) fclose failed", this, configPath.c_str());
        error = true;
    }
    if (error) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) failed to write config", this, configPath.c_str());
        if (remove(configPath.c_str())) {
            if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) remove config failed", this, configPath.c_str());
        }
    } else {
        if (hasBackupFile && remove(backupPath.c_str())) {
            if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) remove backup failed, %s", this, backupPath.c_str(), strerror(errno));
        }
    }
    if (!error) {
        if (LOGS_ENABLED) DEBUG_D("Config(%p, %s) config write ok", this, configPath.c_str());
    }
}

// JNI bridge from SpeechEngine.kt to Cactus's Needle engine (needle.h).
// The engine holds one model per process and isn't thread-safe; SpeechEngine serializes calls.
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "needle.h"

static void throw_error(JNIEnv *env, const char *prefix) {
    const char *msg = needle_last_error();
    char buf[512];
    snprintf(buf, sizeof buf, "%s: %s", prefix, msg && *msg ? msg : "unknown error");
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"), buf);
}

JNIEXPORT void JNICALL
Java_app_whistleflux_engine_SpeechEngine_nativeLoad(JNIEnv *env, jclass cls, jbyteArray model) {
    jsize n = (*env)->GetArrayLength(env, model);
    jbyte *bytes = (*env)->GetByteArrayElements(env, model, NULL);
    if (!bytes) return; // OutOfMemoryError is pending
    int rc = needle_load((const unsigned char *) bytes, (unsigned long long) n);
    (*env)->ReleaseByteArrayElements(env, model, bytes, JNI_ABORT);
    if (rc < 0) throw_error(env, "Couldn't load the speech model");
}

// Returns the engine's JSON reply as UTF-8 bytes: {"text":"...","language":"en",...}
// (Bytes, not a jstring: NewStringUTF expects modified UTF-8 and mangles emoji.)
JNIEXPORT jbyteArray JNICALL
Java_app_whistleflux_engine_SpeechEngine_nativeTranscribe(JNIEnv *env, jclass cls, jfloatArray samples,
                                                      jstring language, jstring keywords) {
    jsize count = (*env)->GetArrayLength(env, samples);
    // The reply is about 4 KB per second of audio at most.
    int capacity = 64 * 1024 + count / 16000 * 4096;
    char *out = malloc((size_t) capacity);
    if (!out) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "speech output");
        return NULL;
    }
    out[0] = '\0';
    const char *lang = language ? (*env)->GetStringUTFChars(env, language, NULL) : NULL;
    const char *keys = keywords ? (*env)->GetStringUTFChars(env, keywords, NULL) : NULL;
    jfloat *pcm = (*env)->GetFloatArrayElements(env, samples, NULL);

    int rc = pcm ? needle_transcribe(pcm, count, lang, keys, 0, out, capacity) : -1;

    if (pcm) (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);
    if (lang) (*env)->ReleaseStringUTFChars(env, language, lang);
    if (keys) (*env)->ReleaseStringUTFChars(env, keywords, keys);

    jbyteArray result = NULL;
    if (rc < 0) {
        if (pcm) throw_error(env, "Transcription failed");
    } else {
        jsize len = (jsize) strnlen(out, (size_t) capacity);
        result = (*env)->NewByteArray(env, len);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, len, (const jbyte *) out);
    }
    free(out);
    return result;
}

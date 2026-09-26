/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

#include <dlfcn.h>
#if defined(__aarch64__) || defined(__arm__)
#include <shadowhook.h>
#endif
#include "dobby.h"
#include <sys/mman.h>
#include <atomic>
#include <cstdint>
#include <map>
#include <mutex>
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-value"
#pragma once

#include <context.h>
#include "macros.h"
#include "utils/jni_helper.hpp"
#include "logging.h"
#include "config.h"
#include <cassert>
#include "config_bridge.h"

namespace lspd {

[[gnu::always_inline]]
inline bool RegisterNativeMethodsInternal(JNIEnv *env,
                                          std::string_view class_name,
                                          const JNINativeMethod *methods,
                                          jint method_count) {

    auto clazz = Context::GetInstance()->FindClassFromCurrentLoader(env, class_name.data());
    if (clazz.get() == nullptr) {
        LOGF("Couldn't find class: {}", class_name.data());
        return false;
    }
    return JNI_RegisterNatives(env, clazz, methods, method_count);
}

#if defined(__cplusplus)
#define _NATIVEHELPER_JNI_MACRO_CAST(to) \
    reinterpret_cast<to>
#else
#define _NATIVEHELPER_JNI_MACRO_CAST(to) \
    (to)
#endif

#ifndef LSP_NATIVE_METHOD
#define LSP_NATIVE_METHOD(className, functionName, signature)                \
  { #functionName,                                                       \
    signature,                                                           \
    _NATIVEHELPER_JNI_MACRO_CAST(void*) (Java_org_lsposed_lspd_nativebridge_## className ## _ ## functionName) \
  }
#endif

#define JNI_START [[maybe_unused]] JNIEnv* env, [[maybe_unused]] jclass clazz

#ifndef LSP_DEF_NATIVE_METHOD
#define LSP_DEF_NATIVE_METHOD(ret, className, functionName, ...)                \
  extern "C" ret Java_org_lsposed_lspd_nativebridge_## className ## _ ## functionName (JNI_START, ##  __VA_ARGS__)
#endif

#define REGISTER_LSP_NATIVE_METHODS(class_name) \
  RegisterNativeMethodsInternal(env, GetNativeBridgeSignature() + #class_name, gMethods, arraysize(gMethods))

// ---------------------------------------------------------------------------------------
// Inline hook backend
//
// Both engines stay compiled in - Dobby everywhere, ShadowHook on arm only - and the
// choice is made once per process, before LSPlant is initialised, because LSPlant latches
// the backend on the first inline hook it installs.
// ---------------------------------------------------------------------------------------
inline constexpr int kInlineHookBackendDobby = 0;
inline constexpr int kInlineHookBackendShadowHook = 1;

inline std::atomic<int> g_inline_hook_backend{kInlineHookBackendDobby};

inline void SetInlineHookBackend(int backend) {
#if defined(__aarch64__) || defined(__arm__)
    backend = backend == kInlineHookBackendShadowHook ? kInlineHookBackendShadowHook
                                                      : kInlineHookBackendDobby;
#else
    // ShadowHook is not built for this ABI. A preference read on another device must not
    // be able to leave a process with no working engine.
    backend = kInlineHookBackendDobby;
#endif
    g_inline_hook_backend.store(backend, std::memory_order_relaxed);
    LOGD("Inline hook backend: {}",
         backend == kInlineHookBackendShadowHook ? "ShadowHook" : "Dobby");
}

#if defined(__aarch64__) || defined(__arm__)
inline std::once_flag g_shadowhook_init_once;
inline int g_shadowhook_init_result = -1;
inline std::mutex g_shadowhook_mutex;
// shadowhook_unhook wants the stub ShadowHook returned, not the target address, so the
// mapping between the two has to live here.
inline std::map<uintptr_t, void *> g_shadowhook_stubs;

inline bool shadowhookInit() {
    std::call_once(g_shadowhook_init_once, [] {
        g_shadowhook_init_result = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
        if (g_shadowhook_init_result != 0) {
            LOGE("ShadowHook init failed: {}",
                 shadowhook_to_errmsg(shadowhook_get_init_errno()));
        }
    });
    return g_shadowhook_init_result == 0;
}
#endif

inline int HookFunction(void *original, void *replace, void **backup) {
    if constexpr (isDebug) {
        Dl_info info;
        if (dladdr(original, &info))
        LOGD("Hooking {} ({}) from {} ({})",
             info.dli_sname ? info.dli_sname : "(unknown symbol)", info.dli_saddr,
             info.dli_fname ? info.dli_fname : "(unknown file)", info.dli_fbase);
    }
#if defined(__aarch64__) || defined(__arm__)
    if (g_inline_hook_backend.load(std::memory_order_relaxed) == kInlineHookBackendShadowHook) {
        if (shadowhookInit()) {
            void *stub = shadowhook_hook_func_addr(original, replace, backup);
            if (stub != nullptr) {
                std::lock_guard<std::mutex> lk(g_shadowhook_mutex);
                g_shadowhook_stubs[reinterpret_cast<uintptr_t>(original)] = stub;
                return RS_SUCCESS;
            }
            // Dobby is kept resident for exactly this case, so a refused hook downgrades
            // the call rather than failing it.
            LOGE("ShadowHook could not hook {} ({}), falling back to Dobby", original,
                 shadowhook_to_errmsg(shadowhook_get_errno()));
        }
    }
#endif
    return DobbyHook(original, reinterpret_cast<dobby_dummy_func_t>(replace), reinterpret_cast<dobby_dummy_func_t *>(backup));
}

inline int UnhookFunction(void *original) {
    if constexpr (isDebug) {
        Dl_info info;
        if (dladdr(original, &info))
        LOGD("Unhooking {} ({}) from {} ({})",
             info.dli_sname ? info.dli_sname : "(unknown symbol)", info.dli_saddr,
             info.dli_fname ? info.dli_fname : "(unknown file)", info.dli_fbase);
    }
#if defined(__aarch64__) || defined(__arm__)
    if (g_inline_hook_backend.load(std::memory_order_relaxed) == kInlineHookBackendShadowHook) {
        void *stub = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_shadowhook_mutex);
            auto it = g_shadowhook_stubs.find(reinterpret_cast<uintptr_t>(original));
            if (it != g_shadowhook_stubs.end()) {
                stub = it->second;
                g_shadowhook_stubs.erase(it);
            }
        }
        if (stub != nullptr) {
            if (shadowhook_unhook(stub) == 0) return RT_SUCCESS;
            LOGE("ShadowHook could not unhook {} ({})", original,
                 shadowhook_to_errmsg(shadowhook_get_errno()));
            return -1;
        }
    }
#endif
    return DobbyDestroy(original);
}

inline std::string GetNativeBridgeSignature() {
    const auto &obfs_map = ConfigBridge::GetInstance()->obfuscation_map();
    static auto signature = obfs_map.at("org.lsposed.lspd.nativebridge.");
    return signature;
}

} // namespace lspd

#pragma clang diagnostic pop

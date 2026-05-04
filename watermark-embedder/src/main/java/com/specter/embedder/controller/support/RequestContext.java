package com.specter.embedder.controller.support;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Per-request log correlation context. Tek noktada `requestId` MDC anahtari'ni
 * yonetir; controller bunu set/remove eder, exception handler get eder.
 *
 * <p>Magic-string ("requestId") sizdirmasini onler ve gelecekte trace_id /
 * span_id eklemek istersek tek dosya degisikligi yeter.
 */
public final class RequestContext {

    private static final String MDC_REQUEST_ID = "requestId";

    private RequestContext() {
    }

    public static void putRequestId(UUID requestId) {
        MDC.put(MDC_REQUEST_ID, requestId.toString());
    }

    public static void clearRequestId() {
        MDC.remove(MDC_REQUEST_ID);
    }

    /** Mevcut MDC'den request id'yi okur; controller disindaysak (orn. exception handler) null olabilir. */
    public static String currentRequestId() {
        return MDC.get(MDC_REQUEST_ID);
    }
}

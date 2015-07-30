/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.agent.remote;

import com.google.gson.*;
import org.apache.ignite.agent.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Allow to execute methods remotely from NodeJS server by web-socket command.
 */
public class RemoteCallable implements AutoCloseable {
    /** */
    private static final Logger log = Logger.getLogger(RemoteCallable.class.getName());

    /** */
    private final WebSocketSender snd;

    /** */
    private final Map<String, MethodDescriptor> methods = new HashMap<>();

    /** */
    private final ExecutorService executorSrvc = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    /**
     * @param snd Session.
     * @param hnds Handlers.
     */
    private RemoteCallable(WebSocketSender snd, Object ... hnds) {
        this.snd = snd;

        for (Object hnd : hnds) {
            for (Method method : hnd.getClass().getMethods()) {
                Remote remoteAnn = method.getAnnotation(Remote.class);

                if (remoteAnn != null) {
                    MethodDescriptor old = methods.put(method.getName(), new MethodDescriptor(method, hnd, remoteAnn.async()));

                    if (old != null)
                        throw new IllegalArgumentException("Duplicated method: " + method.getName());
                }
            }
        }

    }

    /**
     * @param req Request.
     */
    public void onMessage(JsonObject req) {
        if (log.isLoggable(Level.FINE))
            log.fine("Message: " + req);

        JsonPrimitive reqIdJson = req.getAsJsonPrimitive("reqId");

        final Long reqId = reqIdJson == null ? null : reqIdJson.getAsLong();

        String mtdName = req.getAsJsonPrimitive("mtdName").getAsString();

        final MethodDescriptor desc = methods.get(mtdName);

        if (desc == null) {
            sendError(reqId, "Unknown method: " + mtdName);

            return;
        }

        Type[] paramTypes = desc.mtd.getGenericParameterTypes();

        JsonArray argsJson = req.getAsJsonArray("args");

        final Object[] args;

        if (paramTypes.length > 0) {
            args = new Object[paramTypes.length];

            if (argsJson == null || argsJson.size() != paramTypes.length) {
                sendError(reqId, "Inconsistent parameters");

                return;
            }

            for (int i = 0; i < paramTypes.length; i++)
                args[i] = Utils.GSON.fromJson(argsJson.get(i), paramTypes[i]);
        }
        else {
            args = Utils.EMPTY_OBJECTS;

            if (argsJson != null && argsJson.size() > 0) {
                sendError(reqId, "Inconsistent parameters");

                return;
            }
        }

        Runnable run = new Runnable() {
            @Override public void run() {
                final Object res;

                try {
                    res = desc.mtd.invoke(desc.hnd, args);
                } catch (Exception e) {
                    if (reqId != null)
                        sendException(reqId, e);
                    else
                        log.log(Level.SEVERE, "Exception on execute remote method", e);

                    return;
                }

                sendResponse(reqId, res, desc.returnType);
            }
        };

        if (desc.async)
            executorSrvc.submit(run);
        else
            run.run();
    }

    /**
     * @param reqId Request id.
     * @param ex Exception.
     */
    protected void sendException(Long reqId, Exception ex) {
        if (reqId == null)
            return;

        JsonObject res = new JsonObject();

        res.addProperty("type", "CallRes");
        res.addProperty("reqId", reqId);

        JsonObject exJson = new JsonObject();
        exJson.addProperty("type", ex.getClass().getName());
        exJson.addProperty("message", ex.getMessage());

        res.add("ex", exJson);

        snd.send(res);
    }

    /**
     * @param reqId Request id.
     * @param err Exception.
     */
    protected void sendError(Long reqId, String err) {
        if (reqId == null)
            return;

        JsonObject res = new JsonObject();

        res.addProperty("type", "CallRes");
        res.addProperty("reqId", reqId);
        res.addProperty("error", err);

        snd.send(res);
    }

    /**
     * @param reqId Request id.
     * @param res Result.
     * @param type Type.
     */
    private void sendResponse(Long reqId, Object res, Type type) {
        if (reqId == null)
            return;

        JsonObject resp = new JsonObject();

        resp.addProperty("type", "CallRes");

        resp.addProperty("reqId", reqId);

        JsonElement resJson;

        if (type == void.class)
            resJson = JsonNull.INSTANCE;
        else
            resJson = Utils.GSON.toJsonTree(res, type);

        resp.add("res", resJson);

        snd.send(resp);
    }

    /** {@inheritDoc} */
    @Override public void close() {
        executorSrvc.shutdown();
    }

    /**
     * @param hnds Handler.
     * @param snd Sender.
     */
    public static RemoteCallable wrap(WebSocketSender snd, Object ... hnds) {
        return new RemoteCallable(snd, hnds);
    }

    /**
     *
     */
    private static class MethodDescriptor {
        /** */
        private final Method mtd;

        /** */
        private final Object hnd;

        /** */
        private final Type returnType;

        /** */
        private final boolean async;

        /**
         * @param mtd Method.
         * @param hnd Handler.
         * @param async Async.
         */
        MethodDescriptor(Method mtd, Object hnd, boolean async) {
            this.mtd = mtd;
            this.hnd = hnd;
            this.async = async;

            returnType = mtd.getGenericReturnType();
        }
    }
}
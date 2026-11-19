package com.lbaby.orders;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** The vendor JAR bundles its own JSON, HTTP and XML libraries. Keep them off the Spring classpath. */
final class MeituanSdk implements AutoCloseable {
    private static final String BASE = "com.sankuai.meituan.shangou.open.sdk.";
    private final URLClassLoader loader;
    private final Object systemParam;
    private final Constructor<?> detailRequest;
    private final Method setOrder, execute, result;

    MeituanSdk(Path jar, String appId, String secret) throws ReflectiveOperationException, IOException {
        loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        try {
            var params = loader.loadClass(BASE + "domain.SystemParam");
            var detail = loader.loadClass(BASE + "request.OrderGetOrderDetailRequest");
            systemParam = params.getConstructor(String.class,String.class).newInstance(appId,secret);
            detailRequest = detail.getConstructor(params);
            setOrder = detail.getMethod("setOrder_id",String.class);
            execute = detail.getMethod("doRequest");
            result = loader.loadClass(BASE + "response.SgOpenResponse").getMethod("getRequestResult");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            loader.close();
            throw e;
        }
    }

    String fetch(String orderId) throws ReflectiveOperationException {
        var request = detailRequest.newInstance(systemParam);
        setOrder.invoke(request,orderId);
        var response = execute.invoke(request);
        return response == null ? null : (String)result.invoke(response);
    }

    public void close() throws IOException { loader.close(); }
}

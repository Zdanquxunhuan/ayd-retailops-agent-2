package com.lbaby.orders;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;

/** Use the vendor signer without enabling the SDK's token refresh or secondary request paths. */
final class MeituanSdk implements AutoCloseable {
    private static final String ENDPOINT="https://waimaiopen.meituan.com/api/v1/order/getOrderDetail";
    private final URLClassLoader loader;
    private final Method sign;
    private final String appId,secret;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    MeituanSdk(Path jar,String appId,String secret) throws ReflectiveOperationException,IOException {
        this.appId=appId;
        this.secret=secret;
        loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},ClassLoader.getPlatformClassLoader());
        try {
            sign=loader.loadClass("com.sankuai.meituan.shangou.open.sdk.util.SignUtil").getMethod("genSig",String.class);
        } catch (ReflectiveOperationException | LinkageError e) { loader.close(); throw e; }
    }

    String fetch(String orderId) throws ReflectiveOperationException,IOException,InterruptedException {
        String query="app_id="+URLEncoder.encode(appId,StandardCharsets.UTF_8)+"&order_id="
                +URLEncoder.encode(orderId,StandardCharsets.UTF_8)+"&timestamp="+Instant.now().getEpochSecond();
        String url=ENDPOINT+"?"+query;
        String signature=(String)sign.invoke(null,url+secret);
        var request=HttpRequest.newBuilder(URI.create(url+"&sig="+signature)).timeout(Duration.ofSeconds(10)).GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode()!=200) throw new IOException("Detail service returned HTTP "+response.statusCode());
        return response.body();
    }

    public void close() throws IOException { client.close(); loader.close(); }
}

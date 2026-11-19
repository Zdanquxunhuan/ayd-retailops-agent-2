package com.lbaby.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

@Service
@Profile("pos-shadow")
class MeituanShadowLookup implements AutoCloseable {
    enum State { COMPLETED, NOT_COMPLETED, UNKNOWN_STATUS, OUTSIDE_QUERY_WINDOW, API_ERROR,
                 INVALID_RESPONSE, NOT_CONFIGURED, TIMEOUT, UNAVAILABLE }
    record Fact(State state, Integer status, String appPoiCode, Instant completedAt, List<Integer> errorCodes,
                String requestId, Instant observedAt) {
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String getSource() { return "MEITUAN_ORDER_DETAIL"; }
    }

    private final ObjectMapper json;
    private final MeituanSdk sdk;
    MeituanShadowLookup(ObjectMapper json, @Value("${MT_APP_ID:}") String appId,
            @Value("${MT_APP_SECRET:}") String secret,@Value("${meituan.sdk-jar}") Path jar)
            throws ReflectiveOperationException, IOException {
        this.json=json;
        if (appId.isBlank()!=secret.isBlank()) throw new IllegalArgumentException("Configure both Meituan credentials");
        sdk=appId.isBlank() ? null : new MeituanSdk(jar,appId,secret);
    }

    Fact query(String orderId) {
        if (sdk==null) return failure(State.NOT_CONFIGURED);
        try {
            return parse(json,orderId,sdk.fetch(orderId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(State.UNAVAILABLE);
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError e) {
            for (Throwable t=e;t!=null;t=t.getCause())
                if (t instanceof SocketTimeoutException || t instanceof java.net.http.HttpTimeoutException) return failure(State.TIMEOUT);
            return failure(State.UNAVAILABLE);
        }
    }

    static Fact parse(ObjectMapper mapper,String order,String raw) {
        if (raw==null) return failure(State.INVALID_RESPONSE);
        try {
            JsonNode body=mapper.readTree(raw);
            if (body==null || !body.isObject()) return failure(State.INVALID_RESPONSE);
            String requestId=identifier(body.path("mt_request_id"));
            Integer resultCode=integer(body.path("result_code"));
            if (resultCode==null) return failure(State.INVALID_RESPONSE);
            if (resultCode!=1) {
                var codes=new LinkedHashSet<Integer>();
                var main=integer(body.path("error").path("code"));
                if (main!=null) codes.add(main);
                for (var err:body.path("error_list")) {
                    var code=integer(err.path("code"));
                    if (code!=null) codes.add(code);
                }
                State state=codes.size()==1 && codes.contains(838) ? State.OUTSIDE_QUERY_WINDOW : State.API_ERROR;
                return new Fact(state,null,null,null,List.copyOf(codes),requestId,Instant.now());
            }
            var data=body.path("data");
            if (!order.equals(identifier(data.path("order_id")))) return failure(State.INVALID_RESPONSE);
            Integer status=integer(data.path("status"));
            String poi=identifier(data.path("app_poi_code"));
            if (status==null || poi==null || poi.isBlank()) return failure(State.INVALID_RESPONSE);
            State state=switch(status) {
                case 8 -> State.COMPLETED;
                case 1,2,4,9 -> State.NOT_COMPLETED;
                default -> State.UNKNOWN_STATUS;
            };
            String seconds=identifier(data.path("order_completed_time"));
            Instant completed=null;
            if (state==State.COMPLETED && seconds!=null && Long.parseLong(seconds)>0)
                completed=Instant.ofEpochSecond(Long.parseLong(seconds));
            return new Fact(state,status,poi,completed,List.of(),requestId,Instant.now());
        } catch (IOException | NumberFormatException | java.time.DateTimeException invalid) {
            return failure(State.INVALID_RESPONSE);
        }
    }

    private static String identifier(JsonNode node) {
        return node.isTextual() || node.isIntegralNumber() ? node.asText() : null;
    }
    private static Integer integer(JsonNode node) {
        String value=identifier(node);
        return value==null ? null : Integer.valueOf(value);
    }
    private static Fact failure(State state) { return new Fact(state,null,null,null,List.of(),null,Instant.now()); }
    public void close() throws IOException { if (sdk!=null) sdk.close(); }
}

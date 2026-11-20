import cn.com.huoban168.b.push.center.dto.MTSku;
import cn.com.huoban168.b.push.center.entity.pos.*;
import cn.com.huoban168.b.push.center.mapper.pos.MeituanMapper;
import cn.com.huoban168.b.push.center.service.pos.*;
import cn.com.huoban168.b.push.center.service.pos.impl.MeituanServiceImpl;
import cn.com.huoban168.b.push.center.sx.factory.HandleSxDataActuatorFactory;
import cn.com.huoban168.b.push.center.sx.service.HandleSxDataService;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import static org.mockito.Mockito.*;

/** Standalone synthetic business receiver. No Spring application or company configuration is bootstrapped. */
public class BusinessLab {
    static final String ORDER = "123456", STORE = "033101";
    enum Mode { NORMAL, LOST_REPLY, MISSING_MAPPING, PARTIAL, ARRIVED_BEFORE_CONFIRM }
    final ObjectMapper json = new ObjectMapper();
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final String auth;
    final URI agent;
    final Mode mode;
    final MeituanServiceImpl service;
    final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();

    BusinessLab(int agentPort, Mode mode) throws Exception {
        String token = Objects.requireNonNull(System.getenv("REPAIR_LAB_TOKEN"));
        if (token.isBlank()) throw new IllegalArgumentException("Missing test token");
        this.auth = "Bearer " + token;
        this.agent = URI.create("http://127.0.0.1:" + agentPort);
        this.mode = mode;
        // Each acceptance run has its own working directory. Never read a JDBC URL from the company app.
        var source = new DriverManagerDataSource("jdbc:h2:file:./business-lab;WRITE_DELAY=0", "sa", "");
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        db.execute("CREATE TABLE IF NOT EXISTS received(id UUID PRIMARY KEY, order_id VARCHAR NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS pay(store_id VARCHAR, order_id VARCHAR, amount DECIMAL(14,2))");
        db.execute("CREATE TABLE IF NOT EXISTS sale(store_id VARCHAR, order_id VARCHAR, sku VARCHAR)");
        if (mode == Mode.PARTIAL && !exists("sale")) db.update("INSERT INTO sale VALUES(?,?,?)",STORE,ORDER,"fixture");
        var pay = mock(ITRmPayflowFrommailService.class);
        var sale = mock(ITRmSaleflowFrommailService.class);
        when(pay.count(any())).thenAnswer(i -> exists("pay") ? 1L : 0L);
        when(sale.count(any())).thenAnswer(i -> exists("sale") ? 1L : 0L);
        var mapper = mock(MeituanMapper.class);
        if (mode != Mode.MISSING_MAPPING) when(mapper.selectSxStoreId(STORE)).thenReturn(STORE);
        service = spy(new MeituanServiceImpl(null, pay, sale, mapper, json));
        doReturn("{\"data\":[]}").when(service).getRefundMessage(ORDER);
        var gift = mock(MTSku.class);
        when(gift.getUpc()).thenReturn("test-gift");
        when(gift.getPrice()).thenReturn(java.math.BigDecimal.ONE);
        doReturn(gift).when(service).getGoodInfo(STORE,"gift");
        var factory = mock(HandleSxDataActuatorFactory.class);
        var writer = mock(HandleSxDataService.class);
        when(factory.createActuator(STORE)).thenReturn(writer);
        ReflectionTestUtils.setField(service,"handleSxDataActuatorFactory",factory);
        doAnswer(i -> {
            TRmPayflowFrommail row = i.getArgument(1);
            db.update("INSERT INTO pay VALUES(?,?,?)",row.getBranchNo(),row.getMailOrderId(),row.getPayAmount());
            return null;
        }).when(writer).doInsertTRmPay(eq(STORE),any());
        doAnswer(i -> {
            TRmSaleflowFrommail row = i.getArgument(1);
            db.update("INSERT INTO sale VALUES(?,?,?)",row.getBranchNo(),row.getMailOrderId(),row.getItemNo());
            return null;
        }).when(writer).doInsertTRmSale(eq(STORE),any());
    }

    boolean exists(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE store_id=? AND order_id=?",
                Integer.class,STORE,ORDER)>0;
    }

    MeituanToTRm detail() throws Exception {
        var body = Map.of("order_id",123456L,"app_poi_code",STORE,"status",8,"ctime",1700000000,
                "is_pre_order",false,"detail",json.writeValueAsString(List.of(Map.of(
                "skuId","test-sku","upc","test-sku","quantity",1,"actualPrice",12,"originalPrice",12))),
                "extras",json.writeValueAsString(List.of(Map.of("remark","赠品","poiCharge",0,
                "actExtendMsg",Map.of("giftNum",1,"giftsAppFoodCode","gift")))));
        return json.convertValue(body,MeituanToTRm.class);
    }

    void repair(HttpExchange exchange, UUID id) throws Exception {
        var req = HttpRequest.newBuilder(agent.resolve("/internal/repair-lab/grants/" + id))
                .header("Authorization",auth).timeout(Duration.ofSeconds(3)).GET().build();
        var reply = client.send(req,HttpResponse.BodyHandlers.ofString());
        if (reply.statusCode()!=200) { respond(exchange,403,Map.of()); return; }
        var grant = json.readTree(reply.body());
        var order = detail();
        if (!String.valueOf(order.getOrder_id()).equals(grant.path("orderId").asText())
                || !order.getApp_poi_code().equals(grant.path("storeId").asText()) || order.getStatus()!=8) {
            respond(exchange,409,Map.of()); return;
        }
        if (exists("pay") || exists("sale")) { respond(exchange,409,Map.of()); return; }
        try { db.update("INSERT INTO received VALUES(?,?)",id,ORDER); }
        catch (DuplicateKeyException duplicate) { respond(exchange,409,Map.of()); return; }
        DynamicDataSourceContextHolder.push("test-jm");
        try { tx.executeWithoutResult(status -> service.pushFinishedOrder(order)); }
        finally { DynamicDataSourceContextHolder.poll(); }
        if (mode != Mode.MISSING_MAPPING) {
            verify(service).getRefundMessage(ORDER);
            verify(service).getGoodInfo(STORE,"gift");
        }
        if (mode == Mode.LOST_REPLY) { exchange.close(); return; }
        respond(exchange,200,Map.of("ok",true));
    }

    void handle(HttpExchange ex) {
        try (ex) {
            try {
                String supplied = Objects.toString(ex.getRequestHeaders().getFirst("Authorization"), "");
                if (!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
                    respond(ex,403,Map.of()); return;
                }
                String path = ex.getRequestURI().getPath();
                if (ex.getRequestMethod().equals("GET") && path.equals("/lab/pos/"+STORE+"/"+ORDER)) {
                    if (mode == Mode.ARRIVED_BEFORE_CONFIRM && java.nio.file.Files.exists(java.nio.file.Path.of("delivered"))
                            && !exists("pay")) {
                        tx.executeWithoutResult(s -> {
                            db.update("INSERT INTO pay VALUES(?,?,?)",STORE,ORDER,12);
                            db.update("INSERT INTO sale VALUES(?,?,?)",STORE,ORDER,"original-sale");
                        });
                    }
                    respond(ex,200,Map.of("storeId",STORE,"orderId",ORDER,"pay",exists("pay"),"sale",exists("sale")));
                } else if (ex.getRequestMethod().equals("GET") && path.equals("/lab/counts")) {
                    var counts = new LinkedHashMap<String,Integer>();
                    for (String table : List.of("received","pay","sale"))
                        counts.put(table,db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
                    respond(ex,200,counts);
                } else if (ex.getRequestMethod().equals("POST") && path.startsWith("/lab/repairs/")) {
                    repair(ex,UUID.fromString(path.substring("/lab/repairs/".length())));
                } else respond(ex,404,Map.of());
            } catch (Exception failure) {
                failure.printStackTrace();
                respond(ex,503,Map.of());
            }
        } catch (java.io.IOException disconnected) { /* Caller will reconcile against the ledgers. */ }
    }

    void respond(HttpExchange ex,int code,Object body) throws java.io.IOException {
        byte[] bytes=json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type","application/json");
        ex.sendResponseHeaders(code,bytes.length);
        ex.getResponseBody().write(bytes);
    }

    public static void main(String[] args) throws Exception {
        var lab = new BusinessLab(Integer.parseInt(args[1]),Mode.valueOf(args[2]));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[0])),0);
        server.createContext("/lab/",lab::handle);
        server.start();
    }
}

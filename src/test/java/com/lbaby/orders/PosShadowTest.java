package com.lbaby.orders;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"POS_DB_PASSWORD=test-only","POS_SHADOW_TOKEN=test-shadow","spring.datasource.url=jdbc:h2:mem:shadowroutes"})
@ActiveProfiles("pos-shadow")
class PosShadowTest {
    @Autowired TestRestTemplate http;

    @Test void handlesBothLedgersAndFailsClosedWhenTheSourceIsUnavailable() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:ledger;DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(source);
        var lookup=new PosShadowLookup(source);
        var unavailable=lookup.query("01");
        assertThat(unavailable.status()).isEqualTo(PosShadowLookup.Status.UNAVAILABLE);
        assertThat(unavailable.payExists()).isNull();
        db.execute("CREATE TABLE t_rm_payflow_frommail(mail_orderid VARCHAR(64))");
        db.execute("CREATE TABLE t_rm_saleflow_frommail(mail_orderid VARCHAR(64))");
        db.update("INSERT INTO t_rm_payflow_frommail VALUES ('01'),('02')");
        db.update("INSERT INTO t_rm_saleflow_frommail VALUES ('01'),('01'),('03')");
        assertThat(lookup.query("01").status()).isEqualTo(PosShadowLookup.Status.FOUND);
        assertThat(lookup.query("1").status()).isEqualTo(PosShadowLookup.Status.NOT_FOUND);
        var paymentOnly=lookup.query("02");
        assertThat(paymentOnly.status()).isEqualTo(PosShadowLookup.Status.PARTIAL);
        assertThat(paymentOnly.saleExists()).isFalse();
        var saleOnly=lookup.query("03");
        assertThat(saleOnly.status()).isEqualTo(PosShadowLookup.Status.PARTIAL);
        assertThat(saleOnly.payExists()).isFalse();
    }

    @Test void isolatesRealDataFromDemoRolesInvestigationsAndRepairs() {
        var headers=new HttpHeaders();
        headers.setBearerAuth("demo-ops-a");
        var request=new HttpEntity<>(Map.of("orderId","123456"),headers);
        assertThat(http.postForEntity("/pos-shadow/orders/check",request,String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        headers.setBearerAuth("test-shadow");
        var invalid=new HttpEntity<>(Map.of("orderId"," "),headers);
        assertThat(http.postForEntity("/pos-shadow/orders/check",invalid,String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        for (var path : new String[]{"/cases","/cases/1/investigations","/cases/1/repair-attempts/2/confirm"})
            assertThat(http.postForEntity(path,request,String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}

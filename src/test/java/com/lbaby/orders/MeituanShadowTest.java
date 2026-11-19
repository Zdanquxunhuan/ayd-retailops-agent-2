package com.lbaby.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class MeituanShadowTest {
    private final ObjectMapper json=new ObjectMapper();
    private static final String ORDER="1234567890123456789";

    @ParameterizedTest
    @CsvSource({"8,COMPLETED","4,NOT_COMPLETED","9,NOT_COMPLETED","999,UNKNOWN_STATUS"})
    void orderStatusComesFromTheReturnedOrder(int status,String expected) {
        var raw="""
                {"result_code":1,"mt_request_id":"-123","data":{"order_id":1234567890123456789,
                 "app_poi_code":"001","status":%d,"order_completed_time":1730000000,"recipient_name":"private"}}
                """.formatted(status);
        var fact=MeituanShadowLookup.parse(json,ORDER,raw);
        assertThat(fact.state().name()).isEqualTo(expected);
        assertThat(fact.appPoiCode()).isEqualTo("001");
        assertThat(fact.completedAt()!=null).isEqualTo(status==8);
        assertThat(fact.toString()).doesNotContain("private");
        assertThat(MeituanShadowLookup.parse(json,"another",raw).state().name()).isEqualTo("INVALID_RESPONSE");
    }

    @Test void windowLimitAndOtherErrorsCannotProveOrderStatus() {
        var raw="""
                {"result_code":3,"data":"ng","error":{"code":838},"error_list":[{"code":838}],"mt_request_id":"-123"}
                """;
        var fact=MeituanShadowLookup.parse(json,ORDER,raw);
        assertThat(fact.state().name()).isEqualTo("OUTSIDE_QUERY_WINDOW");
        assertThat(fact.errorCodes()).containsExactly(838);
        assertThat(fact.status()).isNull();
        assertThat(MeituanShadowLookup.parse(json,ORDER,raw.replace("838","777")).state().name()).isEqualTo("API_ERROR");
        assertThat(MeituanShadowLookup.parse(json,ORDER,"{}").state().name()).isEqualTo("INVALID_RESPONSE");
        assertThat(MeituanShadowLookup.parse(json,ORDER,null).state().name()).isEqualTo("INVALID_RESPONSE");
    }
}

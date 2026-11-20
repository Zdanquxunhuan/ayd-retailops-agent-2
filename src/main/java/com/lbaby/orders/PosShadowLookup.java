package com.lbaby.orders;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Instant;

/** One InnoDB consistent read across the payment and sale ledgers. */
@org.springframework.stereotype.Service
@org.springframework.context.annotation.Profile("pos-shadow")
class PosShadowLookup {
    enum Status { FOUND, NOT_FOUND, PARTIAL, DENIED, TIMEOUT, UNAVAILABLE }
    record Observation(String orderId, Status status, Boolean payExists, Boolean saleExists, Instant observedAt) {
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String getSource() { return "LOCAL_MYSQL_MTJD"; }
    }

    // 预检查单固定读取本机 mtjd，app_poi_code 仅作门店信息。
    private final DataSource db;
    @org.springframework.beans.factory.annotation.Autowired
    PosShadowLookup(@org.springframework.beans.factory.annotation.Value("${POS_DB_PASSWORD}") String password) {
        this(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/mtjd?connectTimeout=3000&socketTimeout=6000","onlyselect",password));
    }

    PosShadowLookup(DataSource db) { this.db = db; }

    Observation query(String orderId) {
        try (var connection=db.getConnection()) {
            connection.setReadOnly(true);
            try (var stmt=connection.prepareStatement("""
                    SELECT EXISTS(SELECT 1 FROM t_rm_payflow_frommail WHERE mail_orderid=?),
                           EXISTS(SELECT 1 FROM t_rm_saleflow_frommail WHERE mail_orderid=?)
                    """)) {
                stmt.setQueryTimeout(5);
                stmt.setString(1,orderId);
                stmt.setString(2,orderId);
                try (var row=stmt.executeQuery()) {
                    if (!row.next()) throw new SQLException("Expected existence flags");
                    boolean pay=row.getBoolean(1), sale=row.getBoolean(2);
                    Status status = pay != sale ? Status.PARTIAL : pay ? Status.FOUND : Status.NOT_FOUND;
                    return new Observation(orderId,status,pay,sale,Instant.now());
                }
            }
        } catch (SQLException e) {
            Status status=switch(e.getErrorCode()) {
                case 1044,1045,1142,1143 -> Status.DENIED;
                case 3024 -> Status.TIMEOUT;
                default -> timeout(e) ? Status.TIMEOUT : Status.UNAVAILABLE;
            };
            return new Observation(orderId,status,null,null,Instant.now());
        }
    }

    private boolean timeout(SQLException error) {
        for (Throwable t=error; t!=null; t=t.getCause())
            if (t instanceof SQLTimeoutException || t instanceof java.net.SocketTimeoutException) return true;
        return false;
    }
}

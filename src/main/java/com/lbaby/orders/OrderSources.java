package com.lbaby.orders;

import java.util.Optional;

interface OrderSources {
    enum PosStatus { FOUND, NOT_FOUND, PARTIAL, TIMEOUT, DENIED }
    enum MeituanStatus { COMPLETED, NOT_COMPLETED, NOT_FOUND, TIMEOUT, DENIED }

    default String posEvidenceSource() { return "POS_FIXTURE"; }
    Optional<String> storeOf(String orderId);
    PosStatus queryPos(String orderId);
    MeituanStatus queryMeituan(String orderId);
}

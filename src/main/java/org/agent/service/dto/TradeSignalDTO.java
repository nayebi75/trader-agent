package org.agent.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.agent.constants.SignalStatus;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TradeSignalDTO implements Serializable {

    @Builder.Default
    private String id = UUID.randomUUID().toString();

    private String symbol;

    /**
     * Example:
     * "4h"
     */
    private String timeframe;

    /**
     * Reference price of the CLOSED candle
     * that generated the signal.
     */
    private BigDecimal referenceEntryPrice;

    /**
     * Current executable/market price when the running application detected the signal.
     */
    private BigDecimal actualEntryPrice;

    private BigDecimal stopLoss;

    private BigDecimal takeProfit;

    /**
     * Useful for later strategy analysis.
     */
    private double rsi;

    /**
     * Store the value numerically.
     * <p>
     * Do NOT store:
     * <p>
     * "riskRewardRatio: 1.82"
     * <p>
     * inside an arbitrary result String.
     */
    private double riskRewardRatio;

    /**
     * End timestamp of the CLOSED candle
     * that produced the signal.
     */
    private long signalCandleEndTimestamp;

    /**
     * Actual timestamp when the running application detected and persisted the signal.
     */
    private long detectedAtTimestamp;

    @Builder.Default
    private SignalStatus status = SignalStatus.OPEN;

    /**
     * Null until TP / SL / expiration / cancellation.
     */
    private Long resolvedAtTimestamp;

}

package org.agent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.agent.constants.SignalStatus;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TradeSignalDTO implements Serializable {

    @JsonProperty("symbol")
    private String symbol;

    /**
     * Example:
     * "4h"
     */
    @JsonProperty("timeframe")
    private String timeframe;

    /**
     * Reference price of the CLOSED candle
     * that generated the signal.
     */
    @JsonProperty("entryPrice")
    private BigDecimal entryPrice;

    @JsonProperty("stopLoss")
    private BigDecimal stopLoss;

    @JsonProperty("takeProfit")
    private BigDecimal takeProfit;

    /**
     * Useful for later strategy analysis.
     */
    @JsonProperty("rsi")
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
    @JsonProperty("riskRewardRatio")
    private double riskRewardRatio;

    /**
     * End timestamp of the CLOSED candle
     * that produced the signal.
     */
    @JsonProperty("timestamp")
    private long timestamp;

    @Builder.Default
    @JsonProperty("status")
    private SignalStatus status = SignalStatus.OPEN;

    /**
     * Null until TP / SL / expiration / cancellation.
     */
    @JsonProperty("resolvedAtTimestamp")
    private Long resolvedAtTimestamp;

}
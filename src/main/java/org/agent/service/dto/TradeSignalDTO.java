package org.agent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TradeSignalDTO implements Serializable {

    @JsonProperty("symbol")
    private String symbol;
    @JsonProperty("entryPrice")
    private double entryPrice;
    @JsonProperty("rsi")
    private double rsi;
    @JsonProperty("timestamp")
    private long timestamp;
    @JsonProperty("dateTime")
    private String dateTime;
    @JsonProperty("stopLoss")
    private double stopLoss;
    @JsonProperty("takeProfit")
    private double takeProfit;
    @JsonProperty("resultChecked")
    private boolean resultChecked = false;
    @JsonProperty("hitTp")
    private boolean hitTp = false;
    @JsonProperty("hitSL")
    private boolean hitSL = false;
    @JsonProperty("hitTpTimestamp")
    private long hitTpTimestamp;
    @JsonProperty("hitSlTimestamp")
    private long hitSlTimestamp;
    @JsonProperty("result")
    private String result = "";

    @JsonProperty("tpDays")
    private List<Integer> tpDays;

}

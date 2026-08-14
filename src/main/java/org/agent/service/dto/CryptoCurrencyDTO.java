package org.agent.service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CryptoCurrencyDTO implements Serializable, Comparable<CryptoCurrencyDTO> {

    @JsonProperty("high")
    private String high;
    @JsonProperty("vol")
    private String vol;
    @JsonProperty("low")
    private String low;
    @JsonProperty("change")
    private String change;
    @JsonProperty("turnover")
    private String turnover;
    @JsonProperty("latest")
    private String latest;
    @JsonProperty("symbol")
    private String symbol;
    @JsonProperty("timestamp")
    private Long timestamp;

    @Override
    public int compareTo(CryptoCurrencyDTO o) {
        return Double.compare(Double.parseDouble(this.getLatest()), Double.parseDouble(o.getLatest()));
    }
}

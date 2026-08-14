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
public class LBankAvailableCryptoCurrenciesDTO implements Serializable {

    @JsonProperty("symbol")
    private String symbol;
    @JsonProperty("timestamp")
    private Long timestamp;
    @JsonProperty("ticker")
    private LBankTickerDTO ticker;

}

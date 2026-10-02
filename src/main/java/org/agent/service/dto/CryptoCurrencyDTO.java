package org.agent.service.dto;

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

    private String high;
    private String vol;
    private String low;
    private String change;
    private String turnover;
    private String latest;
    private String symbol;
    private Long timestamp;

    @Override
    public int compareTo(CryptoCurrencyDTO o) {
        return Double.compare(Double.parseDouble(this.getLatest()), Double.parseDouble(o.getLatest()));
    }
}

package org.agent.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CandleDTO {

    private long timestamp;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;

}

package com.nalssilog.report.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nalssilog.report.application.dto.LiveReportData;
import com.nalssilog.report.domain.Precipitation;
import com.nalssilog.report.domain.Sunlight;
import com.nalssilog.report.domain.Temperature;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S5960")
class LiveReportMessageResolverTest {

    @Test
    void resolvesTheFirstMatchingWeatherStateAcrossAllAxes() {
        assertThat(resolve(Temperature.HOT, Precipitation.HEAVY, Sunlight.STRONG))
                .isEqualTo("비가 많이 와요");
        assertThat(resolve(Temperature.COLD, Precipitation.LIGHT, Sunlight.LOW))
                .isEqualTo("비가 조금 와요");
        assertThat(resolve(Temperature.HOT, Precipitation.NONE, Sunlight.STRONG))
                .isEqualTo("더워요");
        assertThat(resolve(Temperature.COLD, Precipitation.NONE, Sunlight.LOW))
                .isEqualTo("추워요");
        assertThat(resolve(Temperature.FRESH, Precipitation.NONE, Sunlight.STRONG))
                .isEqualTo("햇빛이 따가워요");
        assertThat(resolve(Temperature.FRESH, Precipitation.NONE, Sunlight.LOW))
                .isEqualTo("햇빛이 부족해요");
        assertThat(resolve(Temperature.FRESH, Precipitation.NONE, Sunlight.MODERATE))
                .isEqualTo("선선해요");
    }

    private String resolve(
            Temperature temperature,
            Precipitation precipitation,
            Sunlight sunlight
    ) {
        return LiveReportMessageResolver.resolve(new LiveReportData(
                1L,
                2L,
                temperature,
                precipitation,
                sunlight,
                Instant.EPOCH));
    }
}

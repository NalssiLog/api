package com.nalssilog.report.application;

import com.nalssilog.report.application.dto.LiveReportData;
import com.nalssilog.report.domain.Precipitation;
import com.nalssilog.report.domain.Sunlight;
import com.nalssilog.report.domain.Temperature;

final class LiveReportMessageResolver {

    private LiveReportMessageResolver() {
    }

    static String resolve(LiveReportData report) {
        if (report.precipitation() == Precipitation.HEAVY) {
            return "비가 많이 와요";
        }

        if (report.precipitation() == Precipitation.LIGHT) {
            return "비가 조금 와요";
        }

        if (report.temperature() == Temperature.HOT) {
            return "더워요";
        }

        if (report.temperature() == Temperature.COLD) {
            return "추워요";
        }

        if (report.sunlight() == Sunlight.STRONG) {
            return "햇빛이 따가워요";
        }

        if (report.sunlight() == Sunlight.LOW) {
            return "햇빛이 부족해요";
        }

        if (report.temperature() == Temperature.FRESH) {
            return "선선해요";
        }

        if (report.sunlight() == Sunlight.MODERATE) {
            return "햇빛이 적당해요";
        }

        return "지금 비가 안 와요";
    }
}

package com.ggmount.weather;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** 시간별 예보로 등산 적합도(좋음/주의/나쁨)와 안내 문구를 정합니다. 가장 나쁜 항목이 전체 등급이 됩니다. */
public final class HikingWeatherRules {

    public enum Level { GOOD, CAUTION, BAD }

    public record Verdict(Level level, String message, List<String> notes) {
    }

    private record Finding(Level level, int priority, String message) {
    }

    private HikingWeatherRules() {
    }

    public static Verdict judge(List<HourForecast> hours) {
        List<Finding> found = new ArrayList<>();
        int rainyHours = (int) hours.stream().filter(h -> h.pty() != null && h.pty() != 0).count();
        HourForecast firstRain = hours.stream().filter(h -> h.pty() != null && h.pty() != 0).findFirst().orElse(null);
        if (rainyHours >= 2) {
            found.add(new Finding(Level.BAD, 0, at(firstRain) + "부터 비나 눈 예보가 있어요. 산행을 미루는 걸 권해요."));
        } else if (rainyHours == 1) {
            found.add(new Finding(Level.CAUTION, 0, at(firstRain) + "에 비나 눈 예보가 있어요. 우비를 챙기세요."));
        }

        HourForecast pop = max(hours, h -> h.pop() == null ? 0.0 : h.pop());
        if (pop != null && pop.pop() != null) {
            if (pop.pop() >= 60) {
                found.add(new Finding(Level.BAD, 1, "강수확률이 " + pop.pop() + "%예요(" + at(pop) + "). 산행을 미루는 걸 권해요."));
            } else if (pop.pop() >= 30) {
                found.add(new Finding(Level.CAUTION, 1, "강수확률 " + pop.pop() + "%예요. 우비를 챙기고 바위 구간은 조심하세요."));
            }
        }

        HourForecast rain = max(hours, HourForecast::precipitationMm);
        if (rain != null && rain.precipitationMm() >= 3) {
            found.add(new Finding(Level.BAD, 2, at(rain) + "에 시간당 " + fmt(rain.precipitationMm()) + "mm 비가 와요. 계곡 물이 불어날 수 있어요."));
        } else if (rain != null && rain.precipitationMm() > 0) {
            found.add(new Finding(Level.CAUTION, 2, "약한 비가 예보돼 있어요. 길이 미끄러울 수 있어요."));
        }

        HourForecast snow = max(hours, HourForecast::snowCm);
        if (snow != null && snow.snowCm() >= 1) {
            found.add(new Finding(Level.BAD, 3, at(snow) + "에 시간당 " + fmt(snow.snowCm()) + "cm 눈이 와요. 산행을 자제하세요."));
        } else if (snow != null && snow.snowCm() > 0) {
            found.add(new Finding(Level.CAUTION, 3, "눈 예보가 있어요. 아이젠과 스패츠를 챙기세요."));
        }

        HourForecast wind = max(hours, h -> h.windSpeed() == null ? 0.0 : h.windSpeed());
        if (wind != null && wind.windSpeed() != null) {
            if (wind.windSpeed() >= 10) {
                found.add(new Finding(Level.BAD, 4, "바람이 초속 " + fmt(wind.windSpeed()) + "m로 강해요. 능선과 정상 산행은 자제하세요."));
            } else if (wind.windSpeed() >= 7) {
                found.add(new Finding(Level.CAUTION, 4, "바람이 초속 " + fmt(wind.windSpeed()) + "m로 다소 강해요. 능선에서 조심하세요."));
            }
        }

        HourForecast hot = max(hours, h -> h.temperature() == null ? -99.0 : h.temperature());
        if (hot != null && hot.temperature() != null) {
            if (hot.temperature() >= 33) {
                found.add(new Finding(Level.BAD, 5, "최고 " + fmt(hot.temperature()) + "℃예요. 한낮 산행은 피하고 물을 충분히 챙기세요."));
            } else if (hot.temperature() >= 30) {
                found.add(new Finding(Level.CAUTION, 5, "최고 " + fmt(hot.temperature()) + "℃로 더워요. 물을 충분히 챙기세요."));
            }
        }

        HourForecast cold = hours.stream().filter(h -> h.temperature() != null)
                .min(Comparator.comparingDouble(HourForecast::temperature)).orElse(null);
        if (cold != null) {
            if (cold.temperature() <= -10) {
                found.add(new Finding(Level.BAD, 6, "최저 " + fmt(cold.temperature()) + "℃예요. 저체온 위험이 있어 산행을 자제하세요."));
            } else if (cold.temperature() <= 0) {
                found.add(new Finding(Level.CAUTION, 6, "최저 " + fmt(cold.temperature()) + "℃예요. 결빙 구간이 있을 수 있으니 아이젠을 챙기세요."));
            }
        }

        if (found.isEmpty()) {
            return new Verdict(Level.GOOD, "등산하기 좋은 날씨예요. 즐거운 산행 되세요!", List.of());
        }
        found.sort(Comparator.comparing(Finding::level).reversed().thenComparingInt(Finding::priority));
        List<String> notes = found.stream().skip(1).map(Finding::message).toList();
        return new Verdict(found.getFirst().level(), found.getFirst().message(), notes);
    }

    private static HourForecast max(List<HourForecast> hours, Function<HourForecast, Double> value) {
        return hours.stream().max(Comparator.comparing(value)).orElse(null);
    }

    private static String at(HourForecast h) {
        return h.time().getHour() + "시";
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}

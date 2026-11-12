package com.ggmount.weather;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** 시간별 예보로 등산 적합도(좋음/주의/나쁨)와 안내 문구를 정합니다. 가장 나쁜 항목이 전체 등급이 됩니다. */
// ToPeak 내부 안내 규칙으로 실제 산행 안전성을 보장하지 않으며, 산행 인증·회원 점수 계산에는 사용하지 않는다.
// 서비스가 선택한 시간대의 예보를 받으며 하늘 상태·습도는 등급 계산에 사용하지 않는다.
public final class HikingWeatherRules {

    public enum Level {
        GOOD, CAUTION, BAD
    }

    public record Verdict(Level level, String message, List<String> notes) {
    }

    private record Finding(Level level, int priority, String message) {
    }

    private HikingWeatherRules() {
    }

    // 입력 목록·표본의 null이나 수치 범위·유한성을 별도 검증하지 않는다. 빈 목록도 조건이 없으면 GOOD이다.
    // 강수형태 null은 횟수에서 제외하고, 최대값 선택 시 강수확률·풍속 null은 0, 기온 null은 -99로 취급한다.
    public static Verdict judge(List<HourForecast> hours) {
        List<Finding> found = new ArrayList<>();
        // 강수형태가 0이 아닌 표본이 2개 이상이면 BAD, 1개면 CAUTION이다. 연속 시간인지 여부는 검사하지 않는다.
        int rainyHours =(int) hours.stream().filter(h -> h.pty() != null && h.pty() != 0).count();
        HourForecast firstRain = hours.stream()
            .filter(h -> h.pty() != null && h.pty() != 0)
            .findFirst()
            .orElse(null);
        if (rainyHours >= 2) {
            found.add(new Finding(Level.BAD, 0, at(firstRain) + "부터 비나 눈 예보가 있어요. 산행을 미루는 걸 권해요."));
        }
        else if (rainyHours == 1) {
            found.add(new Finding(Level.CAUTION, 0, at(firstRain) + "에 비나 눈 예보가 있어요. 우비를 챙기세요."));
        }

        // 최대 강수확률이 60% 이상이면 BAD, 30% 이상이면 CAUTION이다.
        HourForecast pop = max(hours, h -> h.pop() == null ? 0.0 : h.pop());
        if (pop != null && pop.pop() != null) {
            if (pop.pop() >= 60) {
                found.add(new Finding(
                    Level.BAD,
                    1,
                    "강수확률이 " + pop.pop() + "%예요(" + at(pop) + "). 산행을 미루는 걸 권해요."
                ));
            }
            else if (pop.pop() >= 30) {
                found.add(new Finding(
                    Level.CAUTION,
                    1,
                    "강수확률 " + pop.pop() + "%예요. 우비를 챙기고 바위 구간은 조심하세요."
                ));
            }
        }

        // 최대 시간당 강수량이 3mm 이상이면 BAD, 0mm 초과이면 CAUTION이다.
        HourForecast rain = max(hours, HourForecast::precipitationMm);
        if (rain != null && rain.precipitationMm() >= 3) {
            found.add(new Finding(
                Level.BAD,
                2,
                at(rain) + "에 시간당 " + fmt(rain.precipitationMm()) + "mm 비가 와요. 계곡 물이 불어날 수 있어요."
            ));
        }
        else if (rain != null && rain.precipitationMm() > 0) {
            found.add(new Finding(Level.CAUTION, 2, "약한 비가 예보돼 있어요. 길이 미끄러울 수 있어요."));
        }

        // 최대 시간당 신적설이 1cm 이상이면 BAD, 0cm 초과이면 CAUTION이다.
        HourForecast snow = max(hours, HourForecast::snowCm);
        if (snow != null && snow.snowCm() >= 1) {
            found.add(new Finding(
                Level.BAD,
                3,
                at(snow) + "에 시간당 " + fmt(snow.snowCm()) + "cm 눈이 와요. 산행을 자제하세요."
            ));
        }
        else if (snow != null && snow.snowCm() > 0) {
            found.add(new Finding(Level.CAUTION, 3, "눈 예보가 있어요. 아이젠과 스패츠를 챙기세요."));
        }

        // 최대 풍속이 10m/s 이상이면 BAD, 7m/s 이상이면 CAUTION이다.
        HourForecast wind = max(hours, h -> h.windSpeed() == null ? 0.0 : h.windSpeed());
        if (wind != null && wind.windSpeed() != null) {
            if (wind.windSpeed() >= 10) {
                found.add(new Finding(
                    Level.BAD,
                    4,
                    "바람이 초속 " + fmt(wind.windSpeed()) + "m로 강해요. 능선과 정상 산행은 자제하세요."
                ));
            }
            else if (wind.windSpeed() >= 7) {
                found.add(new Finding(
                    Level.CAUTION,
                    4,
                    "바람이 초속 " + fmt(wind.windSpeed()) + "m로 다소 강해요. 능선에서 조심하세요."
                ));
            }
        }

        // 최고 기온이 33℃ 이상이면 BAD, 30℃ 이상이면 CAUTION이다.
        HourForecast hot = max(hours, h -> h.temperature() == null ? -99.0 : h.temperature());
        if (hot != null && hot.temperature() != null) {
            if (hot.temperature() >= 33) {
                found.add(new Finding(
                    Level.BAD,
                    5,
                    "최고 " + fmt(hot.temperature()) + "℃예요. 한낮 산행은 피하고 물을 충분히 챙기세요."
                ));
            }
            else if (hot.temperature() >= 30) {
                found.add(new Finding(
                    Level.CAUTION,
                    5,
                    "최고 " + fmt(hot.temperature()) + "℃로 더워요. 물을 충분히 챙기세요."
                ));
            }
        }

        // null 기온은 최저값 선택에서 제외하며, 최저 기온이 -10℃ 이하이면 BAD, 0℃ 이하이면 CAUTION이다.
        HourForecast cold = hours.stream().filter(h -> h.temperature() != null)
        .min(Comparator.comparingDouble(HourForecast::temperature)).orElse(null);
        if (cold != null) {
            if (cold.temperature() <= -10) {
                found.add(new Finding(
                    Level.BAD,
                    6,
                    "최저 " + fmt(cold.temperature()) + "℃예요. 저체온 위험이 있어 산행을 자제하세요."
                ));
            }
            else if (cold.temperature() <= 0) {
                found.add(new Finding(
                    Level.CAUTION,
                    6,
                    "최저 " + fmt(cold.temperature()) + "℃예요. 결빙 구간이 있을 수 있으니 아이젠을 챙기세요."
                ));
            }
        }

        // 위험 조건이 없다는 뜻이며, 누락된 예보값의 보충이나 자료 완전성 확인 결과는 아니다.
        if (found.isEmpty()) {
            return new Verdict(Level.GOOD, "등산하기 좋은 날씨예요. 즐거운 산행 되세요!", List.of());
        }
        // 여러 위험 요인이 동시에 있으면 위험도와 우선순위로 대표 문구를 정하고 나머지는 참고 사항으로 제공한다.
        // BAD가 CAUTION보다 우선하며 같은 등급에서는 강수형태→강수확률→강수량→적설→풍속→고온→저온 순이다.
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

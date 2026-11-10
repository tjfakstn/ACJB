package com.acjb.server.scan;

/**
 * scan이 비교하는 3개 property 값(SPEC 4절 — color·font_weight·radius만). 한쪽에서 추출하지 못한 값은 null이며,
 * null인 property는 비교·저장하지 않는다(N3).
 *
 * @param color      sRGB 0~255 채널. 투명도가 있으면 범위 밖이라 null
 * @param fontWeight 정수 굵기(예 600)
 * @param radiusPx   네 모서리가 같은 px 반경. 모서리마다 다르거나 px가 아니면 null
 */
public record StyleValues(Rgb color, Integer fontWeight, Double radiusPx) {

    public record Rgb(int r, int g, int b) {
        public Rgb {
            if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) {
                throw new IllegalArgumentException("RGB 채널은 0~255여야 합니다.");
            }
        }

        /** 저장 형식: #RRGGBB(대문자) */
        public String hex() {
            return String.format("#%02X%02X%02X", r, g, b);
        }
    }
}

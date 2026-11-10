package com.acjb.server.liveqa;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;

/**
 * 첨부 이미지의 형식을 파일 이름·Content-Type이 아니라 내용(시그니처)으로 판단하고 크기를 읽는다(AC30).
 * webp는 JDK ImageIO가 읽지 못해 헤더(VP8 /VP8L/VP8X)에서 크기를 직접 읽는다.
 */
public final class ImageFormats {

    public record Info(String contentType, int width, int height) {
    }

    private ImageFormats() {
    }

    /** 지원하지 않는 형식이거나 깨진 이미지면 null */
    public static Info detect(byte[] b) {
        if (b == null || b.length < 16) {
            return null;
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return viaImageIo(b, "image/png");
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return viaImageIo(b, "image/jpeg");
        }
        if (b.length >= 30 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return webp(b);
        }
        return null;
    }

    private static Info viaImageIo(byte[] b, String type) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(b));
            return img == null ? null : new Info(type, img.getWidth(), img.getHeight());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Info webp(byte[] b) {
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        int w;
        int h;
        switch (chunk) {
            case "VP8X" -> {
                w = 1 + u24(b, 24);
                h = 1 + u24(b, 27);
            }
            case "VP8 " -> {
                // 키프레임 시그니처 9d 01 2a 다음 2바이트씩 14비트 크기
                if ((b[23] & 0xFF) != 0x9D || (b[24] & 0xFF) != 0x01 || (b[25] & 0xFF) != 0x2A) {
                    return null;
                }
                w = ((b[26] & 0xFF) | (b[27] & 0xFF) << 8) & 0x3FFF;
                h = ((b[28] & 0xFF) | (b[29] & 0xFF) << 8) & 0x3FFF;
            }
            case "VP8L" -> {
                if ((b[20] & 0xFF) != 0x2F) {
                    return null;
                }
                int bits = (b[21] & 0xFF) | (b[22] & 0xFF) << 8 | (b[23] & 0xFF) << 16 | (b[24] & 0xFF) << 24;
                w = 1 + (bits & 0x3FFF);
                h = 1 + ((bits >> 14) & 0x3FFF);
            }
            default -> {
                return null;
            }
        }
        return w > 0 && h > 0 ? new Info("image/webp", w, h) : null;
    }

    private static int u24(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16;
    }
}

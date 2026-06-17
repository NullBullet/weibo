package com.jun.utils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Random;

/**
 * 验证码工具类
 */
public class CaptchaUtil {

    private static final int WIDTH = 130;
    private static final int HEIGHT = 48;
    private static final int CODE_LENGTH = 4;
    private static final char[] CHARS = "ABCDEFGHJKLMNPQRTUVWXY346789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成验证码图片，返回 {code, imageBase64}
     */
    public static CaptchaResult generate() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // 背景
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // 噪点
        g.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i < 60; i++) {
            int x = RANDOM.nextInt(WIDTH);
            int y = RANDOM.nextInt(HEIGHT);
            g.drawRect(x, y, 1, 1);
        }

        // 干扰线
        g.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i < 3; i++) {
            int x1 = RANDOM.nextInt(WIDTH);
            int y1 = RANDOM.nextInt(HEIGHT);
            int x2 = RANDOM.nextInt(WIDTH);
            int y2 = RANDOM.nextInt(HEIGHT);
            g.drawLine(x1, y1, x2, y2);
        }

        // 生成验证码
        StringBuilder sb = new StringBuilder();
        Font font = new Font("Courier New", Font.BOLD, 30);
        g.setFont(font);

        for (int i = 0; i < CODE_LENGTH; i++) {
            char c = CHARS[RANDOM.nextInt(CHARS.length)];
            sb.append(c);

            g.setColor(new Color(RANDOM.nextInt(150), RANDOM.nextInt(150), RANDOM.nextInt(150)));
            int x = 15 + i * 28 + RANDOM.nextInt(6);
            int y = 30 + RANDOM.nextInt(10);
            // 轻微旋转
            double angle = (RANDOM.nextDouble() - 0.5) * 0.3;
            g.rotate(angle, x, y);
            g.drawString(String.valueOf(c), x, y);
            g.rotate(-angle, x, y);
        }

        g.dispose();

        // Base64
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpeg", bos);
            byte[] bytes = bos.toByteArray();
            String base64 = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
            return new CaptchaResult(sb.toString(), base64);
        } catch (Exception e) {
            throw new RuntimeException("验证码生成失败", e);
        }
    }

    public record CaptchaResult(String code, String image) {}
}

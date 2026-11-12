package com.ggmount.member.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ImageUploadValidator {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_SIDE = 4096;
    private static final long MAX_PIXELS = 16_000_000;

    public record ImageData(byte[] content, String mediaType) {
    }

    // 빈 파일은 400으로 거부하고, 신고된 크기와 실제 읽은 바이트 수 모두 5MiB 이하인지 확인한다.
    public ImageData validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지를 선택해주세요.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw tooLarge();
        }
        try (InputStream input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw tooLarge();
            }
            return decode(bytes);
        }
        catch (IOException e) {
            // 파일 읽기·이미지 처리 중 입출력 실패는 정상 이미지가 아닌 것으로 처리해 400을 반환한다.
            throw invalidImage();
        }
    }

    // 확장자나 요청 MIME을 신뢰하지 않고 실제 이미지 형식과 해상도를 확인한다.
    // 픽셀 수는 디코딩 전에 제한하고, 재인코딩한 이미지 바이트만 저장한다.
    private ImageData decode(byte[] bytes) throws IOException {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) {
                throw invalidImage();
            }
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw invalidImage();
            }
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg")) {
                    throw invalidImage();
                }
                reader.setInput(stream, true, true);
                // PNG·JPEG만 허용하며 첫 이미지의 각 변은 1~4096px, 전체는 1600만 픽셀 이하로 제한한다.
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE
                    ||(long) width * height > MAX_PIXELS) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "이미지는 한 변 4096px, 총 1600만 픽셀 이하로 올려주세요."
                    );
                }
                var decoded = reader.read(0);
                var output = new ByteArrayOutputStream();
                boolean png = format.equals("png");
                // 원본 파일명·메타데이터·뒤에 붙은 데이터를 그대로 저장하지 않고 디코딩한 픽셀로 다시 인코딩한다.
                if (!ImageIO.write(decoded, png ? "png" : "jpeg", output)) {
                    throw invalidImage();
                }
                // 재인코딩 결과도 5MiB를 넘으면 413으로 거부한다.
                if (output.size() > MAX_BYTES) {
                    throw tooLarge();
                }
                return new ImageData(output.toByteArray(), png ? "image/png" : "image/jpeg");
            }
            finally {
                reader.dispose();
            }
        }
    }

    private ResponseStatusException invalidImage() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "정상적인 PNG 또는 JPEG 이미지만 올려주세요.");
    }

    private ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.valueOf(413), "이미지는 5MiB 이하로 올려주세요.");
    }
}

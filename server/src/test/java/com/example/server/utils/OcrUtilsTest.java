package com.example.server.utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
class OcrUtilsTest {
    @TempDir Path directory;
    @Test void actualTesseractKeepsDiagnosticsOutOfRecognizedText() throws Exception {
        try {
            var probe = new ProcessBuilder("tesseract", "--list-langs").redirectErrorStream(true).start();
            String languages = new String(probe.getInputStream().readAllBytes());
            assumeTrue(probe.waitFor() == 0 && languages.contains("chi_sim"));
        } catch (java.io.IOException e) { assumeTrue(false, "Tesseract is unavailable"); }
        var image = new BufferedImage(900, 150, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 900, 150);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 50));
        graphics.drawString("FRAME TRACE VIDEO", 30, 90);
        graphics.dispose();
        var file = directory.resolve("frame.png").toFile();
        ImageIO.write(image, "png", file);
        String text = new OcrUtils("tesseract").recognize(file);
        assertTrue(text.contains("FRAME"), text);
        assertFalse(text.contains("Estimating resolution"), text);
    }
}

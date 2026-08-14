import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * 邀请码「经 JPEG 有损压缩后还认不认」的量化验证（微信转发那一手）。
 *
 * 与 app/src/test 里的 InvitePayloadQrTest 是同一套渲染/解码管线，只是那边跑在 Android 单测里、
 * classpath 上没有 java.awt / javax.imageio，做不了真 JPEG，故这一轮挪到桌面 JVM 上单跑。
 *
 * 用法：java -cp <zxing-core.jar> QrJpegBench.java
 */
public class QrJpegBench {

    static final int MODULE_PX = 10;
    static final int QUIET_MODULES = 4;
    static final Random RND = new Random(20260814);
    static final byte[] KEY = new byte[32];
    static { new SecureRandom().nextBytes(KEY); }

    public static void main(String[] args) throws Exception {
        for (float q : new float[] {0.9f, 0.7f, 0.5f}) {
            run("新格式·密文", 300, q, true);
            run("旧格式·明文", 300, q, false);
        }
    }

    static void run(String label, int count, float quality, boolean encrypted) throws Exception {
        int failures = 0, chars = 0, side = 0;
        TreeMap<Integer, Integer> versions = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            String content = encrypted ? newPayload() : sampleJson(true);
            chars += content.length();
            QRCode code = Encoder.encode(content, ErrorCorrectionLevel.M,
                    Map.of(EncodeHintType.CHARACTER_SET, "UTF-8"));
            versions.merge(code.getVersion().getVersionNumber(), 1, Integer::sum);
            int[] px = render(code);
            side = (int) Math.sqrt(px.length);
            int[] after = jpeg(px, side, quality);
            if (!content.equals(decode(after, side))) failures++;
        }
        System.out.printf("[%s q=%.1f] 失败 %d/%d｜均长 %d 字符｜版本 %s｜出图 %dx%d%n",
                label, quality, failures, count, chars / count, versions, side, side);
    }

    static int[] render(QRCode code) {
        var m = code.getMatrix();
        int quiet = QUIET_MODULES * MODULE_PX;
        int side = m.getWidth() * MODULE_PX + quiet * 2;
        int[] px = new int[side * side];
        java.util.Arrays.fill(px, 0xFFFFFFFF);
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                if (m.get(x, y) != 1) continue;
                for (int dy = 0; dy < MODULE_PX; dy++) {
                    int row = (quiet + y * MODULE_PX + dy) * side;
                    for (int dx = 0; dx < MODULE_PX; dx++) px[row + quiet + x * MODULE_PX + dx] = 0xFF000000;
                }
            }
        }
        return px;
    }

    static String decode(int[] px, int side) {
        RGBLuminanceSource src = new RGBLuminanceSource(side, side, px);
        BinaryBitmap[] cands = { new BinaryBitmap(new HybridBinarizer(src)), new BinaryBitmap(new GlobalHistogramBinarizer(src)) };
        Map<DecodeHintType, Object> pure = Map.of(DecodeHintType.PURE_BARCODE, true, DecodeHintType.TRY_HARDER, true);
        Map<DecodeHintType, Object> scene = Map.of(DecodeHintType.TRY_HARDER, true);
        for (Map<DecodeHintType, Object> hints : java.util.List.of(pure, scene)) {
            for (BinaryBitmap b : cands) {
                try {
                    String t = new QRCodeReader().decode(b, hints).getText();
                    if (t != null && !t.isBlank()) return t;
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    static int[] jpeg(int[] px, int side, float quality) throws Exception {
        BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, side, side, px, 0, side);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageOutputStream ios = ImageIO.createImageOutputStream(out);
        w.setOutput(ios);
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        w.write(null, new IIOImage(img, null, null), p);
        w.dispose();
        ios.close();
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
        return back.getRGB(0, 0, side, side, null, 0, side);
    }

    static String sampleJson(boolean withSidAndVer) {
        String sn = RND.nextBoolean() ? String.format("DEV-%08x", RND.nextInt())
                : randomAlnum(16);
        byte[] pk = new byte[33];
        RND.nextBytes(pk);
        String tpk = Base64.getEncoder().encodeToString(pk);
        long exp = System.currentTimeMillis() + 120_000;
        String v6 = String.format("240e:%x:%x:%x:%x:%x:%x:%x", RND.nextInt(0xffff), RND.nextInt(0xffff),
                RND.nextInt(0xffff), RND.nextInt(0xffff), RND.nextInt(0xffff), RND.nextInt(0xffff), RND.nextInt(0xffff));
        String v4 = "192.168." + RND.nextInt(255) + "." + RND.nextInt(255);
        String addrs = RND.nextInt(4) == 0 ? "[\"" + v6 + "\"]" : "[\"" + v6 + "\",\"" + v4 + "\"]";
        String head = withSidAndVer
                ? String.format("\"ver\":2,\"sn\":\"%s\",\"sid\":\"%08x\",", sn, RND.nextInt())
                : String.format("\"sn\":\"%s\",", sn);
        return "{" + head + "\"tpk\":\"" + tpk + "\",\"exp\":" + exp + ",\"addrs\":" + addrs + "}";
    }

    static String randomAlnum(int n) {
        String set = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(set.charAt(RND.nextInt(set.length())));
        return sb.toString();
    }

    static String newPayload() throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, iv));
        byte[] body = c.doFinal(("3" + sampleJson(false)).getBytes("UTF-8"));
        byte[] all = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(body, 0, all, iv.length, body.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    }
}

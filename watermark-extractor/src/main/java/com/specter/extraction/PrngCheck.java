import com.specter.extraction.util.SpectralPrng;
import java.util.Arrays;

public class PrngCheck {
    public static void main(String[] args) {
        byte[] key = parseHex("471fa92a43ae5853a3068ca7c152fb1faf662892436c910edea59d59f89c64ed");
        String context = "specter-v1/cell-map/48x27/252";
        SpectralPrng prng = new SpectralPrng(key, context);
        int[] cells = prng.selectDistinct(1296, 252);
        System.out.println("First 10 cells: " + Arrays.toString(Arrays.copyOf(cells, 10)));
    }

    private static byte[] parseHex(String hex) {
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
}

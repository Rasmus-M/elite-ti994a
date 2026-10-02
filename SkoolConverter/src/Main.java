import java.io.File;
import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        new SkoolConverter(true).convert(
            new File("../elite-msx-skoolkit/src/elite.skool"),
            new File("src/main-code.a99"),
            0x0100,
            0xf81c
        );
    }
}

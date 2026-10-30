import com.cleanmyphone.launcher.game.MotionDetector;

/** Synthetic-video test:
 * javac -d out launcher/app/src/main/java/com/cleanmyphone/launcher/game/MotionDetector.java tools/DetectorSimTest.java && java -cp out DetectorSimTest
 */
public class DetectorSimTest {
    static final int W = 640, H = 480;
    static long t = 0;
    static MotionDetector d;

    static void run(int n, int px, int top, int bot, boolean person) {
        for (int k = 0; k < n; k++) {
            byte[] y = new byte[W * H];
            java.util.Arrays.fill(y, (byte) 100);
            if (person) for (int r = top; r < bot; r++) for (int c = px - 40; c < px + 40; c++) y[r * W + c] = (byte) 200;
            t += 33;
            d.onFrame(y, W, H, t);
        }
    }

    public static void main(String[] a) {
        d = new MotionDetector(new MotionDetector.Listener() {
            public void emit(String n, int v) { System.out.println(t + "ms  " + n + " = " + v); }
            public void debug(String j) { }
        });
        d.calibrate();
        run(140, 0, 0, 0, false);
        run(70, 320, 100, 420, true);
        System.out.println("-- step to image-left (expect lane 2 with mirror)"); run(20, 220, 100, 420, true);
        System.out.println("-- back to centre (expect lane 1)");                 run(20, 320, 100, 420, true);
        System.out.println("-- jump (expect jump)");                              run(10, 320, 40, 380, true); run(15, 320, 100, 420, true);
        System.out.println("-- hands up (expect hands, no jump)");                run(10, 320, 30, 420, true); run(15, 320, 100, 420, true);
        System.out.println("-- squat (expect slide 1 then 0)");                   run(12, 320, 200, 420, true); run(15, 320, 100, 420, true);
    }
}

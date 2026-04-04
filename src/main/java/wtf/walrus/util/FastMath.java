package wtf.walrus.util;

public class FastMath {
    public static String format(double v, int s) {
        double pow = 1.0;
        for (int i = 0; i < s; i++) pow *= 10.0;

        long l = (long)(v * pow);
        long intPart = l / (long)pow;
        long fracPart = l % (long)pow;

        StringBuilder sb = new StringBuilder();
        sb.append(intPart);
        sb.append('.');

        String fracStr = Long.toString(fracPart);
        sb.append("0".repeat(Math.max(0, s - fracStr.length())));
        sb.append(fracStr);

        return sb.toString();
    }
}

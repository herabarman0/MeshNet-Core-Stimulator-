package org.junit.jupiter.api;
import java.util.Arrays;
/** Offline stand-in for the subset of JUnit 5 assertions the test suites use. */
public final class Assertions {
    private Assertions() { }
    public interface Executable { void execute() throws Throwable; }
    private static void fail0(String m) { throw new AssertionError(m); }
    public static void fail(String m) { fail0(m); }
    public static void assertTrue(boolean c) { if (!c) fail0("expected true"); }
    public static void assertTrue(boolean c, String m) { if (!c) fail0(m); }
    public static void assertFalse(boolean c) { if (c) fail0("expected false"); }
    public static void assertFalse(boolean c, String m) { if (c) fail0(m); }
    public static void assertNull(Object o) { if (o != null) fail0("expected null but was " + o); }
    public static void assertNotNull(Object o) { if (o == null) fail0("expected not null"); }
    public static void assertEquals(Object e, Object a) { if (!(e == null ? a == null : e.equals(a))) fail0("expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(Object e, Object a, String m) { if (!(e == null ? a == null : e.equals(a))) fail0(m + " expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(long e, long a) { if (e != a) fail0("expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(long e, long a, String m) { if (e != a) fail0(m + " expected <" + e + "> but was <" + a + ">"); }
    public static void assertEquals(double e, double a, double d) { if (!(Math.abs(e - a) <= d)) fail0("expected <" + e + "> but was <" + a + "> (delta " + d + ")"); }
    public static void assertEquals(double e, double a, double d, String m) { if (!(Math.abs(e - a) <= d)) fail0(m + " expected <" + e + "> but was <" + a + ">"); }
    public static void assertNotEquals(Object u, Object a) { if (u == null ? a == null : u.equals(a)) fail0("expected different from <" + u + ">"); }
    public static void assertSame(Object e, Object a) { if (e != a) fail0("expected same instance"); }
    public static void assertArrayEquals(byte[] e, byte[] a) { if (!Arrays.equals(e, a)) fail0("byte arrays differ"); }
    public static void assertArrayEquals(int[] e, int[] a) { if (!Arrays.equals(e, a)) fail0("int arrays differ"); }
    public static void assertArrayEquals(long[] e, long[] a) { if (!Arrays.equals(e, a)) fail0("long arrays differ"); }
    public static void assertArrayEquals(Object[] e, Object[] a) { if (!Arrays.deepEquals(e, a)) fail0("arrays differ"); }
    public static <T extends Throwable> T assertThrows(Class<T> type, Executable ex) {
        try { ex.execute(); } catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            throw new AssertionError("expected " + type.getName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + type.getName() + " but nothing was thrown");
    }
}

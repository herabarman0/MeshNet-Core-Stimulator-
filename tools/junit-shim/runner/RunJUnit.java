package runner;
import java.lang.reflect.*;
import java.util.*;
/** Minimal runner: java runner.RunJUnit <class>... runs every @Test method (fresh instance each). Exit 1 on failure. */
public final class RunJUnit {
    public static void main(String[] args) throws Exception {
        int pass = 0, fail = 0;
        for (String cn : args) {
            Class<?> c = Class.forName(cn);
            List<Method> ms = new ArrayList<>();
            for (Method m : c.getDeclaredMethods()) if (m.isAnnotationPresent(org.junit.jupiter.api.Test.class)) ms.add(m);
            Collections.sort(ms, new Comparator<Method>() { public int compare(Method a, Method b) { return a.getName().compareTo(b.getName()); } });
            for (Method m : ms) {
                try {
                    Constructor<?> k = c.getDeclaredConstructor(); k.setAccessible(true);
                    m.setAccessible(true); m.invoke(k.newInstance()); pass++;
                } catch (InvocationTargetException e) {
                    fail++; System.out.println("FAIL " + cn + "." + m.getName() + ": " + e.getCause());
                    if (System.getenv("TRACE") != null) e.getCause().printStackTrace(System.out);
                }
            }
        }
        System.out.println("junit-shim: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}

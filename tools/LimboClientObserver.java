package moe.ryuu.kyokouauth.tools.observer;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Read-only observer: no class transformation, state mutation, packet construction or network access. */
public final class LimboClientObserver {
    public static void premain(String mappingFile, Instrumentation instrumentation) throws Exception {
        Properties names = new Properties();
        try (var input = Files.newBufferedReader(Path.of(mappingFile))) { names.load(input); }
        Thread observer = new Thread(() -> observe(names, instrumentation), "KyokouAuth-read-only-client-observer");
        observer.setDaemon(true);
        observer.start();
    }

    private static void observe(Properties names, Instrumentation instrumentation) {
        try {
            // Do not force-load the game class. Wait until the original launcher has loaded it naturally.
            Class<?> minecraft = null;
            while (minecraft == null) {
                for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
                    if (candidate.getName().equals(names.getProperty("client.class"))) { minecraft = candidate; break; }
                }
                if (minecraft == null) Thread.sleep(250);
            }
            Method instance = minecraft.getDeclaredMethod(names.getProperty("client.instance"));
            instance.setAccessible(true);
            String screenPath = names.getProperty("client.screen");
            Field level = field(minecraft, names.getProperty("client.level"));
            Field player = field(minecraft, names.getProperty("client.player"));
            String previous = "";
            while (true) {
                Object game = instance.invoke(null);
                if (game != null) {
                    Object currentScreen = readPath(game, screenPath);
                    boolean hasLevel = level.get(game) != null;
                    boolean hasPlayer = player.get(game) != null;
                    String state = "level=" + hasLevel + " player=" + hasPlayer + " screen=" +
                        (currentScreen == null ? "null" : currentScreen.getClass().getName());
                    if (!state.equals(previous)) {
                        System.out.println("CLIENT_OBSERVER " + state + " readonly=true");
                        previous = state;
                    }
                }
                Thread.sleep(250);
            }
        } catch (Throwable failure) {
            System.out.println("CLIENT_OBSERVER_ERROR " + failure.getClass().getSimpleName() + " field=" + failure.getMessage());
        }
    }

    private static Object readPath(Object object, String path) throws ReflectiveOperationException {
        Object current = object;
        for (String part : path.split("\\.")) {
            if (current == null) return null;
            current = field(current.getClass(), part).get(current);
        }
        return current;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}

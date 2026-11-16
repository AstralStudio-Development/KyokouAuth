import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;

/** Starts the final artifact with proxy-provided Netty only; never a test/full upstream SDK. */
public final class LimboAssemblySmoke {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Usage: LimboAssemblySmoke artifact.jar [--netty-classpath jars] [--protocol ids...]");
        String netty = null;
        boolean subset = false;
        Set<Integer> versions = new LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--netty-classpath") && i + 1 < args.length) netty = args[++i];
            else if (args[i].equals("--protocol") && !subset) subset = true;
            else if (subset && args[i].matches("[0-9]+")) {
                int protocol = Integer.parseInt(args[i]);
                if (protocol < 767 || protocol > 777 || !versions.add(protocol)) throw new IllegalArgumentException("Invalid/duplicate protocol: " + protocol);
            } else throw new IllegalArgumentException("Unexpected assembly argument: " + args[i]);
        }
        if (subset && versions.isEmpty()) throw new IllegalArgumentException("--protocol requires explicit IDs");
        if (!subset) for (int protocol = 767; protocol <= 777; protocol++) versions.add(protocol);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader = NewExporter.openArtifact(Path.of(args[0]), netty)) {
            Thread.currentThread().setContextClassLoader(loader);
            run(loader, versions, subset);
            System.out.println("ASSEMBLY PROVIDED-NETTY " + loader.nettyProvenance);
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }

    private static void run(ClassLoader loader, Set<Integer> versions, boolean subset) throws Exception {
        String root = "moe.ryuu.kyokouauth.limbo.";
        Class<?> settingsType = Class.forName(root + "LimboSettings", true, loader);
        Object settings = settingsType.getConstructor().newInstance();
        Class<?> listenerType = Class.forName(root + "LimboListener", true, loader);
        Object listener = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{listenerType}, (p,m,a)->null);
        Class<?> loggerType = Class.forName(root + "LimboLogger", true, loader);
        Object logger = java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{loggerType},(p,m,a)->{
            if(a != null && a.length == 2 && a[1] instanceof Throwable failure) failure.printStackTrace();
            return null;
        });
        Class<?> serverType = Class.forName(root + "LimboServer", true, loader);
        Class<?> forwardingType = Class.forName(root + "ForwardingMode", true, loader);
        Constructor<?> constructor = serverType.getConstructor(settingsType, listenerType, loggerType,
                InetSocketAddress.class, byte[].class, forwardingType);
        Object server = constructor.newInstance(settings, listener, logger, new InetSocketAddress("127.0.0.1",0),
                "assembly-test-secret".getBytes(StandardCharsets.UTF_8), null);
        Method start = subset ? serverType.getDeclaredMethod("startProtocols", Set.class) : serverType.getMethod("startAll");
        if (subset) start.setAccessible(true);
        Map<?,?> endpoints;
        try {
            endpoints = (Map<?,?>) (subset ? start.invoke(server, versions) : start.invoke(server));
            if (!endpoints.keySet().equals(versions)) throw new AssertionError("Engine directory differs");
            Set<Integer> ports = new HashSet<>();
            for (var entry:endpoints.entrySet()) {
                InetSocketAddress address = (InetSocketAddress) entry.getValue();
                if(!address.getAddress().isLoopbackAddress() || address.getPort() == 0 || !ports.add(address.getPort()))
                    throw new AssertionError("Listener not private and unique");
                try(Socket socket = new Socket()) { socket.connect(address,2000); }
                System.out.println("ASSEMBLY PASS protocol="+entry.getKey()+" private-loopback=true");
            }
        } finally { serverType.getMethod("close").invoke(server); }
        for (Object value : endpoints.values()) {
            try (Socket socket = new Socket()) {
                socket.connect((InetSocketAddress) value, 2000);
                throw new AssertionError("Listener still accepts after close: " + value);
            } catch (ConnectException expected) { /* Closed loopback listener. */ }
        }
        System.out.println("ASSEMBLY SUMMARY engines="+versions.size()+" scope="+(subset ? "explicit-protocol-subset" : "production-eleven")
                +" all-closed=true renderer=NOT_TESTED");
    }
}

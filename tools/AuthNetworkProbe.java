import java.net.*;
import java.net.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Four read-only, credential-free network measurements; does not authenticate or request codes. */
public final class AuthNetworkProbe {
    public static void main(String[] args) throws Exception {
        boolean directoryOnly = args.length > 0 && args[0].equals("--directory");
        String[] names = directoryOnly ? new String[]{"legacy-name", "services-known-name", "services-test-name"}
            : new String[]{"profile-directory", "session-hasJoined"};
        String[] urls = directoryOnly ? new String[]{
            "https://api.mojang.com/users/profiles/minecraft/KyokouProbe_26",
            "https://api.minecraftservices.com/minecraft/profile/lookup/name/Notch",
            "https://api.minecraftservices.com/minecraft/profile/lookup/name/KyokouProbe_26"}
            : new String[]{"https://api.mojang.com/users/profiles/minecraft/Notch",
            "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=KyokouProbeMissing&serverId=0"};
        for (boolean proxied : new boolean[]{false, true}) {
            ProxySelector route = proxied ? ProxySelector.of(new InetSocketAddress("127.0.0.1", 7890)) : new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
            };
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).proxy(route).build()) {
                for (int i = 0; i < urls.length; i++) {
                    long started = System.nanoTime();
                    try {
                        var response = client.send(HttpRequest.newBuilder(URI.create(urls[i])).timeout(Duration.ofSeconds(8))
                            .header("User-Agent", "KyokouAuth-NetworkProbe").GET().build(), HttpResponse.BodyHandlers.ofString());
                        System.out.printf("%s %s status=%d elapsedMs=%d contentType=%s retryAfter=%s profileId=%s notFound=%s%n",
                            proxied ? "proxy7890" : "direct", names[i], response.statusCode(),
                            (System.nanoTime()-started)/1_000_000, response.headers().firstValue("content-type").orElse("none"),
                            response.headers().firstValue("retry-after").orElse("none"),
                            response.body().contains("\"id\""), response.body().contains("NOT_FOUND"));
                    } catch (IOException failure) {
                        System.out.printf("%s %s failure=%s elapsedMs=%d%n", proxied ? "proxy7890" : "direct", names[i],
                            failure.getClass().getSimpleName(), (System.nanoTime()-started)/1_000_000);
                    }
                }
            }
        }
    }
}

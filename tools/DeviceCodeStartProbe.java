import moe.ryuu.kyokouauth.core.auth.DeviceCodeFlow;

/** One device-code start, no account login; never prints the resulting code, device key, URI or token. */
public final class DeviceCodeStartProbe {
    public static void main(String[] args) {
        long started = System.nanoTime();
        try {
            var challenge = DeviceCodeFlow.INSTANCE.begin();
            if (challenge.getUserCode().isBlank() || challenge.getVerificationUriComplete().isBlank()) {
                throw new AssertionError("Required challenge fields absent");
            }
            System.out.printf("DEVICE_START_OK elapsedMs=%d intervalSeconds=%d lifetimeSeconds=%d%n",
                (System.nanoTime() - started) / 1_000_000, challenge.getIntervalSeconds(), challenge.getExpiresInSeconds());
        } catch (Exception failure) {
            if (failure instanceof DeviceCodeFlow.DeviceCodeException deviceFailure) {
                System.out.printf("DEVICE_START_FAILED code=%s httpStatus=%s elapsedMs=%d%n", deviceFailure.getCode(),
                    deviceFailure.getHttpStatus(), (System.nanoTime() - started) / 1_000_000);
            } else System.out.printf("DEVICE_START_FAILED type=%s%n", failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
}

package dev.textagent.core;

import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounds both response size and total elapsed time, including the response body. */
final class HttpTransport {
  private HttpTransport() {}

  static <T> HttpResponse<T> send(
      HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler, Duration timeout)
      throws Exception {
    var future = client.sendAsync(request, handler);
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }

  static HttpResponse.BodyHandler<byte[]> limited(int maxResponseBytes) {
    return info -> new LimitedSubscriber(maxResponseBytes);
  }

  private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int maxResponseBytes;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedSubscriber(int maxResponseBytes) {
      this.maxResponseBytes = maxResponseBytes;
    }

    public CompletionStage<byte[]> getBody() {
      return result;
    }

    public void onSubscribe(Flow.Subscription newSubscription) {
      subscription = newSubscription;
      newSubscription.request(1);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > maxResponseBytes - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(new IllegalStateException("Response too large"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}

package qunar.tc.qmq.web;


import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.LongSerializationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qunar.tc.qmq.base.RemoteMessageQuery;
import qunar.tc.qmq.concurrent.NamedThreadFactory;
import qunar.tc.qmq.configuration.DynamicConfig;
import qunar.tc.qmq.store.GetMessageResult;
import qunar.tc.qmq.store.GetMessageStatus;
import qunar.tc.qmq.store.SegmentBuffer;
import qunar.tc.qmq.store.Storage;
import qunar.tc.qmq.utils.Bytes;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/**
 * Created by zhaohui.yu
 * 3/14/19
 */
public class QueryMessageServlet extends HttpServlet {
    private static final Logger LOG = LoggerFactory.getLogger(QueryMessageServlet.class);

    private static final Gson serializer = new GsonBuilder().setLongSerializationPolicy(LongSerializationPolicy.STRING).create();

    private Storage store;
    private final ExecutorService threadPoolExecutor;

    public QueryMessageServlet(DynamicConfig config, Storage store) {
        this.store = store;
        this.threadPoolExecutor = new ThreadPoolExecutor(1, config.getInt("query.max.threads", 5)
                , 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), new NamedThreadFactory("query-msg"));
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        String queryJson = req.getParameter("backupQuery");
        if (Strings.isNullOrEmpty(queryJson)) return;
        AsyncContext context = req.startAsync();
        RemoteMessageQuery query = serializer.fromJson(queryJson, RemoteMessageQuery.class);
        if (query == null) {
            context.complete();
            return;
        }

        ServletResponse response = context.getResponse();
        CompletableFuture<Boolean> future = query(query, response);
        future.exceptionally(throwable -> {
            LOG.error("Failed to query messages. {}", query, throwable);
            return true;
        }).thenAccept(aBoolean -> context.complete());
    }

    private CompletableFuture<Boolean> query(RemoteMessageQuery query, ServletResponse response) {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            threadPoolExecutor.execute(() -> {
                try {
                    final String subject = query.getSubject();
                    final List<RemoteMessageQuery.MessageKey> keys = query.getKeys();

                    ServletOutputStream os = response.getOutputStream();
                    for (RemoteMessageQuery.MessageKey key : keys) {
                        long sequence = key.getSequence();
                        GetMessageResult result = store.getMessage(subject, sequence);
                        if (result.getStatus() != GetMessageStatus.SUCCESS) continue;
                        try {
                            final byte[] sequenceBytes = Bytes.long2bytes(sequence);
                            final List<SegmentBuffer> buffers = result.getSegmentBuffers();
                            for (SegmentBuffer buffer : buffers) {
                                os.write(sequenceBytes);
                                ByteBuffer byteBuffer = buffer.getBuffer();
                                byte[] arr = new byte[byteBuffer.remaining()];
                                byteBuffer.get(arr);
                                os.write(arr);
                            }
                        } finally {
                            result.release();
                        }
                    }
                    os.flush();
                    os.close();
                    future.complete(true);
                } catch (IOException e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            threadPoolExecutor.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
    }
}

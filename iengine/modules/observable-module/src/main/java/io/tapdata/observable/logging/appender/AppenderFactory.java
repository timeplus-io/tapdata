package io.tapdata.observable.logging.appender;

import com.alibaba.fastjson.JSON;
import com.tapdata.constant.FileUtil;
import com.tapdata.tm.commons.schema.MonitoringLogsDto;
import lombok.SneakyThrows;
import net.openhft.chronicle.core.threads.InterruptedRuntimeException;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.UnrecoverableTimeoutException;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.ValueOut;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * @author jackin
 * @date 2022/6/20 11:55
 **/
public class AppenderFactory implements Serializable {
	private volatile static AppenderFactory INSTANCE;

	public static AppenderFactory getInstance(){
		if (INSTANCE == null) {
			synchronized (AppenderFactory.class) {
				if (INSTANCE == null) {
					INSTANCE = new AppenderFactory();
				}
			}
		}
		return INSTANCE;
	}

	private final Logger logger = LogManager.getLogger(AppenderFactory.class);
	private final static int BATCH_SIZE = 1000;
	private final static int BATCH_INTERVAL = 5000;
	private final static String APPEND_LOG_THREAD_NAME = "append-observe-logs-thread";
	private final static String CACHE_QUEUE_DIR = "CacheObserveLogs";

	private Long lastFlushAt;
	private ChronicleQueue cacheLogsQueue;

	private final List<Appender<MonitoringLogsDto>> appenders = new ArrayList<>();

	private final Semaphore emptyWaiting = new Semaphore(1);

	private final ExecutorService executorService = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1),
		r -> new Thread(r, APPEND_LOG_THREAD_NAME)
	);

	private AppenderFactory() {

		String cacheLogsDir = "." + File.separator + CACHE_QUEUE_DIR;

		FileUtil.deleteAll(new File(cacheLogsDir));
		cacheLogsQueue = SingleChronicleQueueBuilder.binary(cacheLogsDir)
			.build();

		executorService.submit(() -> {
			final ExcerptTailer tailer = cacheLogsQueue.createTailer();
			List<MonitoringLogsDto> logsDtos = new ArrayList<>();
			if (null == lastFlushAt) {
				lastFlushAt = System.currentTimeMillis();
			}
			while (true) {
				try {
					final MonitoringLogsDto.MonitoringLogsDtoBuilder builder = MonitoringLogsDto.builder();
					boolean success = tailer.readDocument(r -> {
						decodeFromWireIn(r.getValueIn(), builder);

					});
					if (success) {
						logsDtos.add(builder.build());
					} else {
						emptyWaiting.tryAcquire(1, 3, TimeUnit.SECONDS);
					}
					if (logsDtos.size() >= BATCH_SIZE || System.currentTimeMillis() - lastFlushAt >= BATCH_INTERVAL) {
						if (logsDtos.size() == 0) {
							lastFlushAt = System.currentTimeMillis();
							continue;
						}

						for (Appender<MonitoringLogsDto> appender : appenders) {
							appender.append(logsDtos);
						}
						logsDtos.clear();
						lastFlushAt = System.currentTimeMillis();
					}
				} catch (Exception e) {
					logger.warn("failed to append task logs, error: {}", e.getMessage());
					e.printStackTrace();
				}
			}
		});
	}

	public void register(Appender logAppender){
		appenders.add(logAppender);
	}

	public void appendLog(MonitoringLogsDto logsDto) {
		try {
			cacheLogsQueue.acquireAppender().writeDocument(w -> {
				final ValueOut valueOut = w.getValueOut();
				final Date date = logsDto.getDate();
				final String dateString = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(date);
				valueOut.writeString(dateString);
				valueOut.writeString(logsDto.getLevel());
				valueOut.writeString(logsDto.getErrorStack());
				valueOut.writeString(logsDto.getMessage());
				valueOut.writeString(logsDto.getTaskId());
				valueOut.writeString(logsDto.getTaskRecordId());
				valueOut.writeLong(logsDto.getTimestamp());
				valueOut.writeString(logsDto.getTaskName());
				valueOut.writeString(logsDto.getNodeId());
				valueOut.writeString(logsDto.getNodeName());
				final String logTagsJoinStr = String.join(",", CollectionUtils.isNotEmpty(logsDto.getLogTags()) ? logsDto.getLogTags() : new ArrayList<>(0));
				valueOut.writeString(logTagsJoinStr);
				if (null != logsDto.getData()) {
					valueOut.writeString(JSON.toJSON(logsDto.getData()).toString());
				}
			});
		} catch (InterruptedRuntimeException ignored) {
		}
		if (emptyWaiting.availablePermits() < 1) {
			emptyWaiting.release(1);
		}
	}

	@SneakyThrows
	private void decodeFromWireIn(ValueIn valueIn, MonitoringLogsDto.MonitoringLogsDtoBuilder builder) {
		final String dateString = valueIn.readString();
		final Date date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").parse(dateString);
		builder.date(date);
		final String level = valueIn.readString();
		builder.level(level);
		final String errorStack = valueIn.readString();
		builder.errorStack(errorStack);
		final String message = valueIn.readString();
		builder.message(message);
		final String taskId = valueIn.readString();
		builder.taskId(taskId);
		final String taskRecordId = valueIn.readString();
		builder.taskRecordId(taskRecordId);
		final long timestamp = valueIn.readLong();
		builder.timestamp(timestamp);
		final String taskName = valueIn.readString();
		builder.taskName(taskName);
		final String nodeId = valueIn.readString();
		builder.nodeId(nodeId);
		final String nodeName = valueIn.readString();
		builder.nodeName(nodeName);
		final String logTaskStr = valueIn.readString();
		if (StringUtils.isNotBlank(logTaskStr)) {
			builder.logTags(Arrays.asList(logTaskStr.split(",")));
		}
		final String dataStr = valueIn.readString();
		if (StringUtils.isNotBlank(dataStr)) {
			try {
				builder.data((Collection<? extends Map<String, Object>>) JSON.parseArray(dataStr,  (new HashMap<String, Object>()).getClass()));
			} catch (Exception e) {
				System.out.printf("");
			}

		}
	}

	private <T> T nullStringProcess(String inputString, Supplier<T> nullSupplier, Supplier<T> getResult) {
		return "null".equals(inputString) || null == inputString ? nullSupplier.get() : getResult.get();
	}

	public static void main(String[] args) {
		SingleChronicleQueue singleChronicleQueue = SingleChronicleQueueBuilder.binary("./"+CACHE_QUEUE_DIR).build();
		ExcerptTailer tailer = singleChronicleQueue.createTailer();
		while (true) {
			tailer.readDocument(r->{
				System.out.println(r.asText());
			});
		}
	}
}

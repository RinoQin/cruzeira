/*
 * This file is part of cruzeira and it's licensed under the project terms.
 */
package org.cruzeira.netty;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyServer extends ServletServer {

//	private boolean readingChunks;
	final Logger logger = LoggerFactory.getLogger(NettyServer.class);

	public NettyServer(ServerManager serverManager) {
		super(serverManager);
	}

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent event) throws Exception {
		logger.info("Received");
		serverManager.beforeRequest();
		HttpRequest request = (HttpRequest) event.getMessage();
		if (request.getUri().startsWith("/resources/")) {
			return;
		}

		// is100ContinueExpected(request);
		StringBuilder buf = new StringBuilder();

		Object[] servlets = doServlet(ctx, event, request, buf);
		if (servlets == null) {
		} else if (servlets[2] == Boolean.TRUE) {
			ctx.sendUpstream(event);
		} else if (request.isChunked()) {
//			readingChunks = true;
		} else {
			writeResponse(event, request, buf, servlets[0], servlets[1]);
		}
	}

	public static void main(String[] args) {
		Logger logger = LoggerFactory.getLogger(NettyServer.class);
		int cpus = Runtime.getRuntime().availableProcessors();
		logger.info("CPUs " + cpus);

		int asyncPool = cpus * 2 * 2;
		OrderedMemoryAwareThreadPoolExecutor eventExecutor = new OrderedMemoryAwareThreadPoolExecutor(asyncPool, 0, 0, 30, TimeUnit.SECONDS);

		ChannelFactory factory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool());
		// ChannelFactory factory = new
		// NioServerSocketChannelFactory(Executors.newSingleThreadExecutor(),
		// Executors.newSingleThreadExecutor(), 1);

		ServerBootstrap bootstrap = new ServerBootstrap(factory);
		new OpenWebJar();

		bootstrap.setPipelineFactory(new MyPipelineFactory(eventExecutor));

		bootstrap.setOption("child.tcpNoDelay", true);
		bootstrap.setOption("child.keepAlive", false);
		bootstrap.bind(new InetSocketAddress(8080));
		logger.info("Running cruzeira...");
	}

	static class MyPipelineFactory implements ChannelPipelineFactory {
		private ServerManager serverManager;
		private Executor pipelineExecutor;

		public MyPipelineFactory(Executor executor) {
			this.serverManager = new ServerManager();
			this.pipelineExecutor = executor;
		}

		public ChannelPipeline getPipeline() {
			// Create a default pipeline implementation.
			ChannelPipeline pipeline = Channels.pipeline();

			// Uncomment the following line if you want HTTPS
			// SSLEngine engine =
			// SecureChatSslContextFactory.getServerContext().createSSLEngine();
			// engine.setUseClientMode(false);
			// pipeline.addLast("ssl", new SslHandler(engine));

			pipeline.addLast("decoder", new HttpRequestDecoder());
			// Uncomment the following line if you don't want to handle
			// HttpChunks.
			pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
			pipeline.addLast("encoder", new HttpResponseEncoder());
			// Remove the following line if you don't want automatic content
			// compression.
			// pipeline.addLast("deflater", new HttpContentCompressor());
			pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
			pipeline.addLast("filehandler", new FileServer());

			pipeline.addLast("handler", new NettyServer(serverManager));
			pipeline.addLast("pipelineExecutor", new ExecutionHandler(pipelineExecutor));
			pipeline.addLast("asyncHandler", new AsyncServer(serverManager));
			return pipeline;
		}

	}
}

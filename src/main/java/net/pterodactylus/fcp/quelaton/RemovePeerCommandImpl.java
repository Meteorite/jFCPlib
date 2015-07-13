package net.pterodactylus.fcp.quelaton;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.pterodactylus.fcp.PeerRemoved;
import net.pterodactylus.fcp.RemovePeer;
import net.pterodactylus.fcp.UnknownNodeIdentifier;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * Default {@link RemovePeerCommand} implementation based on {@link FcpDialog}.
 *
 * @author <a href="mailto:bombe@freenetproject.org">David ‘Bombe’ Roden</a>
 */
public class RemovePeerCommandImpl implements RemovePeerCommand {

	private final ListeningExecutorService threadPool;
	private final ConnectionSupplier connectionSupplier;
	private final AtomicReference<String> nodeIdentifier = new AtomicReference<>();

	public RemovePeerCommandImpl(ExecutorService threadPool, ConnectionSupplier connectionSupplier) {
		this.threadPool = MoreExecutors.listeningDecorator(threadPool);
		this.connectionSupplier = connectionSupplier;
	}

	@Override
	public Executable<Boolean> byName(String name) {
		nodeIdentifier.set(name);
		return this::execute;
	}

	@Override
	public Executable<Boolean> byIdentity(String nodeIdentity) {
		nodeIdentifier.set(nodeIdentity);
		return this::execute;
	}

	@Override
	public Executable<Boolean> byHostAndPort(String host, int port) {
		nodeIdentifier.set(String.format("%s:%d", host, port));
		return this::execute;
	}

	private ListenableFuture<Boolean> execute() {
		return threadPool.submit(this::executeDialog);
	}

	private boolean executeDialog() throws IOException, ExecutionException, InterruptedException {
		RemovePeer removePeer = new RemovePeer(new RandomIdentifierGenerator().generate(), nodeIdentifier.get());
		try (RemovePeerDialog removePeerDialog = new RemovePeerDialog()) {
			return removePeerDialog.send(removePeer).get();
		}
	}

	private class RemovePeerDialog extends FcpDialog<Boolean> {

		private final AtomicBoolean finished = new AtomicBoolean();
		private final AtomicBoolean removed = new AtomicBoolean();

		public RemovePeerDialog() throws IOException {
			super(threadPool, connectionSupplier.get());
		}

		@Override
		protected boolean isFinished() {
			return finished.get() || removed.get();
		}

		@Override
		protected Boolean getResult() {
			return removed.get();
		}

		@Override
		protected void consumePeerRemoved(PeerRemoved peerRemoved) {
			removed.set(true);
		}

		@Override
		protected void consumeUnknownNodeIdentifier(UnknownNodeIdentifier unknownNodeIdentifier) {
			finished.set(true);
		}

	}

}
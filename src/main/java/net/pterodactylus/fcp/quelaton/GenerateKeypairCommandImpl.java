package net.pterodactylus.fcp.quelaton;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import net.pterodactylus.fcp.FcpKeyPair;
import net.pterodactylus.fcp.GenerateSSK;
import net.pterodactylus.fcp.SSKKeypair;

/**
 * Implementation of the {@link GenerateKeypairCommand}.
 *
 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
 */
class GenerateKeypairCommandImpl implements GenerateKeypairCommand {

	private final ExecutorService threadPool;
	private final ConnectionSupplier connectionSupplier;

	GenerateKeypairCommandImpl(ExecutorService threadPool, ConnectionSupplier connectionSupplier) {
		this.threadPool = threadPool;
		this.connectionSupplier = connectionSupplier;
	}

	@Override
	public Future<FcpKeyPair> execute() {
		return threadPool.submit(() -> new FcpKeyPairReplySequence().send(new GenerateSSK()).get());
	}

	private class FcpKeyPairReplySequence extends FcpReplySequence<FcpKeyPair> {

		private AtomicReference<FcpKeyPair> keyPair = new AtomicReference<>();

		public FcpKeyPairReplySequence() throws IOException {
			super(GenerateKeypairCommandImpl.this.threadPool, GenerateKeypairCommandImpl.this.connectionSupplier.get());
		}

		@Override
		protected boolean isFinished() {
			return keyPair.get() != null;
		}

		@Override
		protected FcpKeyPair getResult() {
			return keyPair.get();
		}

		@Override
		protected void consumeSSKKeypair(SSKKeypair sskKeypair) {
			keyPair.set(new FcpKeyPair(sskKeypair.getRequestURI(), sskKeypair.getInsertURI()));
		}

	}

}

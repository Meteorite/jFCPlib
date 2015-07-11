package net.pterodactylus.fcp.quelaton;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.pterodactylus.fcp.AllData;
import net.pterodactylus.fcp.BaseMessage;
import net.pterodactylus.fcp.CloseConnectionDuplicateClientName;
import net.pterodactylus.fcp.ConfigData;
import net.pterodactylus.fcp.DataFound;
import net.pterodactylus.fcp.EndListPeerNotes;
import net.pterodactylus.fcp.EndListPeers;
import net.pterodactylus.fcp.EndListPersistentRequests;
import net.pterodactylus.fcp.FCPPluginReply;
import net.pterodactylus.fcp.FcpConnection;
import net.pterodactylus.fcp.FcpMessage;
import net.pterodactylus.fcp.FinishedCompression;
import net.pterodactylus.fcp.GetFailed;
import net.pterodactylus.fcp.IdentifierCollision;
import net.pterodactylus.fcp.NodeData;
import net.pterodactylus.fcp.NodeHello;
import net.pterodactylus.fcp.Peer;
import net.pterodactylus.fcp.PeerNote;
import net.pterodactylus.fcp.PeerRemoved;
import net.pterodactylus.fcp.PersistentGet;
import net.pterodactylus.fcp.PersistentPut;
import net.pterodactylus.fcp.PersistentPutDir;
import net.pterodactylus.fcp.PersistentRequestModified;
import net.pterodactylus.fcp.PersistentRequestRemoved;
import net.pterodactylus.fcp.PluginInfo;
import net.pterodactylus.fcp.ProtocolError;
import net.pterodactylus.fcp.PutFailed;
import net.pterodactylus.fcp.PutFetchable;
import net.pterodactylus.fcp.PutSuccessful;
import net.pterodactylus.fcp.ReceivedBookmarkFeed;
import net.pterodactylus.fcp.SSKKeypair;
import net.pterodactylus.fcp.SentFeed;
import net.pterodactylus.fcp.SimpleProgress;
import net.pterodactylus.fcp.StartedCompression;
import net.pterodactylus.fcp.SubscribedUSKUpdate;
import net.pterodactylus.fcp.TestDDAComplete;
import net.pterodactylus.fcp.TestDDAReply;
import net.pterodactylus.fcp.URIGenerated;
import net.pterodactylus.fcp.UnknownNodeIdentifier;
import net.pterodactylus.fcp.UnknownPeerNoteType;

import org.junit.Test;

/**
 * Unit test for {@link FcpDialog}.
 *
 * @author <a href="bombe@freenetproject.org">David ‘Bombe’ Roden</a>
 */
public class FcpDialogTest {

	private final FcpConnection fcpConnection = mock(FcpConnection.class);
	private final ExecutorService executorService = Executors.newSingleThreadExecutor();
	private final TestFcpDialog dialog = new TestFcpDialog(executorService, fcpConnection);
	private final FcpMessage fcpMessage = new FcpMessage("Test");

	@Test
	public void canSendMessage() throws IOException, ExecutionException, InterruptedException {
		FcpDialog dialog = createBasicDialog();
		dialog.send(fcpMessage).get();
		verify(fcpConnection).sendMessage(fcpMessage);
	}

	private FcpDialog createBasicDialog() {
		return new FcpDialog(executorService, fcpConnection) {
				@Override
				protected boolean isFinished() {
					return true;
				}
			};
	}

	@Test
	public void sendingAMessageRegistersTheWaiterAsFcpListener() throws IOException {
		FcpDialog dialog = createBasicDialog();
		dialog.send(fcpMessage);
		verify(fcpConnection).addFcpListener(dialog);
	}

	@Test
	public void closingTheReplyWaiterRemovesTheFcpListener() throws IOException {
		FcpDialog dialog = createBasicDialog();
		dialog.send(fcpMessage);
		dialog.close();
		verify(fcpConnection).removeFcpListener(dialog);
	}

	private <M extends BaseMessage> void waitForASpecificMessage(MessageReceiver<M> messageReceiver, Class<M> messageClass, MessageCreator<M> messageCreator) throws IOException, InterruptedException, ExecutionException {
		waitForASpecificMessage(messageReceiver, messageCreator.create(new FcpMessage(messageClass.getSimpleName())));
	}

	private <M extends BaseMessage> void waitForASpecificMessage(MessageReceiver<M> messageReceiver, M message) throws IOException, InterruptedException, ExecutionException {
		dialog.setExpectedMessage(message.getName());
		Future<Boolean> result = dialog.send(fcpMessage);
		messageReceiver.receiveMessage(fcpConnection, message);
		assertThat(result.get(), is(true));
	}

	private <M extends BaseMessage> M createMessage(Class<M> messageClass, MessageCreator<M> messageCreator) {
		return messageCreator.create(new FcpMessage(messageClass.getSimpleName()));
	}

	private interface MessageCreator<M extends BaseMessage> {

		M create(FcpMessage fcpMessage);

	}

	@Test
	public void waitingForNodeHelloWorks() throws IOException, ExecutionException, InterruptedException {
		waitForASpecificMessage(dialog::receivedNodeHello, NodeHello.class, NodeHello::new);
	}

	@Test(expected = ExecutionException.class)
	public void waitingForConnectionClosedDuplicateClientNameWorks() throws IOException, ExecutionException, InterruptedException {
		dialog.setExpectedMessage("");
		Future<Boolean> result = dialog.send(fcpMessage);
		dialog.receivedCloseConnectionDuplicateClientName(fcpConnection,
			new CloseConnectionDuplicateClientName(new FcpMessage("CloseConnectionDuplicateClientName")));
		result.get();
	}

	@Test
	public void waitingForSSKKeypairWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedSSKKeypair, SSKKeypair.class, SSKKeypair::new);
	}

	@Test
	public void waitForPeerWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedPeer, Peer.class, Peer::new);
	}

	@Test
	public void waitForEndListPeersWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedEndListPeers, EndListPeers.class, EndListPeers::new);
	}

	@Test
	public void waitForPeerNoteWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedPeerNote, PeerNote.class, PeerNote::new);
	}

	@Test
	public void waitForEndListPeerNotesWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedEndListPeerNotes, EndListPeerNotes.class, EndListPeerNotes::new);
	}

	@Test
	public void waitForPeerRemovedWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedPeerRemoved, PeerRemoved.class, PeerRemoved::new);
	}

	@Test
	public void waitForNodeDataWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedNodeData, new NodeData(
			new FcpMessage("NodeData").put("ark.pubURI", "")
					.put("ark.number", "0")
					.put("auth.negTypes", "")
					.put("version", "0,0,0,0")
					.put("lastGoodVersion", "0,0,0,0")));
	}

	@Test
	public void waitForTestDDAReplyWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedTestDDAReply, TestDDAReply.class, TestDDAReply::new);
	}

	@Test
	public void waitForTestDDACompleteWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedTestDDAComplete, TestDDAComplete.class, TestDDAComplete::new);
	}

	@Test
	public void waitForPersistentGetWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedPersistentGet, PersistentGet.class, PersistentGet::new);
	}

	@Test
	public void waitForPersistentPutWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedPersistentPut, PersistentPut.class, PersistentPut::new);
	}

	@Test
	public void waitForEndListPersistentRequestsWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedEndListPersistentRequests, EndListPersistentRequests.class, EndListPersistentRequests::new);
	}

	@Test
	public void waitForURIGeneratedWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedURIGenerated, URIGenerated.class, URIGenerated::new);
	}

	@Test
	public void waitForDataFoundWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedDataFound, DataFound.class, DataFound::new);
	}

	@Test
	public void waitForAllDataWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedAllData, new AllData(new FcpMessage("AllData"), null));
	}

	@Test
	public void waitForSimpleProgressWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedSimpleProgress, SimpleProgress.class, SimpleProgress::new);
	}

	@Test
	public void waitForStartedCompressionWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedStartedCompression, StartedCompression.class, StartedCompression::new);
	}

	@Test
	public void waitForFinishedCompressionWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedFinishedCompression, FinishedCompression.class, FinishedCompression::new);
	}

	@Test
	public void waitForUnknownPeerNoteTypeWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedUnknownPeerNoteType, UnknownPeerNoteType.class, UnknownPeerNoteType::new);
	}

	@Test
	public void waitForUnknownNodeIdentifierWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedUnknownNodeIdentifier, UnknownNodeIdentifier.class, UnknownNodeIdentifier::new);
	}

	@Test
	public void waitForConfigDataWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedConfigData, ConfigData.class, ConfigData::new);
	}

	@Test
	public void waitForGetFailedWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedGetFailed, GetFailed.class, GetFailed::new);
	}

	@Test
	public void waitForPutFailedWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedPutFailed, PutFailed.class, PutFailed::new);
	}

	@Test
	public void waitForIdentifierCollisionWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedIdentifierCollision, IdentifierCollision.class, IdentifierCollision::new);
	}

	@Test
	public void waitForPersistentPutDirWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedPersistentPutDir, PersistentPutDir.class, PersistentPutDir::new);
	}

	@Test
	public void waitForPersistentRequestRemovedWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedPersistentRequestRemoved, PersistentRequestRemoved.class, PersistentRequestRemoved::new);
	}

	@Test
	public void waitForSubscribedUSKUpdateWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedSubscribedUSKUpdate, SubscribedUSKUpdate.class, SubscribedUSKUpdate::new);
	}

	@Test
	public void waitForPluginInfoWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedPluginInfo, PluginInfo.class, PluginInfo::new);
	}

	@Test
	public void waitForFCPPluginReply() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedFCPPluginReply, new FCPPluginReply(new FcpMessage("FCPPluginReply"), null));
	}

	@Test
	public void waitForPersistentRequestModifiedWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedPersistentRequestModified, PersistentRequestModified.class, PersistentRequestModified::new);
	}

	@Test
	public void waitForPutSuccessfulWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedPutSuccessful, PutSuccessful.class, PutSuccessful::new);
	}

	@Test
	public void waitForPutFetchableWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedPutFetchable, PutFetchable.class, PutFetchable::new);
	}

	@Test
	public void waitForSentFeedWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedSentFeed, SentFeed.class, SentFeed::new);
	}

	@Test
	public void waitForReceivedBookmarkFeedWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedBookmarkFeed, ReceivedBookmarkFeed.class, ReceivedBookmarkFeed::new);
	}

	@Test
	public void waitForProtocolErrorWorks() throws InterruptedException, ExecutionException, IOException {
		waitForASpecificMessage(dialog::receivedProtocolError, ProtocolError.class, ProtocolError::new);
	}

	@Test
	public void waitForUnknownMessageWorks() throws IOException, ExecutionException, InterruptedException {
		dialog.setExpectedMessage("SomeFcpMessage");
		Future<Boolean> result = dialog.send(fcpMessage);
		dialog.receivedMessage(fcpConnection, new FcpMessage("SomeFcpMessage"));
		assertThat(result.get(), is(true));
	}

	@Test
	public void waitingForMultipleMessagesWorks() throws IOException, ExecutionException, InterruptedException {
		TestFcpDialog testFcpDialog = new TestFcpDialog(executorService, fcpConnection) {
			private final AtomicBoolean gotPutFailed = new AtomicBoolean();
			private final AtomicBoolean gotGetFailed = new AtomicBoolean();

			@Override
			protected boolean isFinished() {
				return gotPutFailed.get() && gotGetFailed.get();
			}

			@Override
			protected Boolean getResult() {
				return isFinished();
			}

			@Override
			protected void consumePutFailed(PutFailed putFailed) {
				gotPutFailed.set(true);
			}

			@Override
			protected void consumeGetFailed(GetFailed getFailed) {
				gotGetFailed.set(true);
			}
		};
		Future<?> result = testFcpDialog.send(fcpMessage);
		assertThat(result.isDone(), is(false));
		testFcpDialog.receivedGetFailed(fcpConnection, new GetFailed(new FcpMessage("GetFailed")));
		assertThat(result.isDone(), is(false));
		testFcpDialog.receivedPutFailed(fcpConnection, new PutFailed(new FcpMessage("PutFailed")));
		assertThat(result.get(), is(true));
	}

	@Test
	public void waitingForConnectionClosureWorks() throws IOException, ExecutionException, InterruptedException {
		dialog.setExpectedMessage("none");
		Future<Boolean> result = dialog.send(fcpMessage);
		Throwable throwable = new Throwable();
		dialog.connectionClosed(fcpConnection, throwable);
		try {
			result.get();
		} catch (ExecutionException e) {
			Throwable t = e;
			while (t.getCause() != null) {
				t = t.getCause();
			}
			assertThat(t, sameInstance(throwable));
		}
	}

	@FunctionalInterface
	private interface MessageReceiver<M> {

		void receiveMessage(FcpConnection fcpConnection, M message);

	}

	private static class TestFcpDialog extends FcpDialog<Boolean> {

		private final AtomicReference<String> gotMessage = new AtomicReference<>();
		private final AtomicReference<String> expectedMessage = new AtomicReference<>();

		public TestFcpDialog(ExecutorService executorService, FcpConnection fcpConnection) {
			super(executorService, fcpConnection);
		}

		public void setExpectedMessage(String expectedMessage) {
			this.expectedMessage.set(expectedMessage);
		}

		@Override
		protected boolean isFinished() {
			return getResult();
		}

		@Override
		protected Boolean getResult() {
			return expectedMessage.get().equals(gotMessage.get());
		}

		@Override
		protected void consumeNodeHello(NodeHello nodeHello) {
			gotMessage.set(nodeHello.getName());
		}

		@Override
		protected void consumeSSKKeypair(SSKKeypair sskKeypair) {
			gotMessage.set(sskKeypair.getName());
		}

		@Override
		protected void consumePeer(Peer peer) {
			gotMessage.set(peer.getName());
		}

		@Override
		protected void consumeEndListPeers(EndListPeers endListPeers) {
			gotMessage.set(endListPeers.getName());
		}

		@Override
		protected void consumePeerNote(PeerNote peerNote) {
			gotMessage.set(peerNote.getName());
		}

		@Override
		protected void consumeEndListPeerNotes(EndListPeerNotes endListPeerNotes) {
			gotMessage.set(endListPeerNotes.getName());
		}

		@Override
		protected void consumePeerRemoved(PeerRemoved peerRemoved) {
			gotMessage.set(peerRemoved.getName());
		}

		@Override
		protected void consumeNodeData(NodeData nodeData) {
			gotMessage.set(nodeData.getName());
		}

		@Override
		protected void consumeTestDDAReply(TestDDAReply testDDAReply) {
			gotMessage.set(testDDAReply.getName());
		}

		@Override
		protected void consumeTestDDAComplete(TestDDAComplete testDDAComplete) {
			gotMessage.set(testDDAComplete.getName());
		}

		@Override
		protected void consumePersistentGet(PersistentGet persistentGet) {
			gotMessage.set(persistentGet.getName());
		}

		@Override
		protected void consumePersistentPut(PersistentPut persistentPut) {
			gotMessage.set(persistentPut.getName());
		}

		@Override
		protected void consumeEndListPersistentRequests(EndListPersistentRequests endListPersistentRequests) {
			gotMessage.set(endListPersistentRequests.getName());
		}

		@Override
		protected void consumeURIGenerated(URIGenerated uriGenerated) {
			gotMessage.set(uriGenerated.getName());
		}

		@Override
		protected void consumeDataFound(DataFound dataFound) {
			gotMessage.set(dataFound.getName());
		}

		@Override
		protected void consumeAllData(AllData allData) {
			gotMessage.set(allData.getName());
		}

		@Override
		protected void consumeSimpleProgress(SimpleProgress simpleProgress) {
			gotMessage.set(simpleProgress.getName());
		}

		@Override
		protected void consumeStartedCompression(StartedCompression startedCompression) {
			gotMessage.set(startedCompression.getName());
		}

		@Override
		protected void consumeFinishedCompression(FinishedCompression finishedCompression) {
			gotMessage.set(finishedCompression.getName());
		}

		@Override
		protected void consumeUnknownPeerNoteType(UnknownPeerNoteType unknownPeerNoteType) {
			gotMessage.set(unknownPeerNoteType.getName());
		}

		@Override
		protected void consumeUnknownNodeIdentifier(UnknownNodeIdentifier unknownNodeIdentifier) {
			gotMessage.set(unknownNodeIdentifier.getName());
		}

		@Override
		protected void consumeConfigData(ConfigData configData) {
			gotMessage.set(configData.getName());
		}

		@Override
		protected void consumeGetFailed(GetFailed getFailed) {
			gotMessage.set(getFailed.getName());
		}

		@Override
		protected void consumePutFailed(PutFailed putFailed) {
			gotMessage.set(putFailed.getName());
		}

		@Override
		protected void consumeIdentifierCollision(IdentifierCollision identifierCollision) {
			gotMessage.set(identifierCollision.getName());
		}

		@Override
		protected void consumePersistentPutDir(PersistentPutDir persistentPutDir) {
			gotMessage.set(persistentPutDir.getName());
		}

		@Override
		protected void consumePersistentRequestRemoved(PersistentRequestRemoved persistentRequestRemoved) {
			gotMessage.set(persistentRequestRemoved.getName());
		}

		@Override
		protected void consumeSubscribedUSKUpdate(SubscribedUSKUpdate subscribedUSKUpdate) {
			gotMessage.set(subscribedUSKUpdate.getName());
		}

		@Override
		protected void consumePluginInfo(PluginInfo pluginInfo) {
			gotMessage.set(pluginInfo.getName());
		}

		@Override
		protected void consumeFCPPluginReply(FCPPluginReply fcpPluginReply) {
			gotMessage.set(fcpPluginReply.getName());
		}

		@Override
		protected void consumePersistentRequestModified(PersistentRequestModified persistentRequestModified) {
			gotMessage.set(persistentRequestModified.getName());
		}

		@Override
		protected void consumePutSuccessful(PutSuccessful putSuccessful) {
			gotMessage.set(putSuccessful.getName());
		}

		@Override
		protected void consumePutFetchable(PutFetchable putFetchable) {
			gotMessage.set(putFetchable.getName());
		}

		@Override
		protected void consumeSentFeed(SentFeed sentFeed) {
			gotMessage.set(sentFeed.getName());
		}

		@Override
		protected void consumeReceivedBookmarkFeed(ReceivedBookmarkFeed receivedBookmarkFeed) {
			gotMessage.set(receivedBookmarkFeed.getName());
		}

		@Override
		protected void consumeProtocolError(ProtocolError protocolError) {
			gotMessage.set(protocolError.getName());
		}

		@Override
		protected void consumeUnknownMessage(FcpMessage fcpMessage) {
			gotMessage.set(fcpMessage.getName());
		}

	}

}
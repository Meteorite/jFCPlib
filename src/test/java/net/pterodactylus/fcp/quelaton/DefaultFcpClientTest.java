package net.pterodactylus.fcp.quelaton;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.pterodactylus.fcp.FcpKeyPair;
import net.pterodactylus.fcp.Priority;
import net.pterodactylus.fcp.fake.FakeTcpServer;
import net.pterodactylus.fcp.quelaton.ClientGetCommand.Data;

import com.google.common.io.ByteStreams;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.junit.After;
import org.junit.Test;

/**
 * Unit test for {@link DefaultFcpClient}.
 *
 * @author <a href="bombe@freenetproject.org">David ‘Bombe’ Roden</a>
 */
public class DefaultFcpClientTest {

	private static final String INSERT_URI = "SSK@RVCHbJdkkyTCeNN9AYukEg76eyqmiosSaNKgE3U9zUw,7SHH53gletBVb9JD7nBsyClbLQsBubDPEIcwg908r7Y,AQECAAE/";
	private static final String REQUEST_URI = "SSK@wtbgd2loNcJCXvtQVOftl2tuWBomDQHfqS6ytpPRhfw,7SHH53gletBVb9JD7nBsyClbLQsBubDPEIcwg908r7Y,AQACAAE/";

	private static int threadCounter = 0;
	private final ExecutorService threadPool = Executors.newCachedThreadPool((r) -> new Thread(r, "Test-Thread-" + threadCounter++));
	private final FakeTcpServer fcpServer;
	private final DefaultFcpClient fcpClient;

	public DefaultFcpClientTest() throws IOException {
		fcpServer = new FakeTcpServer(threadPool);
		fcpClient = new DefaultFcpClient(threadPool, "localhost", fcpServer.getPort(), () -> "Test", () -> "2.0");
	}

	@After
	public void tearDown() throws IOException {
		fcpServer.close();
	}

	@Test
	public void defaultFcpClientCanGenerateKeypair() throws ExecutionException, InterruptedException, IOException {
		Future<FcpKeyPair> keyPairFuture = fcpClient.generateKeypair().execute();
		connectNode();
		fcpServer.collectUntil(is("EndMessage"));
		fcpServer.writeLine("SSKKeypair",
			"InsertURI=" + INSERT_URI + "",
			"RequestURI=" + REQUEST_URI + "",
			"Identifier=My Identifier from GenerateSSK",
			"EndMessage");
		FcpKeyPair keyPair = keyPairFuture.get();
		assertThat(keyPair.getPublicKey(), is(REQUEST_URI));
		assertThat(keyPair.getPrivateKey(), is(INSERT_URI));
	}

	private void connectNode() throws InterruptedException, ExecutionException, IOException {
		fcpServer.connect().get();
		fcpServer.collectUntil(is("EndMessage"));
		fcpServer.writeLine("NodeHello",
			"CompressionCodecs=4 - GZIP(0), BZIP2(1), LZMA(2), LZMA_NEW(3)",
			"Revision=build01466",
			"Testnet=false",
			"Version=Fred,0.7,1.0,1466",
			"Build=1466",
			"ConnectionIdentifier=14318898267048452a81b36e7f13a3f0",
			"Node=Fred",
			"ExtBuild=29",
			"FCPVersion=2.0",
			"NodeLanguage=ENGLISH",
			"ExtRevision=v29",
			"EndMessage"
		);
	}

	@Test
	public void clientGetCanDownloadData() throws InterruptedException, ExecutionException, IOException {
		Future<Optional<Data>> dataFuture = fcpClient.clientGet().uri("KSK@foo.txt");
		connectNode();
		List<String> lines = fcpServer.collectUntil(is("EndMessage"));
		assertThat(lines, matchesFcpMessage("ClientGet", "ReturnType=direct", "URI=KSK@foo.txt"));
		String identifier = extractIdentifier(lines);
		fcpServer.writeLine(
			"AllData",
			"Identifier=" + identifier,
			"DataLength=6",
			"StartupTime=1435610539000",
			"CompletionTime=1435610540000",
			"Metadata.ContentType=text/plain;charset=utf-8",
			"Data",
			"Hello"
		);
		Optional<Data> data = dataFuture.get();
		assertThat(data.get().getMimeType(), is("text/plain;charset=utf-8"));
		assertThat(data.get().size(), is(6L));
		assertThat(ByteStreams.toByteArray(data.get().getInputStream()), is("Hello\n".getBytes(StandardCharsets.UTF_8)));
	}

	private String extractIdentifier(List<String> lines) {
		return lines.stream().filter(s -> s.startsWith("Identifier=")).map(s -> s.substring(s.indexOf('=') + 1)).findFirst().orElse("");
	}

	@Test
	public void clientGetDownloadsDataForCorrectIdentifier() throws InterruptedException, ExecutionException, IOException {
		Future<Optional<Data>> dataFuture = fcpClient.clientGet().uri("KSK@foo.txt");
		connectNode();
		List<String> lines = fcpServer.collectUntil(is("EndMessage"));
		assertThat(lines, matchesFcpMessage("ClientGet", "URI=KSK@foo.txt"));
		String identifier = extractIdentifier(lines);
		fcpServer.writeLine(
			"AllData",
			"Identifier=not-test",
			"DataLength=12",
			"StartupTime=1435610539000",
			"CompletionTime=1435610540000",
			"Metadata.ContentType=text/plain;charset=latin-9",
			"Data",
			"Hello World"
		);
		fcpServer.writeLine(
			"AllData",
			"Identifier=" + identifier,
			"DataLength=6",
			"StartupTime=1435610539000",
			"CompletionTime=1435610540000",
			"Metadata.ContentType=text/plain;charset=utf-8",
			"Data",
			"Hello"
		);
		Optional<Data> data = dataFuture.get();
		assertThat(data.get().getMimeType(), is("text/plain;charset=utf-8"));
		assertThat(data.get().size(), is(6L));
		assertThat(ByteStreams.toByteArray(data.get().getInputStream()), is("Hello\n".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	public void clientGetRecognizesGetFailed() throws InterruptedException, ExecutionException, IOException {
		Future<Optional<Data>> dataFuture = fcpClient.clientGet().uri("KSK@foo.txt");
		connectNode();
		List<String> lines = fcpServer.collectUntil(is("EndMessage"));
		assertThat(lines, matchesFcpMessage("ClientGet", "URI=KSK@foo.txt"));
		String identifier = extractIdentifier(lines);
		fcpServer.writeLine(
			"GetFailed",
			"Identifier=" + identifier,
			"Code=3",
			"EndMessage"
		);
		Optional<Data> data = dataFuture.get();
		assertThat(data.isPresent(), is(false));
	}

	@Test
	public void clientGetRecognizesGetFailedForCorrectIdentifier() throws InterruptedException, ExecutionException, IOException {
		Future<Optional<Data>> dataFuture = fcpClient.clientGet().uri("KSK@foo.txt");
		connectNode();
		List<String> lines = fcpServer.collectUntil(is("EndMessage"));
		assertThat(lines, matchesFcpMessage("ClientGet", "URI=KSK@foo.txt"));
		String identifier = extractIdentifier(lines);
		fcpServer.writeLine(
			"GetFailed",
			"Identifier=not-test",
			"Code=3",
			"EndMessage"
		);
		fcpServer.writeLine(
			"GetFailed",
			"Identifier=" + identifier,
			"Code=3",
			"EndMessage"
		);
		Optional<Data> data = dataFuture.get();
		assertThat(data.isPresent(), is(false));
	}

	@Test
	public void clientGetRecognizesConnectionClosed() throws InterruptedException, ExecutionException, IOException {
		Future<Optional<Data>> dataFuture = fcpClient.clientGet().uri("KSK@foo.txt");
		connectNode();
		List<String> lines = fcpServer.collectUntil(is("EndMessage"));
		assertThat(lines, matchesFcpMessage("ClientGet", "URI=KSK@foo.txt"));
		fcpServer.close();
		Optional<Data> data = dataFuture.get();
		assertThat(data.isPresent(), is(false));
	}

	@Test
	public void clientGetWithIgnoreDataStoreSettingSendsCorrectCommands() throws InterruptedException, ExecutionException, IOException {
		fcpClient.clientGet().ignoreDataStore().uri("KSK@foo.txt");
		connectNode();
		List<String> lines = fcpServer.collectUntil(is("EndMessage"));
		assertThat(lines, matchesFcpMessage("ClientGet", "URI=KSK@foo.txt", "IgnoreDS=true"));
	}

	@Test
	public void clientGetWithDataStoreOnlySettingSendsCorrectCommands() throws InterruptedException, ExecutionException, IOException {
		fcpClient.clientGet().dataStoreOnly().uri("KSK@foo.txt");
		connectNode();
		List<String> lines = fcpServer.collectUntil(is("EndMessage"));
		assertThat(lines, matchesFcpMessage("ClientGet", "URI=KSK@foo.txt", "DSonly=true"));
	}

	@Test
	public void clientGetWithMaxSizeSettingSendsCorrectCommands() throws InterruptedException, ExecutionException, IOException {
		fcpClient.clientGet().maxSize(1048576).uri("KSK@foo.txt");
		connectNode();
		List<String> lines = fcpServer.collectUntil(is("EndMessage"));
		assertThat(lines, matchesFcpMessage("ClientGet", "URI=KSK@foo.txt", "MaxSize=1048576"));
	}

	@Test
	public void clientGetWithPrioritySettingSendsCorrectCommands() throws InterruptedException, ExecutionException, IOException {
		fcpClient.clientGet().priority(Priority.interactive).uri("KSK@foo.txt");
		connectNode();
		List<String> lines = fcpServer.collectUntil(is("EndMessage"));
		assertThat(lines, matchesFcpMessage("ClientGet", "URI=KSK@foo.txt", "PriorityClass=1"));
	}

	@Test
	public void clientGetWithRealTimeSettingSendsCorrectCommands() throws InterruptedException, ExecutionException, IOException {
		fcpClient.clientGet().realTime().uri("KSK@foo.txt");
		connectNode();
		List<String> lines = fcpServer.collectUntil(is("EndMessage"));
		assertThat(lines, matchesFcpMessage("ClientGet", "URI=KSK@foo.txt", "RealTimeFlag=true"));
	}

	@Test
	public void clientGetWithGlobalSettingSendsCorrectCommands() throws InterruptedException, ExecutionException, IOException {
		fcpClient.clientGet().global().uri("KSK@foo.txt");
		connectNode();
		List<String> lines = fcpServer.collectUntil(is("EndMessage"));
		assertThat(lines, matchesFcpMessage("ClientGet", "URI=KSK@foo.txt", "Global=true"));
	}

	private Matcher<List<String>> matchesFcpMessage(String name, String... requiredLines) {
		return new TypeSafeDiagnosingMatcher<List<String>>() {
			@Override
			protected boolean matchesSafely(List<String> item, Description mismatchDescription) {
				if (!item.get(0).equals(name)) {
					mismatchDescription.appendText("FCP message is named ").appendValue(item.get(0));
					return false;
				}
				for (String requiredLine : requiredLines) {
					if (item.indexOf(requiredLine) < 1) {
						mismatchDescription.appendText("FCP message does not contain ").appendValue(requiredLine);
						return false;
					}
				}
				return true;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("FCP message named ").appendValue(name);
				description.appendValueList(", containing the lines", ", ", "", requiredLines);
			}
		};
	}

}
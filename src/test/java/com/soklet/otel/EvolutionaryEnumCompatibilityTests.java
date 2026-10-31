package com.soklet.otel;

import com.soklet.McpRequestOutcome;
import com.soklet.ServerType;
import com.soklet.ShutdownComponentDisposition;
import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class EvolutionaryEnumCompatibilityTests {
	@TempDir Path directory;

	@Test void existingIntegrationBytecodeAcceptsFutureCoreEnumConstantsAndBalancesStreamMetrics() throws Exception {
		List<String> compilerArguments = new ArrayList<>(List.of("-proc:none", "--release", "21", "-d", directory.toString()));
		for (Class<? extends Enum<?>> type : List.of(ServerType.class, McpRequestOutcome.class,
				StreamTerminationReason.class, ShutdownComponentDisposition.class)) {
			Path source = directory.resolve(type.getSimpleName() + ".java");
			String constants = Stream.of(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(","));
			Files.writeString(source, "package com.soklet; public enum " + type.getSimpleName() + " {" + constants + ", FUTURE}");
			compilerArguments.add(source.toString());
		}
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compilerArguments.toArray(String[]::new)));
		String classPath = directory + java.io.File.pathSeparator
				+ System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		Path output = directory.resolve("future-enum-probe.log");
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", classPath, EvolutionaryEnumCompatibilityProbe.class.getName())
				.redirectErrorStream(true).redirectOutput(output.toFile()).start();
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Future-enum probe timed out");
			assertEquals(0, process.exitValue(), Files.readString(output));
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				assertTrue(process.waitFor(5, TimeUnit.SECONDS));
			}
		}
	}
}

package tau;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A process started on Windows outside this process's job object, so that it
 * outlives whatever started this one.
 *
 * <p>Windows puts a child in its parent's job, and whoever owns a job can end
 * every process in it at once: a Claude Code session does that to the
 * processes its commands started when the session's own process ends. A
 * {@link ProcessBuilder} child stays in the job, so a {@code brightside:relaunch}
 * successor, or an app the MCP bridge started, would die with the session that
 * started its first ancestor. {@link #start} asks {@code CreateProcessW} to
 * break the child away from the job, which works when the job allows it. The
 * Claude desktop app's job does not, and holds every session's job, so then
 * WMI's {@code Win32_Process.Create} starts it instead: as a child of WMI's
 * provider host, in no job of this process's, as an app started from the
 * desktop is. Only when that fails too is the child started in the job, and
 * {@link #brokeAway} says so.
 *
 * <p>The child has no console and no standard streams, as {@link Relaunch}
 * wants: it writes its own log. One that WMI started gets the user's own
 * environment rather than this process's.
 */
final class WindowsProcess extends Process {

	private static final int CREATE_BREAKAWAY_FROM_JOB = 0x0100_0000;
	private static final int CREATE_NEW_PROCESS_GROUP = 0x0000_0200;
	private static final int DETACHED_PROCESS = 0x0000_0008;
	private static final int ERROR_ACCESS_DENIED = 5;
	private static final int SYNCHRONIZE = 0x0010_0000;
	private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
	private static final int PROCESS_TERMINATE = 0x0001;
	/** How long PowerShell may take to have WMI start a process. */
	private static final long WMI_SECONDS = 60;
	private static final int WAIT_OBJECT_0 = 0;
	private static final int WAIT_TIMEOUT = 0x102;
	private static final int INFINITE = -1;
	private static final int STILL_ACTIVE = 259;
	/** {@code sizeof(STARTUPINFOW)} on 64-bit Windows, the only Windows Java 25 runs on. */
	private static final int STARTUPINFOW_SIZE = 104;
	/** {@code PROCESS_INFORMATION}: hProcess, hThread, dwProcessId, dwThreadId. */
	private static final int PROCESS_INFORMATION_SIZE = 24;
	private static final int PI_PROCESS = 0, PI_THREAD = 8, PI_PID = 16;

	private static final Cleaner CLEANER = Cleaner.create();

	/** The Windows calls, bound on first use: only ever on Windows. */
	private static final class Kernel32 {
		static final Linker LINKER = Linker.nativeLinker();
		static final SymbolLookup LIB = SymbolLookup.libraryLookup("kernel32", Arena.global());
		static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
		static final VarHandle LAST_ERROR = CAPTURE.varHandle(PathElement.groupElement("GetLastError"));
		static final MethodHandle CREATE_PROCESS = LINKER.downcallHandle(LIB.findOrThrow("CreateProcessW"),
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS,
				ADDRESS, ADDRESS),
			Linker.Option.captureCallState("GetLastError"));
		static final MethodHandle CLOSE_HANDLE = function("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
		static final MethodHandle OPEN_PROCESS = function("OpenProcess",
			FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
		static final MethodHandle WAIT = function("WaitForSingleObject",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
		static final MethodHandle EXIT_CODE = function("GetExitCodeProcess",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		static final MethodHandle TERMINATE = function("TerminateProcess",
			FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

		private static MethodHandle function(String name, FunctionDescriptor descriptor) {
			return LINKER.downcallHandle(LIB.findOrThrow(name), descriptor);
		}
	}

	private final MemorySegment handle;
	private final long pid;
	private final boolean brokeAway;

	private WindowsProcess(MemorySegment handle, long pid, boolean brokeAway) {
		this.handle = handle;
		this.pid = pid;
		this.brokeAway = brokeAway;
		CLEANER.register(this, () -> close(handle));
	}

	static boolean isWindows() {
		return System.getProperty("os.name", "").startsWith("Windows");
	}

	/**
	 * Starts {@code command} (the program, then its arguments) outside this
	 * process's job: by breaking away if the job allows it, else through WMI,
	 * and only if both fail, in the job.
	 */
	static WindowsProcess start(List<String> command) throws IOException {
		String line = commandLine(command);
		WindowsProcess child = create(line, true);
		if (child != null) return child;
		// The job forbids breaking away.
		try {
			return createThroughWmi(line);
		} catch (IOException e) {
			// The child then shares this process's fate; Relaunch says so.
			return create(line, false);
		}
	}

	/**
	 * Has WMI start {@code line}, through PowerShell's {@code Invoke-CimMethod},
	 * in this process's working directory. The command line goes as Base64, so
	 * no quoting or console code page can change it.
	 */
	private static WindowsProcess createThroughWmi(String line) throws IOException {
		Base64.Encoder b64 = Base64.getEncoder();
		String script = "$line = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('"
			+ b64.encodeToString(line.getBytes(StandardCharsets.UTF_8)) + "'))\n"
			+ "$dir = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('"
			+ b64.encodeToString(Path.of("").toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8)) + "'))\n"
			+ "$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create"
			+ " -Arguments @{ CommandLine = $line; CurrentDirectory = $dir }\n"
			+ "if ($r.ReturnValue -ne 0) { 'refused ' + $r.ReturnValue; exit 1 }\n"
			+ "'pid ' + $r.ProcessId\n";
		Path powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
			"System32", "WindowsPowerShell", "v1.0", "powershell.exe");
		Process ps = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive",
			"-ExecutionPolicy", "Bypass", "-Command", "-").redirectErrorStream(true).start();
		try (OutputStream in = ps.getOutputStream()) {
			in.write(script.getBytes(StandardCharsets.US_ASCII));
		}
		String answer;
		try (InputStream out = ps.getInputStream()) {
			answer = new String(out.readAllBytes(), StandardCharsets.UTF_8).strip();
		}
		try {
			if (!ps.waitFor(WMI_SECONDS, TimeUnit.SECONDS)) {
				ps.destroyForcibly();
				throw new IOException("PowerShell did not answer in " + WMI_SECONDS + " s");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while WMI started a process", e);
		}
		String last = answer.lines().reduce((a, b) -> b).orElse("");
		if (ps.exitValue() != 0 || !last.startsWith("pid ")) {
			throw new IOException("WMI did not start it: " + answer);
		}
		long pid = Long.parseLong(last.substring(4).strip());
		MemorySegment handle;
		try {
			handle = (MemorySegment) Kernel32.OPEN_PROCESS.invokeExact(
				SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_TERMINATE, 0, (int) pid);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new IOException("Could not call Windows to open process " + pid, t);
		}
		if (handle.equals(MemorySegment.NULL)) {
			throw new IOException("WMI started process " + pid + ", which ended before it could be opened");
		}
		return new WindowsProcess(handle, pid, true);
	}

	/** The started process, or null when Windows refused to break it away from the job. */
	private static WindowsProcess create(String line, boolean breakAway) throws IOException {
		int flags = DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP | (breakAway ? CREATE_BREAKAWAY_FROM_JOB : 0);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment commandLine = arena.allocateFrom(line, StandardCharsets.UTF_16LE); // writable, as it must be
			MemorySegment startup = arena.allocate(STARTUPINFOW_SIZE);
			startup.set(JAVA_INT, 0, STARTUPINFOW_SIZE);
			MemorySegment info = arena.allocate(PROCESS_INFORMATION_SIZE);
			MemorySegment state = arena.allocate(Kernel32.CAPTURE);
			int ok = (int) Kernel32.CREATE_PROCESS.invokeExact(state, MemorySegment.NULL, commandLine,
				MemorySegment.NULL, MemorySegment.NULL, 0, flags, MemorySegment.NULL, MemorySegment.NULL, startup, info);
			if (ok == 0) {
				int error = (int) Kernel32.LAST_ERROR.get(state, 0L);
				if (breakAway && error == ERROR_ACCESS_DENIED) return null;
				throw new IOException("Windows could not start " + line + " (error " + error + ")");
			}
			close(info.get(ADDRESS, PI_THREAD));
			return new WindowsProcess(info.get(ADDRESS, PI_PROCESS), Integer.toUnsignedLong(info.get(JAVA_INT, PI_PID)),
				breakAway);
		} catch (IOException | RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new IOException("Could not call Windows to start a process", t);
		}
	}

	/**
	 * {@code command} as one Windows command line that the C runtime's argument
	 * parsing, which Java uses, splits back into the same strings.
	 */
	static String commandLine(List<String> command) {
		StringBuilder line = new StringBuilder();
		for (String arg : command) {
			if (!line.isEmpty()) line.append(' ');
			if (!arg.isEmpty() && arg.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == '"')) {
				line.append(arg);
				continue;
			}
			line.append('"');
			int backslashes = 0;
			for (int i = 0; i < arg.length(); i++) {
				char c = arg.charAt(i);
				if (c == '\\') {
					backslashes++;
					continue;
				}
				// Backslashes are literal unless a quote follows: then each is doubled.
				line.append("\\".repeat(c == '"' ? backslashes * 2 + 1 : backslashes)).append(c);
				backslashes = 0;
			}
			line.append("\\".repeat(backslashes * 2)).append('"');
		}
		return line.toString();
	}

	/** Whether it was started outside this process's job. */
	boolean brokeAway() {
		return brokeAway;
	}

	@Override
	public long pid() {
		return pid;
	}

	@Override
	public boolean isAlive() {
		return waitMillis(0) == WAIT_TIMEOUT;
	}

	@Override
	public int waitFor() {
		waitMillis(INFINITE);
		return exitValue();
	}

	@Override
	public boolean waitFor(long timeout, TimeUnit unit) {
		long millis = Math.min(unit.toMillis(timeout), Integer.MAX_VALUE - 1L);
		return waitMillis((int) Math.max(0, millis)) == WAIT_OBJECT_0;
	}

	@Override
	public int exitValue() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment code = arena.allocate(JAVA_INT);
			int ok = (int) Kernel32.EXIT_CODE.invokeExact(handle, code);
			if (ok == 0) throw new IllegalStateException("Windows could not read process " + pid + "'s exit code");
			if (code.get(JAVA_INT, 0) == STILL_ACTIVE && isAlive()) throw new IllegalThreadStateException("process " + pid + " has not exited");
			return code.get(JAVA_INT, 0);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	@Override
	public void destroy() {
		try {
			int ignored = (int) Kernel32.TERMINATE.invokeExact(handle, 1);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	@Override
	public boolean supportsNormalTermination() {
		return false;
	}

	/**
	 * Its {@link ProcessHandle}. Holding the process's handle keeps its id from
	 * being reused, but Java has no handle for a process that has exited.
	 */
	@Override
	public ProcessHandle toHandle() {
		return ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("process " + pid + " has already exited"));
	}

	@Override
	public OutputStream getOutputStream() {
		return OutputStream.nullOutputStream();
	}

	@Override
	public InputStream getInputStream() {
		return InputStream.nullInputStream();
	}

	@Override
	public InputStream getErrorStream() {
		return InputStream.nullInputStream();
	}

	private int waitMillis(int millis) {
		try {
			return (int) Kernel32.WAIT.invokeExact(handle, millis);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	private static void close(MemorySegment handle) {
		try {
			int ignored = (int) Kernel32.CLOSE_HANDLE.invokeExact(handle);
		} catch (Throwable t) {
			// nothing more to do with a handle that will not close
		}
	}
}

/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr.internal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Locates and loads the native SoapySDR library.
 *
 * <p>Resolution order:</p>
 * <ol>
 *     <li>An explicit library: the {@code soapysdr.library} system property or {@code SOAPYSDR_LIBRARY} environment
 *     variable (a library name for the OS loader, or a path). The bundled image is not used.</li>
 *     <li>The bundled image for the current platform, unless {@code -Dsoapysdr.bundled=false}. It is extracted to a
 *     per-user cache directory (override with {@code soapysdr.natives.dir}) and SoapySDR's root is pointed at it so
 *     the bundled driver modules are found.</li>
 *     <li>A system installation of SoapySDR 0.8 in the platform's usual locations.</li>
 * </ol>
 */
public final class NativeLibrary
{
    public static final String LIBRARY_PROPERTY = "soapysdr.library";
    public static final String LIBRARY_ENVIRONMENT = "SOAPYSDR_LIBRARY";
    public static final String BUNDLED_PROPERTY = "soapysdr.bundled";
    public static final String NATIVES_DIR_PROPERTY = "soapysdr.natives.dir";
    public static final String PLUGIN_PATH_PROPERTY = "soapysdr.plugin.path";
    public static final String BLADERF_FPGA_DIR_PROPERTY = "soapysdr.bladerf.fpga.dir";

    private static final String RESOURCE_ROOT = "/io/github/dsheirer/soapysdr/natives/";
    private static final String MANIFEST = "bundle.txt";
    private static final String COMPLETE_MARKER = ".complete";

    /**
     * Describes the library that was loaded.
     *
     * @param platform platform identifier, e.g. "linux-x86_64"
     * @param bundled true if the bundled image was loaded
     * @param library path or name of the loaded SoapySDR core library
     * @param root SoapySDR root directory of the bundled image, or null when a system library was loaded
     */
    public record Info(String platform, boolean bundled, String library, Path root)
    {
    }

    private static volatile Info sInfo;

    private NativeLibrary()
    {
    }

    /**
     * Details of the loaded library, or null if it has not been loaded.
     */
    public static Info info()
    {
        return sInfo;
    }

    static SymbolLookup load()
    {
        String platform = platform();
        List<String> failures = new ArrayList<>();

        // libbladeRF looks for FPGA images in BLADERF_SEARCH_DIR (among other places)
        String fpgaDir = System.getProperty(BLADERF_FPGA_DIR_PROPERTY);
        if(fpgaDir != null && !fpgaDir.isBlank())
        {
            setEnvironment("BLADERF_SEARCH_DIR", Path.of(fpgaDir).toAbsolutePath().toString());
        }

        // 1. Explicit library
        String explicit = firstNonBlank(System.getProperty(LIBRARY_PROPERTY), System.getenv(LIBRARY_ENVIRONMENT));
        if(explicit != null)
        {
            SymbolLookup lookup = tryLoad(explicit, failures);
            if(lookup != null)
            {
                sInfo = new Info(platform, false, explicit, null);
                return lookup;
            }
            throw unsatisfied(failures);
        }

        // 2. Bundled image
        boolean bundledEnabled = !"false".equalsIgnoreCase(System.getProperty(BUNDLED_PROPERTY, "true"));
        if(bundledEnabled && platform != null)
        {
            try
            {
                SymbolLookup lookup = loadBundled(platform);
                if(lookup != null)
                {
                    return lookup;
                }
                failures.add("no bundled image for " + platform);
            }
            catch(IOException | RuntimeException e)
            {
                failures.add("bundled " + platform + " image (" + e + ")");
            }
        }
        else if(platform == null)
        {
            failures.add("no bundled image for " + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        }

        // 3. System installation
        for(String candidate : systemCandidates())
        {
            SymbolLookup lookup = tryLoad(candidate, failures);
            if(lookup != null)
            {
                sInfo = new Info(platform, false, candidate, null);
                return lookup;
            }
        }

        throw unsatisfied(failures);
    }

    private static UnsatisfiedLinkError unsatisfied(List<String> failures)
    {
        return new UnsatisfiedLinkError("Unable to load the SoapySDR native library. Tried: " + failures +
            ". Set -D" + LIBRARY_PROPERTY + "=<name or path> to use a specific SoapySDR 0.8 installation.");
    }

    private static SymbolLookup tryLoad(String candidate, List<String> failures)
    {
        try
        {
            if(candidate.contains("/") || candidate.contains("\\"))
            {
                Path path = Path.of(candidate);
                if(!Files.exists(path))
                {
                    failures.add(candidate + " (not found)");
                    return null;
                }
                return SymbolLookup.libraryLookup(path, Arena.global());
            }
            return SymbolLookup.libraryLookup(candidate, Arena.global());
        }
        catch(IllegalArgumentException e)
        {
            failures.add(candidate + " (" + e.getMessage() + ")");
            return null;
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Platform detection
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Platform identifier of the running JVM ("linux-x86_64", "macos-aarch64", "windows-x86_64", ...), or null when
     * no bundled image could exist for it. The JVM's own architecture is used, so an emulated x64 JVM on Arm
     * hardware selects the x86_64 image.
     */
    public static String platform()
    {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String osId;
        if(os.startsWith("windows"))
        {
            osId = "windows";
        }
        else if(os.startsWith("mac") || os.startsWith("darwin"))
        {
            osId = "macos";
        }
        else if(os.startsWith("linux"))
        {
            if(isMusl())
            {
                return null; // bundled Linux images are built against glibc
            }
            osId = "linux";
        }
        else
        {
            return null;
        }

        String archId = switch(arch)
        {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> null;
        };

        return archId == null ? null : osId + "-" + archId;
    }

    private static boolean isMusl()
    {
        try(Stream<Path> libs = Files.list(Path.of("/lib")))
        {
            return libs.anyMatch(p -> p.getFileName().toString().startsWith("ld-musl-"));
        }
        catch(IOException | RuntimeException e)
        {
            return false;
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Bundled image
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Parsed bundle.txt manifest.
     */
    private record Manifest(String platform, String core, List<String> preload, List<Entry> files, String id)
    {
    }

    private record Entry(String sha256, long size, String path)
    {
    }

    private static Manifest readManifest(String platform) throws IOException
    {
        byte[] bytes;
        try(InputStream in = NativeLibrary.class.getResourceAsStream(RESOURCE_ROOT + platform + "/" + MANIFEST))
        {
            if(in == null)
            {
                return null;
            }
            bytes = in.readAllBytes();
        }

        String core = null;
        List<String> preload = new ArrayList<>();
        List<Entry> files = new ArrayList<>();
        for(String line : new String(bytes, StandardCharsets.UTF_8).split("\n"))
        {
            line = line.strip();
            if(line.isEmpty() || line.startsWith("#"))
            {
                continue;
            }
            int eq = line.indexOf('=');
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch(key)
            {
                case "core" -> core = value;
                case "preload" -> preload.add(value);
                case "file" ->
                {
                    String[] parts = value.split(":", 3);
                    files.add(new Entry(parts[0], Long.parseLong(parts[1]), parts[2]));
                }
                default ->
                {
                    //platform and future keys
                }
            }
        }
        if(core == null || files.isEmpty())
        {
            throw new IOException("Invalid bundle manifest for " + platform);
        }
        String id = HexFormat.of().formatHex(sha256().digest(bytes)).substring(0, 16);
        return new Manifest(platform, core, preload, files, id);
    }

    private static SymbolLookup loadBundled(String platform) throws IOException
    {
        Manifest manifest = readManifest(platform);
        if(manifest == null)
        {
            return null;
        }

        Path root = extract(manifest);

        // Point SoapySDR at the bundle before anything triggers module loading
        setEnvironment("SOAPY_SDR_ROOT", root.toString());
        String pluginPath = System.getProperty(PLUGIN_PATH_PROPERTY);
        if(pluginPath != null && !pluginPath.isBlank())
        {
            String existing = System.getenv("SOAPY_SDR_PLUGIN_PATH");
            String separator = platform.startsWith("windows") ? ";" : ":";
            setEnvironment("SOAPY_SDR_PLUGIN_PATH",
                existing == null || existing.isBlank() ? pluginPath : existing + separator + pluginPath);
        }

        for(String preload : manifest.preload())
        {
            // Windows: the MSVC runtime. If the JVM already loaded a DLL of that name, imports bind to it anyway, so
            // loading a second copy would only waste memory.
            if(!isModuleLoaded(Path.of(preload).getFileName().toString()))
            {
                try
                {
                    SymbolLookup.libraryLookup(root.resolve(preload), Arena.global());
                }
                catch(IllegalArgumentException e)
                {
                    // Best effort: if the core library really needs it, loading the core reports the failure
                }
            }
        }

        Path core = root.resolve(manifest.core());
        SymbolLookup lookup = SymbolLookup.libraryLookup(core, Arena.global());
        sInfo = new Info(platform, true, core.toString(), root);
        return lookup;
    }

    /**
     * Base directory for extracted images: soapysdr.natives.dir, else the user cache directory for the OS.
     */
    private static Path cacheBase()
    {
        String configured = System.getProperty(NATIVES_DIR_PROPERTY);
        if(configured != null && !configured.isBlank())
        {
            return Path.of(configured);
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if(os.startsWith("windows"))
        {
            String local = System.getenv("LOCALAPPDATA");
            return local != null ? Path.of(local, "jsoapysdr", "natives") : Path.of(home, "AppData", "Local", "jsoapysdr", "natives");
        }
        if(os.startsWith("mac"))
        {
            return Path.of(home, "Library", "Caches", "jsoapysdr", "natives");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        return xdg != null && !xdg.isBlank() ? Path.of(xdg, "jsoapysdr", "natives") : Path.of(home, ".cache", "jsoapysdr", "natives");
    }

    /**
     * Extracts the bundle into a directory named after its content hash, reusing a previous extraction when it is
     * complete. A lock file serializes concurrent JVMs extracting the same bundle.
     */
    private static Path extract(Manifest manifest) throws IOException
    {
        Path base;
        try
        {
            base = cacheBase();
            Files.createDirectories(base);
        }
        catch(IOException | RuntimeException e)
        {
            base = Path.of(System.getProperty("java.io.tmpdir"), "jsoapysdr-" + System.getProperty("user.name", "user"));
            Files.createDirectories(base);
        }

        String name = manifest.platform() + "-" + manifest.id();
        Path target = base.resolve(name).toAbsolutePath();
        if(isComplete(target, manifest))
        {
            return target;
        }

        try(FileChannel channel = FileChannel.open(base.resolve(name + ".lock"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE); FileLock lock = channel.lock())
        {
            if(!lock.isValid() || isComplete(target, manifest))
            {
                return target;
            }

            Path staging = Files.createTempDirectory(base, name + ".tmp");
            for(Entry entry : manifest.files())
            {
                Path destination = staging.resolve(entry.path());
                Files.createDirectories(destination.getParent());
                copyVerified(manifest.platform(), entry, destination);
            }
            Files.writeString(staging.resolve(COMPLETE_MARKER), manifest.id());

            if(Files.exists(target))
            {
                deleteQuietly(target); // an incomplete earlier attempt
            }
            try
            {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            }
            catch(AtomicMoveNotSupportedException | FileAlreadyExistsException e)
            {
                // Leftover files we could not delete (e.g. locked on Windows): use the staging copy directly
                return staging.toAbsolutePath();
            }
            return target;
        }
    }

    private static boolean isComplete(Path dir, Manifest manifest)
    {
        if(!Files.isRegularFile(dir.resolve(COMPLETE_MARKER)))
        {
            return false;
        }
        try
        {
            for(Entry entry : manifest.files())
            {
                Path file = dir.resolve(entry.path());
                if(!Files.isRegularFile(file) || Files.size(file) != entry.size())
                {
                    return false;
                }
            }
            return true;
        }
        catch(IOException e)
        {
            return false;
        }
    }

    private static void copyVerified(String platform, Entry entry, Path destination) throws IOException
    {
        String resource = RESOURCE_ROOT + platform + "/" + entry.path();
        MessageDigest digest = sha256();
        try(InputStream in = NativeLibrary.class.getResourceAsStream(resource))
        {
            if(in == null)
            {
                throw new IOException("Missing bundled resource " + resource);
            }
            try(DigestInputStream digestIn = new DigestInputStream(in, digest))
            {
                Files.copy(digestIn, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if(!actual.equals(entry.sha256()) || Files.size(destination) != entry.size())
        {
            throw new IOException("Checksum mismatch extracting " + resource);
        }
    }

    private static void deleteQuietly(Path dir)
    {
        try(Stream<Path> paths = Files.walk(dir))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch(IOException ignored)
                {
                    //Best effort
                }
            });
        }
        catch(IOException ignored)
        {
            //Best effort
        }
    }

    private static MessageDigest sha256()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch(NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Process environment
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Sets an environment variable in the native process environment (it is not visible through System.getenv()).
     */
    private static void setEnvironment(String name, String value)
    {
        Linker linker = Linker.nativeLinker();
        try(Arena arena = Arena.ofConfined())
        {
            int result;
            if(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
            {
                // SoapySDR reads its variables with GetEnvironmentVariableA (process environment block)
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
                MethodHandle set = linker.downcallHandle(kernel32.find("SetEnvironmentVariableW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                result = (int)set.invokeExact(wide(arena, name), wide(arena, value)) != 0 ? 0 : -1;
            }
            else
            {
                MethodHandle setenv = linker.downcallHandle(linker.defaultLookup().find("setenv").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT));
                result = (int)setenv.invokeExact(arena.allocateFrom(name), arena.allocateFrom(value), 1);
            }
            if(result != 0)
            {
                throw new IllegalStateException("Unable to set " + name + " (error " + result + ")");
            }
        }
        catch(RuntimeException | Error e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw new IllegalStateException("Unable to set " + name, t);
        }
    }

    /**
     * Windows: indicates if a DLL with the given base name is already loaded in the process (GetModuleHandleW).
     */
    private static boolean isModuleLoaded(String name)
    {
        if(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
        {
            return false;
        }
        try(Arena arena = Arena.ofConfined())
        {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
            MethodHandle getModuleHandle = linker.downcallHandle(kernel32.find("GetModuleHandleW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MemorySegment module = (MemorySegment)getModuleHandle.invokeExact(wide(arena, name));
            return !module.equals(MemorySegment.NULL);
        }
        catch(Throwable t)
        {
            return false;
        }
    }

    private static MemorySegment wide(Arena arena, String value)
    {
        return arena.allocateFrom(value + "\0", StandardCharsets.UTF_16LE);
    }

    // -----------------------------------------------------------------------------------------------------------------
    // System installation candidates
    // -----------------------------------------------------------------------------------------------------------------

    private static String firstNonBlank(String... values)
    {
        for(String value : values)
        {
            if(value != null && !value.isBlank())
            {
                return value.trim();
            }
        }
        return null;
    }

    private static List<String> systemCandidates()
    {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();

        if(os.contains("win"))
        {
            candidates.add("SoapySDR.dll");
            candidates.add("libSoapySDR.dll");
            String programFiles = System.getenv("ProgramFiles");
            if(programFiles != null)
            {
                candidates.add(programFiles + "\\PothosSDR\\bin\\SoapySDR.dll");
            }
            String userProfile = System.getenv("USERPROFILE");
            if(userProfile != null)
            {
                candidates.add(userProfile + "\\radioconda\\Library\\bin\\SoapySDR.dll");
            }
        }
        else if(os.contains("mac") || os.contains("darwin"))
        {
            candidates.add("libSoapySDR.0.8.dylib");
            candidates.add("libSoapySDR.dylib");
            candidates.add("/opt/homebrew/lib/libSoapySDR.0.8.dylib");
            candidates.add("/opt/homebrew/lib/libSoapySDR.dylib");
            candidates.add("/usr/local/lib/libSoapySDR.0.8.dylib");
            candidates.add("/usr/local/lib/libSoapySDR.dylib");
            candidates.add("/opt/local/lib/libSoapySDR.dylib");
        }
        else
        {
            candidates.add("libSoapySDR.so.0.8");
            candidates.add("libSoapySDR.so");
            candidates.add("/usr/local/lib/libSoapySDR.so.0.8");
            candidates.add("/usr/local/lib64/libSoapySDR.so.0.8");
            candidates.add("/usr/lib/x86_64-linux-gnu/libSoapySDR.so.0.8");
            candidates.add("/usr/lib/aarch64-linux-gnu/libSoapySDR.so.0.8");
            candidates.add("/usr/lib64/libSoapySDR.so.0.8");
        }

        return candidates;
    }
}
